package com.zenithblue.nativecode.agent.chat.runtime

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * Minimal blocking client for one `opencode serve` on loopback. Every call carries HTTP Basic `opencode:<password>`
 * (UTF-8, as t3code `OpenCode2Client`); the password and the auth header are never logged.
 */
internal class OpenCodeHttp(private val base: String, password: String, private val log: (String) -> Unit) {
    class HttpError(val status: Int, body: String) : IOException("HTTP $status ${body.take(300)}")

    private val auth = "Basic " + Base64.getEncoder().encodeToString("opencode:$password".toByteArray(Charsets.UTF_8))

    /** JSON body or null (204 / empty). Non-2xx throws [HttpError]. [secret] = the body carries a token: not logged. */
    fun call(method: String, path: String, body: JSONObject? = null, timeoutMs: Int = 15_000, secret: Boolean = false): JSONObject? {
        log("> $method $path${body?.let { if (secret) " <redacted>" else " $it" } ?: ""}")
        val c = open(path, timeoutMs)
        try {
            setMethod(c, method)
            c.setRequestProperty("accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            log("< $code ${text.take(LOG_CAP)}")
            if (code !in 200..299) throw HttpError(code, text)
            return if (text.isBlank()) null else JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    /** `GET /api/event`; the read timeout is the heartbeat watchdog (heartbeats every 10-15 s). */
    fun events(silenceMs: Int): HttpURLConnection {
        val c = open("/api/event", silenceMs)
        c.setRequestProperty("accept", "text/event-stream")
        val code = c.responseCode
        if (code != 200) {
            c.disconnect()
            throw HttpError(code, "event stream")
        }
        return c
    }

    private fun open(path: String, readTimeoutMs: Int) = (URL(base + path).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5_000
        readTimeout = readTimeoutMs
        useCaches = false
        setRequestProperty("authorization", auth)
    }

    companion object {
        private const val LOG_CAP = 2_000

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        /** `?location[directory]=<cwd>` (deepObject style). */
        fun location(cwd: String) = "location%5Bdirectory%5D=" + enc(cwd)

        /** Android's HttpURLConnection takes PATCH; a plain JVM one needs the field set directly. */
        private fun setMethod(c: HttpURLConnection, method: String) {
            try {
                c.requestMethod = method
            } catch (_: java.net.ProtocolException) {
                HttpURLConnection::class.java.getDeclaredField("method").apply { isAccessible = true }.set(c, method)
            }
        }
    }
}
