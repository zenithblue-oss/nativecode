package com.zenithblue.nativecode.agent.chat.claude

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RequestDetail
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestOption
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.TodoStep
import com.zenithblue.nativecode.agent.chat.core.Usage
import org.json.JSONArray
import org.json.JSONObject

/**
 * claude stdout frames → [AgentEvent] (full snapshots), plus the answers to the CLI's own control requests
 * (`can_use_tool`, `elicitation`), written through [reply]. Pure: no process, no threads; one lock.
 *
 * Text and thinking stream as `stream_event` deltas keyed `<message id>:<block index>` and are finalised by the
 * full `assistant` frame of the same message; tool cards are keyed by `tool_use.id` and finished by the
 * `user` `tool_result`. `parent_tool_use_id` = subagent traffic (tool cards under the Task/Agent card).
 * A `priority:"now"` steer ends the running turn with an extra `result` that is swallowed, so the
 * controller sees exactly one TurnTerminal per run (spike §1).
 */
internal class ClaudeMapper(
    private val sink: (AgentEvent) -> Unit,
    private val reply: (JSONObject) -> Unit,
) {
    class Req(val event: AgentEvent.RequestUpsert, val tool: String, val toolUseId: String?, val input: JSONObject, val suggestions: JSONArray?)

    private class Tool(val id: String, val name: String, var input: JSONObject, val parent: String?) {
        var status = ItemStatus.RUNNING
        var output: String? = null
        var structured: JSONObject? = null
        var children = 0
    }

    private class Text(val ref: String, val thinking: Boolean, val parent: String?) {
        val sb = StringBuilder()
        var done = false
        var shown = false
    }

    /** After the handshake: mode reports from `system` frames go out as SessionState. */
    @Volatile var ready = false
    var active = false; private set
    /** The last turn failed with `authentication_failed`; the runtime respawns before the next send. */
    @Volatile var signedOut = false

    private var extraResults = 0
    private var interrupting = false
    private var authError: String? = null
    private var apiError: String? = null
    private var retryAt: Long? = null
    private var reportedModes: Pair<Any?, Any?>? = null

    private val texts = LinkedHashMap<String, Text>()
    private val currentMsg = HashMap<String, String>()          // parent scope ("" = root) → message id
    private val blockRefs = HashMap<String, String>()           // "<msg>:<index>" → text ref or tool id
    private val partialJson = HashMap<String, StringBuilder>()  // tool id → input_json_delta
    private val streamTools = HashMap<String, Pair<String, String?>>() // tool id → name, parent
    private val inputs = HashMap<String, JSONObject>()          // every tool_use input (ExitPlanMode's request input is empty)
    private val tools = HashMap<String, Tool>()
    private val plans = HashSet<String>()
    private val hidden = HashMap<String, String>()               // tool id → name, for tools without a card
    private val todos = LinkedHashMap<String, Pair<String, String>>() // task id → subject, status
    private val requests = HashMap<String, Req>()
    private var compaction: String? = null
    private var compactions = 0

    // usage of the controller run (all results of a steered run add up)
    private var turnUsage = Usage()
    private var lastContext: Long? = null
    private var msgInput = 0L
    var contextSize: Long? = null
    /** Last cumulative total_cost_usd of the current process; the runtime zeroes it per spawn. */
    @Volatile var processCost = 0.0
    private var lastModel: String? = null

    @Synchronized fun beginTurn() {
        active = true
        interrupting = false
        authError = null; apiError = null; retryAt = null
        turnUsage = Usage()
        sink(AgentEvent.TurnStarted(null))
    }

    /** A `priority:"now"` message: the running turn ends with an extra `result` (spike: two results). */
    @Synchronized fun steer() {
        if (active) extraResults++ else beginTurn()
    }

    @Synchronized fun markInterrupting() { if (active) interrupting = true }

    @Synchronized fun request(id: String): Req? = requests[id]

    @Synchronized fun settleRequest(id: String, status: RequestStatus) {
        val r = requests.remove(id) ?: return
        sink(r.event.copy(status = status))
    }

    @Synchronized fun handle(f: JSONObject) {
        when (f.optString("type")) {
            "stream_event" -> stream(f)
            "assistant" -> assistant(f)
            "user" -> user(f)
            "result" -> result(f)
            "system" -> system(f)
            "control_request" -> controlRequest(f)
            "control_cancel_request" -> settleRequest(f.optString("request_id"), RequestStatus.CANCELLED)
            "rate_limit_event" -> rateLimit(f.optJSONObject("rate_limit_info"))
            "keep_alive", "tool_progress", "auth_status", "streamlined_text", "streamlined_tool_use_summary" -> Unit
            else -> sink(AgentEvent.Unknown(f.toString().take(2000)))
        }
    }

    // ---------------- answers ----------------

    /** The user's answer → the control_response for the CLI, then the request settles. */
    @Synchronized fun respond(id: String, a: RequestAnswer) {
        val r = requests[id] ?: return
        val toolUse = r.toolUseId
        val resp = if (r.event.kind == RequestKind.USER_INPUT) {
            if (a.cancel) ClaudeWire.deny(ClaudeWire.CANCELLED, toolUse, classification = null)
            else {
                // t3code `claudeSdkUserInputAnswers`: question text → labels joined with ", "
                val answers = JSONObject()
                r.event.detail.questions.forEach { q -> a.answers[q.id]?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { answers.put(q.id, it.joinToString(", ")) } }
                if (answers.length() == 0) a.feedback?.takeIf { it.isNotBlank() }?.let { fb -> r.event.detail.questions.firstOrNull()?.let { answers.put(it.id, fb) } }
                ClaudeWire.allow(JSONObject().put("questions", r.input.optJSONArray("questions") ?: JSONArray()).put("answers", answers), toolUse, null)
            }
        } else when (if (a.cancel) CANCEL else a.optionId) {
            ONCE -> ClaudeWire.allow(r.input, toolUse, "user_temporary")
            SESSION -> ClaudeWire.allow(r.input, toolUse, "user_permanent", ClaudeWire.sessionRules(r.tool, r.suggestions))
            REJECT -> ClaudeWire.deny(a.feedback?.trim()?.ifEmpty { null } ?: ClaudeWire.DECLINED, toolUse)
            else -> ClaudeWire.deny(ClaudeWire.CANCELLED, toolUse, interrupt = true)
        }
        reply(ClaudeWire.success(id, resp))
        settleRequest(id, if (a.cancel || a.optionId == CANCEL) RequestStatus.CANCELLED else RequestStatus.RESOLVED)
    }

    // ---------------- frames ----------------

    private fun scope(f: JSONObject): String? = f.optString("parent_tool_use_id").takeIf { it.isNotBlank() && it != "null" }

    private fun stream(f: JSONObject) {
        val ev = f.optJSONObject("event") ?: return
        val parent = scope(f)
        val key = parent ?: ""
        when (ev.optString("type")) {
            "message_start" -> {
                val m = ev.optJSONObject("message") ?: return
                currentMsg[key] = m.optString("id")
                if (parent == null) {
                    lastModel = m.optString("model").ifBlank { lastModel }
                    m.optJSONObject("usage")?.let { u -> msgInput = inputOf(u); lastContext = msgInput + u.optLong("output_tokens") }
                }
            }
            "message_delta" -> if (parent == null) ev.optJSONObject("usage")?.let { u ->
                lastContext = msgInput + u.optLong("output_tokens")
                sink(AgentEvent.UsageUpdate(turnUsage.copy(contextUsed = lastContext, contextSize = contextSize)))
            }
            "content_block_start" -> {
                val msg = currentMsg[key] ?: return
                val b = ev.optJSONObject("content_block") ?: return
                val idx = ev.optInt("index")
                when (b.optString("type")) {
                    "text", "thinking" -> if (parent == null) {
                        val ref = "$msg:$idx"
                        blockRefs[ref] = ref
                        val t = texts.getOrPut(ref) { Text(ref, b.optString("type") == "thinking", null) }
                        if (t.thinking) emitText(t, ItemStatus.RUNNING) // "Thinking" row from the first frame (its duration starts here)
                    }
                    "tool_use" -> {
                        val id = b.optString("id")
                        blockRefs["$msg:$idx"] = id
                        partialJson[id] = StringBuilder()
                        streamTools[id] = b.optString("name") to parent
                        toolUse(id, b.optString("name"), b.optJSONObject("input") ?: JSONObject(), parent)
                    }
                }
            }
            "content_block_delta" -> {
                val msg = currentMsg[key] ?: return
                val ref = blockRefs["$msg:${ev.optInt("index")}"] ?: return
                val d = ev.optJSONObject("delta") ?: return
                when (d.optString("type")) {
                    "text_delta" -> appendText(ref, d.optString("text"))
                    "thinking_delta" -> appendText(ref, d.optString("thinking"))
                    "input_json_delta" -> partialJson[ref]?.append(d.optString("partial_json"))
                }
            }
            "content_block_stop" -> {
                val msg = currentMsg[key] ?: return
                val ref = blockRefs["$msg:${ev.optInt("index")}"] ?: return
                val json = partialJson.remove(ref) ?: return
                val (name, parent2) = streamTools[ref] ?: return
                runCatching { JSONObject(json.toString()) }.getOrNull()?.let { toolUse(ref, name, it, parent2) }
            }
        }
    }

    private fun appendText(ref: String, s: String) {
        val t = texts[ref] ?: return
        if (s.isEmpty() || t.done) return
        t.sb.append(s)
        emitText(t, ItemStatus.RUNNING)
    }

    private fun emitText(t: Text, status: ItemStatus) {
        if (t.sb.isBlank() && !t.thinking) return // a thinking block with display omitted still shows "Thought for Ns"
        t.shown = true
        sink(AgentEvent.ItemUpsert(t.ref, status, if (t.thinking) ItemPayload.Reasoning(t.sb.toString()) else ItemPayload.AssistantMessage(t.sb.toString())))
    }

    private fun assistant(f: JSONObject) {
        val m = f.optJSONObject("message") ?: return
        val parent = scope(f)
        val content = m.optJSONArray("content")
        val err = f.optString("error").ifBlank { null }
        if (err != null || f.optBoolean("is_api_error_message")) {
            // Synthetic error message ("Not logged in · Please run /login", rate limits…): the turn's failure, not a bubble.
            val text = ClaudeWire.resultText(content).ifBlank { err ?: "Claude API error" }
            if (err == "authentication_failed") { authError = text; signedOut = true } else apiError = text
            return
        }
        val msg = m.optString("id")
        var n = 0
        for (b in ClaudeWire.objects(content)) {
            when (val type = b.optString("type")) {
                "text", "thinking" -> {
                    if (parent != null) { // subagent prose: its latest text is the subagent card's result
                        if (type == "text") tools[parent]?.let { p -> b.optString("text").ifBlank { null }?.let { p.output = it; emitTool(p) } }
                        continue
                    }
                    val thinking = type == "thinking"
                    val body = if (thinking) b.optString("thinking") else b.optString("text")
                    val t = texts.values.firstOrNull { it.ref.startsWith("$msg:") && it.thinking == thinking && !it.done }
                        ?: Text("$msg:f${n++}", thinking, null).also { texts[it.ref] = it }
                    if (body.isNotEmpty()) { t.sb.setLength(0); t.sb.append(body) }
                    t.done = true
                    emitText(t, ItemStatus.COMPLETED)
                }
                "tool_use" -> {
                    val id = b.optString("id")
                    partialJson.remove(id)
                    toolUse(id, b.optString("name"), b.optJSONObject("input") ?: JSONObject(), parent)
                }
            }
        }
    }

    private fun toolUse(id: String, name: String, input: JSONObject, parent: String?) {
        if (input.length() > 0 || !inputs.containsKey(id)) inputs[id] = input
        when (name) {
            "AskUserQuestion" -> return // the question card is the item
            "ExitPlanMode" -> { plan(id, input.optString("plan")); return }
            in HIDDEN -> { hidden[id] = name; return } // task list tools feed the todo strip; ToolSearch is plumbing
        }
        val t = tools[id]
        if (t == null) {
            tools[id] = Tool(id, name, input, parent)
            parent?.let { p -> tools[p]?.let { it.children++; emitTool(it) } }
        } else if (input.length() > 0) t.input = input
        emitTool(tools[id]!!)
    }

    private fun plan(toolUseId: String, md: String?) {
        val text = md?.trim()?.ifEmpty { null } ?: return
        plans += toolUseId
        sink(AgentEvent.ItemUpsert("plan:$toolUseId", ItemStatus.COMPLETED, ItemPayload.ProposedPlan(text)))
    }

    private fun emitTool(t: Tool) {
        var p = ClaudeWire.toolPayload(t.name, t.input, t.output, t.structured)
        if (p is ItemPayload.Subagent) p = p.copy(childCount = t.children)
        sink(AgentEvent.ItemUpsert(t.id, t.status, p, t.parent))
    }

    private fun user(f: JSONObject) {
        val content = f.optJSONObject("message")?.optJSONArray("content") ?: return
        val structured = f.optJSONObject("tool_use_result")
        for (b in ClaudeWire.objects(content)) {
            if (b.optString("type") != "tool_result") continue
            val id = b.optString("tool_use_id")
            hidden.remove(id)?.let { name -> if (!b.optBoolean("is_error")) taskTool(name, inputs[id] ?: JSONObject(), structured, ClaudeWire.resultText(b.opt("content"))); continue }
            val t = tools[id] ?: continue
            t.output = ClaudeWire.resultText(b.opt("content"))
            t.structured = structured
            // A background subagent acknowledges at once; task_notification finishes it.
            t.status = if (b.optBoolean("is_error")) ItemStatus.FAILED else if (structured?.optBoolean("isAsync") == true) ItemStatus.RUNNING else ItemStatus.COMPLETED
            emitTool(t)
        }
    }

    /** t3code `applyClaudeTaskTool`: TaskCreate / TaskUpdate / TaskList (and TodoWrite) → the todo strip. */
    private fun taskTool(name: String, input: JSONObject, result: JSONObject?, text: String) {
        when (name) {
            "TodoWrite" -> { todos.clear(); ClaudeWire.objects(input.optJSONArray("todos")).forEachIndexed { i, t -> todos["$i"] = t.optString("content") to t.optString("status") } }
            "TaskList" -> {
                val listed = ClaudeWire.objects(result?.optJSONArray("tasks"))
                if (listed.isEmpty()) return
                todos.clear()
                listed.forEach { t -> todos[t.optString("id")] = t.optString("subject") to t.optString("status") }
            }
            "TaskCreate" -> {
                val created = result?.optJSONObject("task")
                val m = CREATED.find(text)
                val id = created?.optString("id")?.ifBlank { null } ?: m?.groupValues?.get(1) ?: return
                val subject = created?.optString("subject")?.ifBlank { null } ?: m?.groupValues?.get(2)?.trim() ?: input.optString("subject").ifBlank { return }
                todos[id] = subject to "pending"
            }
            "TaskUpdate" -> {
                if (result?.optBoolean("success", true) == false) return
                val id = input.optString("taskId").ifBlank { null } ?: return
                val old = todos[id] ?: return
                if (input.optString("status") == "deleted") todos.remove(id)
                else todos[id] = (input.optString("subject").ifBlank { null } ?: old.first) to (input.optString("status").ifBlank { null } ?: old.second)
            }
            else -> return
        }
        sink(AgentEvent.ItemUpsert("todos", ItemStatus.COMPLETED, ItemPayload.TodoList(todos.values.map { TodoStep(it.first, it.second) })))
    }

    private fun result(f: JSONObject) {
        if (!active) return // e.g. the error result of a `--resume` whose transcript is gone (the runtime falls back)
        texts.values.filter { !it.done }.forEach { it.done = true; emitText(it, ItemStatus.COMPLETED) }
        texts.clear(); blockRefs.clear(); partialJson.clear(); streamTools.clear()
        f.optJSONObject("usage")?.let { u ->
            turnUsage = turnUsage.copy(
                inputTokens = turnUsage.inputTokens + u.optLong("input_tokens"),
                outputTokens = turnUsage.outputTokens + u.optLong("output_tokens"),
                cacheReadTokens = turnUsage.cacheReadTokens + u.optLong("cache_read_input_tokens"),
                cacheWriteTokens = turnUsage.cacheWriteTokens + u.optLong("cache_creation_input_tokens"),
            )
        }
        // total_cost_usd is cumulative for the process (2.1.323: rises every result, the 0-token steer result repeats it); the run gets the delta
        if (f.has("total_cost_usd")) {
            val total = f.optDouble("total_cost_usd", 0.0)
            turnUsage = turnUsage.copy(costUsd = (turnUsage.costUsd ?: 0.0) + (total - processCost).coerceAtLeast(0.0))
            processCost = total
        }
        windowOf(f.optJSONObject("modelUsage"))?.let { contextSize = it }
        sink(AgentEvent.UsageUpdate(turnUsage.copy(contextUsed = lastContext, contextSize = contextSize)))

        if (extraResults > 0) { extraResults--; return }
        active = false
        val reason = f.optString("terminal_reason")
        val isError = f.optBoolean("is_error") || f.optString("subtype").startsWith("error")
        val status = when {
            interrupting || (isError && reason.startsWith("aborted")) -> RunStatus.INTERRUPTED
            isError || authError != null -> RunStatus.FAILED
            else -> RunStatus.COMPLETED
        }
        interrupting = false
        sink(AgentEvent.TurnTerminal(status, if (status == RunStatus.FAILED) failure(f) else null))
        if (authError != null) sink(AgentEvent.SessionState(AgentEvent.Phase.READY, models = emptyList()))
    }

    private fun failure(f: JSONObject): Failure {
        val text = authError ?: apiError ?: f.optString("result").ifBlank { null }
            ?: ClaudeWire.strings(f.optJSONArray("errors")).joinToString("\n").ifBlank { null } ?: "Claude Code failed (${f.optString("subtype")})"
        val status = f.optInt("api_error_status", 0)
        return when {
            authError != null -> Failure(FailureClass.PERMISSION_ERROR, ClaudeWire.SIGNED_OUT, details = text)
            status == 429 || LIMIT.containsMatchIn(text) -> Failure(FailureClass.USAGE_LIMIT, text, retryAt)
            else -> Failure(FailureClass.PROVIDER_ERROR, text, details = f.optString("terminal_reason").ifBlank { null })
        }
    }

    /** Context window of the model that did the turn (`modelUsage[model].contextWindow`). */
    private fun windowOf(mu: JSONObject?): Long? {
        mu ?: return null
        val main = lastModel?.let { mu.optJSONObject(it) }
        val w = main?.optLong("contextWindow") ?: mu.keys().asSequence().mapNotNull { mu.optJSONObject(it)?.optLong("contextWindow") }.maxOrNull()
        return w?.takeIf { it > 0 }
    }

    private fun inputOf(u: JSONObject) = u.optLong("input_tokens") + u.optLong("cache_creation_input_tokens") + u.optLong("cache_read_input_tokens")

    private fun system(f: JSONObject) {
        when (f.optString("subtype")) {
            "init" -> {
                // Every turn starts with init. One we did not send (a background subagent finished and claude
                // continues on its own) still needs a run.
                if (!active && ready) beginTurn()
                reportMode(f.optString("permissionMode"))
            }
            "task_started" -> tools[f.optString("tool_use_id")]?.let { it.status = ItemStatus.RUNNING; emitTool(it) }
            "task_notification" -> tools[f.optString("tool_use_id")]?.let {
                it.status = if (f.optString("status") == "completed") ItemStatus.COMPLETED else ItemStatus.FAILED
                f.optString("summary").ifBlank { null }?.let { s -> it.output = s }
                emitTool(it)
            }
            "status" -> {
                if (f.has("permissionMode")) reportMode(f.optString("permissionMode"))
                if (f.optString("status") == "compacting" && compaction == null) {
                    compaction = "compaction:${compactions++}"
                    sink(AgentEvent.ItemUpsert(compaction!!, ItemStatus.RUNNING, ItemPayload.Compaction(null)))
                }
                f.optString("compact_result").ifBlank { null }?.let { r ->
                    compaction?.let { sink(AgentEvent.ItemUpsert(it, if (r == "success") ItemStatus.COMPLETED else ItemStatus.FAILED, ItemPayload.Compaction(null))) }
                    if (r != "success") compaction = null
                }
            }
            "compact_boundary" -> {
                val ref = compaction ?: "compaction:${compactions++}"
                compaction = null
                val pre = f.optJSONObject("compact_metadata")?.optLong("pre_tokens")?.takeIf { it > 0 }
                sink(AgentEvent.ItemUpsert(ref, ItemStatus.COMPLETED, ItemPayload.Compaction(pre)))
            }
            "api_retry" -> {
                val delay = f.optLong("retry_delay_ms")
                sink(AgentEvent.Notice("retry", "Retrying (${f.optInt("attempt")}/${f.optInt("max_retries")})" +
                    (if (delay > 0) " in ${(delay + 999) / 1000} s" else "") + (f.optString("error").ifBlank { null }?.let { " · $it" } ?: "")))
            }
        }
    }

    private fun reportMode(pm: String?) {
        val (rm, im) = ClaudeWire.modesOf(pm) ?: return
        if (!ready || reportedModes == (rm to im)) return
        reportedModes = rm to im
        sink(AgentEvent.SessionState(AgentEvent.Phase.READY, runtimeMode = rm, interactionMode = im))
    }

    private fun rateLimit(i: JSONObject?) {
        i ?: return
        val reset = i.optLong("resetsAt").takeIf { it > 0 }?.let { if (it < 100_000_000_000L) it * 1000 else it }
        if (i.optString("status") == "rejected") retryAt = reset
        sink(AgentEvent.RateLimit(i.optString("status").ifBlank { "allowed" }, reset))
    }

    private fun controlRequest(f: JSONObject) {
        val id = f.optString("request_id")
        val r = f.optJSONObject("request") ?: JSONObject()
        when (r.optString("subtype")) {
            "can_use_tool" -> canUseTool(id, r)
            "elicitation" -> reply(ClaudeWire.success(id, JSONObject().put("action", "decline")))
            else -> reply(ClaudeWire.error(id, "Unsupported control request: ${r.optString("subtype")}"))
        }
    }

    private fun canUseTool(id: String, r: JSONObject) {
        val tool = r.optString("tool_name")
        val toolUseId = r.optString("tool_use_id").ifBlank { null }
        val input = r.optJSONObject("input")?.takeIf { it.length() > 0 } ?: toolUseId?.let { inputs[it] } ?: JSONObject()
        if (tool == "ExitPlanMode") {
            // The request's input is empty: the plan came with the earlier tool_use (spike).
            if (toolUseId != null && toolUseId !in plans) plan(toolUseId, input.optString("plan"))
            reply(ClaudeWire.success(id, ClaudeWire.deny(ClaudeWire.PLAN_CAPTURED, toolUseId)))
            return
        }
        val ev = if (tool == "AskUserQuestion") {
            val qs = ClaudeWire.questions(input)
            AgentEvent.RequestUpsert(id, RequestKind.USER_INPUT, RequestStatus.PENDING, emptyList(),
                RequestDetail(qs.firstOrNull()?.header ?: "Question", questions = qs))
        } else {
            val kind = ClaudeWire.requestKind(tool)
            val suggestions = r.optJSONArray("permission_suggestions")
            val path = ClaudeWire.pathOf(input) ?: r.optString("blocked_path").ifBlank { null }
            val command = ClaudeWire.s(input, "command").takeIf { kind == RequestKind.COMMAND }
            val label = r.optString("display_name").ifBlank { ClaudeWire.toolLabel(tool) }
            val title = when (kind) {
                RequestKind.COMMAND -> if (command != null) "Run command" else "Use $label"
                RequestKind.FILE_CHANGE -> (if (ClaudeWire.norm(tool) == "write") "Write " else "Edit ") + (path?.substringAfterLast('/') ?: "file")
                RequestKind.FILE_READ -> "$label " + (path?.substringAfterLast('/') ?: ClaudeWire.s(input, "pattern").orEmpty())
                else -> "Use $label"
            }.trim()
            val reason = listOf(r.optString("decision_reason"), r.optString("description"), r.optString("title"))
                .map { it.replace(ANSI, "").trim() }.firstOrNull { it.isNotEmpty() && it != command }
            val detail = RequestDetail(title, command, listOfNotNull(path), ClaudeWire.inputDiff(tool, input), reason)
            val options = listOf(
                RequestOption(CANCEL, "cancel", "Cancel"),
                RequestOption(REJECT, "reject", "Decline"),
                RequestOption(SESSION, "allow_session", ClaudeWire.sessionLabel(tool, suggestions)),
                RequestOption(ONCE, "allow_once", "Approve"),
            )
            AgentEvent.RequestUpsert(id, kind, RequestStatus.PENDING, options, detail)
        }
        requests[id] = Req(ev, tool, toolUseId, input, r.optJSONArray("permission_suggestions"))
        sink(ev)
    }

    companion object {
        const val ONCE = "once"
        const val SESSION = "session"
        const val REJECT = "reject"
        const val CANCEL = "cancel"
        private val HIDDEN = setOf("TodoWrite", "TaskCreate", "TaskUpdate", "TaskList", "TaskGet", "ToolSearch")
        private val CREATED = Regex("""^Task #(\S+) created successfully: (.+)$""", RegexOption.DOT_MATCHES_ALL)
        private val ANSI =Regex("\u001B\\[[0-9;]*[A-Za-z]")
        private val LIMIT = Regex("rate.?limit|usage limit|limit reached", RegexOption.IGNORE_CASE)
    }
}
