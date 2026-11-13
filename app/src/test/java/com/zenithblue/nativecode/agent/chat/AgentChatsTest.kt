package com.zenithblue.nativecode.agent.chat

import com.zenithblue.nativecode.agent.chat.core.PipeProcess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AgentChatsTest {
    private fun entry(tool: String, project: String?) =
        AgentChats.Entry(tool, project, PipeProcess(listOf("true"), emptyMap(), File("."), {}, { _, _ -> }))

    @After fun clear() { AgentChats.stopAll(); AgentChats.onChanged = null }

    @Test fun countsStartingAndLivePerScope() {
        var changes = 0
        AgentChats.onChanged = { changes++ }
        val a = entry("opencode", null).also { AgentChats.add(it) }
        val b = entry("claude", "/p").also { AgentChats.add(it) }
        // both still starting: they hold a session slot before the handshake ends
        assertEquals(2, AgentChats.startingCount())
        assertEquals(0, AgentChats.liveCount())
        AgentChats.markLive(a)
        assertEquals(1, AgentChats.startingCount())
        assertEquals(1, AgentChats.liveCount())
        assertEquals(1, AgentChats.countFor(null))
        assertEquals(listOf("chat: claude"), AgentChats.toolsFor("/p"))
        assertEquals(setOf("/p"), AgentChats.projectPaths())
        AgentChats.stopProject("/p")
        assertEquals(0, AgentChats.countFor("/p"))
        AgentChats.remove(b) // already gone: no extra change event
        AgentChats.stopAll()
        assertEquals(0, AgentChats.liveCount() + AgentChats.startingCount())
        assertEquals(5, changes)
    }
}
