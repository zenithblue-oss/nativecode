package com.zenithblue.nativecode.agent.chat.core

/**
 * Normalised runtime → controller events (plan 9.2.3). Like t3code `ProviderAdapterV2Event`, every
 * `*Upsert` is a FULL snapshot of one record keyed by its native ref, never a delta.
 * Runtimes emit from their own threads; the controller serialises.
 */
sealed interface AgentEvent {
    enum class Phase { STARTING, READY, RUNNING, WAITING, STOPPED, ERROR }

    /** Session lifecycle + what the session offers. Null lists = unchanged. READY = handshake done (slot goes live). */
    data class SessionState(
        val phase: Phase,
        val caps: RuntimeCaps? = null,
        val models: List<ModelInfo>? = null,
        val defaultModel: String? = null,
        val commands: List<SlashCommand>? = null,
        val account: String? = null,
        val message: String? = null,
        /** Agent-reported current values (claude `system/status.permissionMode`, plan mode switching the model,
         *  ACP `current_mode_update`); non-null = persisted to the thread row, the chips follow. */
        val model: String? = null,
        val runtimeMode: RuntimeMode? = null,
        val interactionMode: InteractionMode? = null,
    ) : AgentEvent

    /** Resume key (opencode session id, claude session_id, codex thread id, ACP sessionId). */
    data class NativeId(val id: String) : AgentEvent

    data class TurnStarted(val nativeTurnId: String?) : AgentEvent

    /** [parentRef] = native ref of the parent item (subagent children). */
    data class ItemUpsert(val nativeRef: String, val status: ItemStatus, val payload: ItemPayload, val parentRef: String? = null) : AgentEvent

    /** An approval / user-input / plan-exit request with the agent's exact options. */
    data class RequestUpsert(
        val nativeId: String,
        val kind: RequestKind,
        val status: RequestStatus,
        val options: List<RequestOption>,
        val detail: RequestDetail,
    ) : AgentEvent

    data class UsageUpdate(val usage: Usage) : AgentEvent

    /** [detail] = human quota summary for Thread settings (codex: "5h 42% · weekly 13%"). */
    data class RateLimit(val status: String, val resetAt: Long? = null, val detail: String? = null) : AgentEvent

    /** Transport to the agent dropped and is being re-established (opencode SSE reconnect); false = back. */
    data class Connection(val reconnecting: Boolean) : AgentEvent

    /** Ephemeral live command output; the item itself keeps a 64 KiB tail. */
    data class CommandOutputDelta(val nativeRef: String, val chunk: String) : AgentEvent

    /** Run end. [reusable] false = the session is broken (controller closes it; next send respawns + resumes). */
    data class TurnTerminal(val status: RunStatus, val failure: Failure? = null, val reusable: Boolean = true) : AgentEvent {
        /** An interrupt the agent never honoured (or grok's restart-after-Stop): the process must be killed, not left running. */
        val mustCloseSession: Boolean get() = !reusable && status == RunStatus.INTERRUPTED
    }

    /** Compaction, retry scheduled, steer delivered, resume fell back… Stored as a system_notice / compaction item. */
    data class Notice(val kind: String, val text: String) : AgentEvent

    /** The agent process ended. stderrTail containing `NativeCode:` = gate denied (plan 9.2.8). */
    data class Exited(val code: Int, val stderrTail: String) : AgentEvent

    /** Unparsed frame: protocol log only, never fatal, never a row. */
    data class Unknown(val raw: String) : AgentEvent
}
