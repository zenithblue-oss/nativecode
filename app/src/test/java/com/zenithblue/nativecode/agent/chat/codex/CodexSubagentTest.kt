package com.zenithblue.nativecode.agent.chat.codex

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.AgentEvent.ItemUpsert
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A codex subagent's thread streams on the same pipe; its rows nest under the spawnAgent row and never touch the main run. */
class CodexSubagentTest {
    private fun n(s: String) = JSONObject(s)

    @Test fun childThreadNestsUnderSpawnRowAndMainTurnStillEnds() {
        val ev = ArrayList<AgentEvent>()
        val m = CodexMapper("n") { ev += it }
        m.mainThreadId = "main"
        m.beginTurn()
        m.notification("turn/started", n("""{"threadId":"main","turn":{"id":"t1"}}"""))
        m.notification("item/started", n("""{"threadId":"main","turnId":"t1","item":{"id":"c1","type":"collabAgentToolCall","tool":"spawnAgent","prompt":"count files","status":"inProgress","receiverThreadIds":["kid"]}}"""))
        // the child's own turn must not replace the main turn id
        m.notification("turn/started", n("""{"threadId":"kid","turn":{"id":"kt"}}"""))
        m.notification("item/started", n("""{"threadId":"kid","turnId":"kt","item":{"id":"x1","type":"commandExecution","command":"ls","status":"inProgress"}}"""))
        m.notification("turn/completed", n("""{"threadId":"kid","turn":{"id":"kt","status":"completed"}}"""))
        val child = ev.filterIsInstance<ItemUpsert>().first { it.payload is ItemPayload.CommandExecution }
        assertEquals("t1:c1", child.parentRef)
        assertTrue(m.active)
        assertEquals("t1", m.turnId)
        m.notification("turn/completed", n("""{"threadId":"main","turn":{"id":"t1","status":"completed"}}"""))
        assertTrue(!m.active)
        assertEquals(RunStatus.COMPLETED, ev.filterIsInstance<AgentEvent.TurnTerminal>().single().status)
    }

    @Test fun multiAgentV2ActivityItemMakesTheSubagentRowAndChildNestsUnderIt() {
        val ev = ArrayList<AgentEvent>()
        val m = CodexMapper("n") { ev += it }
        m.mainThreadId = "main"
        m.beginTurn()
        m.notification("item/started", n("""{"threadId":"main","turnId":"t1","item":{"id":"a1","type":"subAgentActivity","agentPath":"root/worker_1","agentThreadId":"kid","kind":"started"}}"""))
        m.notification("item/started", n("""{"threadId":"kid","turnId":"kt","item":{"id":"x1","type":"commandExecution","command":"pwd","status":"inProgress"}}"""))
        val ups = ev.filterIsInstance<ItemUpsert>()
        val row = ups.first { it.payload is ItemPayload.Subagent }
        assertEquals("worker_1", (row.payload as ItemPayload.Subagent).title)
        assertEquals(row.nativeRef, ups.first { it.payload is ItemPayload.CommandExecution }.parentRef)
        m.notification("turn/completed", n("""{"threadId":"main","turn":{"id":"t1","status":"completed"}}"""))
        assertTrue(ev.filterIsInstance<ItemUpsert>().none { it.nativeRef == row.nativeRef && it.status == com.zenithblue.nativecode.agent.chat.core.ItemStatus.INTERRUPTED })
    }

    @Test fun childWithoutAnyRowGetsALazySubagentRow() {
        val ev = ArrayList<AgentEvent>()
        val m = CodexMapper("n") { ev += it }
        m.mainThreadId = "main"
        m.beginTurn()
        m.notification("item/started", n("""{"threadId":"kid","turnId":"kt","item":{"id":"x1","type":"commandExecution","command":"ls","status":"inProgress"}}"""))
        val ups = ev.filterIsInstance<ItemUpsert>()
        assertEquals(ups.first { it.payload is ItemPayload.Subagent }.nativeRef, ups.first { it.payload is ItemPayload.CommandExecution }.parentRef)
    }

    @Test fun mainThreadItemsStayTopLevel() {
        val ev = ArrayList<AgentEvent>()
        val m = CodexMapper("n") { ev += it }
        m.mainThreadId = "main"
        m.beginTurn()
        m.notification("item/started", n("""{"threadId":"main","turnId":"t1","item":{"id":"x1","type":"commandExecution","command":"ls","status":"inProgress"}}"""))
        assertNull(ev.filterIsInstance<ItemUpsert>().single().parentRef)
    }
}
