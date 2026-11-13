package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {
    private fun nodes(text: String, streaming: Boolean = true): List<Md.Node> {
        val b = Md.split(text)
        val open = streaming && !text.endsWith("\n")
        return b.mapIndexed { i, s -> Md.block(s, open && i == b.lastIndex) }
    }

    @Test fun blocks() {
        val n = nodes("# Title\n\nPara **b**\n\n- one\n- two\n\n1. a\n2. b\n\n> q\n\n---\n\n| h1 | h2 |\n|---|---|\n| a | b |\n\n```kt\nval x = 1\n```\n", streaming = false)
        assertEquals(Md.Node.Heading(1, "Title"), n[0])
        assertEquals(Md.Node.Para("Para **b**"), n[1])
        assertEquals(2, (n[2] as Md.Node.ListBlock).items.size)
        assertTrue((n[3] as Md.Node.ListBlock).items.all { it.ordered })
        assertEquals(Md.Node.Quote("q"), n[4])
        assertEquals(Md.Node.Rule, n[5])
        assertEquals(Md.Node.Table(listOf("h1", "h2"), listOf(listOf("a", "b"))), n[6])
        assertEquals(Md.Node.Code("kt", "val x = 1", closed = true), n[7])
    }

    @Test fun openFenceIsCodeInProgress() {
        val n = nodes("Intro\n\n```python\nprint(1)\nprint(")
        assertEquals(Md.Node.Code("python", "print(1)\nprint(", closed = false), n.last())
        assertEquals(Md.Node.Code("", "", closed = false), nodes("```").single())
    }

    @Test fun halfBoldIsStyledWithoutMarkers() {
        val a = Md.inline("say **hel", open = true)
        assertEquals("say hel", a.text)
        assertTrue(a.spanStyles.any { it.item == Md.BOLD && it.start == 4 && it.end == 7 })
        // Finished text keeps an unmatched marker literal.
        assertEquals("say **hel", Md.inline("say **hel", open = false).text)
        assertEquals("x", Md.inline("`x", open = true).text)
        assertEquals("2*3 and snake_case", Md.inline("2*3 and snake_case", open = true).text)
        assertEquals("a b c", Md.inline("a **b** c").text)
        assertEquals("a b c", Md.inline("a *b* c").text)
    }

    @Test fun unfinishedList() {
        val l = nodes("- one\n- tw").single() as Md.Node.ListBlock
        assertEquals(listOf("one", "tw"), l.items.map { it.text })
        val task = nodes("- [x] done\n- [ ] todo", streaming = false).single() as Md.Node.ListBlock
        assertEquals(listOf(true, false), task.items.map { it.task })
    }

    @Test fun tableHeaderWithoutRowsAndPartialRowWaits() {
        assertEquals(Md.Node.Table(listOf("a", "b"), emptyList()), nodes("| a | b |\n|---|---|").single())
        assertEquals(Md.Node.Table(listOf("a", "b"), emptyList()), nodes("| a | b |\n|---|---|\n| 1 | 2").single())
        // Header line before its separator arrives: held, not shown as raw pipes.
        assertEquals(Md.Node.Empty, nodes("| a | b |").single())
        assertEquals(Md.Node.Para("Text"), nodes("Text\n| a | b |").single())
    }

    @Test fun linkMidStream() {
        assertEquals("see docs", Md.inline("see [docs](https://exa", open = true).text)
        assertEquals("see docs", Md.inline("see [docs", open = true).text)
        val done = Md.inline("see [docs](https://example.com) now")
        assertEquals("see docs now", done.text)
        assertEquals("https://example.com", (done.getLinkAnnotations(0, done.length).single().item as LinkAnnotation.Url).url)
        assertFalse(Md.inline("[x] box").text.isEmpty())
    }

    @Test fun finishedBlocksStableWhileStreaming() {
        val samples = listOf(
            "# H\n\nFirst para.\n\n- a\n- b\n\n```kt\ncode\n```\n\nTail text grows" to 5,
            "Intro line\n- item one\n- item two\nAfter\n## Next\n| a | b |\n|---|---|\n| 1 | 2 |\n\n> quote\n1. x\n2. y" to 7,
        )
        for ((full, blocks) in samples) {
            var prev = emptyList<String>()
            for (end in 1..full.length) {
                val cur = Md.split(full.substring(0, end))
                // Every block but the last of the previous frame is unchanged in this frame.
                for (i in 0 until (prev.size - 1).coerceAtLeast(0)) assertEquals("frame $end block $i", prev[i], cur[i])
                prev = cur
            }
            assertEquals(blocks, prev.size)
        }
    }
}
