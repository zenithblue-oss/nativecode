package com.zenithblue.nativecode.agent.chat.tools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket

/** M7 MCP framing + auth over a real loopback socket (Streamable HTTP, JSON replies). */
class NcMcpServerTest {
    private val calls = ArrayList<Pair<String, JSONObject>>()
    private val handler = object : NcMcpServer.Handler {
        override fun instructions(threadId: String) = "rules for $threadId"
        override fun tools(threadId: String) = JSONArray().put(JSONObject().put("name", "nc_echo").put("inputSchema", JSONObject().put("type", "object")))
        override fun call(threadId: String, name: String, args: JSONObject): JSONObject? {
            if (name != "nc_echo") return null
            calls += threadId to args
            return NcToolSpec.ok(JSONObject().put("thread", threadId).put("args", args))
        }
    }
    private lateinit var server: NcMcpServer
    private lateinit var token: String

    @Before fun setUp() { server = NcMcpServer(handler).start(); token = server.issue("t1") }
    @After fun tearDown() = server.stop()

    private class Resp(val status: Int, val headers: Map<String, String>, val body: String)

    /** Raw HTTP/1.1 exchange(s) on one connection. */
    private fun exchange(vararg requests: String): List<Resp> = Socket("127.0.0.1", server.port).use { s ->
        s.soTimeout = 5_000
        val out = s.getOutputStream()
        val inp = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        requests.map { r ->
            out.write(r.toByteArray(Charsets.UTF_8)); out.flush()
            val status = inp.readLine().split(' ')[1].toInt()
            val h = HashMap<String, String>()
            while (true) { val l = inp.readLine(); if (l.isEmpty()) break; h[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
            val n = h["content-length"]!!.toInt()
            val buf = CharArray(n); var off = 0
            while (off < n) off += inp.read(buf, off, n - off)
            Resp(status, h, String(buf))
        }
    }

    private fun post(body: String, auth: String? = "Bearer $token", extra: String = "", close: Boolean = true) =
        "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\n" +
            (auth?.let { "Authorization: $it\r\n" } ?: "") + extra + "Content-Length: ${body.toByteArray().size}\r\n" +
            (if (close) "Connection: close\r\n" else "") + "\r\n" + body

    private fun rpc(id: Int, method: String, params: JSONObject = JSONObject()) =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString()

    @Test fun binds_loopback_only() {
        assertTrue(server.port > 0)
        // the socket's address is 127.0.0.1 (not 0.0.0.0): reachable only from this device
        val f = NcMcpServer::class.java.getDeclaredField("socket").apply { isAccessible = true }
        assertEquals("127.0.0.1", (f.get(server) as java.net.ServerSocket).inetAddress.hostAddress)
    }

    @Test fun initialize_list_call_round_trip_keep_alive() {
        val r = exchange(
            post(rpc(1, "initialize", JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "t").put("version", "1"))), close = false),
            post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString(), close = false),
            post(rpc(2, "tools/list"), close = false),
            post(rpc(3, "tools/call", JSONObject().put("name", "nc_echo").put("arguments", JSONObject().put("x", 1)))),
        )
        val init = JSONObject(r[0].body).getJSONObject("result")
        assertEquals("2025-06-18", init.getString("protocolVersion"))
        assertTrue(init.getJSONObject("capabilities").has("tools"))
        assertEquals("rules for t1", init.getString("instructions"))
        assertEquals("application/json", r[0].headers["content-type"])
        assertEquals(202, r[1].status)
        assertEquals("nc_echo", JSONObject(r[2].body).getJSONObject("result").getJSONArray("tools").getJSONObject(0).getString("name"))
        val call = JSONObject(r[3].body).getJSONObject("result")
        assertFalse(call.getBoolean("isError"))
        assertEquals("t1", JSONObject(call.getJSONArray("content").getJSONObject(0).getString("text")).getString("thread"))
        assertEquals(1, calls.size)
    }

    @Test fun missing_or_bad_token_is_401_and_never_reaches_tools() {
        val r = exchange(post(rpc(1, "tools/list"), auth = null))
        assertEquals(401, r[0].status)
        assertTrue(r[0].headers["www-authenticate"]!!.startsWith("Bearer"))
        assertEquals(401, exchange(post(rpc(1, "tools/list"), auth = "Bearer ncmcp_wrong"))[0].status)
        assertEquals(401, exchange(post(rpc(1, "tools/list"), auth = "Basic $token"))[0].status)
        assertEquals(401, exchange(post(rpc(1, "tools/call", JSONObject().put("name", "nc_echo")), auth = "Bearer "))[0].status)
        assertTrue(calls.isEmpty())
    }

    @Test fun reissue_revokes_previous_token_and_scopes_by_thread() {
        val second = server.issue("t1")
        assertNotEquals(token, second)
        assertNull(server.authorize("Bearer $token"))
        assertEquals("t1", server.authorize("bearer $second"))
        val other = server.issue("t2")
        assertEquals("t2", server.authorize("Bearer $other"))
        server.revoke("t2")
        assertNull(server.authorize("Bearer $other"))
        assertTrue(second.startsWith(NcMcpServer.PREFIX) && second.length > 40)
    }

    @Test fun get_is_405_delete_200_other_path_404() {
        assertEquals(405, exchange("GET /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\nConnection: close\r\n\r\n")[0].status)
        assertEquals(200, exchange("DELETE /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\nConnection: close\r\n\r\n")[0].status)
        assertEquals(404, exchange("POST /other HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")[0].status)
    }

    @Test fun foreign_origin_is_403_loopback_origin_ok() {
        assertEquals(403, exchange(post(rpc(1, "ping"), extra = "Origin: https://evil.example\r\n"))[0].status)
        assertEquals(200, exchange(post(rpc(1, "ping"), extra = "Origin: http://localhost:3000\r\n"))[0].status)
    }

    @Test fun chunked_body_batch_and_errors() {
        val body = "[" + rpc(1, "ping") + "," + rpc(2, "nope") + "," + rpc(3, "tools/call", JSONObject().put("name", "missing")) + "]"
        val bytes = body.toByteArray()
        val half = bytes.size / 2
        val chunked = "POST /mcp HTTP/1.1\r\nAuthorization: Bearer $token\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
            Integer.toHexString(half) + "\r\n" + String(bytes, 0, half) + "\r\n" +
            Integer.toHexString(bytes.size - half) + "\r\n" + String(bytes, half, bytes.size - half) + "\r\n0\r\n\r\n"
        val arr = JSONArray(exchange(chunked)[0].body)
        assertEquals(3, arr.length())
        assertTrue(arr.getJSONObject(0).has("result"))
        assertEquals(-32601, arr.getJSONObject(1).getJSONObject("error").getInt("code"))
        assertEquals(-32602, arr.getJSONObject(2).getJSONObject("error").getInt("code"))
        val bad = exchange(post("{not json"))[0]
        assertEquals(400, bad.status)
        assertEquals(-32700, JSONObject(bad.body).getJSONObject("error").getInt("code"))
    }

    @Test fun version_negotiation() {
        assertEquals("2025-03-26", NcMcpServer.negotiate("2025-03-26"))
        assertEquals("2025-06-18", NcMcpServer.negotiate("1999-01-01"))
        assertEquals("2025-06-18", NcMcpServer.negotiate(null))
        assertTrue(NcMcpServer.loopbackOrigin("http://127.0.0.1:8080"))
        assertTrue(NcMcpServer.loopbackOrigin("http://[::1]:1"))
        assertFalse(NcMcpServer.loopbackOrigin("null"))
        assertFalse(NcMcpServer.loopbackOrigin("http://127.0.0.1.evil.com"))
    }
}
