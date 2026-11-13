package com.zenithblue.nativecode.agent.chat.acp

import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.TurnInput
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** JSON-RPC framing both ways + the pure wire helpers. */
class AcpRpcTest {
    private val out = ArrayList<JSONObject>()
    private val notes = ArrayList<String>()
    private val rpc = AcpRpc({ out += JSONObject(it); true }, { m, _ -> notes += m }, { id, m, _ ->
        when (m) {
            "later" -> null
            "boom" -> throw AcpError(-32602, "bad")
            "session/request_permission" -> JSONObject().put("ok", id)
            else -> throw AcpError(AcpError.METHOD_NOT_FOUND, "Method not found: $m")
        }
    })

    @Test fun requestResultAndError() {
        val a = rpc.request("initialize", JSONObject())
        val b = rpc.request("session/new", JSONObject())
        assertEquals(listOf(0L, 1L), out.map { it.getLong("id") })
        assertEquals("2.0", out[0].getString("jsonrpc"))
        // agy puts `id` last; reply order differs from request order
        rpc.onLine("""{"jsonrpc":"2.0","error":{"code":-32000,"message":"Authentication required"},"id":1}""")
        rpc.onLine("""{"jsonrpc":"2.0","result":{"protocolVersion":1},"id":0}""")
        assertEquals(1, a.get(1, TimeUnit.SECONDS).getInt("protocolVersion"))
        val e = runCatching { b.get(1, TimeUnit.SECONDS) }.exceptionOrNull() as ExecutionException
        assertTrue((e.cause as AcpError).authRequired)
    }

    @Test fun agentRequestsAreAlwaysAnswered() {
        rpc.onLine("""{"jsonrpc":"2.0","id":7,"method":"fs/read_text_file","params":{}}""")
        assertEquals(AcpError.METHOD_NOT_FOUND, out.last().getJSONObject("error").getInt("code"))
        rpc.onLine("""{"jsonrpc":"2.0","id":"s8","method":"boom","params":{}}""")
        assertEquals("s8", out.last().get("id"))
        assertEquals(-32602, out.last().getJSONObject("error").getInt("code"))
        rpc.onLine("""{"jsonrpc":"2.0","id":9,"method":"session/request_permission","params":{}}""")
        assertEquals(9, out.last().getJSONObject("result").getInt("ok"))
        val n = out.size
        rpc.onLine("""{"jsonrpc":"2.0","id":10,"method":"later","params":{}}""") // answered later by the card
        assertEquals(n, out.size)
        rpc.reply(10, JSONObject().put("outcome", JSONObject().put("outcome", "cancelled")))
        assertEquals(10, out.last().getInt("id"))
    }

    @Test fun noiseAndNotifications() {
        rpc.onLine("proot warning: can't sanitize binding \"/proc/self/fd/1\"")
        rpc.onLine("")
        rpc.onLine("""{"jsonrpc":"2.0","method":"session/update","params":{"update":{}}}""")
        assertEquals(listOf("session/update"), notes)
        assertTrue(out.isEmpty())
    }

    @Test fun exitFailsInFlight() {
        val f = rpc.request("session/prompt", JSONObject())
        rpc.failAll(java.io.IOException("agent exited"))
        assertTrue(f.isCompletedExceptionally)
    }

    @Test fun unifiedDiff() {
        val d = AcpWire.unifiedDiff("a.txt", "1\n2\n3\n4\n5\n", "1\n2\nthree\n4\n5\n")
        assertEquals("--- a/a.txt\n+++ b/a.txt\n@@ -1,5 +1,5 @@\n 1\n 2\n-3\n+three\n 4\n 5", d)
        val created = AcpWire.unifiedDiff("n.txt", null, "hello\n")
        assertEquals("--- /dev/null\n+++ b/n.txt\n@@ -0,0 +1,1 @@\n+hello", created)
        assertEquals(1 to 0, AcpWire.diffStat(created))
        assertEquals("", AcpWire.unifiedDiff("x", "same", "same"))
    }

    @Test fun promptBlocks() {
        val img = ChatAttachment("p.png", "image/png", 3, "/home/flux/p/attach_p.png", "/host/p.png")
        val txt = ChatAttachment("n.md", "text/markdown", 2, "/home/flux/p/attach_n.md", "/host/n.md")
        val bin = ChatAttachment("z.zip", "application/zip", 9, "/home/flux/p/attach_z.zip", "/host/z.zip")
        val bytes = mapOf("/host/p.png" to byteArrayOf(1, 2, 3), "/host/n.md" to "hi".toByteArray())
        val b = AcpWire.promptBlocks(TurnInput("c", "look", listOf(img, txt, bin)), images = true, embed = true) { bytes[it] }
        assertEquals(listOf("text", "image", "resource"), (0 until b.length()).map { b.getJSONObject(it).getString("type") })
        assertTrue(b.getJSONObject(0).getString("text").endsWith("[Attached file \"z.zip\" is saved at: /home/flux/p/attach_z.zip]"))
        assertEquals("AQID", b.getJSONObject(1).getString("data"))
        assertEquals("hi", b.getJSONObject(2).getJSONObject("resource").getString("text"))
        // no image capability: path line instead
        val plain = AcpWire.promptBlocks(TurnInput("c", "", listOf(img)), images = false, embed = false) { bytes[it] }
        assertEquals(1, plain.length())
        assertTrue(plain.getJSONObject(0).getString("text").contains("attach_p.png"))
    }

    @Test fun commandHintsAndMcpHook() {
        val c = AcpWire.commands(org.json.JSONArray("""[{"name":"goal","description":"Run","input":{"hint":"<objective>"}},{"name":"context","input":null},{"name":"compact"}]"""), setOf("context"))
        assertEquals(listOf("goal" to "<objective>", "compact" to null), c.map { it.name to it.argumentHint })
        val nc = com.zenithblue.nativecode.agent.chat.core.McpEndpoint("nc", "http://127.0.0.1:5000/mcp", mapOf("Authorization" to "Bearer tok"))
        assertEquals(0, AcpWire.mcpServers(JSONObject(), nc).length()) // agy 1.5.8 advertises no mcpCapabilities
        val s = AcpWire.mcpServers(JSONObject("""{"mcpCapabilities":{"http":true}}"""), nc).getJSONObject(0)
        assertEquals("http", s.getString("type"))
        assertEquals("Bearer tok", s.getJSONArray("headers").getJSONObject(0).getString("value"))
        assertEquals(false, AcpWire.redact(s.toString()).contains("tok\""))
    }

    @Test fun toolPayloads() {
        fun tool(json: String) = AcpWire.Tool("t").apply { merge(JSONObject(json)) }
        val cmd = AcpWire.toolPayload(tool("""{"kind":"execute","title":"Run","rawInput":{"CommandLine":"echo hi"},"rawOutput":{"combinedOutput":"hi\n","exitCode":0}}""")) as ItemPayload.CommandExecution
        assertEquals("echo hi", cmd.command)
        assertEquals(0, cmd.exitCode)
        val edit = AcpWire.toolPayload(tool("""{"kind":"edit","content":[{"type":"diff","path":"/w/a.txt","oldText":"a\n","newText":"b\n"}]}""")) as ItemPayload.FileChange
        assertEquals("/w/a.txt", edit.path)
        assertEquals(1 to 1, edit.add to edit.del)
        val sub = AcpWire.toolPayload(tool("""{"title":"Running invoke_subagent","rawInput":{"Subagents":[{"Role":"tester","Prompt":"x"}]},"rawOutput":"done"}""")) as ItemPayload.Subagent
        assertEquals("tester", sub.title)
        assertEquals("done", sub.result)
    }
}
