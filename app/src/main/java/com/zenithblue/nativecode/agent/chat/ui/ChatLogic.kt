package com.zenithblue.nativecode.agent.chat.ui

import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.TurnItem

/** Derived thread status (plan 9.2.3): approval > input > working > failed > done-unread > idle. */
enum class ThreadStatus { APPROVAL, INPUT, WORKING, FAILED, DONE, IDLE }

data class ThreadGroup(val key: String, val title: String, val threads: List<ChatThread>)

data class DiffLine(val kind: Char, val text: String, val oldNo: Int?, val newNo: Int?)
data class DiffFile(val path: String, val kind: Char, val add: Int, val del: Int, val lines: List<DiffLine>)

/** Timeline rows: one item, a collapsible work group (tool calls of one run), or a run's meta line. */
sealed class Row {
    abstract val key: String
    data class Item(val item: TurnItem) : Row() { override val key get() = item.id }
    /** [kids] = subagent activity by parent item id (nested under its card, never top-level rows). */
    data class Work(val runId: String?, val items: List<TurnItem>, val kids: Map<String, List<TurnItem>> = emptyMap()) : Row() { override val key get() = "w:" + items.first().id }
    data class Meta(val run: Run) : Row() { override val key get() = "m:" + run.id }
}

/** Pure chat-UI logic (JVM-tested). */
object ChatLogic {
    const val PREF_BETA = "pref_chat_view_beta"
    const val PREF_MODE_APP = "pref_terminal_mode_app"
    const val PREF_MODE_WS = "pref_terminal_mode_ws"
    const val MODE_TERMINAL = "terminal"
    const val MODE_CHAT = "chat"
    const val NO_PROJECT = ""
    const val ARCHIVED = "\u0000archived"

    /** Default Chat; beta off = terminal only (1.7.x). */
    fun isChatMode(stored: String?, betaOn: Boolean): Boolean = betaOn && stored != MODE_TERMINAL

    fun status(thread: ChatThread, lastRun: Run?, open: List<ChatRequest>): ThreadStatus {
        val pending = open.filter { it.status == RequestStatus.PENDING }
        return when {
            pending.any { !it.isInput() } -> ThreadStatus.APPROVAL
            pending.any { it.isInput() } -> ThreadStatus.INPUT
            lastRun?.status?.active == true -> ThreadStatus.WORKING
            lastRun?.status == RunStatus.FAILED -> ThreadStatus.FAILED
            lastRun?.status == RunStatus.COMPLETED && (lastRun.completedAt ?: 0) > (thread.lastVisitedAt ?: 0) -> ThreadStatus.DONE
            else -> ThreadStatus.IDLE
        }
    }

    fun ChatRequest.isInput() = kind == RequestKind.USER_INPUT || kind == RequestKind.PLAN_EXIT

    private val order = compareByDescending<ChatThread> { it.pinnedAt != null }
        .thenByDescending { it.pinnedAt ?: 0 }.thenByDescending { it.updatedAt }

    /** TERMINAL tab: one group per project (newest first), then No project, then Archived. Workspace: no headers. */
    fun group(threads: List<ChatThread>, byProject: Boolean): List<ThreadGroup> {
        val (archived, live) = threads.partition { it.archivedAt != null }
        val out = ArrayList<ThreadGroup>()
        if (byProject) {
            live.filter { it.projectPath != null }.groupBy { it.projectPath!! }.entries
                .sortedByDescending { e -> e.value.maxOf { it.updatedAt } }
                .forEach { (p, l) -> out += ThreadGroup(p, baseName(p), l.sortedWith(order)) }
            live.filter { it.projectPath == null }.takeIf { it.isNotEmpty() }
                ?.let { out += ThreadGroup(NO_PROJECT, "No project", it.sortedWith(order)) }
        } else if (live.isNotEmpty()) {
            out += ThreadGroup(NO_PROJECT, "", live.sortedWith(order))
        }
        if (archived.isNotEmpty()) out += ThreadGroup(ARCHIVED, "Archived", archived.sortedByDescending { it.archivedAt })
        return out
    }

    fun baseName(path: String) = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

    fun relativeTime(now: Long, then: Long): String {
        val s = ((now - then) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "now"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            s < 7 * 86_400 -> "${s / 86_400}d"
            else -> "${s / (7 * 86_400)}w"
        }
    }

    fun timer(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Text folded to [max] lines; second = was folded. */
    fun fold(text: String, max: Int = 12): Pair<String, Boolean> {
        val lines = text.lines()
        return if (lines.size <= max) text to false else lines.take(max).joinToString("\n") to true
    }

    /** What the agent is doing right now (working pill, t3code "Running · <cmd>"). */
    fun activity(p: ItemPayload): String? = when (p) {
        is ItemPayload.CommandExecution -> "Running " + p.command.lineSequence().first().take(60)
        is ItemPayload.FileChange -> "Editing " + baseName(p.path)
        is ItemPayload.FileSearch -> "Searching " + p.pattern.take(40)
        is ItemPayload.WebSearch -> "Fetching " + p.query.take(40)
        is ItemPayload.DynamicTool -> "Calling " + p.name
        is ItemPayload.Subagent -> "Subagent · " + p.title.take(40)
        is ItemPayload.Reasoning -> "Thinking"
        is ItemPayload.AssistantMessage -> "Writing"
        is ItemPayload.Compaction -> "Compacting context"
        else -> null
    }

    /** Activity of the newest running item of [runId]. */
    fun currentActivity(items: List<TurnItem>, runId: String?): String? =
        items.lastOrNull { it.runId == runId && it.status == ItemStatus.RUNNING }?.let { activity(it.payload) }

    /** Branch name from `.git/HEAD` content, or a short sha when detached. */
    fun branch(head: String): String? {
        val h = head.trim()
        return when {
            h.startsWith("ref: refs/heads/") -> h.removePrefix("ref: refs/heads/")
            h.matches(Regex("[0-9a-f]{7,64}")) -> h.take(7)
            else -> null
        }
    }

    fun duration(ms: Long): String {
        val s = ms / 1000.0
        return when {
            ms < 1000 -> "${ms}ms"
            s < 60 -> "%.1fs".format(s)
            else -> "%dm %02ds".format(ms / 60_000, ms / 1000 % 60)
        }
    }

    /** Cost label; null when not reported. Free models report 0. */
    fun cost(usd: Double?): String? = when {
        usd == null -> null
        usd == 0.0 -> "\$0"
        usd < 0.001 -> "<\$0.001"
        usd < 1 -> "\$%.3f".format(usd)
        else -> "\$%.2f".format(usd)
    }

    /** Context fill 0..1; warning ≥ 0.8, critical ≥ 0.95 (t3code warns at 80/90). */
    fun contextFraction(used: Long?, size: Long?): Float? =
        if (used == null || size == null || size <= 0) null else (used.toFloat() / size).coerceIn(0f, 1f)

    private val hunk = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

    /** Unified diff (git or bare) to files. [fallbackPath] names a header-less single-file diff. */
    fun parseUnifiedDiff(patch: String, fallbackPath: String = "file"): List<DiffFile> {
        val files = ArrayList<DiffFile>()
        var path: String? = null
        var kind = 'M'
        var lines = ArrayList<DiffLine>()
        var add = 0; var del = 0; var o = 0; var n = 0
        var inHunk = false
        var hunks = 0; var oldEmpty = true; var newEmpty = true // header-less patches (opencode, jsdiff): `@@ -0,0` = added
        fun flush() {
            // ponytail: one `-0,0` hunk is also a -U0 insert at line 1 of a non-empty file; jsdiff/git default context never emit that
            if (kind == 'M' && hunks == 1) kind = if (oldEmpty) 'A' else if (newEmpty) 'D' else kind
            if (path != null || lines.isNotEmpty()) files += DiffFile(path ?: fallbackPath, kind, add, del, lines)
            path = null; kind = 'M'; lines = ArrayList(); add = 0; del = 0; inHunk = false
            hunks = 0; oldEmpty = true; newEmpty = true
        }
        for (l in patch.lines()) {
            when {
                l.startsWith("diff --git ") -> { flush(); path = l.substringAfter(" b/", l.substringAfterLast(' ')) }
                !inHunk && l.startsWith("new file mode") -> kind = 'A'
                !inHunk && l.startsWith("deleted file mode") -> kind = 'D'
                !inHunk && l.startsWith("rename to ") -> { kind = 'R'; path = l.removePrefix("rename to ") }
                l.startsWith("--- ") && (!inHunk || l.startsWith("--- a/") || l == "--- /dev/null") -> {
                    if (inHunk) flush()
                    if (path == null && l != "--- /dev/null") path = l.removePrefix("--- ").removePrefix("a/")
                }
                !inHunk && l.startsWith("+++ ") -> {
                    val p = l.removePrefix("+++ ").removePrefix("b/")
                    if (p != "/dev/null") path = p else kind = 'D'
                }
                l.startsWith("@@") -> {
                    inHunk = true
                    hunks++
                    hunk.find(l)?.let {
                        o = it.groupValues[1].toInt(); n = it.groupValues[3].toInt()
                        oldEmpty = oldEmpty && o == 0 && it.groupValues[2] == "0"
                        newEmpty = newEmpty && n == 0 && it.groupValues[4] == "0"
                    }
                    lines += DiffLine('@', l, null, null)
                }
                inHunk && l.startsWith("+") -> { lines += DiffLine('+', l.substring(1), null, n++); add++ }
                inHunk && l.startsWith("-") -> { lines += DiffLine('-', l.substring(1), o++, null); del++ }
                inHunk && l.startsWith(" ") -> lines += DiffLine(' ', l.substring(1), o++, n++)
                else -> {}
            }
        }
        flush()
        return files.filter { it.lines.isNotEmpty() || it.kind != 'M' }
    }

    private val WORK = setOf("command_execution", "file_change", "file_search", "web_search", "dynamic_tool", "subagent")

    /** Groups consecutive tool items of one run; adds a meta row after each finished run. todo_list rows are hidden (strip). */
    fun rows(all: List<TurnItem>, runs: Map<String, Run>): List<Row> {
        // A queued message is stored at the ordinal it was typed (mid-turn); it belongs at the end (dequeue re-orders it there).
        // Left in place it splits its neighbour run in two, and that run's meta row (LazyColumn key) would appear twice.
        val items = all.sortedBy { runs[it.runId]?.status == RunStatus.QUEUED }
        val out = ArrayList<Row>()
        var work = ArrayList<TurnItem>()
        var runId: String? = null
        val ids = items.mapTo(HashSet()) { it.id }
        val kids = items.filter { it.parentId != null && it.parentId in ids }.groupBy { it.parentId!! }
        fun flushWork() {
            if (work.isEmpty()) return
            out += Row.Work(work.first().runId, work, kids); work = ArrayList() // whole map: grandchildren nest too
        }
        fun endRun() { runs[runId ?: return]?.takeIf { it.status.terminal }?.let { out += Row.Meta(it) } }
        for (it in items) {
            if (it.parentId != null && it.parentId in ids) continue // nested under its subagent card
            if (it.runId != runId) { flushWork(); endRun(); runId = it.runId }
            when {
                it.type in WORK -> work += it
                it.payload is ItemPayload.TodoList -> {}
                else -> { flushWork(); out += Row.Item(it) }
            }
        }
        flushWork(); endRun()
        return out.distinctBy { it.key } // LazyColumn throws on a repeated key: tolerate any corrupt history
    }

    fun workSummary(items: List<TurnItem>): String {
        val c = items.groupingBy { it.type }.eachCount()
        fun n(k: String, one: String, many: String) = c[k]?.let { if (it == 1) one else many.format(it) }
        return listOfNotNull(
            n("file_search", "searched 1", "searched %d"),
            n("command_execution", "ran 1 command", "ran %d commands"),
            n("file_change", "edited 1 file", "edited %d files"),
            n("web_search", "fetched 1", "fetched %d"),
            n("dynamic_tool", "1 tool call", "%d tool calls"),
            n("subagent", "1 subagent", "%d subagents"),
        ).joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    fun tokens(n: Long): String = if (n >= 1000) "${(n + 500) / 1000}k" else "$n"
}
