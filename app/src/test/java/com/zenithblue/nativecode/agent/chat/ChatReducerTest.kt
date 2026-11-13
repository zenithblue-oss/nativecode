package com.zenithblue.nativecode.agent.chat

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RequestDetail
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestOption
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.RuntimeCaps
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import com.zenithblue.nativecode.agent.chat.core.Usage
import com.zenithblue.nativecode.agent.chat.store.ChatWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake runtime events → reducer → store writes / effects / live snapshot (plan 9.2.3, 9.2.8). */
class ChatReducerTest {
    private var n = 0
    private var clock = 1000L
    private val thread = ChatThread("t", null, "/home/flux", "opencode", ChatReducer.DEFAULT_TITLE, createdAt = 0, updatedAt = 0)
    private fun reducer() = ChatReducer(thread, 0, 0, now = { clock++ }, newId = { "id${n++}" })

    /** Applies writes to an in-memory projection (what ChatStore would hold). */
    private class Rows {
        val items = LinkedHashMap<String, TurnItem>()
        val runs = LinkedHashMap<String, com.zenithblue.nativecode.agent.chat.core.Run>()
        val requests = LinkedHashMap<String, com.zenithblue.nativecode.agent.chat.core.ChatRequest>()
        var thread: ChatThread? = null
        fun apply(ws: List<ChatWrite>) = ws.forEach { w ->
            when (w) {
                is ChatWrite.PutItem -> items[w.i.id] = w.i
                is ChatWrite.UpdateItem -> items[w.id]?.let { items[w.id] = w.f(it) }
                is ChatWrite.PutRun -> runs[w.r.id] = w.r
                is ChatWrite.UpdateRun -> runs[w.id]?.let { runs[w.id] = w.f(it) }
                is ChatWrite.PutRequest -> requests[w.r.id] = w.r
                is ChatWrite.UpdateThread -> thread = w.f(thread ?: return@forEach)
                is ChatWrite.DeleteRun -> { runs.remove(w.id); items.values.removeIf { it.runId == w.id } }
                is ChatWrite.SettleItems, is ChatWrite.PutThread, is ChatWrite.DeleteThread -> Unit
            }
        }
    }

    private fun ChatReducer.step(rows: Rows, e: AgentEvent? = null): List<ChatReducer.Effect> {
        e?.let(::reduce)
        flushDirty()
        val (w, fx) = drain()
        rows.apply(w)
        return fx
    }

    @Test fun firstTokenPerRunAndReconnecting() {
        val r = reducer(); val rows = Rows().apply { thread = this@ChatReducerTest.thread }
        val run1 = r.beginRun("c1", "hi", emptyList())
        r.step(rows, AgentEvent.TurnStarted("n1"))
        r.step(rows, AgentEvent.ItemUpsert("tool", ItemStatus.RUNNING, ItemPayload.CommandExecution("ls")))
        assertNull(r.state.firstTokenMs) // tools do not count
        clock += 1500
        r.step(rows, AgentEvent.ItemUpsert("a1", ItemStatus.RUNNING, ItemPayload.AssistantMessage("He")))
        val ttft = r.state.firstTokenMs!!
        assertTrue(ttft >= 1500)
        assertEquals(run1.id, r.state.firstTokenRunId)
        r.step(rows, AgentEvent.ItemUpsert("a1", ItemStatus.RUNNING, ItemPayload.AssistantMessage("Hello")))
        assertEquals(ttft, r.state.firstTokenMs) // first token only
        r.step(rows, AgentEvent.TurnTerminal(RunStatus.COMPLETED))
        val run2 = r.beginRun("c2", "again", emptyList())
        r.step(rows, AgentEvent.TurnStarted("n2"))
        r.step(rows, AgentEvent.ItemUpsert("a2", ItemStatus.RUNNING, ItemPayload.Reasoning("think")))
        assertEquals(run2.id, r.state.firstTokenRunId)

        r.step(rows, AgentEvent.Connection(true))
        assertTrue(r.state.reconnecting)
        r.step(rows, AgentEvent.Connection(false))
        assertFalse(r.state.reconnecting)
        r.step(rows, AgentEvent.Connection(true))
        r.exited(1, "", expected = false)
        assertFalse(r.state.reconnecting)
    }

    @Test fun fullTurnWithApproval() {
        val r = reducer(); val rows = Rows().apply { thread = this@ChatReducerTest.thread }
        val run = r.beginRun("c1", "Fix the login crash\nplease", emptyList())
        rows.apply(r.drain().first)
        assertEquals("Fix the login crash", rows.thread!!.title)
        assertEquals(RunStatus.STARTING, rows.runs[run.id]!!.status)
        assertEquals(run.id, r.state.runningRunId)

        assertEquals(listOf(ChatReducer.Effect.Ready), r.step(rows, AgentEvent.SessionState(AgentEvent.Phase.READY, caps = RuntimeCaps(steer = true))))
        assertTrue(r.state.caps!!.steer)
        r.step(rows, AgentEvent.NativeId("ses_1"))
        assertEquals("ses_1", rows.thread!!.nativeId)
        r.step(rows, AgentEvent.TurnStarted("turn1"))
        assertEquals(RunStatus.RUNNING, rows.runs[run.id]!!.status)
        assertEquals(LivePhase.RUNNING, r.state.phase)

        // streaming snapshots upsert one row keyed by native ref
        r.reduce(AgentEvent.ItemUpsert("m1", ItemStatus.RUNNING, ItemPayload.AssistantMessage("Hel")))
        r.reduce(AgentEvent.ItemUpsert("m1", ItemStatus.RUNNING, ItemPayload.AssistantMessage("Hello")))
        assertTrue(r.hasDirty())
        assertEquals(ItemPayload.AssistantMessage("Hello"), r.state.liveItems.values.single { it.nativeRef == "m1" }.payload)
        r.step(rows)
        assertEquals(1, rows.items.values.count { it.nativeRef == "m1" })
        // runtime echo of the user message never overwrites the client's item
        r.step(rows, AgentEvent.ItemUpsert("c1", ItemStatus.COMPLETED, ItemPayload.UserMessage("echo")))
        assertEquals("Fix the login crash\nplease", (rows.items.values.single { it.nativeRef == "c1" }.payload as ItemPayload.UserMessage).text)

        val fx = r.step(rows, AgentEvent.RequestUpsert("perm1", RequestKind.COMMAND, RequestStatus.PENDING, emptyList(), RequestDetail("Run ls", "ls")))
        val req = (fx.single() as ChatReducer.Effect.NeedsAttention).request
        assertEquals(RunStatus.WAITING, rows.runs[run.id]!!.status)
        assertEquals(ItemPayload.ApprovalRequest(req.id), rows.items[req.itemId]!!.payload)
        assertEquals(LivePhase.WAITING, r.state.phase)

        val (answered, live) = r.answer(req.id, RequestAnswer("once"))!!
        assertTrue(live)
        rows.apply(r.drain().first)
        assertEquals(RequestStatus.RESOLVED, rows.requests[req.id]!!.status)
        assertEquals(answered.answerJson, rows.requests[req.id]!!.answerJson)
        assertEquals(RunStatus.RUNNING, rows.runs[run.id]!!.status)

        r.step(rows, AgentEvent.ItemUpsert("cmd1", ItemStatus.RUNNING, ItemPayload.CommandExecution("ls")))
        r.step(rows, AgentEvent.CommandOutputDelta("cmd1", "a.txt\n"))
        assertEquals("a.txt\n", (rows.items.values.single { it.nativeRef == "cmd1" }.payload as ItemPayload.CommandExecution).outputTail)
        r.step(rows, AgentEvent.UsageUpdate(Usage(10, 20)))
        val end = r.step(rows, AgentEvent.TurnTerminal(RunStatus.COMPLETED))
        val done = (end.single() as ChatReducer.Effect.TurnEnded).run
        assertEquals(RunStatus.COMPLETED, done.status)
        assertEquals(Usage(10, 20), rows.runs[run.id]!!.usage)
        assertEquals(RunStatus.COMPLETED, rows.runs[run.id]!!.status)
        // items still open at turn end are settled
        assertEquals(ItemStatus.COMPLETED, rows.items.values.single { it.nativeRef == "cmd1" }.status)
        assertNull(r.state.runningRunId)
        assertEquals(LivePhase.READY, r.state.phase)
    }

    /** The approval row says what the user chose: Decline = declined, Cancel = cancelled, Approve = completed. */
    @Test fun approvalRowShowsDecision() {
        val r = reducer(); val rows = Rows().apply { thread = this@ChatReducerTest.thread }
        r.beginRun("c1", "go", emptyList())
        r.step(rows, AgentEvent.SessionState(AgentEvent.Phase.READY))
        r.step(rows, AgentEvent.TurnStarted("t1"))
        val opts = listOf(RequestOption("cancel", "cancel", "Cancel"), RequestOption("reject", "reject", "Decline"), RequestOption("once", "allow_once", "Approve"))
        fun ask(id: String): ChatRequest {
            val fx = r.step(rows, AgentEvent.RequestUpsert(id, RequestKind.COMMAND, RequestStatus.PENDING, opts, RequestDetail("Run ls", "ls")))
            return (fx.single() as ChatReducer.Effect.NeedsAttention).request
        }
        for ((option, want) in listOf("reject" to ItemStatus.DECLINED, "cancel" to ItemStatus.CANCELLED, "once" to ItemStatus.COMPLETED)) {
            val req = ask("p-$option")
            r.answer(req.id, RequestAnswer(option, feedback = "no".takeIf { option == "reject" }))
            rows.apply(r.drain().first)
            assertEquals(option, want, rows.items[req.itemId]!!.status)
        }
    }

    @Test fun gateDeniedExit() {
        val r = reducer(); val rows = Rows().apply { thread = this@ChatReducerTest.thread }
        val run = r.beginRun("c1", "hi", emptyList())
        r.starting()
        rows.apply(r.drain().first)
        r.exited(1, "proot noise\nNativeCode: AI session limit reached (1/1)\n", expected = false)
        val (w, fx) = r.drain(); rows.apply(w)
        assertTrue(fx.any { it is ChatReducer.Effect.GateDenied && it.message.startsWith("NativeCode:") })
        assertEquals(RunStatus.FAILED, rows.runs[run.id]!!.status)
        assertEquals(FailureClass.GATE_DENIED, rows.runs[run.id]!!.failure!!.cls)
        assertTrue(rows.items.values.any { (it.payload as? ItemPayload.Error)?.cls == FailureClass.GATE_DENIED })
        assertEquals(BannerKind.GATE_DENIED, r.state.banner!!.kind)
        assertEquals(LivePhase.IDLE, r.state.phase)
    }

    @Test fun unexpectedExitFailsRunAndExpiresRequests() {
        val r = reducer(); val rows = Rows().apply { thread = this@ChatReducerTest.thread }
        val run = r.beginRun("c1", "hi", emptyList())
        r.step(rows, AgentEvent.SessionState(AgentEvent.Phase.READY))
        r.step(rows, AgentEvent.TurnStarted(null))
        r.step(rows, AgentEvent.ItemUpsert("m1", ItemStatus.RUNNING, ItemPayload.AssistantMessage("partial")))
        r.step(rows, AgentEvent.RequestUpsert("p1", RequestKind.FILE_CHANGE, RequestStatus.PENDING, emptyList(), RequestDetail("edit")))
        r.step(rows, AgentEvent.RequestUpsert("f1", RequestKind.USER_INPUT, RequestStatus.PENDING, emptyList(), RequestDetail("ask")))
        r.enqueue("c2", "next", emptyList())
        r.exited(137, "killed", expected = false)
        val (w, fx) = r.drain(); rows.apply(w)
        assertTrue(ChatReducer.Effect.SessionLost in fx)
        val failed = rows.runs[run.id]!!
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(FailureClass.TRANSPORT_ERROR, failed.failure!!.cls)
        assertEquals("killed", failed.failure!!.details)
        assertTrue(rows.requests.values.all { it.status == RequestStatus.EXPIRED })
        assertEquals(ItemStatus.FAILED, rows.items.values.single { it.nativeRef == "m1" }.status)
        assertEquals(LivePhase.ENDED, r.state.phase)
        assertEquals(BannerKind.SESSION_ENDED, r.state.banner!!.kind)
        assertTrue(r.state.queueHeld)
    }

    @Test fun queueThenDequeueAndSteer() {
        val r = reducer(); val rows = Rows().apply { thread = this@ChatReducerTest.thread }
        r.beginRun("c1", "one", emptyList())
        val q1 = r.enqueue("c2", "two", emptyList())
        val q2 = r.enqueue("c3", "three", emptyList())
        rows.apply(r.drain().first)
        assertEquals(RunStatus.QUEUED, rows.runs[q1.run.id]!!.status)
        assertEquals(listOf(0, 1), r.state.queued.map { it.run.queuePosition })
        r.editQueued(q2.run.id, "three!")
        assertEquals("three!", r.state.queued[1].text)
        r.steerQueued(q2.run.id)
        rows.apply(r.drain().first)
        assertFalse(rows.runs.containsKey(q2.run.id))
        assertEquals("steer", (rows.items.values.single { it.nativeRef == "c3" }.payload as ItemPayload.UserMessage).intent)
        r.step(rows, AgentEvent.TurnTerminal(RunStatus.COMPLETED))
        val next = r.dequeue()!!
        rows.apply(r.drain().first)
        assertEquals(q1.run.id, next.run.id)
        assertEquals(RunStatus.STARTING, rows.runs[q1.run.id]!!.status)
        assertNull(rows.runs[q1.run.id]!!.queuePosition)
        // moved to the end of the timeline
        assertEquals(rows.items.values.maxOf { it.ordinal }, rows.items.values.single { it.nativeRef == "c2" }.ordinal)
        assertTrue(r.state.queued.isEmpty())
    }

    @Test fun turnEndKeepsUserInputAnswerableAsMessage() {
        val r = reducer(); val rows = Rows().apply { thread = this@ChatReducerTest.thread }
        r.beginRun("c1", "hi", emptyList())
        r.step(rows, AgentEvent.TurnStarted(null))
        val fx = r.step(rows, AgentEvent.RequestUpsert("f1", RequestKind.USER_INPUT, RequestStatus.PENDING, emptyList(), RequestDetail("ask")))
        val req = (fx.single() as ChatReducer.Effect.NeedsAttention).request
        r.step(rows, AgentEvent.TurnTerminal(RunStatus.INTERRUPTED))
        assertEquals(RequestStatus.PENDING, rows.requests[req.id]!!.status)
        val (_, live) = r.answer(req.id, RequestAnswer(answers = mapOf("q" to listOf("yes"))))!!
        assertFalse(live)
        rows.apply(r.drain().first)
        assertEquals(RequestStatus.RESOLVED, rows.requests[req.id]!!.status)
    }

    @Test fun noticesAndResumeFallback() {
        val r = reducer(); val rows = Rows()
        r.step(rows, AgentEvent.Notice("resume_failed", "x"))
        r.step(rows, AgentEvent.Notice("compaction", "compacted"))
        r.step(rows, AgentEvent.Notice("retry", "Retrying"))
        assertEquals(listOf(ItemPayload.SystemNotice(ChatReducer.RESUME_FAILED), ItemPayload.Compaction(null), ItemPayload.SystemNotice("Retrying")),
            rows.items.values.map { it.payload })
    }

    @Test fun expectedExitInterruptsQuietly() {
        val r = reducer(); val rows = Rows()
        val run = r.beginRun("c1", "hi", emptyList())
        r.exited(0, "", expected = true)
        val (w, fx) = r.drain(); rows.apply(w)
        assertEquals(RunStatus.INTERRUPTED, rows.runs[run.id]!!.status)
        assertFalse(fx.any { it is ChatReducer.Effect.SessionLost || it is ChatReducer.Effect.GateDenied })
        assertNull(r.state.banner)
    }

    @Test fun agentReportedModeAndModelPersist() {
        val r = reducer(); val rows = Rows().apply { thread = this@ChatReducerTest.thread }
        r.step(rows, AgentEvent.SessionState(AgentEvent.Phase.READY,
            model = "sonnet", runtimeMode = com.zenithblue.nativecode.agent.chat.core.RuntimeMode.AUTO_ACCEPT_EDITS))
        assertEquals("sonnet", r.thread.model)
        assertEquals("sonnet", rows.thread!!.model)
        assertEquals(com.zenithblue.nativecode.agent.chat.core.RuntimeMode.AUTO_ACCEPT_EDITS, rows.thread!!.runtimeMode)
        val before = n
        r.step(rows, AgentEvent.SessionState(AgentEvent.Phase.READY)) // nulls = unchanged, no write
        assertEquals("sonnet", rows.thread!!.model)
        assertEquals(before, n)
    }
}
