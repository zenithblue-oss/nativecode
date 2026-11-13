package com.zenithblue.nativecode.agent.chat.tools

import android.content.Context
import com.zenithblue.nativecode.agent.chat.AgentChats
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Delegated tasks (item 12 M7): parent thread → child thread links, persisted in `filesDir/agent/nc-tasks.json`.
 * A monitor polls each running task's child runs; when the child settles it closes the child's process (frees the
 * AI session slot) and, unless the parent already got the result (wait / task_status / thread_read), wakes the
 * parent with a `<nc_task_result>` message through the normal send path (Pro gate and session cap apply).
 *
 * ponytail: 1.5 s store poll instead of controller listeners; one JSON file rewritten per change (tens of rows).
 */
object NcTasks {
    data class Task(
        val id: String,
        val parentThreadId: String,
        val childThreadId: String,
        val tool: String,
        val model: String?,
        val title: String,
        val createdAt: Long,
        /** First child run that belongs to this task (a follow-up send to a finished child is a new task). */
        val fromRunOrdinal: Int,
        val status: String = "queued",       // queued | running | waiting | completed | failed | cancelled | interrupted
        val summary: String? = null,
        val failure: String? = null,
        val gateDenied: Boolean = false,
        val delivered: Boolean = false,
        val cancelRequested: Boolean = false,
        val clientRequestId: String? = null,
        val completedAt: Long? = null,
    ) {
        val terminal: Boolean get() = status in TERMINAL
    }

    val TERMINAL = setOf("completed", "failed", "cancelled", "interrupted")

    private val tasks = LinkedHashMap<String, Task>()
    private val awaited = HashMap<String, Int>()          // task id → blocking waiters (no wake while > 0)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val exec = Executors.newSingleThreadScheduledExecutor { Thread(it, "nc-tasks").apply { isDaemon = true } }
    private var tick: ScheduledFuture<*>? = null
    @Volatile private var app: Context? = null

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    @Synchronized
    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        runCatching {
            val arr = JSONArray(file(ctx).readText())
            for (i in 0 until arr.length()) decode(arr.getJSONObject(i))?.let { t ->
                // Loaded from disk (app restarted): never wake a parent at startup.
                tasks[t.id] = if (t.terminal) t else t.copy(delivered = true)
            }
        }
        if (tasks.values.any { !it.terminal }) schedule()
    }

    @Synchronized fun all(): List<Task> = tasks.values.toList()
    @Synchronized fun get(id: String): Task? = tasks[id]
    @Synchronized fun ofParent(threadId: String): List<Task> = tasks.values.filter { it.parentThreadId == threadId }
    /** The task that created or last drove [childThreadId] (newest). */
    @Synchronized fun ofChild(childThreadId: String): Task? = tasks.values.lastOrNull { it.childThreadId == childThreadId }
    fun isChild(threadId: String): Boolean = ofChild(threadId) != null
    @Synchronized fun byRequest(parent: String, clientRequestId: String): Task? =
        tasks.values.lastOrNull { it.parentThreadId == parent && it.clientRequestId == clientRequestId }

    fun add(t: Task) {
        synchronized(this) { tasks[t.id] = t }
        changed()
        synchronized(this) { schedule() }
    }

    fun update(id: String, f: (Task) -> Task): Task? {
        val t = synchronized(this) { tasks[id]?.let(f)?.also { tasks[id] = it } } ?: return null
        changed()
        return t
    }

    fun remove(id: String) { synchronized(this) { tasks.remove(id) }; changed() }

    /** Thread deleted: its tasks go (as parent or child). */
    fun forgetThread(threadId: String) {
        val gone = synchronized(this) { tasks.values.removeAll { it.parentThreadId == threadId || it.childThreadId == threadId } }
        if (gone) changed()
    }

    /** A blocking wait holds the wake (the waiter delivers the result itself). */
    @Synchronized fun await(id: String, on: Boolean) {
        val n = (awaited[id] ?: 0) + if (on) 1 else -1
        if (n <= 0) awaited.remove(id) else awaited[id] = n
    }

    fun markDelivered(id: String) { update(id) { if (it.terminal && !it.delivered) it.copy(delivered = true) else it } }

    // ---------------- status ----------------

    /** Live view of [t] from the child's runs; stores the change. Store reads: call off the main thread. */
    fun refresh(ctx: Context, t: Task): Task {
        if (t.terminal) return t
        val store = AgentChats.store(ctx)
        val runs = runCatching { store.runs(t.childThreadId) }.getOrDefault(emptyList()).filter { it.ordinal >= t.fromRunOrdinal }
        if (store.thread(t.childThreadId) == null) return update(t.id) { it.copy(status = "cancelled", failure = "Child thread deleted", completedAt = now()) } ?: t
        val open = runs.filter { it.status == RunStatus.QUEUED || it.status.active }
        val next = when {
            runs.isEmpty() -> t
            open.isNotEmpty() -> t.copy(status = statusOf(open.last(), store.openRequests(t.childThreadId).isNotEmpty()))
            else -> {
                val last = runs.last()
                val summary = runCatching {
                    store.itemsOfRun(t.childThreadId, last.id).lastOrNull { it.payload is ItemPayload.AssistantMessage }?.let { (it.payload as ItemPayload.AssistantMessage).text }
                }.getOrNull()
                t.copy(
                    status = if (t.cancelRequested) "cancelled" else last.status.wire,
                    summary = summary, failure = last.failure?.message,
                    gateDenied = last.failure?.cls == com.zenithblue.nativecode.agent.chat.core.FailureClass.GATE_DENIED,
                    completedAt = now(),
                )
            }
        }
        if (next == t) return t
        val saved = update(t.id) { cur -> if (cur.terminal) cur else next.copy(delivered = cur.delivered) } ?: next
        if (saved.terminal && saved.completedAt == next.completedAt) settled(ctx, saved) // this call made it terminal
        return saved
    }

    private fun statusOf(r: Run, pending: Boolean) = when {
        pending || r.status == RunStatus.WAITING -> "waiting"
        r.status == RunStatus.QUEUED -> "queued"
        else -> "running"
    }

    /** Oldest pending approval / question of the child (ends a wait). */
    fun pending(ctx: Context, t: Task): Pair<Boolean, JSONObject?> {
        val reqs = runCatching { AgentChats.store(ctx).openRequests(t.childThreadId) }.getOrDefault(emptyList())
        val q = reqs.any { it.kind == RequestKind.USER_INPUT || it.kind == RequestKind.MCP_ELICITATION }
        val a = reqs.firstOrNull { it.kind != RequestKind.USER_INPUT && it.kind != RequestKind.MCP_ELICITATION }
            ?.let { JSONObject().put("requestId", it.id).put("kind", it.kind.wire).put("runId", it.runId ?: JSONObject.NULL) }
        return q to a
    }

    // ---------------- monitor ----------------

    private fun schedule() {
        if (tick?.isDone == false) return
        tick = exec.scheduleWithFixedDelay({ runCatching { poll() } }, 1_500, 1_500, TimeUnit.MILLISECONDS)
    }

    private fun poll() {
        val ctx = app ?: return
        val open = synchronized(this) { tasks.values.filter { !it.terminal } }
        if (open.isEmpty()) { synchronized(this) { tick?.cancel(false); tick = null }; return }
        for (t in open) refresh(ctx, t)
    }

    /** Child done: close its process (slot freed, history kept), then wake the parent unless already delivered. */
    private fun settled(ctx: Context, t: Task) {
        AgentChats.peekController(t.childThreadId)?.stop()
        val held = synchronized(this) { (awaited[t.id] ?: 0) > 0 }
        if (t.delivered || held || t.cancelRequested) return
        update(t.id) { it.copy(delivered = true) }
        val text = NcToolSpec.taskResult(t.id, t.childThreadId, t.tool, t.status, t.summary ?: t.failure)
        NcTools.sendFromApp(ctx, t.parentThreadId, text)
    }

    private fun changed() {
        persist()
        listeners.forEach { runCatching { it() } }
    }

    private fun persist() {
        val ctx = app ?: return
        val json = synchronized(this) { JSONArray(tasks.values.toList().takeLast(MAX_KEPT).map(::encode)).toString() }
        exec.execute {
            runCatching {
                val f = file(ctx)
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp")
                tmp.writeText(json)
                tmp.renameTo(f)
            }
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "agent/nc-tasks.json")
    private fun now() = System.currentTimeMillis()
    private const val MAX_KEPT = 500

    internal fun encode(t: Task) = JSONObject().put("id", t.id).put("parent", t.parentThreadId).put("child", t.childThreadId).put("tool", t.tool)
        .put("model", t.model).put("title", t.title).put("createdAt", t.createdAt).put("from", t.fromRunOrdinal).put("status", t.status)
        .put("summary", t.summary).put("failure", t.failure).put("gate", t.gateDenied).put("delivered", t.delivered)
        .put("cancel", t.cancelRequested).put("crid", t.clientRequestId).put("completedAt", t.completedAt)

    internal fun decode(o: JSONObject): Task? = runCatching {
        Task(o.getString("id"), o.getString("parent"), o.getString("child"), o.getString("tool"), o.optString("model").ifEmpty { null },
            o.optString("title"), o.optLong("createdAt"), o.optInt("from"), o.optString("status", "queued"),
            o.optString("summary").ifEmpty { null }, o.optString("failure").ifEmpty { null }, o.optBoolean("gate"),
            o.optBoolean("delivered"), o.optBoolean("cancel"), o.optString("crid").ifEmpty { null },
            if (o.has("completedAt") && !o.isNull("completedAt")) o.optLong("completedAt") else null)
    }.getOrNull()
}
