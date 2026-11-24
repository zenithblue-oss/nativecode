package com.zenithblue.nativecode.agent.chat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.zenithblue.nativecode.agent.chat.core.PipeProcess
import com.zenithblue.nativecode.agent.chat.store.ChatStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * What the host (MainActivity) decides for chat launches (plan 9.2.9). Called on the main thread.
 * [allowLaunch] = `MA.aiLaunchAllowed` (counts terminals + chats; shows the session/upgrade sheet itself on deny);
 * [canUseTool] = `FeatureGate.canLaunchAiTool(isPro, tool)`; [showUpsell] = the Pro sheet.
 */
interface ChatGate {
    fun allowLaunch(tool: String, projectPath: String?): Boolean
    fun canUseTool(tool: String): Boolean
    fun showUpsell(tool: String)
    /** The CLI is older than its [need] minimum: the update popup; [done] true = updated (the launch goes on), false = cancelled / failed. Any thread. */
    fun updateCli(tool: String, need: com.zenithblue.nativecode.cliauth.CliNeed, done: (Boolean) -> Unit)
}

/**
 * Process-scoped registry of agent chats (item 12): chat processes (counted with terminal tabs in the AI session
 * cap and the TerminalService notification), one [ChatThreadController] per opened thread, the [ChatStore], the
 * Pro [gate] and the turn wake lock. Outlives Activity recreation.
 */
object AgentChats {
    /** One chat process. [projectPath] null = TERMINAL page (app scope), else its workspace project. */
    class Entry(val tool: String, val projectPath: String?, val process: PipeProcess) {
        @Volatile var starting = true
    }

    private val entries = CopyOnWriteArrayList<Entry>()

    /** Called on any add/remove/state change, from any thread (MainActivity posts to its main thread). */
    @Volatile var onChanged: (() -> Unit)? = null

    /** Set by MainActivity; null = everything allowed (tests, before wiring). */
    @Volatile var gate: ChatGate? = null

    /** Thread currently on screen in the Chat position (UI sets/clears it); its notifications are suppressed. */
    @Volatile var visibleThreadId: String? = null

    fun add(e: Entry) { entries += e; onChanged?.invoke() }
    fun markLive(e: Entry) { e.starting = false; onChanged?.invoke() }
    fun remove(e: Entry) { if (entries.remove(e)) onChanged?.invoke() }

    /** Any chat process (live or starting) of this terminal-type tool. */
    fun hasTool(tool: String): Boolean = entries.any { it.tool == tool }
    fun startingCount(): Int = entries.count { it.starting }
    fun liveCount(): Int = entries.count { !it.starting }

    /** Live + starting chats of one scope (FGS notification). */
    fun countFor(projectPath: String?): Int = entries.count { it.projectPath == projectPath }
    fun toolsFor(projectPath: String?): List<String> = entries.filter { it.projectPath == projectPath }.map { "chat: ${it.tool}" }
    fun projectPaths(): Set<String> = entries.mapNotNull { it.projectPath }.toSet()

    /** Project removal: its chats stop (runs in flight → interrupted, history kept). */
    fun stopProject(path: String) {
        controllers.values.filter { it.thread.projectPath == path }.forEach { it.shutdown() }
        stop(entries.filter { it.projectPath == path })
    }

    /** Activity really finishing (incl. swipe-away, plan Q5): every chat stops, runs → interrupted, history kept. */
    fun stopAll() {
        controllers.values.forEach { it.shutdown() }
        stop(entries.toList())
        if (turns.clear()) releaseWakeLock()
    }

    private fun stop(list: List<Entry>) {
        if (list.isEmpty()) return
        list.forEach { it.process.close() }
        entries.removeAll(list.toSet())
        onChanged?.invoke()
    }

    // ---------------- store + controllers ----------------

    @Volatile private var store: ChatStore? = null
    private val controllers = ConcurrentHashMap<String, ChatThreadController>()

    fun store(ctx: Context): ChatStore = store ?: synchronized(this) { store ?: ChatStore(ctx.applicationContext).also { store = it } }

    /** The thread's controller (created on first use; no process until a send). Null if the thread does not exist. Reads the store: prefer a background thread. */
    fun controller(ctx: Context, threadId: String): ChatThreadController? {
        controllers[threadId]?.let { return it }
        val s = store(ctx)
        val t = s.thread(threadId) ?: return null
        return controllers.computeIfAbsent(threadId) { ChatThreadController(ctx.applicationContext, s, t) }
    }

    /** Existing controller only (never reads the store). */
    fun peekController(threadId: String): ChatThreadController? = controllers[threadId]

    /** Threads with a live or starting process (sessions chip). */
    fun liveThreadIds(): List<String> = controllers.values.filter { it.state.live }.map { it.threadId }

    /** Stop the process, delete rows (cascade), prune checkpoint refs, logs and the notification. */
    fun deleteThread(ctx: Context, threadId: String) {
        val app = ctx.applicationContext
        controllers.remove(threadId)?.stop()
        if (visibleThreadId == threadId) visibleThreadId = null
        ChatNotifications.cancel(app, threadId)
        bg.execute {
            val s = store(app)
            val t = s.thread(threadId)
            s.deleteThread(threadId)
            ProtocolLog.delete(app, threadId)
            if (t != null) TurnCheckpoints.prune(app, t.cwd, threadId)
        }
    }

    // ---------------- loop + wake lock ----------------

    private val bg = Executors.newSingleThreadExecutor { Thread(it, "agent-chat-bg").apply { isDaemon = true } }
    internal val checkpoints = Executors.newFixedThreadPool(2) { Thread(it, "agent-chat-chk").apply { isDaemon = true } }
    private val loopExec = Executors.newSingleThreadScheduledExecutor { Thread(it, "agent-chat-loop").apply { isDaemon = true } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** One serial loop for every controller (events are cheap; I/O is on the store writer / runtimes). */
    internal fun post(r: () -> Unit) { loopExec.execute { runCatching(r) } }
    internal fun schedule(ms: Long, r: () -> Unit): Future<*> = loopExec.schedule({ runCatching(r) }, ms, TimeUnit.MILLISECONDS)
    internal fun onMain(r: () -> Unit) { main.post(r) }

    internal val turns = TurnSet()
    private var wakeLock: PowerManager.WakeLock? = null
    const val WAKE_TAG = "nativecode:agent-turn"
    private const val WAKE_TIMEOUT_MS = 2 * 60 * 60 * 1000L

    /** Running turns 0 → 1 acquires the one PARTIAL_WAKE_LOCK (2 h leak guard), back to 0 releases it. */
    internal fun turnRunning(ctx: Context, threadId: String, running: Boolean) {
        when (turns.set(threadId, running)) {
            TurnSet.Edge.FIRST -> synchronized(this) {
                val wl = wakeLock ?: (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply { setReferenceCounted(false) }.also { wakeLock = it }
                runCatching { wl.acquire(WAKE_TIMEOUT_MS) }
            }
            TurnSet.Edge.LAST -> releaseWakeLock()
            TurnSet.Edge.NONE -> Unit
        }
    }

    private fun releaseWakeLock() = synchronized(this) { wakeLock?.let { if (it.isHeld) runCatching { it.release() } } }
}

/** Threads with a running turn; reports the 0 → 1 and 1 → 0 edges (pure, JVM-tested). */
class TurnSet {
    enum class Edge { FIRST, LAST, NONE }

    private val ids = HashSet<String>()

    @Synchronized
    fun set(threadId: String, running: Boolean): Edge {
        val before = ids.size
        if (running) ids += threadId else ids -= threadId
        return when {
            before == 0 && ids.size == 1 -> Edge.FIRST
            before > 0 && ids.isEmpty() -> Edge.LAST
            else -> Edge.NONE
        }
    }

    /** True if something was running. */
    @Synchronized
    fun clear(): Boolean = ids.isNotEmpty().also { ids.clear() }

    @Synchronized
    fun count() = ids.size
}
