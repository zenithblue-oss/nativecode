package com.zenithblue.nativecode.agent.chat.ui

import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.RequestDetail
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.TodoStep
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogicTest {
    private fun thread(id: String, project: String? = null, updated: Long = 0, pinned: Long? = null, archived: Long? = null, visited: Long? = null) =
        ChatThread(id, project, project ?: "/home/flux", "opencode", id, pinnedAt = pinned, archivedAt = archived, lastVisitedAt = visited, createdAt = 0, updatedAt = updated)

    private fun run(status: RunStatus, completed: Long? = null) = Run("r", "t", 1, status, completedAt = completed)
    private fun req(kind: RequestKind, status: RequestStatus = RequestStatus.PENDING) =
        ChatRequest("q", "t", null, null, "n", kind, status, emptyList(), RequestDetail("x"), createdAt = 0)

    @Test fun statusPriority() {
        val t = thread("a", visited = 10)
        assertEquals(ThreadStatus.APPROVAL, ChatLogic.status(t, run(RunStatus.RUNNING), listOf(req(RequestKind.USER_INPUT), req(RequestKind.COMMAND))))
        assertEquals(ThreadStatus.INPUT, ChatLogic.status(t, run(RunStatus.RUNNING), listOf(req(RequestKind.USER_INPUT))))
        assertEquals(ThreadStatus.WORKING, ChatLogic.status(t, run(RunStatus.WAITING), listOf(req(RequestKind.COMMAND, RequestStatus.RESOLVED))))
        assertEquals(ThreadStatus.FAILED, ChatLogic.status(t, run(RunStatus.FAILED), emptyList()))
        assertEquals(ThreadStatus.DONE, ChatLogic.status(t, run(RunStatus.COMPLETED, completed = 20), emptyList()))
        assertEquals(ThreadStatus.IDLE, ChatLogic.status(t, run(RunStatus.COMPLETED, completed = 5), emptyList()))
        assertEquals(ThreadStatus.IDLE, ChatLogic.status(t, null, emptyList()))
    }

    @Test fun switchPrefDefault() {
        assertTrue(ChatLogic.isChatMode(null, betaOn = true))
        assertTrue(ChatLogic.isChatMode(ChatLogic.MODE_CHAT, betaOn = true))
        assertFalse(ChatLogic.isChatMode(ChatLogic.MODE_TERMINAL, betaOn = true))
        assertFalse(ChatLogic.isChatMode(null, betaOn = false))
    }

    @Test fun groupingByProject() {
        val g = ChatLogic.group(listOf(
            thread("a", "/p/one", updated = 1), thread("b", "/p/two", updated = 5), thread("c", null, updated = 3),
            thread("d", "/p/one", updated = 2, pinned = 1), thread("e", "/p/one", archived = 9),
        ), byProject = true)
        assertEquals(listOf("/p/two", "/p/one", ChatLogic.NO_PROJECT, ChatLogic.ARCHIVED), g.map { it.key })
        assertEquals(listOf("d", "a"), g[1].threads.map { it.id }) // pinned first
        assertEquals("one", g[1].title)
        val ws = ChatLogic.group(listOf(thread("a", "/p", updated = 1), thread("b", "/p", updated = 2)), byProject = false)
        assertEquals(1, ws.size); assertEquals(listOf("b", "a"), ws[0].threads.map { it.id })
    }

    @Test fun relativeTimeAndTimer() {
        assertEquals("now", ChatLogic.relativeTime(59_000, 0))
        assertEquals("14m", ChatLogic.relativeTime(14 * 60_000L, 0))
        assertEquals("3h", ChatLogic.relativeTime(3 * 3_600_000L, 0))
        assertEquals("2d", ChatLogic.relativeTime(2 * 86_400_000L, 0))
        assertEquals("1w", ChatLogic.relativeTime(8 * 86_400_000L, 0))
        assertEquals("1:04", ChatLogic.timer(64_000))
        assertEquals("1:00:05", ChatLogic.timer(3_605_000))
    }

    @Test fun fencesSplitAndStreamingFence() {
        val b = ChatLogic.splitFences("Hi\n```kotlin\nval x = 1\n```\nbye")
        assertEquals(listOf(MdBlock.Text("Hi"), MdBlock.Code("kotlin", "val x = 1"), MdBlock.Text("bye")), b)
        assertEquals(MdBlock.Code("", "partial"), ChatLogic.splitFences("```\npartial").single())
    }

    @Test fun unifiedDiffParse() {
        val patch = """
            diff --git a/a.kt b/a.kt
            --- a/a.kt
            +++ b/a.kt
            @@ -1,2 +1,2 @@
             keep
            -old
            +new
            diff --git a/n.kt b/n.kt
            new file mode 100644
            --- /dev/null
            +++ b/n.kt
            @@ -0,0 +1 @@
            +x
        """.trimIndent()
        val f = ChatLogic.parseUnifiedDiff(patch)
        assertEquals(2, f.size)
        assertEquals("a.kt", f[0].path); assertEquals(1, f[0].add); assertEquals(1, f[0].del)
        assertEquals(DiffLine('-', "old", 2, null), f[0].lines[2])
        assertEquals('A', f[1].kind); assertEquals("n.kt", f[1].path)
        // header-less single-file hunk
        assertEquals("p.kt", ChatLogic.parseUnifiedDiff("@@ -1 +1 @@\n-a\n+b", "p.kt").single().path)
    }

    @Test fun rowsGroupWorkAndMeta() {
        fun item(id: String, run: String, p: ItemPayload) = TurnItem(id, "t", run, id.hashCode(), null, null, ItemStatus.COMPLETED, p, 0)
        val items = listOf(
            item("u", "r1", ItemPayload.UserMessage("hi")),
            item("c1", "r1", ItemPayload.CommandExecution("ls")),
            item("f1", "r1", ItemPayload.FileChange("a.kt", 1, 0)),
            item("td", "r1", ItemPayload.TodoList(listOf(TodoStep("x", "pending")))),
            item("a", "r1", ItemPayload.AssistantMessage("done")),
        )
        val rows = ChatLogic.rows(items, mapOf("r1" to Run("r1", "t", 1, RunStatus.COMPLETED)))
        assertEquals(listOf("u", "w:c1", "a", "m:r1"), rows.map { it.key })
        assertEquals("Ran 1 command · edited 1 file", ChatLogic.workSummary((rows[1] as Row.Work).items))
    }

    @Test fun foldLongText() {
        val t = (1..20).joinToString("\n")
        val (f, folded) = ChatLogic.fold(t)
        assertTrue(folded); assertEquals(12, f.lines().size)
        assertFalse(ChatLogic.fold("a\nb").second)
    }
}
