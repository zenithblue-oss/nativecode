package com.zenithblue.nativecode.agent.chat.acp

import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A JSON-RPC error reply (`-32000` auth required, `-32003` grok rate limit, `-32601` unknown method…). */
class AcpError(val code: Int, message: String, val data: Any? = null) : IOException(message) {
    val authRequired: Boolean get() = code == AUTH_REQUIRED || message.orEmpty().contains("authentication required", ignoreCase = true)

    companion object {
        const val AUTH_REQUIRED = -32000
        const val RATE_LIMITED = -32003
        const val METHOD_NOT_FOUND = -32601
        const val INTERNAL = -32603
    }
}

/**
 * JSON-RPC 2.0 over NDJSON lines, both directions (ACP v1, t3code `effect-acp/src/protocol.ts`). Transport-free:
 * [write] sends one line, [onLine] takes one stdout line, so tests drive it with recorded fixtures.
 * Agent → client requests go to [onRequest], which returns the result or throws [AcpError]; a null return
 * means the handler answers later through [reply] / [replyError] (permission cards). Unknown methods get -32601.
 * Lines that are not JSON (proot / flux-gpu noise) are dropped.
 */
class AcpRpc(
    private val write: (String) -> Boolean,
    private val onNotification: (method: String, params: JSONObject) -> Unit,
    private val onRequest: (id: Any, method: String, params: JSONObject) -> JSONObject?,
    /** False = codex app-server dialect: no `"jsonrpc"` field either way (app-server-protocol rpc.rs). */
    private val tagged: Boolean = true,
    private val log: (String) -> Unit = {},
) {
    private val next = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()

    private fun frame() = JSONObject().apply { if (tagged) put("jsonrpc", "2.0") }

    /** [params] null = no `params` key (codex unit params such as `account/rateLimits/read`). */
    fun request(method: String, params: JSONObject?): CompletableFuture<JSONObject> {
        val id = next.getAndIncrement()
        val f = CompletableFuture<JSONObject>()
        pending[id] = f
        val line = frame().put("id", id).put("method", method).putOpt("params", params).toString()
        log(line)
        if (!write(line)) { pending.remove(id); f.completeExceptionally(IOException("agent stdin closed")) }
        return f
    }

    fun notify(method: String, params: JSONObject?) {
        val line = frame().put("method", method).putOpt("params", params).toString()
        log(line)
        write(line)
    }

    fun reply(id: Any, result: JSONObject) = send(frame().put("id", id).put("result", result))

    fun replyError(id: Any, code: Int, message: String) =
        send(frame().put("id", id).put("error", JSONObject().put("code", code).put("message", message)))

    /** Process gone: every in-flight request fails (the turn ends instead of hanging). */
    fun failAll(e: Exception) {
        pending.keys.toList().forEach { pending.remove(it)?.completeExceptionally(e) }
    }

    fun onLine(line: String) {
        val t = line.trim()
        if (!t.startsWith("{")) return
        val m = runCatching { JSONObject(t) }.getOrNull() ?: return
        val method = m.optString("method").ifEmpty { null }
        val id = if (m.has("id") && !m.isNull("id")) m.get("id") else null
        when {
            method != null && id != null -> {
                val params = m.optJSONObject("params") ?: JSONObject()
                try {
                    onRequest(id, method, params)?.let { reply(id, it) }
                } catch (e: AcpError) {
                    replyError(id, e.code, e.message ?: "error")
                } catch (e: Exception) {
                    replyError(id, AcpError.INTERNAL, e.message ?: "client error")
                }
            }
            method != null -> onNotification(method, m.optJSONObject("params") ?: JSONObject())
            id != null -> {
                val key = (id as? Number)?.toLong() ?: id.toString().toLongOrNull() ?: return
                val f = pending.remove(key) ?: return
                val err = m.optJSONObject("error")
                if (err != null) f.completeExceptionally(AcpError(err.optInt("code"), err.optString("message").ifEmpty { "agent error" }, err.opt("data")))
                else f.complete(m.optJSONObject("result") ?: JSONObject())
            }
        }
    }

    private fun send(o: JSONObject) {
        val line = o.toString()
        log(line)
        write(line)
    }
}
