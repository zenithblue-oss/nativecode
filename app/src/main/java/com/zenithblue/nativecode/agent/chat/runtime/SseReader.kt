package com.zenithblue.nativecode.agent.chat.runtime

import java.io.Reader

/** One server-sent event. [event] and [id] are null when the frame has none; comments (heartbeats) never make a frame. */
data class SseFrame(val event: String?, val id: String?, val data: String)

/**
 * Incremental SSE parser (WHATWG event-stream): lines end in LF, CRLF or CR, also when a CRLF is split across
 * chunks; `data:` lines join with `\n`; a blank line dispatches; `:` lines are comments; one space after the
 * colon is dropped. Not thread-safe: one reader thread feeds it.
 */
class SseParser(private val onFrame: (SseFrame) -> Unit) {
    private val line = StringBuilder()
    private val data = StringBuilder()
    private var event: String? = null
    private var id: String? = null
    private var hasData = false
    private var skipLf = false

    fun feed(chunk: CharSequence) {
        for (c in chunk) {
            if (skipLf) {
                skipLf = false
                if (c == '\n') continue
            }
            when (c) {
                '\r' -> { skipLf = true; endLine() }
                '\n' -> endLine()
                else -> line.append(c)
            }
        }
    }

    /** Reads until EOF (or an exception from [reader], e.g. the 45 s read timeout). */
    fun pump(reader: Reader) {
        val buf = CharArray(8 * 1024)
        while (true) {
            val n = reader.read(buf)
            if (n < 0) return
            feed(java.nio.CharBuffer.wrap(buf, 0, n))
        }
    }

    private fun endLine() {
        val l = line.toString()
        line.setLength(0)
        if (l.isEmpty()) return dispatch()
        if (l[0] == ':') return
        val colon = l.indexOf(':')
        val name = if (colon < 0) l else l.substring(0, colon)
        var value = if (colon < 0) "" else l.substring(colon + 1)
        if (value.startsWith(' ')) value = value.substring(1)
        when (name) {
            "data" -> { if (hasData) data.append('\n'); data.append(value); hasData = true }
            "event" -> event = value
            "id" -> id = value
        }
    }

    private fun dispatch() {
        if (hasData) onFrame(SseFrame(event, id, data.toString()))
        data.setLength(0)
        hasData = false
        event = null
    }
}
