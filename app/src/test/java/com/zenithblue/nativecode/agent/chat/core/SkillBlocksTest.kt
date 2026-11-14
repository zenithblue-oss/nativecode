package com.zenithblue.nativecode.agent.chat.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillBlocksTest {
    @Test fun roundTrip() {
        val a = SkillBlocks.Block("Host Agent", "# Host\nuse \$HOME\n\n</not_end>")
        val b = SkillBlocks.Block("Say \"hi\"", "body\n")
        val text = SkillBlocks.wrap(listOf(a, b), "do it\nnow")
        val (blocks, rest) = SkillBlocks.split(text)
        assertEquals(listOf(a, SkillBlocks.Block("Say 'hi'", "body")), blocks)
        assertEquals("do it\nnow", rest)
        assertEquals("do it\nnow", SkillBlocks.plain(text))
        assertEquals("Skill: Host Agent", SkillBlocks.plain(SkillBlocks.wrap(listOf(a), "")))
        assertEquals("plain <nc_skill name=\"x\">\ny\n</nc_skill>\n\n", SkillBlocks.split("plain <nc_skill name=\"x\">\ny\n</nc_skill>\n\n").second)
    }
}
