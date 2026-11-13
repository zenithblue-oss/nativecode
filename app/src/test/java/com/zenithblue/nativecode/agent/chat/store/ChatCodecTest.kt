package com.zenithblue.nativecode.agent.chat.store

import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.DiffStat
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.Question
import com.zenithblue.nativecode.agent.chat.core.QuestionOption
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RequestDetail
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestOption
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.TodoStep
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import com.zenithblue.nativecode.agent.chat.core.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCodecTest {
    private val att = ChatAttachment("a b.png", "image/png", 12, "/home/flux/attach_1_a_b.png", "/data/x")

    @Test fun everyPayloadRoundTrips() {
        val all = listOf(
            ItemPayload.UserMessage("hi \"there\"\n", listOf(att, att.copy(guestPath = null, hostPath = null)), "steer"),
            ItemPayload.AssistantMessage("**md**", "final"), ItemPayload.AssistantMessage("x"),
            ItemPayload.Reasoning("think", 1200), ItemPayload.Reasoning("t"),
            ItemPayload.CommandExecution("ls -la", "out", 0, 33), ItemPayload.CommandExecution("sleep"),
            ItemPayload.FileChange("a/b.kt", 3, 1, "@@ -1 +1 @@"), ItemPayload.FileChange("c"),
            ItemPayload.FileSearch("grep", "foo", listOf("a", "b")), ItemPayload.WebSearch("q", listOf("u")),
            ItemPayload.DynamicTool("mcp", "{}", "ok"), ItemPayload.Subagent("explore", 2, "done"), ItemPayload.Subagent("s"),
            ItemPayload.TodoList(listOf(TodoStep("a", "pending"), TodoStep("b", "completed"))),
            ItemPayload.ProposedPlan("# plan"), ItemPayload.ApprovalRequest("r1"), ItemPayload.UserInputRequest("r2"),
            ItemPayload.Error(FailureClass.USAGE_LIMIT, "limit", 99, "d"), ItemPayload.Error(FailureClass.GATE_DENIED, "x"),
            ItemPayload.Compaction(4000), ItemPayload.Compaction(), ItemPayload.SystemNotice("Session ended"),
        )
        for (p in all) assertEquals(p, ChatCodec.payload(p.type, ChatCodec.payload(p)))
    }

    @Test fun badRowsDecodeSafely() {
        assertEquals(ItemPayload.SystemNotice("[weird]"), ChatCodec.payload("weird", "{}"))
        assertEquals(ItemPayload.AssistantMessage(""), ChatCodec.payload("assistant_message", "not json"))
        assertNull(ChatCodec.usage("nope"))
        assertTrue(ChatCodec.options("[").isEmpty())
    }

    @Test fun requestPartsRoundTrip() {
        val opts = listOf(RequestOption("once", "allow_once", "Allow once"), RequestOption("always", "allow_always", "Always", "persists"))
        assertEquals(opts, ChatCodec.options(ChatCodec.options(opts)))
        val d = RequestDetail("Run command", "rm -rf x", listOf("a", "b"), "diff", "why",
            listOf(Question("q1", "H", "Pick", listOf(QuestionOption("A", "first"), QuestionOption("B")), multiple = true, custom = false)), "plan")
        assertEquals(d, ChatCodec.detail(ChatCodec.detail(d)))
        assertEquals(RequestDetail("t"), ChatCodec.detail(ChatCodec.detail(RequestDetail("t"))))
        val a = RequestAnswer("once", mapOf("q1" to listOf("A", "B")), false, "fb")
        assertEquals(a, ChatCodec.answer(ChatCodec.answer(a)))
        assertEquals(RequestAnswer(cancel = true), ChatCodec.answer(ChatCodec.answer(RequestAnswer(cancel = true))))
    }

    @Test fun runPartsRoundTrip() {
        val u = Usage(1, 2, 3, 4, 5, 0.25, 100, 200000)
        assertEquals(u, ChatCodec.usage(ChatCodec.usage(u)))
        assertEquals(Usage(), ChatCodec.usage(ChatCodec.usage(Usage())))
        val f = Failure(FailureClass.TRANSPORT_ERROR, "gone", 5, "tail")
        assertEquals(f, ChatCodec.failure(ChatCodec.failure(f)))
        val s = listOf(DiffStat("a", "M", 1, 2), DiffStat("b", "A", 3, 0))
        assertEquals(s, ChatCodec.diffStat(ChatCodec.diffStat(s)))
        assertEquals(listOf(att), ChatCodec.attachments(ChatCodec.attachments(listOf(att))))
    }

    @Test fun recoveryInterruptsRunsExpiresApprovalsKeepsInputAndQueue() {
        val running = Run("r1", "t", 0, RunStatus.RUNNING)
        val waiting = Run("r2", "t2", 3, RunStatus.WAITING)
        val queued = Run("r3", "t", 1, RunStatus.QUEUED, queuePosition = 0)
        val approval = ChatRequest("q1", "t", "r1", "i1", "n1", RequestKind.COMMAND, RequestStatus.PENDING, emptyList(), RequestDetail("cmd"), createdAt = 1)
        val input = ChatRequest("q2", "t", "r1", "i2", "n2", RequestKind.USER_INPUT, RequestStatus.PENDING, emptyList(), RequestDetail("ask"), createdAt = 1)
        var n = 0
        val w = ChatRecovery.plan(listOf(running, waiting, queued), listOf(approval, input), { if (it == "t") 10 else 20 }, 7) { "id${n++}" }

        val runs = w.filterIsInstance<ChatWrite.PutRun>().map { it.r }
        assertEquals(listOf("r1" to RunStatus.INTERRUPTED, "r2" to RunStatus.INTERRUPTED), runs.map { it.id to it.status })
        assertTrue(runs.all { it.completedAt == 7L })
        val notices = w.filterIsInstance<ChatWrite.PutItem>().map { it.i }
        assertEquals(listOf(10, 20), notices.map { it.ordinal })
        assertTrue(notices.all { it.payload == ItemPayload.SystemNotice("Session ended") && it.status == ItemStatus.COMPLETED })
        assertEquals(2, w.filterIsInstance<ChatWrite.SettleItems>().size)
        val reqs = w.filterIsInstance<ChatWrite.PutRequest>().map { it.r }
        assertEquals(listOf("q1" to RequestStatus.EXPIRED), reqs.map { it.id to it.status })
        val upd = w.filterIsInstance<ChatWrite.UpdateItem>().single()
        assertEquals(ItemStatus.CANCELLED, upd.f(TurnItem("i1", "t", "r1", 2, null, null, ItemStatus.WAITING, ItemPayload.ApprovalRequest("q1"), 0)).status)
    }
}
