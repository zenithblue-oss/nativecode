package com.zenithblue.nativecode.agent.chat.tools

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/**
 * The NativeCode tools MCP server (item 12 M7): MCP Streamable HTTP (2025-06-18, JSON-only replies, no SSE stream)
 * on `127.0.0.1:<random port>/mcp`. Every request needs `Authorization: Bearer <token>`; a token belongs to one
 * chat thread (32 random bytes, only its SHA-256 is kept). Never logs requests, bodies or tokens.
 *
 * ponytail: hand-rolled HTTP/1.1 (Content-Length or chunked request bodies, keep-alive, one request at a time per
 * connection); no MCP sessions, no server-initiated messages. Upgrade only if a CLI needs SSE.
 */
class NcMcpServer(private val handler: Handler) {
    interface Handler {
        /** Server `instructions` for the initialize result. */
        fun instructions(threadId: String): String
        /** `tools/list` for this thread's token. */
        fun tools(threadId: String): JSONArray
        /** `tools/call`: an MCP CallToolResult. Null = unknown tool (JSON-RPC -32602). May block (waits). */
        fun call(threadId: String, name: String, args: JSONObject): JSONObject?
    }

    class Http(val status: Int, val body: String?, val headers: Map<String, String> = emptyMap())

    private val tokens = ConcurrentHashMap<String, String>()   // sha256(token) → thread id
    private val byThread = ConcurrentHashMap<String, String>() // thread id → sha256(token)
    private val random = SecureRandom()
    private val slots = Semaphore(MAX_CONNECTIONS)
    private val pool = Executors.newCachedThreadPool { Thread(it, "nc-mcp").apply { isDaemon = true } }
    @Volatile private var socket: ServerSocket? = null

    val port: Int get() = socket?.localPort ?: -1

    /** Binds 127.0.0.1 on a free port (loopback only) and starts accepting. Idempotent. */
    @Synchronized
    fun start(): NcMcpServer {
        if (socket != null) return this
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        socket = s
        Thread({ acceptLoop(s) }, "nc-mcp-accept").apply { isDaemon = true; start() }
        return this
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    /** A fresh token for [threadId]; its previous token stops working. */
    fun issue(threadId: String): String {
        val token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val h = sha256(token)
        tokens[h] = threadId
        byThread.put(threadId, h)?.let { tokens.remove(it) }
        return token
    }

    fun revoke(threadId: String) { byThread.remove(threadId)?.let { tokens.remove(it) } }

    /** Thread id of a bearer header value, null when missing, malformed or unknown. */
    fun authorize(header: String?): String? {
        val v = header?.trim() ?: return null
        if (!v.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
        val token = v.substring(7).trim()
        if (token.isEmpty() || token.length > 256) return null
        return tokens[sha256(token)]
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: IOException) { break }
            if (!slots.tryAcquire()) { runCatching { c.close() }; continue }
            try {
                pool.execute { try { serve(c) } finally { slots.release() } }
            } catch (_: Exception) {
                slots.release(); runCatching { c.close() }
            }
        }
    }

    private fun serve(c: Socket) = runCatching {
        c.use {
            c.soTimeout = IDLE_MS
            val input = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            while (true) {
                val req = try { readRequest(input) } catch (_: SocketTimeoutException) { null } catch (e: BadRequest) {
                    write(out, Http(e.status, null), close = true); null
                } ?: return@use
                write(out, handle(req.method, req.path, req.headers, req.body), req.close)
                if (req.close) return@use
            }
        }
    }

    // ---------------- HTTP → MCP ----------------

    /** One HTTP request (pure: unit-tested without sockets). [headers] keys are lowercase. */
    fun handle(method: String, path: String, headers: Map<String, String>, body: ByteArray): Http {
        if (path.substringBefore('?') != "/mcp") return Http(404, null)
        // DNS-rebinding guard (spec): a browser page may only reach us from a loopback origin.
        headers["origin"]?.let { if (!loopbackOrigin(it)) return Http(403, null) }
        val threadId = authorize(headers["authorization"])
            ?: return Http(401, null, mapOf("WWW-Authenticate" to "Bearer realm=\"nativecode\""))
        return when (method) {
            "POST" -> post(threadId, body)
            "DELETE" -> Http(200, null)
            else -> Http(405, null, mapOf("Allow" to "POST, DELETE"))
        }
    }

    private fun post(threadId: String, body: ByteArray): Http {
        val parsed = try { JSONTokener(String(body, Charsets.UTF_8)).nextValue() } catch (_: Exception) { null }
        val msgs: List<Any?> = when (parsed) {
            is JSONObject -> listOf(parsed)
            is JSONArray -> (0 until parsed.length()).map { parsed.opt(it) }
            else -> return json(400, error(null, -32700, "Parse error"))
        }
        val replies = msgs.mapNotNull { m -> if (m is JSONObject) rpc(threadId, m) else error(null, -32600, "Invalid Request") }
        if (replies.isEmpty()) return Http(202, null) // notifications / responses only
        return json(200, if (parsed is JSONArray) JSONArray(replies) else replies[0])
    }

    /** One JSON-RPC message; null for notifications and client responses. */
    private fun rpc(threadId: String, m: JSONObject): JSONObject? {
        val method = m.optString("method").ifEmpty { null }
        val hasId = m.has("id") && !m.isNull("id")
        if (method == null) return if (hasId && !m.has("result") && !m.has("error")) error(m.opt("id"), -32600, "Invalid Request") else null
        if (!hasId) return null
        val id = m.opt("id")
        val params = m.optJSONObject("params") ?: JSONObject()
        return try {
            when (method) {
                "initialize", "server/discover" -> result(id, JSONObject() // discover: claude's pre-initialize probe
                    .put("protocolVersion", negotiate(params.optString("protocolVersion")))
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "nativecode").put("title", "NativeCode").put("version", SERVER_VERSION))
                    .put("instructions", handler.instructions(threadId)))
                "ping" -> result(id, JSONObject())
                "tools/list" -> result(id, JSONObject().put("tools", handler.tools(threadId)))
                "tools/call" -> {
                    val name = params.optString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    handler.call(threadId, name, args)?.let { result(id, it) } ?: error(id, -32602, "Unknown tool: $name")
                }
                "resources/list" -> result(id, JSONObject().put("resources", JSONArray()))
                "prompts/list" -> result(id, JSONObject().put("prompts", JSONArray()))
                else -> error(id, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            error(id, -32603, "Internal error")
        }
    }

    private fun json(status: Int, v: Any) = Http(status, v.toString(), mapOf("Content-Type" to "application/json"))

    // ---------------- wire ----------------

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray, val close: Boolean)
    private class BadRequest(val status: Int) : IOException()

    private fun readRequest(input: InputStream): Request? {
        val start = readLine(input) ?: return null
        if (start.isEmpty()) return readRequest(input) // stray CRLF between keep-alive requests
        val parts = start.split(' ')
        if (parts.size < 3) throw BadRequest(400)
        val headers = HashMap<String, String>()
        var size = start.length
        while (true) {
            val line = readLine(input) ?: throw BadRequest(400)
            if (line.isEmpty()) break
            size += line.length
            if (size > MAX_HEADER) throw BadRequest(431)
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            else -> {
                val n = headers["content-length"]?.toLongOrNull() ?: 0L
                if (n < 0 || n > MAX_BODY) throw BadRequest(413)
                readExactly(input, n.toInt())
            }
        }
        val close = headers["connection"].equals("close", ignoreCase = true) || parts[2] == "HTTP/1.0"
        return Request(parts[0].uppercase(), parts[1], headers, body, close)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val n = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw BadRequest(400)
            if (n == 0) { while (readLine(input)?.isNotEmpty() == true) Unit; return out.toByteArray() }
            if (out.size() + n > MAX_BODY) throw BadRequest(413)
            out.write(readExactly(input, n))
            readLine(input)
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw BadRequest(400)
            off += r
        }
        return b
    }

    /** CRLF (or LF) line, ISO-8859-1; null at EOF before any byte. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > MAX_HEADER) throw BadRequest(431)
            sb.append(b.toChar())
        }
    }

    private fun write(out: OutputStream, r: Http, close: Boolean) {
        val body = r.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = StringBuilder("HTTP/1.1 ${r.status} ${reason(r.status)}\r\n")
        r.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Cache-Control: no-store\r\n")
        head.append("Connection: ").append(if (close) "close" else "keep-alive").append("\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    companion object {
        const val PREFIX = "ncmcp_"
        const val SERVER_VERSION = "1.8.4"
        val VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
        private const val MAX_CONNECTIONS = 64
        private const val MAX_HEADER = 16 * 1024
        private const val MAX_BODY = 4L * 1024 * 1024
        private const val IDLE_MS = 15 * 60 * 1000 // long tool waits keep the connection quiet

        /** The client's version when we speak it, else our newest stable. */
        fun negotiate(requested: String?): String = requested?.takeIf { it in VERSIONS } ?: "2025-06-18"

        fun loopbackOrigin(origin: String): Boolean {
            if (origin == "null") return false
            val host = origin.substringAfter("://", "").substringBefore('/').substringBeforeLast(':').removePrefix("[").removeSuffix("]")
            return host == "127.0.0.1" || host == "localhost" || host == "::1"
        }

        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun result(id: Any?, r: JSONObject): JSONObject = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", r)
        fun error(id: Any?, code: Int, message: String): JSONObject =
            JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("error", JSONObject().put("code", code).put("message", message))

        private fun reason(s: Int) = when (s) {
            200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 413 -> "Payload Too Large"; 431 -> "Request Header Fields Too Large"; else -> "Error"
        }
    }
}
