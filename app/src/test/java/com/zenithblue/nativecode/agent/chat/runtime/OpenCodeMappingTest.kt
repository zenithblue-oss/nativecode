package com.zenithblue.nativecode.agent.chat.runtime

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.AgentEvent.ItemUpsert
import com.zenithblue.nativecode.agent.chat.core.AgentEvent.RequestUpsert
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays SSE recorded from the real opencode 2.3.0 binary (`app/src/test/resources/agent/opencode-2.3.0/`). */
class OpenCodeMappingTest {
    private fun resource(name: String) = javaClass.getResource("/agent/opencode-2.3.0/$name")!!.readText()

    private fun replay(file: String, root: String, firstPrompt: String? = null): List<AgentEvent> {
        val out = ArrayList<AgentEvent>()
        val m = OpenCodeMapper({ out += it })
        m.root = root
        firstPrompt?.let(m::expectTurn)
        SseParser { m.handle(it.data) }.feed(resource(file))
        return out
    }

    private fun List<AgentEvent>.items(ref: String) = filterIsInstance<ItemUpsert>().filter { it.nativeRef == ref }
    private fun List<AgentEvent>.terminals() = filterIsInstance<AgentEvent.TurnTerminal>().map { it.status }

    @Test fun approveThenDecline() {
        val ev = replay("approve-decline.sse", "ses_e2ac30a3cffe76fHIsHeW9CmRd", "msg_nc_1")
        assertEquals(listOf("msg_nc_1", null), ev.filterIsInstance<AgentEvent.TurnStarted>().map { it.nativeTurnId })
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.COMPLETED), ev.terminals())
        assertTrue(ev.none { it is AgentEvent.Unknown })

        // shell approval: agent's exact options, then resolved
        val ask = ev.filterIsInstance<RequestUpsert>().filter { it.nativeId == "per_1d53cffe1001e7iw5WkW2NrDri" }
        assertEquals(listOf(RequestStatus.PENDING, RequestStatus.RESOLVED), ask.map { it.status })
        assertEquals(RequestKind.COMMAND, ask[0].kind)
        assertEquals("echo hello-fixture", ask[0].detail.command)
        assertEquals(listOf("cancel", "reject", "session", "once"), ask[0].options.map { it.id })
        assertEquals("Allow echo * this session", ask[0].options[2].label)

        val shell = ev.items("call_50d7029509134daf8b856c96")
        assertEquals(listOf(ItemStatus.RUNNING, ItemStatus.RUNNING, ItemStatus.COMPLETED), shell.map { it.status })
        val cmd = shell.last().payload as ItemPayload.CommandExecution
        assertEquals("echo hello-fixture", cmd.command)
        assertEquals("hello-fixture\n", cmd.outputTail)
        assertEquals(0, cmd.exitCode)
        assertNull(shell.last().parentRef)

        // edit approval carries the proposed diff; declined write ends failed
        val edit = ev.filterIsInstance<RequestUpsert>().first { it.nativeId == "per_1d53d1017001tLE1kc2l3UcIDi" }
        assertEquals(RequestKind.FILE_CHANGE, edit.kind)
        assertEquals(listOf("note.txt"), edit.detail.paths)
        assertTrue(edit.detail.diff!!.contains("+hi"))
        assertEquals("Allow every edit request this session", edit.options[2].label)
        val write = ev.items("call_c8201850470a48318f633200").last()
        assertEquals(ItemStatus.FAILED, write.status)
        assertEquals("note.txt", (write.payload as ItemPayload.FileChange).path)

        // text snapshots accumulate to the full text
        val texts = ev.items("msg_1d53d104b00170CWcV5UVVP0lS:text:0").map { (it.payload as ItemPayload.AssistantMessage).text }
        texts.zipWithNext().forEach { (a, b) -> assertTrue(b.startsWith(a)) }
        assertTrue(texts.last().startsWith("The write was declined, so `note.txt` was not created."))
        assertEquals(ItemStatus.COMPLETED, ev.items("msg_1d53d104b00170CWcV5UVVP0lS:text:0").last().status)
        assertTrue(ev.items("msg_1d53d104b00170CWcV5UVVP0lS:reasoning:0").last().payload is ItemPayload.Reasoning)

        // usage = per-turn sum of step.ended, context = last step
        val firstTurnUsage = ev.subList(0, ev.indexOfFirst { it is AgentEvent.TurnTerminal }).filterIsInstance<AgentEvent.UsageUpdate>().last().usage
        assertEquals(3744L, firstTurnUsage.inputTokens)
        assertEquals(44L, firstTurnUsage.outputTokens)
        assertEquals(7168L, firstTurnUsage.cacheReadTokens)
        assertEquals(0.0, firstTurnUsage.costUsd!!, 0.0)
        assertEquals(108L + 3 + 5376, firstTurnUsage.contextUsed)
    }

    @Test fun subagentFormAndInterrupt() {
        val ev = replay("subagent-form-interrupt.sse", "ses_e2ac26c70ffeP0HSzRwTvVjESc", "msg_nc_b1")
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.INTERRUPTED), ev.terminals())

        val call = "call_c6b63c8606e64ecc86410492"
        val sub = ev.items(call)
        assertEquals(ItemStatus.COMPLETED, sub.last().status)
        val p = sub.last().payload as ItemPayload.Subagent
        assertEquals("List current directory files", p.title)
        assertEquals(1, p.childCount)
        assertTrue(p.result!!.contains("empty"))
        // child items hang under the subagent call
        val read = ev.items("call_06355100d1ca4d4ea72aaa51").last()
        assertEquals(call, read.parentRef)
        assertEquals("read", (read.payload as ItemPayload.FileSearch).tool)
        assertTrue(ev.filterIsInstance<ItemUpsert>().filter { it.nativeRef.startsWith("msg_1d53dd0b2001UlNQKl6Af43YQW") }.all { it.parentRef == call })

        // codemode `execute` → dynamic tool; `question` has no item (the form is the request)
        assertTrue(ev.items("call_c86fab5610d4485fb0a043b3").last().payload is ItemPayload.DynamicTool)
        assertTrue(ev.items("call_e4e59fd62c504dcda076fdd5").isEmpty())
        val form = ev.filterIsInstance<RequestUpsert>().filter { it.nativeId == "frm_1d53dd152001Pf9qgDXE5zwPRG" }
        assertEquals(listOf(RequestStatus.PENDING, RequestStatus.RESOLVED), form.map { it.status })
        assertEquals(RequestKind.USER_INPUT, form[0].kind)
        val q = form[0].detail.questions.single()
        assertEquals("q0", q.id)
        assertEquals("Continue?", q.text)
        assertEquals(listOf("Yes", "No"), q.options.map { it.label })

        // interrupted shell
        assertEquals(ItemStatus.INTERRUPTED, ev.items("call_713a8711ea314bd8960008c7").last().status)
    }

    @Test fun undecodableFramesAreSkippedButExecutionEndIsRecovered() {
        val out = ArrayList<AgentEvent>()
        val m = OpenCodeMapper({ out += it })
        m.root = "ses_r"
        m.expectTurn("msg_t")
        m.handle("""{"id":"evt_1","type":"session.execution.started","data":{"sessionID":"ses_r"}}""")
        m.handle("""{"id":"evt_2","type":"session.text.delta","data":{"sessionID":"ses_r"}}""") // missing fields
        m.handle("""{"id":"evt_3","type":"session.brand.new","data":{"sessionID":"ses_r"}}""")
        m.handle("""{"id":"evt_4","type":"session.execution.succeeded","data":{"sessionID":"ses_other"}}""")
        m.handle("""{"id":"evt_5","type":"session.execution.succeeded","durable":{"seq":"bad"},"data":{"sessionID":"ses_r","x":""") // truncated
        assertEquals(AgentEvent.TurnStarted("msg_t"), out[0])
        assertEquals(2, out.count { it is AgentEvent.Unknown })
        assertEquals(listOf(RunStatus.COMPLETED), out.terminals())
        assertTrue(!m.active)
    }

    @Test fun replayAfterReconnectFinishesItemsAndEndsTheTurn() {
        val out = ArrayList<AgentEvent>()
        val m = OpenCodeMapper({ out += it })
        m.root = "ses_e2ac30a3cffe76fHIsHeW9CmRd"
        m.handle("""{"id":"evt_1","type":"session.execution.started","data":{"sessionID":"ses_e2ac30a3cffe76fHIsHeW9CmRd"}}""")
        m.replay(JSONObject(resource("messages.json")).getJSONArray("data"))
        assertEquals(listOf(RunStatus.COMPLETED), out.terminals())
        assertEquals(ItemStatus.FAILED, out.items("call_c8201850470a48318f633200").single().status)
        val text = out.items("msg_1d53d104b00170CWcV5UVVP0lS:text:0").single()
        assertTrue((text.payload as ItemPayload.AssistantMessage).text.startsWith("The write was declined"))
        // older turn (before the newest user message) is not replayed
        assertTrue(out.items("call_50d7029509134daf8b856c96").isEmpty())
    }

    @Test fun unsupportedFormIsCancelled() {
        val cancelled = ArrayList<String>()
        val out = ArrayList<AgentEvent>()
        val m = OpenCodeMapper({ out += it }) { _, fid -> cancelled += fid }
        m.root = "ses_r"
        m.handle("""{"id":"e","type":"form.created","data":{"form":{"id":"frm_1","sessionID":"ses_r","title":"T","fields":[{"key":"k","type":"external","url":"https://x"}]}}}""")
        assertEquals(listOf("frm_1"), cancelled)
        assertTrue(out.none { it is RequestUpsert })
    }
}
