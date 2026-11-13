package com.zenithblue.nativecode.agent.chat.tools

import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * NativeCode tools (item 12 M7): t3code's orchestrator, thread and project MCP tools under an `nc_` prefix
 * (`delegate_task` → `nc_delegate_task`, `t3_thread_send` → `nc_thread_send`), same input schemas minus linked
 * environments, worktrees and attachments. Pure (JVM-tested): schemas, input checks, mode clamping, depth rule.
 */
object NcToolSpec {
    class Tool(
        val name: String,
        val title: String,
        val description: String,
        val schema: JSONObject,
        val readOnly: Boolean = false,
        /** Starts or steers work in another thread: hidden from delegated children (depth limit 1). */
        val writer: Boolean = false,
    ) {
        fun json(): JSONObject = JSONObject().put("name", name).put("title", title).put("description", description)
            .put("inputSchema", schema)
            .put("annotations", JSONObject().put("title", title).put("readOnlyHint", readOnly).put("destructiveHint", !readOnly)
                .put("openWorldHint", writer))
    }

    class Failure(val code: String, override val message: String) : Exception(message)

    const val RUNNING_TASK_NEXT = "Completion wakes this thread, so end your turn. Do not wait on it or poll it."

    // ---------------- schema DSL ----------------

    private fun obj(vararg p: Pair<String, JSONObject>, required: List<String> = emptyList()) = JSONObject().put("type", "object")
        .put("properties", JSONObject().apply { p.forEach { put(it.first, it.second) } }).put("required", JSONArray(required))
    private fun str(d: String? = null, max: Int? = null, enum: List<String>? = null) = JSONObject().put("type", "string").apply {
        d?.let { put("description", it) }; max?.let { put("maxLength", it) }; enum?.let { put("enum", JSONArray(it)) }
    }
    private fun int(d: String? = null, min: Int? = null, max: Int? = null) = JSONObject().put("type", "integer").apply {
        d?.let { put("description", it) }; min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
    }
    private fun num(d: String? = null) = JSONObject().put("type", "number").apply { d?.let { put("description", it) } }
    private fun bool(d: String? = null) = JSONObject().put("type", "boolean").apply { d?.let { put("description", it) } }
    private fun anyObj(d: String) = JSONObject().put("type", "object").put("description", d)

    private val RUNTIME_MODES = listOf("inherit") + RuntimeMode.entries.map { it.wire }
    private val INTERACTION_MODES = listOf("inherit") + InteractionMode.entries.map { it.wire }
    private const val PROMPT_MAX = 110_000 // + the cross-thread wrapper stays under the 120k chat message cap

    private val target = obj(
        "providerInstanceId" to str("Provider from `nc_orchestrator_capabilities` (opencode, claude-code, codex, agy, grok)."),
        "driverKind" to str("Prefer `providerInstanceId`."),
        "model" to str("Model id from `nc_orchestrator_capabilities`."),
        "options" to anyObj("Options from `nc_orchestrator_capabilities`, such as {effort:'high'}."),
    )

    val ALL: List<Tool> = listOf(
        Tool("nc_orchestrator_capabilities", "Get orchestration capabilities",
            "List providers, models, options, and runtime modes for `nc_delegate_task`. Short list. `search` finds others.",
            obj("search" to str("Match model ids and labels. Replaces the short list.", 200)), readOnly = true),
        Tool("nc_delegate_task", "Delegate a child task",
            "Start one child agent (any NativeCode chat CLI or model) with only `task`. No parent history. Keep `taskId` for `nc_task_status` and `nc_task_cancel`. No secrets in `task`. A child cannot delegate again. Free plan: opencode only.",
            obj(
                "task" to str(null, PROMPT_MAX),
                "target" to target,
                "title" to str(null, 512),
                "role" to str(null, enum = listOf("implementation", "research", "review", "design", "test", "general")),
                "isolation" to str("'shared' (default): parent checkout. 'worktree' is not available on NativeCode.", enum = listOf("shared", "worktree")),
                "mode" to str("Default 'async'. 'wait' only if this turn needs the result. A question or approval ends the wait.", enum = listOf("async", "wait")),
                "timeoutMs" to num("Mode 'wait' only. Default 4 min. A timeout does not cancel the child."),
                "clientRequestId" to str("Reuse it to retry the same call. A new call gets a new key.", 256),
                "runtimeMode" to str("Omit unless the user asks for a supervised child. Above yours is rejected.", enum = RUNTIME_MODES),
                "interactionMode" to str(null, enum = INTERACTION_MODES),
                required = listOf("task"),
            ), writer = true),
        Tool("nc_task_status", "Get delegated task status",
            "Read one delegated task you started. Do not call it on a running task before its completion wakes you. `summary`: final result. Reading it marks it delivered.",
            obj("taskId" to str(), required = listOf("taskId"))),
        Tool("nc_task_cancel", "Cancel delegated task",
            "Stop one delegated task by `taskId`: its running and queued turns in its child thread. A finished task keeps its result.",
            obj("taskId" to str(), "reason" to str(null, 2000), "clientRequestId" to str(null, 256), required = listOf("taskId"))),
        Tool("nc_thread_list", "List NativeCode threads",
            "List chat threads newest first, in this thread's project or with `allProjects`. Page with `cursor`.",
            obj(
                "projectId" to str("Project path. Default: your thread's project."),
                "allProjects" to bool(),
                "statuses" to JSONObject().put("type", "array").put("items", str()),
                "titleContains" to str(null, 256),
                "includeSubagents" to bool("Include delegated child threads."),
                "cursor" to int(null, 0),
                "limit" to int(null, 1, 100),
            ), readOnly = true),
        Tool("nc_thread_read", "Read a NativeCode thread",
            "Read any thread by id. View `messages` (default) or `activity` (all items). Newest first; older: `beforePosition=earlierPosition`; from the start: `afterPosition=-1`, then `nextPosition`. Cut-off text: `itemId`, `textOffset=nextTextOffset`. Reading a child's final result marks it delivered. In your reply, name it as its `link` verbatim.",
            obj(
                "threadId" to str(),
                "itemId" to str(),
                "textOffset" to int(null, 0),
                "view" to str(null, enum = listOf("messages", "activity")),
                "afterPosition" to int(null, -1),
                "beforePosition" to int(null, 0),
                "limit" to int("Max 100.", 1),
                "runLimit" to int("Max 50.", 1),
                "maxCharsPerItem" to int("Max 20000.", 1),
                required = listOf("threadId"),
            ), readOnly = true),
        Tool("nc_thread_send", "Send to a NativeCode thread",
            "Send a message to another thread. The target cannot run with broader modes than yours. `mode:'auto'` starts an idle thread, steers a running turn, or queues. `restart`: interrupt and restart. A send to a finished child thread starts a fresh task and returns its `taskId`. In your reply, name it as its `link` verbatim.",
            obj(
                "threadId" to str(),
                "message" to str(null, PROMPT_MAX),
                "mode" to str(null, enum = listOf("auto", "queue", "steer", "restart")),
                "freshContext" to bool("Not available on NativeCode."),
                "clientRequestId" to str(null, 256),
                required = listOf("threadId", "message"),
            ), writer = true),
        Tool("nc_thread_wait", "Wait for a NativeCode thread",
            "Wait for a thread's run to finish. No `runId`: latest. Idle: at once. On timeout, with `next` end your turn; without, call again.",
            obj(
                "threadId" to str(),
                "runId" to str(),
                "timeoutMs" to num("Default 2 min, max 10 min."),
                "untilDelegatedWorkSettled" to bool("Also wait for its delegated tasks to settle."),
                required = listOf("threadId"),
            ), readOnly = true),
        Tool("nc_thread_interrupt", "Interrupt a NativeCode thread",
            "Interrupt a top-level thread's running turn. No effect when idle. For a delegate, use `nc_task_cancel`. `stop`: also close its agent process and hold its queue.",
            obj("threadId" to str(), "runId" to str(), "reason" to str(null, 2000), "stop" to bool(), "clientRequestId" to str(null, 256),
                required = listOf("threadId")), writer = true),
        Tool("nc_thread_launch", "Launch a NativeCode thread",
            "Create one new top-level thread (only when the user asks for a new thread; 'subagent' or 'delegate' never means one). Task in `message`. Omit `projectId`, model and modes to inherit yours; modes cannot exceed yours. Mention it with its `link`.",
            obj(
                "projectId" to str("Project path. Default: yours."),
                "title" to str(null, 512),
                "modelSelection" to anyObj("{instanceId: provider, model?, options?}"),
                "runtimeMode" to str(null, enum = RuntimeMode.entries.map { it.wire }),
                "interactionMode" to str(null, enum = InteractionMode.entries.map { it.wire }),
                "message" to str("First prompt. Omit for an idle thread.", PROMPT_MAX),
                "clientRequestId" to str(null, 256),
                required = listOf("title"),
            ), writer = true),
        Tool("nc_thread_transfer", "Transfer thread context",
            "Move context between threads. Omit `threadId` for this thread. fork: copy its recent conversation into a new thread with the same setup; no turn runs yet (the context goes with its first message). merge_back: send its latest result into a related `targetThreadId` in the same project. status: its context transfers.",
            obj(
                "action" to str(null, enum = listOf("fork", "merge_back", "status")),
                "threadId" to str(),
                "targetThreadId" to str(),
                "title" to str(null, 512),
                required = listOf("action"),
            ), writer = true),
        Tool("nc_pending_request", "Answer a pending question",
            "Pending user questions in a thread. Omit `threadId` for this thread. list: their ids; approvals are not listed. read: one by `requestId`. respond: answer one with `answers` ({questionId: answer or [answers]}); this cannot approve a permission request.",
            obj(
                "action" to str(null, enum = listOf("list", "read", "respond")),
                "threadId" to str(),
                "requestId" to str(),
                "answers" to anyObj("{questionId: string | string[]}"),
                required = listOf("action"),
            )),
        Tool("nc_queue", "Manage queued messages",
            "Manage a thread's queued messages. Omit `threadId` for this thread. list: send order. read: up to 16,000 chars of one. edit: replace its `text`. cancel. promote_to_steer: send it now (steers a running turn where the CLI can). Writes fail once the run is no longer queued.",
            obj(
                "action" to str(null, enum = listOf("list", "read", "edit", "cancel", "reorder", "promote_to_steer")),
                "threadId" to str(),
                "queuedRunId" to str(),
                "text" to str(null, 100_000),
                "beforeRunId" to str(),
                "targetRunId" to str(),
                "cursor" to int(null, 0),
                "limit" to int(null, 1, 100),
                required = listOf("action"),
            )),
        Tool("nc_thread_update", "Rename a NativeCode thread",
            "Rename a thread (`action:'rename'` with `title`). Omit `threadId` for this thread.",
            obj("action" to str(null, enum = listOf("rename")), "threadId" to str(), "title" to str(null, 200), required = listOf("action", "title"))),
        Tool("nc_project_list", "List NativeCode projects",
            "List the user's NativeCode projects (workspace folders in the Linux guest).",
            obj("cursor" to int(null, 0), "limit" to int(null, 1, 100)), readOnly = true),
        Tool("nc_project_read", "Read a NativeCode project",
            "Read one project: workspace path, Linux method and its chat threads.",
            obj("projectId" to str("Project path from `nc_project_list`."), required = listOf("projectId")), readOnly = true),
        Tool("nc_environment_read", "Read the NativeCode environment",
            "This environment: the phone, the Linux guest, the plan (Free or Pro), AI session use and the chat CLIs.",
            obj(), readOnly = true),
        Tool("nc_device_list", "List devices",
            "The device this environment runs on (this phone): model, Android version, ABI, memory, storage, battery.",
            obj(), readOnly = true),
    )

    private val byName = ALL.associateBy { it.name }

    fun tool(name: String): Tool? = byName[name]

    /** Depth limit 1: a delegated child sees no tool that starts or steers work elsewhere. */
    fun visible(isChild: Boolean): List<Tool> = if (isChild) ALL.filter { !it.writer } else ALL

    // ---------------- input checks ----------------

    /**
     * Normalises then checks [args] against [schema] (required, type, enum, maxLength, minimum); unknown keys are
     * ignored. Object parameters sent as JSON text (opencode 1.15 does this) are parsed. Throws [Failure].
     */
    fun validate(schema: JSONObject, args: JSONObject, path: String = "") {
        val props = schema.optJSONObject("properties") ?: JSONObject()
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) {
            val k = required.getString(i)
            if (!args.has(k) || args.isNull(k)) throw Failure("invalid_request", "Missing `$path$k`.")
        }
        for (k in props.keys()) {
            if (!args.has(k) || args.isNull(k)) { args.remove(k); continue }
            val p = props.getJSONObject(k)
            var v = args.get(k)
            val name = "$path$k"
            when (p.optString("type")) {
                "string" -> {
                    if (v !is String) throw Failure("invalid_request", "`$name` must be a string.")
                    if (p.has("maxLength") && v.length > p.getInt("maxLength")) throw Failure("invalid_request", "`$name` is longer than ${p.getInt("maxLength")}.")
                    p.optJSONArray("enum")?.let { e -> if ((0 until e.length()).none { e.getString(it) == v }) throw Failure("invalid_request", "`$name` must be one of ${e.join(", ")}.") }
                }
                "integer", "number" -> {
                    if (v is String) v = v.toDoubleOrNull() ?: throw Failure("invalid_request", "`$name` must be a number.")
                    if (v !is Number) throw Failure("invalid_request", "`$name` must be a number.")
                    if (p.optString("type") == "integer" && v.toDouble() % 1.0 != 0.0) throw Failure("invalid_request", "`$name` must be an integer.")
                    if (p.has("minimum") && v.toDouble() < p.getDouble("minimum")) throw Failure("invalid_request", "`$name` is below ${p.get("minimum")}.")
                    args.put(k, if (p.optString("type") == "integer") v.toLong() else v.toDouble())
                }
                "boolean" -> {
                    if (v is String && (v == "true" || v == "false")) args.put(k, v == "true")
                    else if (v !is Boolean) throw Failure("invalid_request", "`$name` must be a boolean.")
                }
                "object" -> {
                    if (v is String) v = runCatching { JSONObject(v) }.getOrNull() ?: throw Failure("invalid_request", "`$name` must be an object.")
                    if (v !is JSONObject) throw Failure("invalid_request", "`$name` must be an object.")
                    args.put(k, v)
                    if (p.has("properties")) validate(p, v, "$name.")
                }
                "array" -> {
                    if (v is String) v = runCatching { JSONArray(v) }.getOrNull() ?: throw Failure("invalid_request", "`$name` must be an array.")
                    if (v !is JSONArray) throw Failure("invalid_request", "`$name` must be an array.")
                    args.put(k, v)
                }
            }
        }
    }

    /** Integer argument clamped to [max] (t3: Codex hides maximums, so too-large values clamp instead of failing). */
    fun clamp(args: JSONObject, key: String, default: Int, max: Int): Int = (if (args.has(key)) args.getLong(key) else default.toLong()).coerceIn(1, max.toLong()).toInt()

    // ---------------- modes (t3 delegatedTaskModes.ts) ----------------

    fun rank(m: RuntimeMode): Int = when (m) {
        RuntimeMode.APPROVAL_REQUIRED -> 0
        RuntimeMode.AUTO_ACCEPT_EDITS -> 1
        RuntimeMode.AUTO -> 2
        RuntimeMode.FULL_ACCESS -> 3
    }

    fun rank(m: InteractionMode): Int = if (m == InteractionMode.PLAN) 0 else 1

    /**
     * The mode a child runs at: the one asked for, never broader than its parent's. An inherited mode the target
     * does not offer steps down to the broadest one it does; an explicitly requested one is refused.
     */
    fun resolveRuntimeMode(parent: RuntimeMode, requested: String?, supported: List<RuntimeMode>?): RuntimeMode {
        val inherited = requested == null || requested == "inherit"
        val resolved = if (inherited) parent else RuntimeMode.entries.firstOrNull { it.wire == requested }
            ?: throw Failure("invalid_request", "Unknown runtime mode $requested.")
        if (rank(resolved) > rank(parent)) throw Failure("runtime_mode_escalation_denied", "Child runtime mode ${resolved.wire} is broader than parent mode ${parent.wire}.")
        if (supported.isNullOrEmpty() || resolved in supported) return resolved
        if (!inherited) throw Failure("invalid_request", "The target does not offer runtime mode ${resolved.wire}. Use one of: ${supported.joinToString { it.wire }}.")
        return supported.filter { rank(it) <= rank(resolved) }.maxByOrNull { rank(it) } ?: RuntimeMode.APPROVAL_REQUIRED
    }

    fun resolveInteractionMode(parent: InteractionMode, requested: String?): InteractionMode {
        val resolved = if (requested == null || requested == "inherit") parent else InteractionMode.entries.firstOrNull { it.wire == requested }
            ?: throw Failure("invalid_request", "Unknown interaction mode $requested.")
        if (rank(resolved) > rank(parent)) throw Failure("interaction_mode_escalation_denied", "Child interaction mode ${resolved.wire} is broader than parent mode ${parent.wire}.")
        return resolved
    }

    /** A thread may only be driven by a caller whose modes are at least as broad (t3_thread_send). */
    fun canDrive(caller: RuntimeMode, callerI: InteractionMode, target: RuntimeMode, targetI: InteractionMode): Boolean =
        rank(target) <= rank(caller) && rank(targetI) <= rank(callerI)

    // ---------------- delegation rules ----------------

    /** Catalog id of a provider name (aliases included); null = unknown. */
    fun providerId(name: String?, known: Collection<String>): String? {
        val n = name?.trim()?.lowercase()?.ifEmpty { null } ?: return null
        val alias = when (n) { "claude", "claude_code", "anthropic" -> "claude-code"; "antigravity", "gemini" -> "agy"; "xai" -> "grok"; else -> n }
        return alias.takeIf { it in known }
    }

    /**
     * Who may delegate where, before anything spawns (plan 9.2.9 + M7): a delegated child never delegates (depth 1);
     * a tool the plan does not allow (Free: opencode only) is refused with the upsell. Null = allowed so far
     * (the session cap is the controller's send check).
     */
    fun delegateDenied(callerIsChild: Boolean, tool: String, canUseTool: (String) -> Boolean): Failure? = when {
        callerIsChild -> Failure("capability_denied", "A delegated child cannot delegate again (depth limit 1). Do the work yourself.")
        !canUseTool(tool) -> Failure("capability_denied", "$tool needs NativeCode Pro. The Free plan delegates to opencode only. Tell the user; do not work around it.")
        else -> null
    }

    /** The task as its child receives it (t3 `taskPrompt`), plus the child's ground rules. */
    fun taskPrompt(task: String, role: String?): String {
        val body = if (role == null || role == "general") task else "Act as the $role sub-agent for this task.\n\n$task"
        return "$body\n\n(Delegated NativeCode task: do the work yourself, you cannot delegate it again. End with a short final answer; it is returned to the thread that delegated it.)"
    }

    /** `effort` from target options: {effort:'high'} or [{id:'effort', value:'high'}]. */
    fun effortOf(options: Any?): String? = when (options) {
        is JSONObject -> options.optString("effort").ifEmpty { null } ?: options.optString("reasoningEffort").ifEmpty { null }
        is JSONArray -> (0 until options.length()).mapNotNull { options.optJSONObject(it) }.firstOrNull { it.optString("id") == "effort" }?.optString("value")?.ifEmpty { null }
        else -> null
    }

    // ---------------- results ----------------

    fun ok(v: JSONObject): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", v.toString())))
        .put("isError", false)

    fun fail(f: Failure): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text",
            JSONObject().put("error", JSONObject().put("code", f.code).put("message", f.message)).toString())))
        .put("isError", true)

    // ---------------- cross-thread messages ----------------

    /** Link the agents cite and the UI resolves. */
    fun link(threadId: String) = "nativecode://thread/$threadId"

    fun threadMessage(fromId: String, fromTitle: String, text: String) =
        "<nc_thread_message from_thread=\"$fromId\" from_title=\"${attr(fromTitle)}\">\n$text\n</nc_thread_message>"

    fun taskResult(taskId: String, childId: String, tool: String, status: String, summary: String?) =
        "<nc_task_result task=\"$taskId\" thread=\"$childId\" provider=\"$tool\" status=\"$status\">\n${summary?.take(8_000)?.ifBlank { null } ?: "(no final message)"}\n</nc_task_result>"

    private fun attr(s: String) = s.replace("\"", "'").replace("\n", " ").take(120)

    /** A message another thread or a task completion delivered: kind, source thread, label, body. */
    data class Incoming(val kind: String, val fromThread: String, val label: String, val status: String?, val body: String)

    private val INCOMING = Regex("""^<nc_(thread_message|task_result) ([^>]*)>\n?(.*?)\n?</nc_\1>\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val ATTR = Regex("""(\w+)="([^"]*)"""")

    fun parseIncoming(text: String): Incoming? {
        val m = INCOMING.find(text.trim()) ?: return null
        val a = ATTR.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
        return if (m.groupValues[1] == "thread_message") Incoming("message", a["from_thread"].orEmpty(), a["from_title"].orEmpty(), null, m.groupValues[3])
        else Incoming("task", a["thread"].orEmpty(), a["provider"].orEmpty(), a["status"], m.groupValues[3])
    }
}
