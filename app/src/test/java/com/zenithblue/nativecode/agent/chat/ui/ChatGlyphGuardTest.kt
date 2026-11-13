package com.zenithblue.nativecode.agent.chat.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Item 12 UI rule: icons are vectors only. No arrows, shapes, dingbats, symbols or emoji in the chat UI sources. */
class ChatGlyphGuardTest {
    private fun banned(cp: Int) =
        cp in 0x2190..0x21FF || cp in 0x2300..0x23FF || cp in 0x2500..0x27BF || cp in 0x2900..0x2BFF ||
            cp in 0x1F000..0x1FFFF || cp == 0x2022 || cp == 0x203A || cp == 0x2039 || cp == 0x00BB || cp == 0x00AB || cp == 0xFE0F

    @Test fun noGlyphIconsInChatUi() {
        val dir = listOf("src/main/java", "app/src/main/java").map { File(it, "com/zenithblue/nativecode/agent/chat/ui") }.first { it.isDirectory }
        val hits = dir.listFiles { f -> f.extension == "kt" }!!.flatMap { f ->
            // Literal characters and \uXXXX escapes both count.
            val src = Regex("\\\\u([0-9a-fA-F]{4})").replace(f.readText()) { it.groupValues[1].toInt(16).toChar().toString() }
            src.lines().withIndex().flatMap { (n, line) ->
                line.codePoints().toArray().filter(::banned).map { "${f.name}:${n + 1} U+%04X".format(it) }
            }
        }
        assertTrue("glyphs used as icons: $hits", hits.isEmpty())
    }
}
