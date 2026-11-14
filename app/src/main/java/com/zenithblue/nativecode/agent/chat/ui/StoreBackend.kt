package com.zenithblue.nativecode.agent.chat.ui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.zenithblue.nativecode.agent.chat.AgentChats
import com.zenithblue.nativecode.agent.chat.BannerKind
import com.zenithblue.nativecode.agent.chat.ChatAttachments
import com.zenithblue.nativecode.agent.chat.ChatLiveState
import com.zenithblue.nativecode.agent.chat.ChatThreadController
import com.zenithblue.nativecode.agent.chat.LivePhase
import com.zenithblue.nativecode.agent.chat.TurnCheckpoints
import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import com.zenithblue.nativecode.agent.chat.store.ChatStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** [ChatBackend] over ChatStore + ChatThreadController (AgentChats). Controller commands run on the main thread (they may show the gate's sheets). */
class StoreBackend(ctx: Context) : ChatBackend {
    private val app = ctx.applicationContext
    private val store: ChatStore get() = AgentChats.store(app)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "chat-ui-cmd").apply { isDaemon = true } }
    private val listeners = ConcurrentHashMap<(Set<String>) -> Unit, ChatStore.Listener>()

    override fun threads(projectPath: String?, all: Boolean) = store.threads(projectPath, allProjects = all)
    override fun thread(id: String) = store.thread(id)
    override fun runs(threadId: String): List<Run> = store.runs(threadId)
    override fun openRequests(threadId: String): List<ChatRequest> = store.openRequests(threadId)
    override fun items(threadId: String, limit: Int, beforeOrdinal: Int?): List<TurnItem> = store.items(threadId, limit, beforeOrdinal)
    override fun createThread(projectPath: String?, cwd: String, tool: String): ChatThread = store.createThread(projectPath, cwd, tool)
    override fun rename(id: String, title: String) = store.rename(id, title)
    override fun pin(id: String, pinned: Boolean) = store.pin(id, pinned)
    override fun archive(id: String, archived: Boolean) = if (archived) store.archive(id) else store.unarchive(id)
    override fun setDraft(id: String, json: String?) { if (store.thread(id)?.draftJson != json) store.setDraft(id, json) }
    override fun markVisited(id: String) = store.markVisited(id)
    override fun delete(id: String) = AgentChats.deleteThread(app, id)

    override fun addListener(l: (Set<String>) -> Unit) {
        val sl = ChatStore.Listener { l(it) }
        listeners[l] = sl
        store.addListener(sl)
    }

    override fun removeListener(l: (Set<String>) -> Unit) { listeners.remove(l)?.let { store.removeListener(it) } }

    override fun live(threadId: String): LiveState? = AgentChats.peekController(threadId)?.state?.let(::map)

    override fun observeLive(threadId: String, l: () -> Unit): () -> Unit {
        val sub: (ChatLiveState) -> Unit = { l() }
        var c: ChatThreadController? = null
        var gone = false
        io.execute {
            val ctl = AgentChats.controller(app, threadId) ?: return@execute
            synchronized(this) { if (gone) return@execute; c = ctl; ctl.addListener(sub) }
            l()
        }
        return { synchronized(this) { gone = true; c?.removeListener(sub) } }
    }

    /** Controller lookup off main (it may read the store), the command on main. */
    private fun cmd(threadId: String, block: (ChatThreadController) -> Unit) = io.execute {
        val c = AgentChats.controller(app, threadId) ?: return@execute
        main.post { block(c) }
    }

    override fun send(threadId: String, text: String, attachments: List<ChatAttachment>, steer: Boolean, fresh: Boolean, onRefused: () -> Unit) = cmd(threadId) { c ->
        // ponytail: the controller picks steer vs queue itself (steer when the runtime can); the long-press choice is advisory.
        ChatAttachments.checkMessage(text, attachments.size)?.let { Toast.makeText(app, it, Toast.LENGTH_LONG).show(); onRefused(); return@cmd }
        if (!c.send(text, attachments, fresh)) onRefused()
    }

    override fun sideChat(parentId: String, prompt: String): ChatThread? {
        val p = store.thread(parentId) ?: return null
        val native = p.nativeId ?: return null
        val t = store.createThread(p.projectPath, p.cwd, p.tool, "Side chat", p.model, p.effort, p.runtimeMode)
        val c = AgentChats.controller(app, t.id) ?: return null
        main.post { c.forkFrom(native); if (prompt.isNotBlank()) c.send(prompt) }
        return t
    }

    override fun interrupt(threadId: String) = cmd(threadId) { it.interrupt() }
    override fun forceStop(threadId: String) = cmd(threadId) { it.forceStop() }
    override fun stop(threadId: String) { AgentChats.peekController(threadId)?.stop() }
    override fun respond(threadId: String, requestId: String, answer: RequestAnswer) = cmd(threadId) { it.respond(requestId, answer) }
    override fun configure(threadId: String, change: ConfigChange) = cmd(threadId) { it.configure(change) }
    override fun queueSendNow(threadId: String, runId: String) = cmd(threadId) { it.sendQueuedNow(runId) }
    override fun queueRemove(threadId: String, runId: String) = cmd(threadId) { it.removeQueued(runId) }
    override fun queueResume(threadId: String) = cmd(threadId) { it.resumeQueue() }
    override fun resume(threadId: String) = cmd(threadId) { it.resume() }

    override fun attach(uri: Uri, cwd: String, imageOnly: Boolean, cb: (ChatAttachment?, String?) -> Unit) =
        ChatAttachments.copyAsync(app, uri, cwd, imageOnly, cb)

    override fun diff(threadId: String, scope: DiffScope): String? {
        val t = store.thread(threadId) ?: return null
        val runs = store.runs(threadId)
        return when (scope) {
            DiffScope.TURN -> runs.lastOrNull { it.chkBase != null }?.let { TurnCheckpoints.turnPatch(app, t.cwd, it) }
            DiffScope.THREAD -> TurnCheckpoints.threadPatch(app, t.cwd, runs)
            DiffScope.UNCOMMITTED -> TurnCheckpoints.uncommittedPatch(app, t.cwd)
        }
    }

    override fun bridgeEvents(threadId: String): List<TurnItem> = store.bridgeItems(threadId)
    override fun bridgeState(threadId: String, itemId: String, state: String) = com.zenithblue.nativecode.agent.chat.ChatBridge.setState(app, threadId, itemId, state)

    override fun liveThreadIds(): List<String> = AgentChats.liveThreadIds()

    private fun map(s: ChatLiveState) = LiveState(
        phase = when (s.phase) {
            LivePhase.STARTING -> AgentEvent.Phase.STARTING
            LivePhase.READY -> AgentEvent.Phase.READY
            LivePhase.RUNNING -> AgentEvent.Phase.RUNNING
            LivePhase.WAITING -> AgentEvent.Phase.WAITING
            LivePhase.IDLE, LivePhase.ENDED -> AgentEvent.Phase.STOPPED
        },
        alive = s.live,
        runStartedAt = s.turnStartedAt.takeIf { s.turnActive },
        liveItems = s.liveItems.values.sortedBy { it.ordinal },
        models = s.models,
        defaultModel = s.defaultModel,
        commands = s.commands,
        caps = s.caps,
        banner = banner(s),
        usage = s.usage,
        forceStopAvailable = s.forceStopAvailable,
        ttftMs = s.firstTokenMs,
        ttftRunId = s.firstTokenRunId,
        reconnecting = s.reconnecting,
        rateLimit = s.rateLimit,
        account = s.account,
        runId = s.runningRunId,
    )

    private fun banner(s: ChatLiveState): Banner? {
        s.banner?.let { b ->
            return when (b.kind) {
                BannerKind.SESSION_ENDED -> Banner("ended", "Session ended", "Resume")
                BannerKind.QUEUE_HELD -> Banner("queue", b.text.substringBefore(" · Resume"), "Resume queue")
                BannerKind.GATE_DENIED, BannerKind.UPSELL -> Banner("upsell", b.text, "Upgrade")
                BannerKind.ERROR -> Banner("error", b.text, "Open in terminal")
            }
        }
        s.rateLimit?.takeIf { it.status !in setOf("ok", "allowed", "allowed_warning") }?.let { r ->
            return Banner("ratelimit", "Rate limited" + (r.resetAt?.let { " · resets " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: ""))
        }
        if (s.phase == LivePhase.READY && s.models.isEmpty()) return Banner("signin", "Not signed in · no models", "Sign in")
        return null
    }
}
