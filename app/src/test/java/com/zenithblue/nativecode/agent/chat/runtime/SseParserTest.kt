package com.zenithblue.nativecode.agent.chat.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class SseParserTest {
    private fun parse(vararg chunks: String): List<SseFrame> {
        val out = ArrayList<SseFrame>()
        val p = SseParser { out += it }
        chunks.forEach { p.feed(it) }
        return out
    }

    @Test fun multiLineDataJoinsWithNewline() {
        assertEquals(listOf(SseFrame(null, null, "a\nb\n")), parse("data: a\ndata:b\ndata\n\n"))
    }

    @Test fun commentsAndHeartbeatsAreNotFrames() {
        assertEquals(listOf("1", "2"), parse(": heartbeat\n\ndata: 1\n\n:x\n\n\ndata: 2\n\n").map { it.data })
    }

    @Test fun crlfAndCrLineEnds() {
        assertEquals(listOf("x", "y"), parse("data: x\r\n\r\ndata: y\r\r").map { it.data })
    }

    @Test fun chunksSplitAnywhereIncludingInsideCrlf() {
        val text = "event: e1\r\nid: 7\r\ndata: {\"a\":1}\r\n\r\ndata: z\n\n"
        val whole = parse(text)
        for (cut in 1 until text.length) assertEquals("cut $cut", whole, parse(text.substring(0, cut), text.substring(cut)))
        assertEquals(SseFrame("e1", "7", "{\"a\":1}"), whole[0])
        assertEquals(SseFrame(null, "7", "z"), whole[1]) // id persists (last event id); event resets
    }

    @Test fun noDispatchWithoutBlankLineAndOnlyOneSpaceStripped() {
        assertEquals(emptyList<SseFrame>(), parse("data: pending\n"))
        assertEquals("  two", parse("data:   two\n\n")[0].data)
    }

    @Test fun pumpReadsReaderToEnd() {
        val out = ArrayList<SseFrame>()
        SseParser { out += it }.pump(StringReader("data: " + "x".repeat(20_000) + "\n\n"))
        assertEquals(20_000, out.single().data.length)
    }
}
