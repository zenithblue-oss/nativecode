package com.zenithblue.nativecode.agent.chat.ui

import android.net.Uri
import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.RuntimeCaps
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import com.zenithblue.nativecode.agent.chat.core.Usage

/** Banner slot content. [action] labels the button (null = none). kind: signin | ratelimit | queue | ended | info */
data class Banner(val kind: String, val text: String, val action: String? = null)

/** In-memory snapshot of a thread's controller; [alive] = a process runs or starts. Null = no controller yet. */
data class LiveState(
    val phase: AgentEvent.Phase,
    val alive: Boolean = false,
    val runStartedAt: Long? = null,
    val liveItems: List<TurnItem> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val defaultModel: String? = null,
    val commands: List<SlashCommand> = emptyList(),
    val caps: RuntimeCaps? = null,
    val banner: Banner? = null,
    val usage: Usage? = null,
    val forceStopAvailable: Boolean = false,
    /** Time to first token of run [ttftRunId] (live only, not persisted). */
    val ttftMs: Long? = null,
    val ttftRunId: String? = null,
    val reconnecting: Boolean = false,
    /** Provider quota state (claude rate_limit_event, codex rateLimits; M2+); null = not reported. */
    val rateLimit: AgentEvent.RateLimit? = null,
    val account: String? = null,
    /** Run id of the turn streaming now. */
    val runId: String? = null,
)

enum class DiffScope(val label: String) { TURN("This turn"), THREAD("Whole thread"), UNCOMMITTED("Uncommitted") }

/**
 * The UI's whole view of the chat engine: store queries (IO thread) + controller commands (any thread).
 * One implementation over ChatStore / ChatThreadController ([StoreBackend]); screens never touch them directly.
 */
interface ChatBackend {
    /** [projectPath] null + [all] = every thread (TERMINAL tab); else only that project's threads. */
    fun threads(projectPath: String?, all: Boolean): List<ChatThread>
    fun thread(id: String): ChatThread?
    fun runs(threadId: String): List<Run>
    fun openRequests(threadId: String): List<ChatRequest>
    fun items(threadId: String, limit: Int = 200, beforeOrdinal: Int? = null): List<TurnItem>
    fun createThread(projectPath: String?, cwd: String, tool: String): ChatThread
    fun rename(id: String, title: String)
    fun pin(id: String, pinned: Boolean)
    fun archive(id: String, archived: Boolean)
    fun setDraft(id: String, json: String?)
    fun markVisited(id: String)
    /** Stops the process, deletes rows and checkpoint refs. */
    fun delete(id: String)
    fun addListener(l: (Set<String>) -> Unit)
    fun removeListener(l: (Set<String>) -> Unit)

    fun live(threadId: String): LiveState?
    /** Live snapshot changes of one thread; returns the unsubscribe. */
    fun observeLive(threadId: String, l: () -> Unit): () -> Unit
    /** [fresh] = `/clear` armed: an idle thread starts a new native session for this message. */
    fun send(threadId: String, text: String, attachments: List<ChatAttachment>, steer: Boolean, fresh: Boolean = false)
    /** `/side`: new "Side chat" thread forking [parentId]'s session (same tool, cwd, picks), [prompt] sent when not blank. IO thread. */
    fun sideChat(parentId: String, prompt: String): ChatThread?
    fun interrupt(threadId: String)
    fun forceStop(threadId: String)
    /** Close the process (frees the slot); history stays. */
    fun stop(threadId: String)
    fun respond(threadId: String, requestId: String, answer: RequestAnswer)
    fun configure(threadId: String, change: ConfigChange)
    fun queueSendNow(threadId: String, runId: String)
    fun queueRemove(threadId: String, runId: String)
    fun queueResume(threadId: String)
    /** "Session ended · Resume": respawn and resume the native session without sending. */
    fun resume(threadId: String)
    /** Copy a pick into [cwd]; callback on the main thread with the attachment or an error message. */
    fun attach(uri: Uri, cwd: String, imageOnly: Boolean, cb: (ChatAttachment?, String?) -> Unit)
    /** Unified patch for the scope (IO thread); null = not available. */
    fun diff(threadId: String, scope: DiffScope): String?
    /** Live (or starting) chat processes, for the sessions chip. */
    fun liveThreadIds(): List<String>
}
