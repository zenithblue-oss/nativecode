package com.zenithblue.nativecode.agent.chat

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.ChatFailures
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.RuntimeCaps
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.SessionConfig
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import com.zenithblue.nativecode.agent.chat.core.Usage
import com.zenithblue.nativecode.agent.chat.store.ChatCodec
import com.zenithblue.nativecode.agent.chat.store.ChatWrite
import com.zenithblue.nativecode.agent.chat.tools.NcToolSpec
import java.util.UUID

/** Session phase the UI shows. ENDED = the process died unexpectedly ("Session ended · Resume"). */
enum class LivePhase { IDLE, STARTING, READY, RUNNING, WAITING, ENDED }

enum class BannerKind { SESSION_ENDED, GATE_DENIED, QUEUE_HELD, UPSELL, ERROR }
data class ChatBanner(val kind: BannerKind, val text: String)

/** A queued message: its `runs` row (status queued) and its user_message item. */
data class QueuedMessage(val run: Run, val item: TurnItem) {
    val text: String get() = (item.payload as? ItemPayload.UserMessage)?.text.orEmpty()
    val attachments: List<ChatAttachment> get() = (item.payload as? ItemPayload.UserMessage)?.attachments.orEmpty()
}

/**
 * Immutable live snapshot of one thread for Compose. [liveItems] (item id → item) overrides / extends the store's
 * rows for the current run while it streams: merge by id, append unknown ids by ordinal.
 */
data class ChatLiveState(
    val phase: LivePhase = LivePhase.IDLE,
    val runningRunId: String? = null,
    val turnStartedAt: Long? = null,
    val liveItems: Map<String, TurnItem> = emptyMap(),
    val models: List<ModelInfo> = emptyList(),
    val defaultModel: String? = null,
    val commands: List<SlashCommand> = emptyList(),
    val caps: RuntimeCaps? = null,
    val account: String? = null,
    val banner: ChatBanner? = null,
    val usage: Usage? = null,
    val rateLimit: AgentEvent.RateLimit? = null,
    val forceStopAvailable: Boolean = false,
    val queued: List<QueuedMessage> = emptyList(),
    val queueHeld: Boolean = false,
    val lastError: String? = null,
    /** Time to first token of run [firstTokenRunId]: turn start to its first assistant text or reasoning item. */
    val firstTokenMs: Long? = null,
    val firstTokenRunId: String? = null,
    val reconnecting: Boolean = false,
) {
    val live: Boolean get() = phase != LivePhase.IDLE && phase != LivePhase.ENDED
    val turnActive: Boolean get() = runningRunId != null
}

/**
 * Pure state machine of one thread: AgentEvents and user commands in, store writes + side effects out
 * ([drain]). No Android, no threads: the controller serialises calls. Streaming item rows are held in
 * memory and written by [flushDirty] (the controller calls it at most once per second and at turn end).
 */
class ChatReducer(
    thread: ChatThread,
    nextItemOrdinal: Int,
    nextRunOrdinal: Int,
    queued: List<QueuedMessage> = emptyList(),
    staleRequests: List<ChatRequest> = emptyList(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    sealed interface Effect {
        data object Ready : Effect
        data class TurnEnded(val run: Run) : Effect
        data class NeedsAttention(val request: ChatRequest) : Effect
        data class GateDenied(val message: String) : Effect
        data object SessionLost : Effect
    }

    var thread = thread; private set
    var state = ChatLiveState(queued = queued, queueHeld = queued.isNotEmpty(),
        banner = if (queued.isNotEmpty()) ChatBanner(BannerKind.QUEUE_HELD, "${queued.size} queued · Resume queue") else null)
        private set

    private var itemOrd = nextItemOrdinal
    private var runOrd = nextRunOrdinal
    private var run: Run? = null
    private var runDirty = false
    private val items = LinkedHashMap<String, TurnItem>()          // current run, keyed by native ref (or id)
    private val dirty = LinkedHashSet<String>()
    private val reasonStart = HashMap<String, Long>()               // reasoning native ref → first seen
    private val live = LinkedHashMap<String, ChatRequest>()        // pending requests of the live session, by native id
    private val stale = LinkedHashMap<String, ChatRequest>()       // pending user-input from an ended session, by id
    private val writes = mutableListOf<ChatWrite>()
    private val effects = mutableListOf<Effect>()
    private var readySeen = false

    init { staleRequests.filter { it.status == RequestStatus.PENDING }.forEach { stale[it.id] = it } }

    val activeRun: Run? get() = run
    val hasPendingRequests: Boolean get() = live.isNotEmpty()
    fun openRequestIds(): Set<String> = (live.values.map { it.id } + stale.keys).toSet()
    fun hasDirty() = dirty.isNotEmpty() || runDirty

    fun drain(): Pair<List<ChatWrite>, List<Effect>> {
        val r = writes.toList() to effects.toList()
        writes.clear(); effects.clear()
        return r
    }

    fun flushDirty() {
        dirty.mapNotNullTo(writes) { items[it]?.let(ChatWrite::PutItem) }
        dirty.clear()
        if (runDirty) run?.let { writes += ChatWrite.PutRun(it) }
        runDirty = false
    }

    // ---------------- user commands ----------------

    /** A new turn (idle thread). Returns the run; the controller sends once the session is ready. */
    fun beginRun(clientId: String, text: String, attachments: List<ChatAttachment>): Run {
        items.clear()
        val r = Run(newId(), thread.id, runOrd++, RunStatus.STARTING, startedAt = now(), tool = thread.tool, model = thread.model)
        run = r
        writes += ChatWrite.PutRun(r)
        addUserItem(clientId, text, attachments, "start")
        touchThread(text)
        state = state.copy(runningRunId = r.id, turnStartedAt = r.startedAt, forceStopAvailable = false,
            banner = state.banner?.takeIf { it.kind == BannerKind.QUEUE_HELD }, lastError = null)
        publishItems()
        return r
    }

    fun steer(clientId: String, text: String, attachments: List<ChatAttachment>) {
        addUserItem(clientId, text, attachments, "steer")
        touchThread(text)
        publishItems()
    }

    fun enqueue(clientId: String, text: String, attachments: List<ChatAttachment>): QueuedMessage {
        val pos = (state.queued.mapNotNull { it.run.queuePosition }.maxOrNull() ?: -1) + 1
        val r = Run(newId(), thread.id, runOrd++, RunStatus.QUEUED, queuePosition = pos)
        val it = TurnItem(newId(), thread.id, r.id, itemOrd++, clientId, null, ItemStatus.COMPLETED,
            ItemPayload.UserMessage(text, attachments, "queued"), now())
        writes += ChatWrite.PutRun(r)
        writes += ChatWrite.PutItem(it)
        touchThread(text)
        val q = QueuedMessage(r, it)
        state = state.copy(queued = state.queued + q)
        return q
    }

    /** Head of the queue becomes the current run (re-ordered to the end of the timeline). */
    fun dequeue(runId: String? = null): QueuedMessage? {
        val q = (if (runId != null) state.queued.firstOrNull { it.run.id == runId } else state.queued.firstOrNull()) ?: return null
        items.clear()
        val r = q.run.copy(status = RunStatus.STARTING, queuePosition = null, startedAt = now(), tool = thread.tool, model = thread.model)
        val it = q.item.copy(ordinal = itemOrd++, updatedAt = now())
        run = r
        items[it.nativeRef ?: it.id] = it
        writes += ChatWrite.PutRun(r)
        writes += ChatWrite.PutItem(it)
        val rest = state.queued - q
        state = state.copy(queued = rest, runningRunId = r.id, turnStartedAt = r.startedAt, forceStopAvailable = false,
            queueHeld = state.queueHeld && rest.isNotEmpty(), banner = state.banner?.takeIf { it.kind != BannerKind.QUEUE_HELD || rest.isNotEmpty() })
        publishItems()
        return q.copy(run = r, item = it)
    }

    /** Queued message sent as a steer: its run row is dropped, the item moves into the current run. */
    fun steerQueued(runId: String): QueuedMessage? {
        val q = state.queued.firstOrNull { it.run.id == runId } ?: return null
        val cur = run ?: return null
        writes += ChatWrite.DeleteRun(thread.id, q.run.id)
        val it = q.item.copy(runId = cur.id, ordinal = itemOrd++, updatedAt = now(),
            payload = (q.item.payload as? ItemPayload.UserMessage)?.copy(intent = "steer") ?: q.item.payload)
        items[it.nativeRef ?: it.id] = it
        writes += ChatWrite.PutItem(it)
        state = state.copy(queued = state.queued - q)
        publishItems()
        return q
    }

    fun removeQueued(runId: String) {
        val q = state.queued.firstOrNull { it.run.id == runId } ?: return
        writes += ChatWrite.DeleteRun(thread.id, runId)
        val rest = state.queued - q
        state = state.copy(queued = rest, queueHeld = state.queueHeld && rest.isNotEmpty(),
            banner = if (rest.isEmpty() && state.banner?.kind == BannerKind.QUEUE_HELD) null else state.banner)
    }

    fun editQueued(runId: String, text: String) {
        val q = state.queued.firstOrNull { it.run.id == runId } ?: return
        val item = q.item.copy(payload = (q.item.payload as? ItemPayload.UserMessage)?.copy(text = text) ?: return, updatedAt = now())
        writes += ChatWrite.PutItem(item)
        state = state.copy(queued = state.queued.map { if (it.run.id == runId) q.copy(item = item) else it })
    }

    fun holdQueue(held: Boolean, banner: ChatBanner? = null) {
        state = state.copy(queueHeld = held && state.queued.isNotEmpty(),
            banner = banner ?: if (held && state.queued.isNotEmpty()) ChatBanner(BannerKind.QUEUE_HELD, "${state.queued.size} queued · Resume queue")
            else state.banner?.takeIf { it.kind != BannerKind.QUEUE_HELD })
    }

    fun setCheckpointBase(sha: String) {
        val r = run?.copy(chkBase = sha) ?: return
        run = r
        writes += ChatWrite.PutRun(r)
    }

    fun setBanner(b: ChatBanner?) { state = state.copy(banner = b) }
    fun setForceStopAvailable(v: Boolean) { state = state.copy(forceStopAvailable = v) }
    fun starting() { state = state.copy(phase = LivePhase.STARTING, banner = state.banner?.takeIf { it.kind == BannerKind.QUEUE_HELD }) }

    fun configure(c: ConfigChange) {
        thread = thread.copy(model = c.model ?: thread.model, effort = if (c.clearEffort) null else c.effort ?: thread.effort,
            runtimeMode = c.runtimeMode ?: thread.runtimeMode, interactionMode = c.interactionMode ?: thread.interactionMode)
        val t = thread
        writes += ChatWrite.UpdateThread(t.id) { it.copy(model = t.model, effort = t.effort, runtimeMode = t.runtimeMode, interactionMode = t.interactionMode) }
    }

    /** Last-known list for the tool ([ModelCache]): the picker works before a session; a live SessionState replaces it. */
    fun seedModels(c: ModelCache.Entry) {
        if (state.models.isNotEmpty()) return
        state = state.copy(models = c.models, defaultModel = state.defaultModel ?: c.defaultModel, caps = state.caps ?: c.caps,
            commands = state.commands.ifEmpty { c.commands })
    }

    /** t3code `/clear`: forget the native session (the next turn starts a new one); earlier rows stay visible. */
    fun freshSession() {
        thread = thread.copy(nativeId = null)
        writes += ChatWrite.UpdateThread(thread.id) { it.copy(nativeId = null) }
        readySeen = false
        state = state.copy(phase = LivePhase.IDLE, usage = null, reconnecting = false)
        reduce(AgentEvent.Notice("fresh_session", "New session · earlier messages are not in the agent's context"))
    }

    /**
     * Provider switch (idle thread), modelled on [freshSession]: the next turn starts a new session on [tool] with its own
     * [model] / [effort] (slugs never carry across tools) and carries the earlier conversation as text ([ChatHandoff], flag
     * [ChatThread.handoffFrom]). [modes] = what the target offers; the mode steps down to the broadest one it has.
     * Empty live models/caps: the controller re-seeds them from [ModelCache]. A divider row marks the switch.
     * [history] false (nothing said yet, new thread): no handoff and no divider, the thread just starts on [tool].
     */
    fun switchProvider(tool: String, model: String?, effort: String?, modes: List<RuntimeMode>?, history: Boolean = true) {
        val from = thread.tool
        if (tool == from) return
        val handoff = if (history) from else thread.handoffFrom
        thread = thread.copy(tool = tool, model = model, effort = effort, nativeId = null, handoffFrom = handoff,
            runtimeMode = NcToolSpec.resolveRuntimeMode(thread.runtimeMode, null, modes))
        val t = thread
        writes += ChatWrite.UpdateThread(t.id) { it.copy(tool = t.tool, model = t.model, effort = t.effort, nativeId = null, handoffFrom = handoff, runtimeMode = t.runtimeMode) }
        readySeen = false
        state = state.copy(phase = LivePhase.IDLE, usage = null, reconnecting = false, models = emptyList(), defaultModel = null,
            commands = emptyList(), caps = null, account = null, rateLimit = null, banner = state.banner?.takeIf { it.kind == BannerKind.QUEUE_HELD })
        if (history) external(ItemPayload.ProviderSwitch(tool, model))
    }

    /** The handoff went out with a turn: later turns of the new session are native again. */
    fun handoffSent() {
        if (thread.handoffFrom == null) return
        thread = thread.copy(handoffFrom = null)
        writes += ChatWrite.UpdateThread(thread.id) { it.copy(handoffFrom = null) }
    }

    /** What the runtime starts with: the thread's persisted picks (model/effort chosen before the first send included). */
    fun sessionConfig() = thread.let { SessionConfig(it.id, it.cwd, it.projectPath, it.model, it.effort, it.runtimeMode, it.interactionMode, it.title) }

    /**
     * The user answered request [requestId]. Returns the request and whether the live session can take the
     * answer (false = recovered user-input: the controller sends the answer as a new message).
     */
    fun answer(requestId: String, a: RequestAnswer): Pair<ChatRequest, Boolean>? {
        val liveReq = live.values.firstOrNull { it.id == requestId }
        val req = liveReq ?: stale[requestId] ?: return null
        val kind = req.options.firstOrNull { it.id == a.optionId }?.kind.orEmpty()
        val done = req.copy(status = if (a.cancel || kind == "cancel") RequestStatus.CANCELLED else RequestStatus.RESOLVED,
            answerJson = ChatCodec.answer(a), resolvedAt = now())
        if (liveReq != null) live.remove(req.nativeId) else stale.remove(requestId)
        settleRequest(done, declined = kind.startsWith("reject"))
        return done to (liveReq != null)
    }

    /** Process ended. [expected] = we closed it (stop, idle release, force stop, stopAll). */
    fun exited(code: Int, stderrTail: String, expected: Boolean) {
        val gate = stderrTail.lineSequence().firstOrNull { it.contains(GATE_MARK) }?.trim()
        when {
            gate != null -> {
                holdQueue(true)
                endSession(RunStatus.FAILED, Failure(FailureClass.GATE_DENIED, gate))
                state = state.copy(phase = LivePhase.IDLE, banner = ChatBanner(BannerKind.GATE_DENIED, gate))
                effects += Effect.GateDenied(gate)
            }
            !expected -> {
                holdQueue(true)
                // The tail may hold why it ended (claude prints "Not logged in · Please run /login" and exits 0); so may the start error.
                endSession(RunStatus.FAILED, ChatFailures.refine(Failure(FailureClass.TRANSPORT_ERROR, "Session ended unexpectedly (exit $code)",
                    details = stderrTail.takeLast(4096).ifBlank { null }), raw = state.lastError))
                state = state.copy(phase = LivePhase.ENDED, banner = ChatBanner(BannerKind.SESSION_ENDED, "Session ended · Resume"))
                effects += Effect.SessionLost
            }
            else -> {
                endSession(RunStatus.INTERRUPTED, null)
                state = state.copy(phase = LivePhase.IDLE)
            }
        }
        readySeen = false
        state = state.copy(forceStopAvailable = false, reconnecting = false)
    }

    /** Force stop / stopAll: the turn ends interrupted now; the later expected exit is a no-op. */
    fun interruptNow() {
        holdQueue(true)
        endSession(RunStatus.INTERRUPTED, null)
        state = state.copy(forceStopAvailable = false)
    }

    // ---------------- runtime events ----------------

    fun reduce(e: AgentEvent) {
        when (e) {
            is AgentEvent.SessionState -> session(e)
            is AgentEvent.NativeId -> if (thread.nativeId != e.id) {
                thread = thread.copy(nativeId = e.id)
                writes += ChatWrite.UpdateThread(thread.id) { it.copy(nativeId = e.id) }
            }
            is AgentEvent.TurnStarted -> {
                val r = (run ?: Run(newId(), thread.id, runOrd++, RunStatus.RUNNING, startedAt = now(), tool = thread.tool, model = thread.model).also { items.clear() })
                    .copy(status = if (live.isEmpty()) RunStatus.RUNNING else RunStatus.WAITING, nativeTurnId = e.nativeTurnId ?: run?.nativeTurnId)
                run = r
                writes += ChatWrite.PutRun(r)
                state = state.copy(runningRunId = r.id, turnStartedAt = r.startedAt, phase = if (live.isEmpty()) LivePhase.RUNNING else LivePhase.WAITING)
            }
            is AgentEvent.ItemUpsert -> upsertItem(e)
            is AgentEvent.CommandOutputDelta -> {
                val it = items[e.nativeRef] ?: return
                val p = it.payload as? ItemPayload.CommandExecution ?: return
                items[e.nativeRef] = it.copy(payload = p.copy(outputTail = (p.outputTail + e.chunk).takeLast(OUTPUT_TAIL)), updatedAt = now())
                dirty += e.nativeRef
                publishItems()
            }
            is AgentEvent.RequestUpsert -> request(e)
            is AgentEvent.UsageUpdate -> {
                run = run?.copy(usage = e.usage)
                runDirty = run != null
                state = state.copy(usage = e.usage)
            }
            is AgentEvent.RateLimit -> state = state.copy(rateLimit = e)
            is AgentEvent.Connection -> state = state.copy(reconnecting = e.reconnecting)
            is AgentEvent.TurnTerminal -> {
                if (run == null) return
                endTurn(e.status, e.failure, expireUserInput = false)
                state = state.copy(phase = if (state.live) LivePhase.READY else state.phase)
            }
            is AgentEvent.Notice -> {
                val p = when (e.kind) {
                    "compaction" -> ItemPayload.Compaction(null)
                    "resume_failed" -> ItemPayload.SystemNotice(RESUME_FAILED)
                    else -> ItemPayload.SystemNotice(e.text)
                }
                val it = TurnItem(newId(), thread.id, run?.id, itemOrd++, null, null, ItemStatus.COMPLETED, p, now())
                items[it.id] = it
                writes += ChatWrite.PutItem(it)
                publishItems()
            }
            is AgentEvent.Exited -> exited(e.code, e.stderrTail, expected = false)
            is AgentEvent.Unknown -> Unit
        }
    }

    /**
     * An item from outside the runtime (agent bridge card) at the next ordinal, in the current run if one streams.
     * Store only (not in the live items), so later status edits to the row are not overwritten by a live snapshot.
     */
    fun external(p: ItemPayload): String {
        val it = TurnItem(newId(), thread.id, run?.id, itemOrd++, null, null, ItemStatus.COMPLETED, p, now())
        writes += ChatWrite.PutItem(it)
        return it.id
    }

    private fun session(e: AgentEvent.SessionState) {
        val s = state
        state = s.copy(
            caps = e.caps ?: s.caps, models = e.models ?: s.models, defaultModel = e.defaultModel ?: s.defaultModel,
            commands = e.commands ?: s.commands, account = e.account ?: s.account,
            phase = when (e.phase) {
                AgentEvent.Phase.STARTING -> LivePhase.STARTING
                AgentEvent.Phase.READY -> if (run != null) (if (live.isEmpty()) LivePhase.RUNNING else LivePhase.WAITING) else LivePhase.READY
                AgentEvent.Phase.RUNNING -> LivePhase.RUNNING
                AgentEvent.Phase.WAITING -> LivePhase.WAITING
                AgentEvent.Phase.STOPPED -> LivePhase.IDLE
                AgentEvent.Phase.ERROR -> s.phase
            },
            banner = if (e.phase == AgentEvent.Phase.ERROR) ChatBanner(BannerKind.ERROR, e.message ?: "Chat not available for this version") else s.banner,
            lastError = if (e.phase == AgentEvent.Phase.ERROR) e.message else s.lastError,
        )
        if (e.phase == AgentEvent.Phase.READY && !readySeen) { readySeen = true; effects += Effect.Ready }
        val t = thread.copy(
            model = e.model ?: thread.model,
            runtimeMode = e.runtimeMode ?: thread.runtimeMode,
            interactionMode = e.interactionMode ?: thread.interactionMode,
        )
        if (t != thread) {
            thread = t
            writes += ChatWrite.UpdateThread(t.id) { it.copy(model = t.model, runtimeMode = t.runtimeMode, interactionMode = t.interactionMode) }
        }
    }

    private fun upsertItem(e: AgentEvent.ItemUpsert) {
        val old = items[e.nativeRef]
        if (old?.payload is ItemPayload.UserMessage) return // the client owns user messages (text + attachments)
        var payload = (e.payload as? ItemPayload.CommandExecution)?.let { it.copy(outputTail = it.outputTail.takeLast(OUTPUT_TAIL)) } ?: e.payload
        if (payload is ItemPayload.Reasoning) {
            val start = reasonStart.getOrPut(e.nativeRef) { now() }
            if (payload.durationMs == null && e.status !in OPEN_ITEM) payload = payload.copy(durationMs = now() - start)
        } else closeReasoning(ItemStatus.COMPLETED) // t3code: thinking ends when the next part starts ("Thought for Ns")
        items[e.nativeRef] = old?.copy(status = e.status, payload = payload, updatedAt = now(),
            parentId = e.parentRef?.let { items[it]?.id } ?: old.parentId)
            ?: TurnItem(newId(), thread.id, run?.id, itemOrd++, e.nativeRef, e.parentRef?.let { items[it]?.id }, e.status, payload, now())
        dirty += e.nativeRef
        val r = run
        val started = r?.startedAt
        if (r != null && started != null && state.firstTokenRunId != r.id &&
            (payload is ItemPayload.AssistantMessage || payload is ItemPayload.Reasoning)
        ) state = state.copy(firstTokenMs = (now() - started).coerceAtLeast(0), firstTokenRunId = r.id)
        detectPreview(payload, e.status in OPEN_ITEM)
        publishItems()
    }

    private val previewPorts = HashSet<Int>()

    /** A loopback dev-server URL in tool output or a finished agent message = one Preview card per port (plan 12). */
    private fun detectPreview(p: ItemPayload, open: Boolean) {
        val text = when (p) {
            is ItemPayload.CommandExecution -> p.outputTail
            is ItemPayload.DynamicTool -> p.output
            is ItemPayload.AssistantMessage -> if (open) return else p.text
            else -> return
        }
        // Streaming output: the last line may be cut mid-URL ("localhost:51"), scan whole lines only.
        val scan = if (open) text.substringBeforeLast('\n', "") else text
        if (!scan.contains("://")) return
        for (url in com.zenithblue.nativecode.preview.PortProbe.findUrls(scan)) {
            val port = com.zenithblue.nativecode.preview.PortProbe.portOf(url) ?: continue
            if (!previewPorts.add(port)) continue
            com.zenithblue.nativecode.preview.PortProbe.remember(port)
            external(ItemPayload.Bridge("preview", "Dev server on :$port", "", target = url, state = ItemPayload.Bridge.DONE, at = now()))
        }
    }

    /** Open reasoning items of this run → [status], with their duration (runtimes that send no timing). */
    private fun closeReasoning(status: ItemStatus) {
        for ((k, it) in items) {
            val p = it.payload as? ItemPayload.Reasoning ?: continue
            if (it.status !in OPEN_ITEM) continue
            items[k] = it.copy(status = status, payload = p.copy(durationMs = p.durationMs ?: reasonStart[k]?.let { s -> now() - s }), updatedAt = now())
            dirty += k
        }
    }

    private fun request(e: AgentEvent.RequestUpsert) {
        val old = live[e.nativeId]
        if (e.status == RequestStatus.PENDING) {
            if (old != null) {
                val upd = old.copy(kind = e.kind, options = e.options, detail = e.detail)
                live[e.nativeId] = upd
                writes += ChatWrite.PutRequest(upd)
                return
            }
            val reqId = newId()
            val input = e.kind == RequestKind.USER_INPUT || e.kind == RequestKind.MCP_ELICITATION
            val item = TurnItem(newId(), thread.id, run?.id, itemOrd++, "req:${e.nativeId}", null, ItemStatus.WAITING,
                if (input) ItemPayload.UserInputRequest(reqId) else ItemPayload.ApprovalRequest(reqId), now())
            val req = ChatRequest(reqId, thread.id, run?.id, item.id, e.nativeId, e.kind, RequestStatus.PENDING, e.options, e.detail, createdAt = now())
            live[e.nativeId] = req
            items[item.nativeRef!!] = item
            writes += ChatWrite.PutItem(item)
            writes += ChatWrite.PutRequest(req)
            run?.let { if (it.status != RunStatus.WAITING) { run = it.copy(status = RunStatus.WAITING); writes += ChatWrite.PutRun(run!!) } }
            state = state.copy(phase = LivePhase.WAITING)
            effects += Effect.NeedsAttention(req)
            publishItems()
        } else if (old != null) {
            live.remove(e.nativeId)
            settleRequest(old.copy(status = e.status, resolvedAt = now()))
        }
    }

    // ---------------- internals ----------------

    private fun settleRequest(req: ChatRequest, declined: Boolean = false) {
        writes += ChatWrite.PutRequest(req)
        val st = if (req.status != RequestStatus.RESOLVED) ItemStatus.CANCELLED else if (declined) ItemStatus.DECLINED else ItemStatus.COMPLETED
        val key = "req:${req.nativeId}"
        val it = items[key]
        if (it != null && it.id == req.itemId) {
            items[key] = it.copy(status = st, updatedAt = now()); dirty += key
        } else req.itemId?.let { id -> writes += ChatWrite.UpdateItem(thread.id, id) { i -> i.copy(status = st, updatedAt = now()) } }
        val r = run
        if (live.isEmpty() && r != null && r.status == RunStatus.WAITING) {
            run = r.copy(status = RunStatus.RUNNING); writes += ChatWrite.PutRun(run!!)
            state = state.copy(phase = LivePhase.RUNNING)
        }
        flushDirty()
        publishItems()
    }

    /** Session gone: the open turn ends, every live request expires (user input included). */
    private fun endSession(status: RunStatus, failure: Failure?) {
        if (run != null) endTurn(status, failure, expireUserInput = true)
        else expireLive(true)
    }

    private fun endTurn(status: RunStatus, rawFailure: Failure?, expireUserInput: Boolean) {
        val r = run ?: return
        val failure = rawFailure?.let { ChatFailures.refine(it) }
        val itemStatus = when (status) {
            RunStatus.COMPLETED -> ItemStatus.COMPLETED
            RunStatus.FAILED -> ItemStatus.FAILED
            RunStatus.CANCELLED -> ItemStatus.CANCELLED
            else -> ItemStatus.INTERRUPTED
        }
        expireLive(expireUserInput)
        closeReasoning(itemStatus)
        for ((k, it) in items) if (!k.startsWith("req:") && it.status in OPEN_ITEM) { items[k] = it.copy(status = itemStatus, updatedAt = now()); dirty += k }
        if (failure != null) {
            val it = TurnItem(newId(), thread.id, r.id, itemOrd++, null, null, ItemStatus.COMPLETED,
                ItemPayload.Error(failure.cls, failure.message, failure.retryAt, failure.details), now())
            items[it.id] = it; dirty += it.id
        }
        val done = r.copy(status = status, failure = failure, completedAt = now(), usage = r.usage)
        run = done
        runDirty = true
        flushDirty()
        run = null
        state = state.copy(runningRunId = null, turnStartedAt = null, forceStopAvailable = false)
        effects += Effect.TurnEnded(done)
        publishItems()
    }

    /** Approvals expire; user input left at a normal turn end stays answerable as a new message. */
    private fun expireLive(includeUserInput: Boolean) {
        for (req in live.values.toList()) {
            live.remove(req.nativeId)
            if (!includeUserInput && req.kind == RequestKind.USER_INPUT) stale[req.id] = req
            else settleRequestQuiet(req.copy(status = RequestStatus.EXPIRED, resolvedAt = now()))
        }
    }

    private fun settleRequestQuiet(req: ChatRequest) {
        writes += ChatWrite.PutRequest(req)
        val key = "req:${req.nativeId}"
        items[key]?.let { items[key] = it.copy(status = ItemStatus.CANCELLED, updatedAt = now()); dirty += key }
            ?: req.itemId?.let { id -> writes += ChatWrite.UpdateItem(thread.id, id) { i -> i.copy(status = ItemStatus.CANCELLED) } }
    }

    private fun addUserItem(clientId: String, text: String, attachments: List<ChatAttachment>, intent: String) {
        val it = TurnItem(newId(), thread.id, run?.id, itemOrd++, clientId, null, ItemStatus.COMPLETED,
            ItemPayload.UserMessage(text, attachments, intent), now())
        items[clientId] = it
        writes += ChatWrite.PutItem(it)
    }

    private fun touchThread(text: String) {
        val title = if (thread.title == DEFAULT_TITLE) titleOf(text) else thread.title
        thread = thread.copy(title = title, updatedAt = now())
        val t = thread
        writes += ChatWrite.UpdateThread(t.id) { it.copy(title = if (it.title == DEFAULT_TITLE) t.title else it.title, updatedAt = t.updatedAt) }
    }

    private fun publishItems() { state = state.copy(liveItems = items.values.associateBy { it.id }) }

    companion object {
        const val DEFAULT_TITLE = "New thread"
        const val GATE_MARK = "NativeCode:"
        const val RESUME_FAILED = "Earlier context could not be restored"
        const val OUTPUT_TAIL = 64 * 1024
        private val OPEN_ITEM = setOf(ItemStatus.PENDING, ItemStatus.RUNNING, ItemStatus.WAITING)

        fun titleOf(prompt: String): String = com.zenithblue.nativecode.agent.chat.core.SkillBlocks.plain(prompt).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(60) ?: DEFAULT_TITLE
    }
}
