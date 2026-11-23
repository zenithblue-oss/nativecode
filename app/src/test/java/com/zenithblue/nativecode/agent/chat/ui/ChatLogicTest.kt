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

    @Test fun defaultToolRule() {
        val chat = listOf("claude-code", "codex", "opencode", "agy")
        val all = { _: String -> true }
        val free = { t: String -> t == "opencode" }
        val signed = setOf("codex", "agy")
        fun pick(last: String?, canUse: (String) -> Boolean) = ChatLogic.defaultTool(last, chat, canUse) { it in signed }
        assertEquals("agy", pick("agy", all)) // last used wins, even over an earlier signed-in tool
        assertEquals("codex", pick(null, all)) // first signed-in Pro tool in catalog order (claude-code is signed out)
        assertEquals("codex", pick("qwen-code", all)) // no chat runtime: ignored
        assertEquals("codex", pick("kiro", all))
        assertEquals("opencode", pick("claude-code", free)) // Free: a Pro last-used tool is locked
        assertEquals("opencode", pick(null, free))
        assertEquals("opencode", ChatLogic.defaultTool(null, chat, all) { false }) // Pro, nothing signed in
        assertEquals("opencode", pick("opencode", free))
    }

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

    @Test fun infoHelpers() {
        assertEquals("main", ChatLogic.branch("ref: refs/heads/main\n"))
        assertEquals("feature/x", ChatLogic.branch("ref: refs/heads/feature/x"))
        assertEquals("abcdef1", ChatLogic.branch("abcdef1234567890abcdef1234567890abcdef12"))
        assertEquals(null, ChatLogic.branch("garbage"))
        assertEquals("850ms", ChatLogic.duration(850))
        assertEquals("4.2s", ChatLogic.duration(4_200))
        assertEquals("1m 04s", ChatLogic.duration(64_000))
        assertEquals(null, ChatLogic.cost(null))
        assertEquals("\$0", ChatLogic.cost(0.0))
        assertEquals("\$0.012", ChatLogic.cost(0.0123))
        assertEquals(0.5f, ChatLogic.contextFraction(50, 100))
        assertEquals(null, ChatLogic.contextFraction(50, null))
        val cmd = TurnItem("i", "t", "r", 0, null, null, ItemStatus.RUNNING, ItemPayload.CommandExecution("npm test\nmore"), 0)
        val done = cmd.copy(id = "j", status = ItemStatus.COMPLETED, payload = ItemPayload.FileChange("/a/b/File.kt"))
        assertEquals("Running npm test", ChatLogic.currentActivity(listOf(cmd, done), "r"))
        assertEquals(null, ChatLogic.currentActivity(listOf(done), "r"))
        assertEquals("Editing File.kt", ChatLogic.activity(done.payload))
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

    /** opencode/jsdiff patches carry no `new file mode` or /dev/null: an empty old side means Added, an empty new side Deleted. */
    @Test fun headerlessNewAndDeletedFiles() {
        val added = ChatLogic.parseUnifiedDiff("Index: notes.txt\n===\n--- notes.txt\n+++ notes.txt\n@@ -0,0 +1,1 @@\n+hello chat\n").single()
        assertEquals('A', added.kind); assertEquals("notes.txt", added.path); assertEquals(1, added.add)
        assertEquals('D', ChatLogic.parseUnifiedDiff("--- a.txt\n+++ a.txt\n@@ -1,2 +0,0 @@\n-x\n-y").single().kind)
        assertEquals('M', ChatLogic.parseUnifiedDiff("--- a.txt\n+++ a.txt\n@@ -1,1 +1,1 @@\n-x\n+y").single().kind)
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

    @Test fun queuedMessageTypedMidTurnNeverSplitsItsRun() {
        // crash: a message queued while r1 ran sits between r1's items; r1 then ends (Stop / Force stop) -> two "m:r1" keys.
        fun item(id: String, ord: Int, run: String, p: ItemPayload) = TurnItem(id, "t", run, ord, null, null, ItemStatus.COMPLETED, p, 0)
        val items = listOf(
            item("u", 1, "r1", ItemPayload.UserMessage("go")),
            item("a1", 2, "r1", ItemPayload.AssistantMessage("working")),
            item("q", 3, "r2", ItemPayload.UserMessage("later", intent = "queued")),
            item("a2", 4, "r1", ItemPayload.AssistantMessage("more")),
        )
        val runs = mapOf("r1" to Run("r1", "t", 1, RunStatus.INTERRUPTED), "r2" to Run("r2", "t", 2, RunStatus.QUEUED, queuePosition = 0))
        assertEquals(listOf("u", "a1", "a2", "m:r1", "q"), ChatLogic.rows(items, runs).map { it.key })
    }

    @Test fun oneUnreadableThreadIsStillListedAndDoesNotHideTheRest() {
        val rows = listOf(thread("a"), thread("bad"), thread("c")).map { t ->
            ChatLogic.rowOrBare(t) { if (t.id == "bad") error("corrupt rows") else ThreadRow(t, ThreadStatus.DONE, null, 1, 2) }
        }
        assertEquals(listOf("a", "bad", "c"), rows.map { it.thread.id })
        assertEquals(ThreadStatus.IDLE, rows[1].status)
        assertFalse(rows[1].draft)
        assertEquals(ThreadStatus.DONE, rows[0].status)
    }

    @Test fun rowKeysAreUniqueEvenForCorruptHistory() {
        fun item(id: String, ord: Int, run: String?, p: ItemPayload) = TurnItem(id, "t", run, ord, null, null, ItemStatus.COMPLETED, p, 0)
        val items = listOf(
            item("a", 1, "r1", ItemPayload.AssistantMessage("x")),
            item("b", 2, "r2", ItemPayload.AssistantMessage("y")),
            item("c", 3, "r1", ItemPayload.AssistantMessage("z")), // runs interleaved without a queue
            item("c", 4, "r1", ItemPayload.AssistantMessage("z")), // repeated id
        )
        val runs = mapOf("r1" to Run("r1", "t", 1, RunStatus.INTERRUPTED), "r2" to Run("r2", "t", 2, RunStatus.COMPLETED))
        val keys = ChatLogic.rows(items, runs).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun subagentActivityNestsUnderItsCard() {
        fun item(id: String, p: ItemPayload, parent: String? = null) = TurnItem(id, "t", "r1", id.hashCode(), null, parent, ItemStatus.COMPLETED, p, 0)
        val items = listOf(
            item("s", ItemPayload.Subagent("explore")),
            item("k1", ItemPayload.CommandExecution("ls"), "s"),
            item("k2", ItemPayload.AssistantMessage("found it"), "s"),
            item("a", ItemPayload.AssistantMessage("done")),
        )
        val rows = ChatLogic.rows(items, emptyMap())
        assertEquals(listOf("w:s", "a"), rows.map { it.key })
        assertEquals(listOf("k1", "k2"), (rows[0] as Row.Work).kids["s"]!!.map { it.id })
    }

    @Test fun foldLongText() {
        val t = (1..20).joinToString("\n")
        val (f, folded) = ChatLogic.fold(t)
        assertTrue(folded); assertEquals(12, f.lines().size)
        assertFalse(ChatLogic.fold("a\nb").second)
    }
}
