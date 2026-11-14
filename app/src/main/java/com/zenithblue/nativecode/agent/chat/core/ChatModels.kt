package com.zenithblue.nativecode.agent.chat.core

/**
 * Chat domain model (item 12, plan 9.2.3/9.2.5), after t3code `orchestrationV2.ts`. Rows of the
 * `agent_chat.db` projection; [ItemPayload] is serialised into `turn_items.payload_json` by the store.
 * Every runtime builds these; the UI only reads them.
 */

enum class RunStatus(val wire: String) {
    QUEUED("queued"), STARTING("starting"), RUNNING("running"), WAITING("waiting"),
    COMPLETED("completed"), INTERRUPTED("interrupted"), FAILED("failed"), CANCELLED("cancelled");

    val active: Boolean get() = this == STARTING || this == RUNNING || this == WAITING
    val terminal: Boolean get() = this == COMPLETED || this == INTERRUPTED || this == FAILED || this == CANCELLED

    companion object { fun of(w: String?) = entries.firstOrNull { it.wire == w } ?: FAILED }
}

enum class ItemStatus(val wire: String) {
    PENDING("pending"), RUNNING("running"), WAITING("waiting"), COMPLETED("completed"),
    FAILED("failed"), CANCELLED("cancelled"), INTERRUPTED("interrupted"),
    /** Approval answered with a reject option (the request itself is RESOLVED). */
    DECLINED("declined");

    companion object { fun of(w: String?) = entries.firstOrNull { it.wire == w } ?: COMPLETED }
}

enum class RequestStatus(val wire: String) {
    PENDING("pending"), RESOLVED("resolved"), EXPIRED("expired"), CANCELLED("cancelled");

    companion object { fun of(w: String?) = entries.firstOrNull { it.wire == w } ?: EXPIRED }
}

enum class RequestKind(val wire: String) {
    COMMAND("command"), FILE_READ("file-read"), FILE_CHANGE("file-change"), MCP_ELICITATION("mcp-elicitation"),
    PERMISSION("permission"), USER_INPUT("user_input"), PLAN_EXIT("plan_exit");

    companion object { fun of(w: String?) = entries.firstOrNull { it.wire == w } ?: PERMISSION }
}

/** t3code's four modes. Default [APPROVAL_REQUIRED] ("Supervised"): nothing is auto-approved. */
enum class RuntimeMode(val wire: String, val label: String) {
    APPROVAL_REQUIRED("approval-required", "Supervised"),
    AUTO_ACCEPT_EDITS("auto-accept-edits", "Auto-accept edits"),
    AUTO("auto", "Auto"),
    FULL_ACCESS("full-access", "Full access");

    companion object { fun of(w: String?) = entries.firstOrNull { it.wire == w } ?: APPROVAL_REQUIRED }
}

enum class InteractionMode(val wire: String) {
    DEFAULT("default"), PLAN("plan");

    companion object { fun of(w: String?) = entries.firstOrNull { it.wire == w } ?: DEFAULT }
}

enum class FailureClass(val wire: String) {
    USAGE_LIMIT("usage_limit"), PROVIDER_ERROR("provider_error"), TRANSPORT_ERROR("transport_error"),
    PERMISSION_ERROR("permission_error"), VALIDATION_ERROR("validation_error"), GATE_DENIED("gate_denied"),
    UNKNOWN("unknown");

    companion object { fun of(w: String?) = entries.firstOrNull { it.wire == w } ?: UNKNOWN }
}

data class Failure(val cls: FailureClass, val message: String, val retryAt: Long? = null, val details: String? = null)

/** Tokens and cost of one run (opencode `session.step.ended`, claude `result`, codex tokenUsage…). */
data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val costUsd: Double? = null,
    val contextUsed: Long? = null,
    val contextSize: Long? = null,
)

data class ChatThread(
    val id: String,
    val projectPath: String?,          // null = TERMINAL-tab thread
    val cwd: String,                   // guest path
    val tool: String,                  // CliToolDef.terminalType: opencode | claude-code | codex | agy | grok
    val title: String,
    val nativeId: String? = null,      // resume key
    val model: String? = null,
    val effort: String? = null,
    val runtimeMode: RuntimeMode = RuntimeMode.APPROVAL_REQUIRED,
    val interactionMode: InteractionMode = InteractionMode.DEFAULT,
    val draftJson: String? = null,     // {text, attachments[]}
    val pinnedAt: Long? = null,
    val archivedAt: Long? = null,
    val lastVisitedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

data class Run(
    val id: String,
    val threadId: String,
    val ordinal: Int,
    val status: RunStatus,
    val queuePosition: Int? = null,
    val nativeTurnId: String? = null,
    val failure: Failure? = null,
    val usage: Usage? = null,
    val chkBase: String? = null,
    val chkEnd: String? = null,
    val diffStat: List<DiffStat>? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
)

data class DiffStat(val path: String, val kind: String, val add: Int, val del: Int) // kind: M | A | D | R

/** A file the user attached; [guestPath] is the copy inside the guest (null for inline-only providers). */
data class ChatAttachment(
    val name: String,
    val mime: String,
    val sizeBytes: Long,
    val guestPath: String?,
    val hostPath: String? = null,      // host-side copy / thumbnail for the bubble
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

data class TodoStep(val text: String, val status: String) // pending | in_progress | completed

/** `turn_items` payloads (plan 9.2.5). [type] is the stored `turn_items.type`. */
sealed class ItemPayload(val type: String) {
    data class UserMessage(val text: String, val attachments: List<ChatAttachment> = emptyList(), val intent: String = "start") : ItemPayload("user_message")
    data class AssistantMessage(val text: String, val phase: String? = null) : ItemPayload("assistant_message")
    data class Reasoning(val text: String, val durationMs: Long? = null) : ItemPayload("reasoning")
    data class CommandExecution(val command: String, val outputTail: String = "", val exitCode: Int? = null, val durationMs: Long? = null) : ItemPayload("command_execution")
    data class FileChange(val path: String, val add: Int = 0, val del: Int = 0, val diff: String? = null) : ItemPayload("file_change")
    data class FileSearch(val tool: String, val pattern: String, val results: List<String> = emptyList()) : ItemPayload("file_search")
    data class WebSearch(val query: String, val results: List<String> = emptyList()) : ItemPayload("web_search")
    data class DynamicTool(val name: String, val input: String = "", val output: String = "") : ItemPayload("dynamic_tool")
    data class Subagent(val title: String, val childCount: Int = 0, val result: String? = null) : ItemPayload("subagent")
    data class TodoList(val steps: List<TodoStep>) : ItemPayload("todo_list")
    data class ProposedPlan(val markdown: String) : ItemPayload("proposed_plan")
    data class ApprovalRequest(val requestId: String) : ItemPayload("approval_request")
    data class UserInputRequest(val requestId: String) : ItemPayload("user_input_request")
    data class Error(val cls: FailureClass, val message: String, val retryAt: Long? = null, val details: String? = null) : ItemPayload("error")
    data class Compaction(val tokens: Long? = null) : ItemPayload("compaction")
    data class SystemNotice(val text: String) : ItemPayload("system_notice")
    /**
     * A `nativecode-agent` bridge request from this thread's CLI, shown as a card instead of a popup.
     * [action]: notify | status | toast | open_link | install_apk | share_file. [target] = URL or host file path;
     * [detail] = APK label/package, status progress. [state]: pending | done | dismissed. [key] = status key (updates in place).
     */
    data class Bridge(
        val action: String, val title: String, val body: String = "", val target: String? = null, val detail: String? = null,
        val state: String = PENDING, val key: String? = null, val at: Long = 0,
    ) : ItemPayload("bridge_event") {
        companion object { const val PENDING = "pending"; const val DONE = "done"; const val DISMISSED = "dismissed" }
    }
}

data class TurnItem(
    val id: String,
    val threadId: String,
    val runId: String?,
    val ordinal: Int,
    val nativeRef: String?,
    val parentId: String?,
    val status: ItemStatus,
    val payload: ItemPayload,
    val updatedAt: Long,
) {
    val type: String get() = payload.type
}

/** One option exactly as the agent offered it; answered by [id]. kind: allow_once | allow_session | allow_always | reject | cancel */
data class RequestOption(val id: String, val kind: String, val label: String, val warning: String? = null)

data class QuestionOption(val label: String, val description: String? = null)
data class Question(
    val id: String,
    val header: String? = null,
    val text: String,
    val options: List<QuestionOption> = emptyList(),
    val multiple: Boolean = false,
    val custom: Boolean = true,
)

/** What the card shows: command, path, proposed diff, reason, questions, plan. */
data class RequestDetail(
    val title: String,
    val command: String? = null,
    val paths: List<String> = emptyList(),
    val diff: String? = null,
    val reason: String? = null,
    val questions: List<Question> = emptyList(),
    val plan: String? = null,
)

data class ChatRequest(
    val id: String,
    val threadId: String,
    val runId: String?,
    val itemId: String?,
    val nativeId: String,
    val kind: RequestKind,
    val status: RequestStatus,
    val options: List<RequestOption>,
    val detail: RequestDetail,
    val answerJson: String? = null,
    val createdAt: Long,
    val resolvedAt: Long? = null,
)

/** A model the runtime reports. [id] is the slug the runtime takes back (opencode `providerID/id`). */
data class ModelInfo(
    val id: String,
    val name: String,
    val providerId: String? = null,
    val free: Boolean = false,
    val variants: List<String> = emptyList(),   // effort levels
    val contextLimit: Long? = null,
)

/**
 * One slash-popup entry (plan 9.2.15 "Slash commands"). Runtimes report [Kind.PROVIDER] and [Kind.SKILL] entries in
 * `SessionState.commands`; [Kind.BUILTIN] ones are the client-side t3code built-ins (ui/SlashMenu). [argumentHint] is
 * shown after the name and as ghost text once `/name ` is inserted (claude `argumentHint`, ACP `input.hint`).
 * A provider command is run by sending its raw text (`/name args`) through [AgentRuntime.send]; a skill is inserted
 * as a `$name` mention the runtime resolves (opencode prompt `skills`, codex native `$name`).
 */
data class SlashCommand(
    val name: String,
    val description: String? = null,
    val argumentHint: String? = null,
    val kind: Kind = Kind.PROVIDER,
) {
    enum class Kind { BUILTIN, PROVIDER, SKILL }
}
