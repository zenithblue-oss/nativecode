package com.zenithblue.nativecode.agent.chat.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The chat projection (plan 9.2.5). All writes go through one writer thread, one transaction per [apply] batch;
 * after each commit listeners get the changed thread ids (on the writer thread: post to main yourself).
 * Reads are blocking: call them off the main thread, or use [read]. Recovery (9.2.8) runs first on the writer.
 * Never logs row contents (prompts, code, tokens).
 */
class ChatStore(ctx: Context) {
    fun interface Listener { fun onChanged(threadIds: Set<String>) }

    private val helper = ChatDb(ctx)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "chat-store-w").apply { isDaemon = true } }
    private val reader = Executors.newFixedThreadPool(2) { Thread(it, "chat-store-r").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    init { writer.execute { recover() } }

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    /** Runs [block] on a reader thread and hands the result to [onMain] on the main thread. */
    fun <T> read(block: ChatStore.() -> T, onMain: (T) -> Unit) {
        reader.execute { val v = runCatching { block() }; main.post { v.onSuccess(onMain) } }
    }

    // ---------------- reads ----------------

    /** [allProjects] = TERMINAL tab (every thread incl. project-less); else only [projectPath]'s (null = project-less). Pinned first, then newest. */
    fun threads(projectPath: String?, allProjects: Boolean = false): List<ChatThread> {
        val (where, args) = when {
            allProjects -> null to null
            projectPath == null -> "project_path IS NULL" to null
            else -> "project_path=?" to arrayOf(projectPath)
        }
        return db().query("threads", null, where, args, null, null, "pinned_at IS NULL, pinned_at DESC, updated_at DESC").use { c -> c.list(::rowThread) }
    }

    fun thread(id: String): ChatThread? = db().query("threads", null, "id=?", arrayOf(id), null, null, null).use { c -> c.list(::rowThread).firstOrNull() }

    /** The newest [limit] items (before [beforeOrdinal] when paging "Load earlier"), in ordinal order. */
    fun items(threadId: String, limit: Int = 200, beforeOrdinal: Int? = null): List<TurnItem> {
        val where = "thread_id=?" + if (beforeOrdinal != null) " AND ordinal<?" else ""
        val args = listOfNotNull(threadId, beforeOrdinal?.toString()).toTypedArray()
        return db().query("turn_items", null, where, args, null, null, "ordinal DESC", limit.toString()).use { c -> c.list(::rowItem) }.asReversed()
    }

    fun itemsOfRun(threadId: String, runId: String): List<TurnItem> =
        db().query("turn_items", null, "thread_id=? AND run_id=?", arrayOf(threadId, runId), null, null, "ordinal").use { c -> c.list(::rowItem) }

    /** Agent bridge cards of a thread, newest first (bridge history). */
    fun bridgeItems(threadId: String): List<TurnItem> =
        db().query("turn_items", null, "thread_id=? AND type=?", arrayOf(threadId, "bridge_event"), null, null, "ordinal DESC").use { c -> c.list(::rowItem) }

    fun runs(threadId: String): List<Run> = db().query("runs", null, "thread_id=?", arrayOf(threadId), null, null, "ordinal").use { c -> c.list(::rowRun) }

    fun openRequests(threadId: String): List<ChatRequest> =
        db().query("requests", null, "thread_id=? AND status=?", arrayOf(threadId, RequestStatus.PENDING.wire), null, null, "created_at").use { c -> c.list(::rowRequest) }

    fun request(id: String): ChatRequest? = db().query("requests", null, "id=?", arrayOf(id), null, null, null).use { c -> c.list(::rowRequest).firstOrNull() }

    /** Highest item and run ordinals of a thread (-1 when none). */
    fun maxOrdinals(threadId: String): Pair<Int, Int> = Pair(
        longFor("SELECT COALESCE(MAX(ordinal),-1) FROM turn_items WHERE thread_id=?", threadId).toInt(),
        longFor("SELECT COALESCE(MAX(ordinal),-1) FROM runs WHERE thread_id=?", threadId).toInt(),
    )

    // ---------------- writes ----------------

    fun apply(writes: List<ChatWrite>, then: (() -> Unit)? = null) {
        if (writes.isEmpty() && then == null) return
        writer.execute {
            val changed = runCatching { commit(writes) }.getOrDefault(emptySet())
            then?.let { runCatching(it) }
            if (changed.isNotEmpty()) listeners.forEach { runCatching { it.onChanged(changed) } }
        }
    }

    fun apply(vararg w: ChatWrite) = apply(w.toList())

    fun createThread(
        projectPath: String?, cwd: String, tool: String, title: String = "New thread",
        model: String? = null, effort: String? = null,
        runtimeMode: RuntimeMode = RuntimeMode.APPROVAL_REQUIRED, interactionMode: InteractionMode = InteractionMode.DEFAULT,
    ): ChatThread {
        val now = System.currentTimeMillis()
        val t = ChatThread(UUID.randomUUID().toString(), projectPath, cwd, tool, title, null, model, effort, runtimeMode, interactionMode,
            createdAt = now, updatedAt = now)
        // Committed before returning: callers read it back at once (AgentChats.controller). Never call from the writer thread.
        val done = java.util.concurrent.CountDownLatch(1)
        apply(listOf(ChatWrite.PutThread(t))) { done.countDown() }
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return t
    }

    fun updateThread(id: String, f: (ChatThread) -> ChatThread) = apply(ChatWrite.UpdateThread(id, f))
    fun rename(id: String, title: String) = updateThread(id) { it.copy(title = title.trim().take(200).ifBlank { it.title }) }
    fun pin(id: String, pinned: Boolean) = updateThread(id) { it.copy(pinnedAt = if (pinned) System.currentTimeMillis() else null) }
    fun archive(id: String) = updateThread(id) { it.copy(archivedAt = System.currentTimeMillis()) }
    fun unarchive(id: String) = updateThread(id) { it.copy(archivedAt = null) }
    fun setDraft(id: String, draftJson: String?) = updateThread(id) { it.copy(draftJson = draftJson) }
    fun markVisited(id: String) = updateThread(id) { it.copy(lastVisitedAt = System.currentTimeMillis()) }

    /** Rows only (cascade). Use [com.zenithblue.nativecode.agent.chat.AgentChats.deleteThread] to also stop the process and prune refs. */
    fun deleteThread(id: String) = apply(ChatWrite.DeleteThread(id))

    // ---------------- internals ----------------

    private fun db(): SQLiteDatabase = helper.writableDatabase

    private fun longFor(sql: String, vararg args: String): Long = db().rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getLong(0) else -1 }

    private fun recover() = runCatching {
        val active = db().query("runs", null, "status IN (?,?,?)",
            arrayOf(RunStatus.STARTING.wire, RunStatus.RUNNING.wire, RunStatus.WAITING.wire), null, null, null).use { c -> c.list(::rowRun) }
        val pending = db().query("requests", null, "status=?", arrayOf(RequestStatus.PENDING.wire), null, null, null).use { c -> c.list(::rowRequest) }
        val writes = ChatRecovery.plan(active, pending, { maxOrdinals(it).first + 1 }, System.currentTimeMillis()) { UUID.randomUUID().toString() }
        commit(writes)
    }

    private fun commit(writes: List<ChatWrite>): Set<String> {
        val db = db()
        val changed = LinkedHashSet<String>()
        db.beginTransaction()
        try {
            // one bad op (e.g. a row of a just-deleted thread) must not drop the rest of the batch
            for (w in writes) if (runCatching { write(db, w) }.getOrDefault(false)) changed += w.threadId
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    private fun write(db: SQLiteDatabase, w: ChatWrite): Boolean = when (w) {
        is ChatWrite.PutThread -> upsert(db, "threads", w.t.id, values(w.t))
        is ChatWrite.UpdateThread -> thread(w.threadId)?.let { db.update("threads", values(w.f(it)), "id=?", arrayOf(w.threadId)) > 0 } ?: false
        is ChatWrite.PutRun -> upsert(db, "runs", w.r.id, values(w.r))
        is ChatWrite.UpdateRun -> db.query("runs", null, "id=?", arrayOf(w.id), null, null, null).use { c -> c.list(::rowRun).firstOrNull() }
            ?.let { db.update("runs", values(w.f(it)), "id=?", arrayOf(w.id)) > 0 } ?: false
        is ChatWrite.PutItem -> putItem(db, w.i)
        is ChatWrite.UpdateItem -> db.query("turn_items", null, "id=?", arrayOf(w.id), null, null, null).use { c -> c.list(::rowItem).firstOrNull() }
            ?.let { db.update("turn_items", values(w.f(it)), "id=?", arrayOf(w.id)) > 0 } ?: false
        is ChatWrite.PutRequest -> upsert(db, "requests", w.r.id, values(w.r))
        is ChatWrite.SettleItems -> db.update("turn_items", ContentValues().apply { put("status", w.status.wire); put("updated_at", System.currentTimeMillis()) },
            "run_id=? AND status IN (?,?,?)", arrayOf(w.runId, ItemStatus.PENDING.wire, ItemStatus.RUNNING.wire, ItemStatus.WAITING.wire)) > 0
        is ChatWrite.DeleteRun -> (db.delete("turn_items", "run_id=?", arrayOf(w.id)) + db.delete("runs", "id=?", arrayOf(w.id))) > 0
        is ChatWrite.DeleteThread -> db.delete("threads", "id=?", arrayOf(w.threadId)) > 0
    }

    // No INSERT OR REPLACE: REPLACE deletes the row first and would cascade away its children. No UPSERT before API 30.
    private fun upsert(db: SQLiteDatabase, table: String, id: String, v: ContentValues): Boolean =
        db.update(table, v, "id=?", arrayOf(id)) > 0 || insert(db, table, v)

    /** insertOrThrow: plain insert() logs the row (prompt text) to logcat on a constraint error. */
    private fun insert(db: SQLiteDatabase, table: String, v: ContentValues): Boolean = runCatching { db.insertOrThrow(table, null, v) != -1L }.getOrDefault(false)

    private fun putItem(db: SQLiteDatabase, i: TurnItem): Boolean {
        val v = values(i)
        if (db.update("turn_items", v, "id=?", arrayOf(i.id)) > 0) return true
        if (i.nativeRef != null) {
            val keep = ContentValues(v).apply { remove("id"); remove("ordinal"); remove("run_id") }
            if (db.update("turn_items", keep, "thread_id=? AND native_ref=?", arrayOf(i.threadId, i.nativeRef)) > 0) return true
        }
        return insert(db, "turn_items", v)
    }

    private fun values(t: ChatThread) = ContentValues().apply {
        put("id", t.id); put("project_path", t.projectPath); put("cwd", t.cwd); put("tool", t.tool); put("title", t.title)
        put("native_id", t.nativeId); put("model", t.model); put("effort", t.effort)
        put("runtime_mode", t.runtimeMode.wire); put("interaction_mode", t.interactionMode.wire); put("draft_json", t.draftJson)
        put("pinned_at", t.pinnedAt); put("archived_at", t.archivedAt); put("last_visited_at", t.lastVisitedAt)
        put("created_at", t.createdAt); put("updated_at", t.updatedAt)
    }

    private fun values(r: Run) = ContentValues().apply {
        put("id", r.id); put("thread_id", r.threadId); put("ordinal", r.ordinal); put("status", r.status.wire)
        put("queue_position", r.queuePosition); put("native_turn_id", r.nativeTurnId)
        put("failure_json", r.failure?.let(ChatCodec::failure)); put("usage_json", r.usage?.let(ChatCodec::usage))
        put("chk_base", r.chkBase); put("chk_end", r.chkEnd); put("diff_stat_json", r.diffStat?.let(ChatCodec::diffStat))
        put("started_at", r.startedAt); put("completed_at", r.completedAt)
    }

    private fun values(i: TurnItem) = ContentValues().apply {
        put("id", i.id); put("thread_id", i.threadId); put("run_id", i.runId); put("ordinal", i.ordinal)
        put("native_ref", i.nativeRef); put("parent_id", i.parentId); put("type", i.type); put("status", i.status.wire)
        put("payload_json", ChatCodec.payload(i.payload)); put("updated_at", i.updatedAt)
    }

    private fun values(q: ChatRequest) = ContentValues().apply {
        put("id", q.id); put("thread_id", q.threadId); put("run_id", q.runId); put("item_id", q.itemId); put("native_id", q.nativeId)
        put("kind", q.kind.wire); put("status", q.status.wire); put("options_json", ChatCodec.options(q.options))
        put("payload_json", ChatCodec.detail(q.detail)); put("answer_json", q.answerJson)
        put("created_at", q.createdAt); put("resolved_at", q.resolvedAt)
    }

    private fun <T> Cursor.list(f: (Cursor) -> T): List<T> = buildList { while (moveToNext()) add(f(this@list)) }
    private fun Cursor.s(c: String): String? = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.l(c: String): Long? = getColumnIndexOrThrow(c).let { if (isNull(it)) null else getLong(it) }

    private fun rowThread(c: Cursor) = ChatThread(
        c.s("id")!!, c.s("project_path"), c.s("cwd")!!, c.s("tool")!!, c.s("title")!!, c.s("native_id"), c.s("model"), c.s("effort"),
        RuntimeMode.of(c.s("runtime_mode")), InteractionMode.of(c.s("interaction_mode")), c.s("draft_json"),
        c.l("pinned_at"), c.l("archived_at"), c.l("last_visited_at"), c.l("created_at") ?: 0, c.l("updated_at") ?: 0,
    )

    private fun rowRun(c: Cursor) = Run(
        c.s("id")!!, c.s("thread_id")!!, c.l("ordinal")!!.toInt(), RunStatus.of(c.s("status")), c.l("queue_position")?.toInt(),
        c.s("native_turn_id"), ChatCodec.failure(c.s("failure_json")), ChatCodec.usage(c.s("usage_json")),
        c.s("chk_base"), c.s("chk_end"), ChatCodec.diffStat(c.s("diff_stat_json")), c.l("started_at"), c.l("completed_at"),
    )

    private fun rowItem(c: Cursor) = TurnItem(
        c.s("id")!!, c.s("thread_id")!!, c.s("run_id"), c.l("ordinal")!!.toInt(), c.s("native_ref"), c.s("parent_id"),
        ItemStatus.of(c.s("status")), ChatCodec.payload(c.s("type")!!, c.s("payload_json") ?: "{}"), c.l("updated_at") ?: 0,
    )

    private fun rowRequest(c: Cursor) = ChatRequest(
        c.s("id")!!, c.s("thread_id")!!, c.s("run_id"), c.s("item_id"), c.s("native_id")!!, RequestKind.of(c.s("kind")),
        RequestStatus.of(c.s("status")), ChatCodec.options(c.s("options_json")), ChatCodec.detail(c.s("payload_json")),
        c.s("answer_json"), c.l("created_at") ?: 0, c.l("resolved_at"),
    )
}
