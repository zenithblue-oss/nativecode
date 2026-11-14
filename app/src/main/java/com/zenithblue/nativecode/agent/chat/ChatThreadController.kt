package com.zenithblue.nativecode.agent.chat

import android.content.Context
import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.AgentRuntime
import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.ChatMcp
import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.DeltaCoalescer
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.PipeProcess
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.TurnInput
import com.zenithblue.nativecode.agent.chat.store.ChatStore
import com.zenithblue.nativecode.agent.chat.store.ChatWrite
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future

/**
 * One thread's live side (plan 9.2.2/9.2.8): owns at most one [AgentRuntime] (spawned on the first send), feeds its
 * events through the 50 ms coalescer into [ChatReducer], writes the store (streaming rows at most once a second,
 * everything at turn end), and runs send / queue / steer, interrupt + Force stop, approvals, config, idle release,
 * checkpoints, notifications and the turn wake lock. All state changes run on the AgentChats loop.
 *
 * Commands are meant for the main thread: they ask [AgentChats.gate] first (Pro tool, session cap) before posting.
 * Observe [state] with [addListener] (called on the loop thread; post to main).
 */
class ChatThreadController internal constructor(
    private val ctx: Context,
    private val store: ChatStore,
    initial: ChatThread,
) {
    val threadId: String = initial.id
    private val reducer: ChatReducer

    @Volatile var state: ChatLiveState; private set
    @Volatile var thread: ChatThread = initial; private set
    private val listeners = CopyOnWriteArrayList<(ChatLiveState) -> Unit>()

    // loop-confined
    private var runtime: AgentRuntime? = null
    @Volatile private var proc: PipeProcess? = null
    @Volatile private var entry: AgentChats.Entry? = null
    private var ready = false
    private var closing = false
    private var pendingTurn: TurnInput? = null
    private var flushTask: Future<*>? = null
    private var idleTask: Future<*>? = null
    private var forceTask: Future<*>? = null
    private var forkFrom: String? = null // side chat: parent native session, until this thread has its own
    private val coalescer = DeltaCoalescer<AgentEvent.ItemUpsert>(
        schedule = { ms, block -> AgentChats.schedule(ms) { block(); commit() } },
        emit = { reducer.reduce(it) },
    )

    init {
        val (maxItem, maxRun) = store.maxOrdinals(threadId)
        val queued = store.runs(threadId).filter { it.status == RunStatus.QUEUED }.sortedBy { it.queuePosition ?: it.ordinal }
            .mapNotNull { r -> store.itemsOfRun(threadId, r.id).firstOrNull { it.payload is ItemPayload.UserMessage }?.let { QueuedMessage(r, it) } }
        val stale = store.openRequests(threadId).filter { it.kind == RequestKind.USER_INPUT }
        reducer = ChatReducer(initial, maxItem + 1, maxRun + 1, queued, stale)
        ModelCache.load(ctx, initial.tool)?.let { c -> reducer.seedModels(c.copy(caps = ChatRuntimes.staticCaps(initial.tool) ?: c.caps)) } // picker usable before the first send
        state = reducer.state
    }

    fun addListener(l: (ChatLiveState) -> Unit) { listeners += l }
    fun removeListener(l: (ChatLiveState) -> Unit) { listeners -= l }

    // ---------------- commands (main thread) ----------------

    /**
     * Send a message: steer if the runtime can and a turn runs, queue if a turn runs, else start a turn. False = refused.
     * [fresh] (t3code `/clear` armed): when idle, the turn starts a new native session instead of resuming.
     */
    fun send(text: String, attachments: List<ChatAttachment> = emptyList(), fresh: Boolean = false): Boolean {
        if (text.isBlank() && attachments.isEmpty()) return false
        if (ChatAttachments.checkMessage(text, attachments.size) != null) return false
        if (!preflight()) return false
        AgentChats.post {
            val old = if (fresh && reducer.activeRun == null) freshSession() else null
            afterExit(old, 0) { doSend(text, attachments) }
        }
        return true
    }

    /** /clear: the detached process still holds a guest session slot (nc-gate counts live pids) until it exits; spawn after (≤ 5 s). */
    private fun afterExit(p: PipeProcess?, tries: Int, then: () -> Unit) {
        if (p?.isAlive != true || tries >= 25) then() else AgentChats.schedule(200) { afterExit(p, tries + 1, then) }
    }

    /** Side chat (t3code `/side`): this thread's first session forks [parentNativeId] (runtime `caps.fork`). */
    fun forkFrom(parentNativeId: String) = AgentChats.post { if (thread.nativeId == null) forkFrom = parentNativeId }

    fun interrupt() = AgentChats.post {
        if (reducer.activeRun == null) return@post
        if (pendingTurn != null) { // not sent yet (still starting): just drop it
            pendingTurn = null
            reducer.reduce(AgentEvent.TurnTerminal(RunStatus.INTERRUPTED))
            commit()
            return@post
        }
        runtime?.interrupt() ?: return@post
        forceTask?.cancel(false)
        forceTask = AgentChats.schedule(FORCE_STOP_MS) {
            if (reducer.activeRun != null) { reducer.setForceStopAvailable(true); commit() }
        }
    }

    /** Hung turn: kill the process now; run → interrupted, slot freed. */
    fun forceStop() = AgentChats.post {
        reducer.interruptNow()
        closing = true
        runtime?.kill() ?: proc?.kill()
        commit()
    }

    /** Answer an approval / question. A question left from an ended session is sent as a new message. */
    fun respond(requestId: String, answer: RequestAnswer): Boolean {
        if (!state.live && !preflight()) return false
        AgentChats.post {
            val (req, liveSession) = reducer.answer(requestId, answer) ?: return@post
            if (liveSession) {
                runtime?.respond(req.nativeId, answer)
                // codex / ACP answers carry no reason: "decline with feedback" follows as the user's next words.
                // A Stop-type answer ends the turn, so the feedback waits in the queue for the next turn instead.
                val fb = answer.feedback?.trim().orEmpty()
                if (fb.isNotEmpty() && thread.tool !in FEEDBACK_NATIVE) {
                    if (req.options.firstOrNull { it.id == answer.optionId }?.kind == "cancel") reducer.enqueue(UUID.randomUUID().toString(), fb, emptyList())
                    else doSend(fb, emptyList())
                }
            }
            else if (!answer.cancel) {
                val tool = thread.tool
                if (AgentChats.gate?.canUseTool(tool) == false) AgentChats.onMain { AgentChats.gate?.showUpsell(tool) } // answering = a new send
                else doSend(answerText(req, answer), emptyList())
            }
            commit()
        }
        return true
    }

    fun configure(change: ConfigChange) = AgentChats.post {
        reducer.configure(change)
        runtime?.configure(change)
        commit()
    }

    /** Close the process (frees the Free/Pro slot); history stays, queue is held, next send resumes. */
    fun stop() = AgentChats.post { close(hold = true) }

    /** "Session ended · Resume": respawn and resume the native session without sending. */
    fun resume(): Boolean {
        if (state.live || !preflight()) return false
        AgentChats.post { reducer.setBanner(null); ensureStarted(); commit() }
        return true
    }

    /** "N queued · Resume queue". */
    fun resumeQueue(): Boolean {
        if (!preflight()) return false
        AgentChats.post { reducer.holdQueue(false); dispatchQueue(); commit() }
        return true
    }

    fun removeQueued(runId: String) = AgentChats.post { reducer.removeQueued(runId); commit() }
    fun editQueued(runId: String, text: String) = AgentChats.post { reducer.editQueued(runId, text); commit() }

    /** Queued sheet › Send now: steer it in where the runtime can, else start it if idle. */
    fun sendQueuedNow(runId: String): Boolean {
        if (!preflight()) return false
        AgentChats.post {
            val run = reducer.activeRun
            val rt = runtime
            if (run != null && rt != null && ready && rt.caps.steer) {
                reducer.steerQueued(runId)?.let { rt.send(TurnInput(it.item.nativeRef ?: it.item.id, it.text, it.attachments), steer = true) }
            } else if (run == null) {
                reducer.holdQueue(false)
                reducer.dequeue(runId)?.let { startTurn(TurnInput(it.item.nativeRef ?: it.item.id, it.text, it.attachments), it.run) }
            }
            commit()
        }
        return true
    }

    /** stopAll / stopProject: runs in flight → interrupted, process closed, history kept. */
    internal fun shutdown() = AgentChats.post {
        if (reducer.activeRun != null) reducer.interruptNow()
        close(hold = true)
    }

    // ---------------- loop ----------------

    private fun preflight(): Boolean {
        val g = AgentChats.gate ?: return true
        val tool = thread.tool
        if (!g.canUseTool(tool)) { g.showUpsell(tool); return false }
        return state.live || g.allowLaunch(tool, thread.projectPath)
    }

    private fun doSend(text: String, attachments: List<ChatAttachment>) {
        val clientId = UUID.randomUUID().toString()
        val run = reducer.activeRun
        val rt = runtime
        when {
            run == null -> startTurn(TurnInput(clientId, text, attachments), reducer.beginRun(clientId, text, attachments))
            rt != null && ready && rt.caps.steer && (run.status == RunStatus.RUNNING || run.status == RunStatus.WAITING) -> {
                reducer.steer(clientId, text, attachments)
                rt.send(TurnInput(clientId, text, attachments), steer = true)
            }
            else -> reducer.enqueue(clientId, text, attachments)
        }
        commit()
    }

    private fun startTurn(turn: TurnInput, run: Run) {
        idleTask?.cancel(false)
        AgentChats.turnRunning(ctx, threadId, true)
        pendingTurn = turn
        if (!ensureStarted()) return
        if (ready) dispatch()
    }

    /** Spawns the runtime if none. False = no chat runtime for this tool (run fails). */
    private fun ensureStarted(): Boolean {
        if (runtime != null) return true
        val t = reducer.thread
        val host = ThreadHost(ctx, threadId, t.tool, t.projectPath, t.cwd,
            onSpawned = { e -> entry = e; proc = e.process },
            onProcessExit = { p, code, tail -> AgentChats.post { if (p === proc) onExit(code, tail) } })
        val rt = runCatching { ChatRuntimes.create(t.tool, host) }.getOrNull()
        if (rt == null) {
            reducer.reduce(AgentEvent.SessionState(AgentEvent.Phase.ERROR, message = "Chat not available for ${t.tool}"))
            if (reducer.activeRun != null) reducer.reduce(AgentEvent.TurnTerminal(RunStatus.FAILED,
                Failure(FailureClass.VALIDATION_ERROR, "Chat not available for ${t.tool}")))
            pendingTurn = null
            return false
        }
        runtime = rt
        ready = false
        closing = false
        reducer.starting()
        try {
            // Events of a runtime detached by /clear are dropped (its process still winds down).
            rt.start(reducer.sessionConfig().copy(forkFrom = forkFrom.takeIf { t.nativeId == null }), t.nativeId) { e ->
                AgentChats.post { if (runtime === rt) onEvent(e) }
            }
        } catch (e: Exception) {
            runtime = null
            onExit(-1, "Chat failed to start: ${e.message}")
            return false
        }
        return true
    }

    /** Turn-start checkpoint off the loop (≤ 5 s), then the prompt goes out. */
    private fun dispatch() {
        val turn = pendingTurn ?: return
        pendingTurn = null
        val run = reducer.activeRun ?: return
        val cwd = reducer.thread.cwd
        AgentChats.checkpoints.execute {
            val base = runCatching { TurnCheckpoints.capture(ctx, cwd, threadId, run.ordinal, "base") }.getOrNull()
            AgentChats.post {
                if (reducer.activeRun?.id != run.id) return@post
                if (base != null) reducer.setCheckpointBase(base)
                runtime?.send(turn, steer = false)
                commit()
            }
        }
    }

    private fun onEvent(e: AgentEvent) {
        logCanon(e)
        when (e) {
            is AgentEvent.ItemUpsert -> { coalescer.offer(e.nativeRef, e); return }
            is AgentEvent.Exited, is AgentEvent.Unknown -> return // exits come from the process itself (onExit); unknown = log only
            else -> coalescer.flush()
        }
        if (e is AgentEvent.SessionState && e.phase == AgentEvent.Phase.READY) {
            ready = true
            entry?.let { if (it.starting) AgentChats.markLive(it) }
            if (pendingTurn == null && reducer.activeRun == null) scheduleIdleRelease() // warm-up / resume without a send
        }
        if (e is AgentEvent.TurnTerminal) forceTask?.cancel(false)
        if (e is AgentEvent.NativeId) forkFrom = null
        reducer.reduce(e)
        if (e is AgentEvent.SessionState && (!e.models.isNullOrEmpty() || e.commands != null)) reducer.state.let { s ->
            ModelCache.save(ctx, thread.tool, ModelCache.Entry(s.models, s.defaultModel, s.caps, s.commands))
        }
        commit()
    }

    private fun onExit(code: Int, stderrTail: String) {
        coalescer.flush()
        val expected = closing
        runtime = null
        proc = null
        entry = null
        ready = false
        closing = false
        pendingTurn = null
        forceTask?.cancel(false)
        idleTask?.cancel(false)
        reducer.exited(code, stderrTail, expected)
        AgentChats.turnRunning(ctx, threadId, false)
        ChatMcp.release(threadId)
        commit()
    }

    /** Detach the live runtime (it closes in the background; its exit and events are ignored) and drop the native id. */
    private fun freshSession(): PipeProcess? {
        val rt = runtime
        val old = proc
        if (rt != null) {
            runtime = null; proc = null; entry = null; ready = false; pendingTurn = null
            idleTask?.cancel(false)
            rt.close()
            ChatMcp.release(threadId)
        }
        reducer.freshSession()
        return old
    }

    private fun close(hold: Boolean) {
        idleTask?.cancel(false)
        if (hold) reducer.holdQueue(true)
        val rt = runtime
        if (rt != null) { closing = true; rt.close() } else proc?.let { closing = true; it.close() }
        commit()
    }

    private fun dispatchQueue() {
        if (reducer.activeRun != null || state.queueHeld || reducer.state.queued.isEmpty()) return
        val g = AgentChats.gate
        if (g != null && !g.canUseTool(reducer.thread.tool)) { // Pro → Free after the turn: queued messages stay held
            reducer.holdQueue(true, ChatBanner(BannerKind.UPSELL, "${reducer.thread.tool} needs Pro · queued messages held"))
            return
        }
        if (runtime == null) { reducer.holdQueue(true); return } // spawning needs the session check on the main thread
        val q = reducer.dequeue() ?: return
        startTurn(TurnInput(q.item.nativeRef ?: q.item.id, q.text, q.attachments), q.run)
    }

    /** Applies reducer output: store writes, side effects, live snapshot, the 1 s streaming flush. */
    private fun commit() {
        while (true) {
            val (writes, effects) = reducer.drain()
            if (writes.isNotEmpty()) store.apply(writes)
            thread = reducer.thread
            if (effects.isEmpty()) break
            effects.forEach(::effect)
        }
        if (reducer.hasDirty() && flushTask?.isDone != false) {
            flushTask = AgentChats.schedule(ROW_FLUSH_MS) { reducer.flushDirty(); commit() }
        }
        val s = reducer.state
        if (s !== state) { state = s; listeners.forEach { runCatching { it(s) } } }
    }

    private fun effect(e: ChatReducer.Effect) {
        when (e) {
            ChatReducer.Effect.Ready -> dispatch()
            is ChatReducer.Effect.NeedsAttention -> ChatNotifications.notify(ctx, thread,
                if (e.request.kind == RequestKind.USER_INPUT || e.request.kind == RequestKind.MCP_ELICITATION) ChatNotifications.Kind.INPUT else ChatNotifications.Kind.APPROVAL,
                e.request.detail.title.ifBlank { null })
            is ChatReducer.Effect.TurnEnded -> turnEnded(e.run)
            is ChatReducer.Effect.GateDenied -> {
                val tool = thread.tool
                val p = thread.projectPath
                AgentChats.onMain {
                    val g = AgentChats.gate ?: return@onMain
                    if (!g.canUseTool(tool)) g.showUpsell(tool) else g.allowLaunch(tool, p) // shows the session sheet when at the cap
                }
            }
            ChatReducer.Effect.SessionLost -> Unit
        }
    }

    private fun turnEnded(run: Run) {
        forceTask?.cancel(false)
        AgentChats.turnRunning(ctx, threadId, false)
        when {
            run.status == RunStatus.COMPLETED -> ChatNotifications.notify(ctx, thread, ChatNotifications.Kind.DONE)
            run.status == RunStatus.FAILED && run.failure?.cls != FailureClass.GATE_DENIED ->
                ChatNotifications.notify(ctx, thread, ChatNotifications.Kind.FAILED, run.failure?.message)
        }
        run.chkBase?.let { base ->
            val cwd = thread.cwd
            AgentChats.checkpoints.execute {
                val end = runCatching { TurnCheckpoints.capture(ctx, cwd, threadId, run.ordinal, "end") }.getOrNull() ?: return@execute
                val stat = runCatching { TurnCheckpoints.diffStat(ctx, cwd, base, end) }.getOrNull()
                store.apply(ChatWrite.UpdateRun(threadId, run.id) { it.copy(chkEnd = end, diffStat = stat ?: it.diffStat) })
            }
        }
        if (runtime != null && !closing) {
            dispatchQueue()
            if (reducer.activeRun == null) scheduleIdleRelease()
        }
    }

    private fun scheduleIdleRelease() {
        idleTask?.cancel(false)
        idleTask = AgentChats.schedule(IDLE_RELEASE_MS) {
            if (reducer.activeRun == null && !reducer.hasPendingRequests && runtime != null) close(hold = false)
        }
    }

    /** Canonical events (streaming snapshots dropped) next to the runtime's NATIVE frames. */
    private fun logCanon(e: AgentEvent) {
        if (e is AgentEvent.CommandOutputDelta || (e is AgentEvent.ItemUpsert && e.status == ItemStatus.RUNNING)) return
        plog.append("CANON", JSONObject().put("e", e.javaClass.simpleName).put("v", e.toString().take(4000)).toString())
    }

    private val plog = ProtocolLog.of(ctx, threadId)

    private fun answerText(req: ChatRequest, a: RequestAnswer): String = buildString {
        for (q in req.detail.questions) {
            val ans = a.answers[q.id].orEmpty()
            if (ans.isEmpty()) continue
            append(q.text).append('\n').append(ans.joinToString(", ")).append("\n\n")
        }
        a.feedback?.takeIf { it.isNotBlank() }?.let { append(it) }
    }.trim().ifBlank { a.answers.values.flatten().joinToString(", ") }

    companion object {
        const val FORCE_STOP_MS = 10_000L
        /** Runtimes whose reject answer carries the feedback itself (claude deny message, opencode declined note). */
        private val FEEDBACK_NATIVE = setOf("claude-code", "opencode")
        const val ROW_FLUSH_MS = 1_000L
        const val IDLE_RELEASE_MS = 30 * 60 * 1000L
    }
}
