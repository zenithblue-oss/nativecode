package com.zenithblue.nativecode.agent.chat.runtime

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.Question
import com.zenithblue.nativecode.agent.chat.core.QuestionOption
import com.zenithblue.nativecode.agent.chat.core.RequestDetail
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestOption
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.TodoStep
import com.zenithblue.nativecode.agent.chat.core.TurnInput
import com.zenithblue.nativecode.agent.chat.core.Usage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure opencode 2.x helpers (no I/O): argv, permission rules, model catalog, request bodies, tool items.
 * Behaviour follows t3code `provider-opencode/src/server/v2/adapter.ts` + `toolItems.ts`; field names from
 * `@opencode/schema` 2.3.0 and fixtures recorded from the 2.3.0 binary.
 */
internal object OpenCodeWire {
    /** `opencode serve --service` (the TUI's managed service) listens here; our own servers never take it. */
    const val MANAGED_SERVICE_PORT = 49374
    const val DECLINED = "The user declined this request."
    const val SIGN_IN = "sign in"
    private const val PREVIEW = 8_000
    private const val OUTPUT_TAIL = 64 * 1024
    private val FREE_PROVIDERS = setOf("opencode", "opencode-go")
    private val COMMAND = Regex("""^/(\S+)(?:\s+([\s\S]*))?$""")

    fun serveArgv(port: Int) = listOf("opencode", "serve", "--hostname=127.0.0.1", "--port=$port")

    fun rule(action: String, effect: String, resource: String = "*"): JSONObject =
        JSONObject().put("action", action).put("resource", resource).put("effect", effect)

    /** Session rules, last match wins (t3code `sessionRules`). `read` is never denied: Zen free models refuse tool-less sessions. */
    fun rules(mode: RuntimeMode, interaction: InteractionMode, grants: List<JSONObject> = emptyList()): JSONArray {
        val out = JSONArray()
        if (mode == RuntimeMode.FULL_ACCESS) out.put(rule("*", "allow")) else {
            out.put(rule("shell", "ask"))
            out.put(rule("edit", if (mode == RuntimeMode.AUTO_ACCEPT_EDITS) "allow" else "ask"))
            out.put(rule("external_directory", "ask"))
        }
        grants.forEach { out.put(it) }
        if (interaction == InteractionMode.PLAN) out.put(rule("edit", "deny"))
        return out
    }

    /** Zen free: `-free` id (t3code usage.ts) or every listed cost is 0 (catches `opencode/big-pickle`). */
    fun isFree(m: JSONObject): Boolean {
        if (m.optString("providerID") !in FREE_PROVIDERS) return false
        if (m.optString("id").endsWith("-free")) return true
        val cost = m.optJSONArray("cost") ?: return false
        return cost.length() > 0 && objects(cost).all { it.optDouble("input", 1.0) == 0.0 && it.optDouble("output", 1.0) == 0.0 }
    }

    fun parseModels(data: JSONArray?): List<ModelInfo> = objects(data).filter { it.optBoolean("enabled", true) }.map {
        ModelInfo(
            id = it.optString("providerID") + "/" + it.optString("id"),
            name = it.optString("name").ifBlank { it.optString("id") },
            providerId = it.optString("providerID"),
            free = isFree(it),
            variants = objects(it.optJSONArray("variants")).map { v -> v.optString("id") },
            contextLimit = it.optJSONObject("limit")?.optLong("context")?.takeIf { c -> c > 0 },
        )
    }

    fun slugOf(m: JSONObject?): String? = m?.let { "${it.optString("providerID")}/${it.optString("id")}" }?.takeIf { it.length > 1 }

    /** t3code default effort for opencode providers: `medium`, when the model lists it. */
    fun defaultEffort(model: ModelInfo?): String? = "medium".takeIf { model?.variants?.contains(it) == true }

    /** `Model.Ref`; an effort the model does not list is dropped (opencode rejects unknown variants). */
    fun modelRef(slug: String?, effort: String?, models: List<ModelInfo>): JSONObject? {
        val s = slug ?: return null
        val cut = s.indexOf('/')
        if (cut <= 0 || cut == s.length - 1) return null
        val ref = JSONObject().put("providerID", s.substring(0, cut)).put("id", s.substring(cut + 1))
        if (effort != null && models.firstOrNull { it.id == s }?.variants?.contains(effort) == true) ref.put("variant", effort)
        return ref
    }

    /** opencode message ids start with `msg_` and are unique across all sessions (409 otherwise): scope by session, as t3code. */
    fun promptId(sessionId: String, clientId: String) = "msg_nc_${sessionId}_$clientId".replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun commandOf(text: String): Pair<String, String>? = COMMAND.find(text.trim())?.let { it.groupValues[1] to it.groupValues[2] }

    /** t3code `providerMessageTextWithAttachmentPaths`: one path line per attachment (images also go in `files`). */
    fun promptText(turn: TurnInput): String {
        var text = turn.text
        for (a in turn.attachments) {
            val kind = if (a.isImage) "image" else "file"
            val line = if (a.guestPath == null) "[Attached $kind \"${a.name}\" is unavailable: could not resolve a saved path.]"
            else "[Attached $kind \"${a.name}\" is saved at: ${a.guestPath}]"
            text = if (text.isEmpty()) line else "$text\n\n$line"
        }
        return text.trim()
    }

    fun fileUri(path: String): String = java.net.URI("file", "", path, null).toASCIIString()

    fun promptBody(id: String, turn: TurnInput, steer: Boolean): JSONObject {
        val body = JSONObject().put("id", id).put("text", promptText(turn))
        val files = JSONArray()
        turn.attachments.filter { it.isImage && it.guestPath != null }.forEach {
            files.put(JSONObject().put("uri", fileUri(it.guestPath!!)).put("name", it.name))
        }
        if (files.length() > 0) body.put("files", files)
        if (steer) body.put("delivery", "steer")
        return body
    }

    fun declinedNote(action: String, resources: List<String>) =
        "The user declined the $action request${if (resources.isEmpty()) "" else " (${resources.joinToString(", ")})"}. " +
            "Do not retry it; continue without it or ask the user how to proceed."

    fun sessionGrantLabel(action: String, save: List<String>) =
        if (save.all { it == "*" }) "Allow every $action request this session" else "Allow ${save.joinToString(", ")} this session"

    /** Form reply: multiselect → array, other fields → comma-joined text; option labels map back to values. */
    fun formAnswer(fields: JSONArray, answers: Map<String, List<String>>): JSONObject {
        val out = JSONObject()
        for (f in objects(fields)) {
            val key = f.optString("key")
            val byLabel = objects(f.optJSONArray("options")).associate { it.optString("label") to it.optString("value") }
            val values = answers[key].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.map { byLabel[it] ?: it }
            if (values.isEmpty()) continue
            out.put(key, if (f.optString("type") == "multiselect") JSONArray(values) else values.joinToString(", "))
        }
        return out
    }

    /** t3code `formQuestions`: only string and multiselect fields without conditions can be shown. */
    fun formQuestions(form: JSONObject): List<Question>? = objects(form.optJSONArray("fields")).mapIndexed { i, f ->
        val type = f.optString("type")
        if ((type != "string" && type != "multiselect") || f.optBoolean("hidden") || (f.optJSONArray("when")?.length() ?: 0) > 0) return null
        val header = f.optString("title").ifBlank { "Question ${i + 1}" }
        val options = objects(f.optJSONArray("options")).map {
            QuestionOption(it.optString("label").ifBlank { it.optString("value") }, it.optString("description").ifBlank { null })
        }
        Question(f.optString("key"), header, f.optString("description").ifBlank { header }, options, type == "multiselect", f.optBoolean("custom") || options.isEmpty())
    }

    /** Tool → item kind (t3code `openCodeToolProjectionKind`, plus read/list as file_search per plan 9.2.4). */
    fun toolPayload(name: String, input: JSONObject, output: String?, meta: JSONObject?): ItemPayload {
        val n = name.lowercase()
        fun s(vararg keys: String) = keys.firstNotNullOfOrNull { k -> input.optString(k).takeIf { it.isNotBlank() } }
        val results = listOfNotNull(output?.takeIf { it.isNotBlank() }?.take(PREVIEW))
        val todos = input.optJSONArray("todos")
        return when {
            n == "todowrite" && todos != null -> ItemPayload.TodoList(objects(todos).map { TodoStep(it.optString("content"), it.optString("status")) })
            "bash" in n || "shell" in n -> ItemPayload.CommandExecution(s("command", "cmd") ?: input.toString(), output.orEmpty().takeLast(OUTPUT_TAIL), exitCode(meta))
            "edit" in n || "write" in n || "patch" in n -> {
                val diff = meta?.let { m -> m.optString("diff").ifBlank { m.optString("patch") }.ifBlank { filesPatch(m) } }?.ifBlank { null }
                // write's metadata has no diff: count content lines, a trailing newline is not a line ("a\n" = +1)
                val (add, del) = if (diff != null) diffStat(diff) else (s("content", "newString")?.removeSuffix("\n")?.lines()?.size ?: 0) to 0 // s() skips blank content
                ItemPayload.FileChange(s("filePath", "path", "file") ?: name, add, del, diff)
            }
            "web" in n || n == "codesearch" || n == "code_search" -> ItemPayload.WebSearch(s("query", "url", "pattern").orEmpty(), results)
            n == "read" || n == "list" || "glob" in n || "grep" in n || "search" in n || "lsp" in n ->
                ItemPayload.FileSearch(name, s("pattern", "query", "path", "filePath").orEmpty(), results)
            else -> ItemPayload.DynamicTool(name, input.toString(), output.orEmpty())
        }
    }

    fun exitCode(meta: JSONObject?): Int? = (meta?.opt("exit") ?: meta?.opt("exitCode")).let { (it as? Number)?.toInt() }

    /** Unified patches from a permission request / tool metadata `files[].patch`. */
    fun filesPatch(meta: JSONObject?): String = objects(meta?.optJSONArray("files")).mapNotNull { it.optString("patch").ifBlank { null } }.joinToString("\n")

    fun diffStat(diff: String): Pair<Int, Int> {
        var add = 0
        var del = 0
        for (l in diff.lineSequence()) when {
            l.startsWith("+++") || l.startsWith("---") -> Unit
            l.startsWith("+") -> add++
            l.startsWith("-") -> del++
        }
        return add to del
    }

    fun textOf(content: JSONArray?): String = objects(content).filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }

    fun subagentOutput(text: String) = Regex("""^<subagent\b[^>]*>\n?([\s\S]*?)\n?</subagent>$""").find(text.trim())?.groupValues?.get(1) ?: text

    fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

/**
 * Stateful opencode SSE / message-history → [AgentEvent] mapper for one root session and its subagent children.
 * Every upsert is a full snapshot (text accumulates). Thread-safe: SSE thread and runtime I/O thread both call in.
 * [cancelForm] is the one side effect: forms the UI cannot show are cancelled (t3code `onFormCreated`).
 */
internal class OpenCodeMapper(
    private val emit: (AgentEvent) -> Unit,
    private val cancelForm: (sessionId: String, formId: String) -> Unit = { _, _ -> },
) {
    /** A pending approval / form, kept to re-emit full snapshots and to build replies. */
    class Req(val event: AgentEvent.RequestUpsert, val sessionId: String, val action: String, val resources: List<String>, val save: List<String>, val fields: JSONArray?)

    private class Tool(val name: String, var input: JSONObject, val session: String)
    private class Call(val session: String, var title: String, var child: String? = null, var status: ItemStatus = ItemStatus.RUNNING, var result: String? = null)

    @Volatile var root: String? = null
    @Volatile var contextSize: Long? = null
    val active: Boolean @Synchronized get() = running
    private var running = false
    private var interrupting = false
    private var pendingTurnId: String? = null
    private var usage = Usage()
    private var compactions = 0
    private val children = HashMap<String, Boolean>()      // child session → running
    private val childCall = HashMap<String, String>()      // child session → subagent call ref
    private val childText = HashMap<String, String>()      // child session → last full assistant text
    private val text = HashMap<String, String>()
    private val tools = HashMap<String, Tool>()
    private val calls = HashMap<String, Call>()
    private val requests = LinkedHashMap<String, Req>()
    private val steers = HashSet<String>()

    @Synchronized fun expectTurn(promptId: String) { pendingTurnId = promptId }
    @Synchronized fun expectSteer(promptId: String) { steers += promptId }
    @Synchronized fun markInterrupting() { if (running) interrupting = true }
    @Synchronized fun request(id: String): Req? = requests[id]
    @Synchronized fun runningChildren(): List<String> = children.filterValues { it }.keys.toList()

    @Synchronized fun settleRequest(id: String, status: RequestStatus) {
        val r = requests.remove(id) ?: return
        emit(r.event.copy(status = status))
    }

    /** One SSE `data` payload. Undecodable frames are skipped, except execution start/end recovered from the envelope. */
    @Synchronized fun handle(raw: String) {
        val ev = try { JSONObject(raw) } catch (_: Exception) { null }
        if (ev == null) return recoverEnvelope(raw)
        try {
            dispatch(ev, raw)
        } catch (_: Exception) {
            recoverEnvelope(raw)
        }
    }

    private fun recoverEnvelope(raw: String) {
        val type = TYPE.find(raw)?.groupValues?.get(1)
        val sid = SESSION_ID.find(raw)?.groupValues?.get(1)
        if (type == null || sid == null || !type.startsWith("session.execution.") || !owns(sid)) return emit(AgentEvent.Unknown(raw))
        execution(type, sid, null, 0L)
    }

    private fun owns(sid: String) = sid == root || sid in children

    private fun parentOf(sid: String): String? = if (sid == root) null else childCall[sid] ?: sid

    private fun dispatch(ev: JSONObject, raw: String) {
        val type = ev.getString("type")
        val d = ev.optJSONObject("data") ?: JSONObject()
        if (type == "session.created") {
            val parent = d.optString("parentID")
            if (parent.isNotEmpty() && owns(parent)) children[d.getString("sessionID")] = false
            return
        }
        val sid = (if (type == "form.created") d.optJSONObject("form")?.optString("sessionID") else d.optString("sessionID"))?.ifEmpty { null } ?: return
        if (!owns(sid)) return
        when (type) {
            "session.execution.started", "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted" ->
                execution(type, sid, d.optJSONObject("error"), ev.optLong("created"))
            "session.text.delta", "session.reasoning.delta" -> {
                val ref = textRef(type, d)
                text[ref] = (text[ref] ?: "") + d.getString("delta")
                emitText(type, ref, sid, ItemStatus.RUNNING)
            }
            "session.text.ended", "session.reasoning.ended" -> {
                val ref = textRef(type, d)
                text[ref] = d.getString("text")
                emitText(type, ref, sid, ItemStatus.COMPLETED)
                if (type == "session.text.ended" && sid != root) childText[sid] = d.getString("text")
                text.remove(ref)
            }
            "session.tool.input.started" -> {
                val id = d.getString("id")
                when (val name = d.getString("name")) {
                    "question" -> Unit // the form carries it
                    "subagent", "task" -> { calls[id] = Call(sid, "Subagent"); emitCall(id) }
                    else -> { tools[id] = Tool(name, JSONObject(), sid); emitTool(id, ItemStatus.RUNNING, null, null) }
                }
            }
            "session.tool.called" -> {
                val id = d.getString("id")
                val input = d.optJSONObject("input") ?: JSONObject()
                calls[id]?.let { c ->
                    c.title = input.optString("description").ifBlank { input.optString("agent").ifBlank { c.title } }
                    return emitCall(id)
                }
                val t = tools[id] ?: return
                t.input = input
                emitTool(id, ItemStatus.RUNNING, null, null)
            }
            "session.tool.progress" -> link(d)
            "session.tool.success" -> {
                val id = d.getString("id")
                val meta = d.optJSONObject("metadata")
                calls[id]?.let { c ->
                    link(d)
                    if (meta?.optString("status") == "running") return emitCall(id) // background: the child's end settles it
                    c.status = ItemStatus.COMPLETED
                    c.result = OpenCodeWire.subagentOutput(OpenCodeWire.textOf(d.optJSONArray("content")))
                    return emitCall(id)
                }
                val t = tools[id] ?: return
                val failed = ("bash" in t.name.lowercase() || "shell" in t.name.lowercase()) && (OpenCodeWire.exitCode(meta) ?: 0) != 0
                emitTool(id, if (failed) ItemStatus.FAILED else ItemStatus.COMPLETED, OpenCodeWire.textOf(d.optJSONArray("content")), meta)
                tools.remove(id)
            }
            "session.tool.failed" -> {
                val id = d.getString("id")
                val meta = d.optJSONObject("metadata")
                val err = d.optJSONObject("error")
                val status = if (err?.optString("type") == "aborted" || meta?.optBoolean("interrupted") == true) ItemStatus.INTERRUPTED else ItemStatus.FAILED
                calls[id]?.let { c -> c.status = status; c.result = err?.optString("message"); return emitCall(id) }
                if (!tools.containsKey(id)) return
                emitTool(id, status, err?.optString("message"), meta)
                tools.remove(id)
            }
            "permission.asked" -> permission(d)
            "permission.replied" -> settleRequest(d.getString("requestID"), RequestStatus.RESOLVED)
            "form.created" -> form(d.getJSONObject("form"), sid)
            "form.replied" -> settleRequest(d.getString("id"), RequestStatus.RESOLVED)
            "form.cancelled" -> settleRequest(d.getString("id"), RequestStatus.CANCELLED)
            "session.step.ended", "session.step.failed" -> if (sid == root) step(d)
            "session.compaction.started" -> emit(AgentEvent.ItemUpsert("compaction:$sid:${compactions++}", ItemStatus.RUNNING, ItemPayload.Compaction(null), parentOf(sid)))
            "session.compaction.ended", "session.compaction.failed" -> {
                val tokens = d.optJSONObject("tokens")?.let { tokenTotal(it) }
                val status = if (type.endsWith("ended")) ItemStatus.COMPLETED else ItemStatus.FAILED
                emit(AgentEvent.ItemUpsert("compaction:$sid:${(compactions - 1).coerceAtLeast(0)}", status, ItemPayload.Compaction(tokens), parentOf(sid)))
            }
            "session.retry.scheduled" -> emit(AgentEvent.Notice("retry", "Retrying (attempt ${d.optInt("attempt")}): ${d.optJSONObject("error")?.optString("message").orEmpty()}"))
            "session.inbox.delivered" -> if (steers.remove(d.optString("inboxID"))) emit(AgentEvent.Notice("steer", "Steer delivered"))
            in IGNORED -> Unit
            else -> emit(AgentEvent.Unknown(raw))
        }
    }

    private fun textRef(type: String, d: JSONObject): String {
        val kind = if (type.startsWith("session.text.")) "text" else "reasoning"
        return "${d.getString("assistantMessageID")}:$kind:${d.optInt("ordinal")}"
    }

    private fun emitText(type: String, ref: String, sid: String, status: ItemStatus) {
        val t = text[ref].orEmpty()
        val payload = if (type.startsWith("session.text.")) ItemPayload.AssistantMessage(t) else ItemPayload.Reasoning(t)
        emit(AgentEvent.ItemUpsert(ref, status, payload, parentOf(sid)))
    }

    private fun emitTool(id: String, status: ItemStatus, output: String?, meta: JSONObject?) {
        val t = tools[id] ?: return
        emit(AgentEvent.ItemUpsert(id, status, OpenCodeWire.toolPayload(t.name, t.input, output, meta), parentOf(t.session)))
    }

    private fun emitCall(id: String) {
        val c = calls[id] ?: return
        emit(AgentEvent.ItemUpsert(id, c.status, ItemPayload.Subagent(c.title, if (c.child == null) 0 else 1, c.result), parentOf(c.session)))
    }

    /** `metadata.sessionID` on a subagent call names its child session. */
    private fun link(d: JSONObject) {
        val id = d.optString("id")
        val c = calls[id] ?: return
        val child = d.optJSONObject("metadata")?.optString("sessionID")?.ifEmpty { null } ?: return
        if (c.child == child) return
        c.child = child
        childCall[child] = id
        if (child !in children) children[child] = false
        emitCall(id)
    }

    private fun execution(type: String, sid: String, error: JSONObject?, created: Long) {
        if (sid != root) {
            children[sid] = type == "session.execution.started"
            if (type == "session.execution.started") return
            val id = childCall[sid] ?: return
            val c = calls[id] ?: return
            if (c.status != ItemStatus.RUNNING) return
            c.status = when (type) {
                "session.execution.succeeded" -> ItemStatus.COMPLETED
                "session.execution.interrupted" -> ItemStatus.INTERRUPTED
                else -> ItemStatus.FAILED
            }
            c.result = childText[sid] ?: error?.optString("message")
            return emitCall(id)
        }
        if (type == "session.execution.started") {
            if (running) return
            running = true
            interrupting = false
            usage = Usage()
            emit(AgentEvent.TurnStarted(pendingTurnId))
            pendingTurnId = null
            return
        }
        if (!running) return
        when (type) {
            "session.execution.succeeded" -> terminal(if (interrupting) RunStatus.INTERRUPTED else RunStatus.COMPLETED, null)
            "session.execution.interrupted" -> terminal(RunStatus.INTERRUPTED, null)
            else -> terminal(RunStatus.FAILED, failure(error, created))
        }
    }

    /** t3code: provider.quota (not HTTP 402, a spent balance) and provider.rate-limit are usage limits. */
    private fun failure(error: JSONObject?, created: Long): Failure {
        val type = error?.optString("type").orEmpty()
        val status = error?.optInt("status") ?: 0
        val limit = (type == "provider.quota" && status != 402) || type == "provider.rate-limit"
        val retryAt = error?.optLong("retryAfterMs", -1L)?.takeIf { it >= 0 && created > 0 }?.let { created + it }
        val message = error?.optString("message")?.ifBlank { null } ?: "opencode reported a failure"
        return Failure(if (limit) FailureClass.USAGE_LIMIT else FailureClass.PROVIDER_ERROR, message, retryAt, error?.optJSONObject("response")?.optString("body"))
    }

    private fun terminal(status: RunStatus, failure: Failure?) {
        running = false
        interrupting = false
        val left = if (status == RunStatus.COMPLETED) ItemStatus.COMPLETED else ItemStatus.INTERRUPTED
        tools.keys.filter { tools[it]?.session == root }.forEach { emitTool(it, left, null, null); tools.remove(it) }
        requests.keys.filter { requests[it]?.sessionId == root }.forEach { settleRequest(it, RequestStatus.EXPIRED) }
        emit(AgentEvent.TurnTerminal(status, failure))
    }

    private fun step(d: JSONObject) {
        val t = d.optJSONObject("tokens") ?: return
        val cache = t.optJSONObject("cache")
        usage = usage.copy(
            inputTokens = usage.inputTokens + t.optLong("input"),
            outputTokens = usage.outputTokens + t.optLong("output"),
            reasoningTokens = usage.reasoningTokens + t.optLong("reasoning"),
            cacheReadTokens = usage.cacheReadTokens + (cache?.optLong("read") ?: 0),
            cacheWriteTokens = usage.cacheWriteTokens + (cache?.optLong("write") ?: 0),
            costUsd = (usage.costUsd ?: 0.0) + d.optDouble("cost", 0.0),
            contextUsed = tokenTotal(t),
            contextSize = contextSize,
        )
        emit(AgentEvent.UsageUpdate(usage))
    }

    private fun tokenTotal(t: JSONObject): Long {
        val cache = t.optJSONObject("cache")
        return t.optLong("input") + t.optLong("output") + t.optLong("reasoning") + (cache?.optLong("read") ?: 0) + (cache?.optLong("write") ?: 0)
    }

    private fun permission(d: JSONObject) {
        val id = d.getString("id")
        val action = d.getString("action")
        val resources = OpenCodeWire.strings(d.optJSONArray("resources"))
        val save = OpenCodeWire.strings(d.optJSONArray("save"))
        val kind = when (action) {
            "shell" -> RequestKind.COMMAND
            "edit" -> RequestKind.FILE_CHANGE
            "read" -> RequestKind.FILE_READ
            else -> RequestKind.PERMISSION
        }
        val detail = RequestDetail(
            title = when (kind) {
                RequestKind.COMMAND -> "Run command"
                RequestKind.FILE_CHANGE -> "Edit files"
                RequestKind.FILE_READ -> "Read files"
                else -> "Allow $action"
            },
            command = resources.joinToString("\n").takeIf { kind == RequestKind.COMMAND },
            paths = if (kind == RequestKind.COMMAND) emptyList() else resources,
            diff = OpenCodeWire.filesPatch(d.optJSONObject("metadata")).ifBlank { null },
            reason = d.optString("message").ifBlank { null },
        )
        // t3code option order; "cancel" = reject without a message, which stops the whole execution.
        val options = listOfNotNull(
            RequestOption(CANCEL, "cancel", "Cancel"),
            RequestOption(REJECT, "reject", "Decline"),
            if (save.isEmpty()) null else RequestOption(SESSION, "allow_session", OpenCodeWire.sessionGrantLabel(action, save)),
            RequestOption(ONCE, "allow_once", "Approve"),
        )
        val ev = AgentEvent.RequestUpsert(id, kind, RequestStatus.PENDING, options, detail)
        requests[id] = Req(ev, d.getString("sessionID"), action, resources, save, null)
        emit(ev)
    }

    private fun form(form: JSONObject, sid: String) {
        val id = form.getString("id")
        val questions = OpenCodeWire.formQuestions(form)
        if (questions == null) {
            emit(AgentEvent.Notice("form_unsupported", "opencode asked a question the chat cannot show; it was declined."))
            return cancelForm(sid, id)
        }
        val ev = AgentEvent.RequestUpsert(id, RequestKind.USER_INPUT, RequestStatus.PENDING, emptyList(), RequestDetail(form.optString("title").ifBlank { "Question" }, questions = questions))
        requests[id] = Req(ev, sid, "form", emptyList(), emptyList(), form.optJSONArray("fields"))
        emit(ev)
    }

    /**
     * After an SSE reconnect: replay `GET /api/session/:id/message` (newest first) newer than the latest user
     * message, so items finish with their final text, and end the turn if an `idle` record shows it ended.
     */
    @Synchronized fun replay(messages: JSONArray?) {
        val sid = root ?: return
        val all = OpenCodeWire.objects(messages)
        val lastUser = all.indexOfFirst { it.optString("type") == "user" }
        for (m in (if (lastUser < 0) all else all.subList(0, lastUser)).asReversed()) when (m.optString("type")) {
            "assistant" -> {
                val ordinals = HashMap<String, Int>()
                for (p in OpenCodeWire.objects(m.optJSONArray("content"))) when (val kind = p.optString("type")) {
                    "text", "reasoning" -> {
                        val n = ordinals.merge(kind, 1, Int::plus)!! - 1
                        val ref = "${m.optString("id")}:$kind:$n"
                        val payload = if (kind == "text") ItemPayload.AssistantMessage(p.optString("text")) else ItemPayload.Reasoning(p.optString("text"))
                        text.remove(ref)
                        emit(AgentEvent.ItemUpsert(ref, ItemStatus.COMPLETED, payload, null))
                    }
                    "tool" -> replayTool(p, sid)
                }
            }
            "idle" -> if (running) when (m.optString("outcome")) {
                "failed" -> terminal(RunStatus.FAILED, Failure(FailureClass.PROVIDER_ERROR, "opencode reported a failure"))
                "interrupted" -> terminal(RunStatus.INTERRUPTED, null)
                else -> terminal(if (interrupting) RunStatus.INTERRUPTED else RunStatus.COMPLETED, null)
            }
        }
    }

    private fun replayTool(p: JSONObject, sid: String) {
        val id = p.optString("id")
        val name = p.optString("name")
        val state = p.optJSONObject("state") ?: return
        val input = state.optJSONObject("input") ?: JSONObject()
        val meta = state.optJSONObject("metadata")
        val output = OpenCodeWire.textOf(state.optJSONArray("content"))
        val status = when (state.optString("status")) {
            "completed" -> if (meta?.optString("status") == "running") ItemStatus.RUNNING else ItemStatus.COMPLETED
            "error" -> if (state.optJSONObject("error")?.optString("type") == "aborted") ItemStatus.INTERRUPTED else ItemStatus.FAILED
            else -> ItemStatus.RUNNING
        }
        when (name) {
            "question" -> Unit
            "subagent", "task" -> {
                val c = calls.getOrPut(id) { Call(sid, input.optString("description").ifBlank { "Subagent" }) }
                if (status != ItemStatus.RUNNING) { c.status = status; c.result = OpenCodeWire.subagentOutput(output) }
                emitCall(id)
            }
            else -> {
                val err = state.optJSONObject("error")?.optString("message")
                emit(AgentEvent.ItemUpsert(id, status, OpenCodeWire.toolPayload(name, input, if (status == ItemStatus.COMPLETED) output else err ?: output, meta), null))
                if (status != ItemStatus.RUNNING) tools.remove(id)
            }
        }
    }

    companion object {
        const val ONCE = "once"
        const val SESSION = "session"
        const val REJECT = "reject"
        const val CANCEL = "cancel"
        private val TYPE = Regex(""""type"\s*:\s*"([^"]+)"""")
        private val SESSION_ID = Regex(""""sessionID"\s*:\s*"([^"]+)"""")

        /** Known 2.3.0 session events with nothing to show; anything else unhandled goes to the log as Unknown. */
        private val IGNORED = setOf(
            "session.step.started", "session.step.first-token", "session.step.streamed", "session.text.started",
            "session.reasoning.started", "session.tool.input.delta", "session.tool.input.ended", "session.usage.updated",
            "session.usage.recorded", "session.instructions.updated", "session.inbox.enqueued", "session.inbox.cancelled",
            "session.inbox.delivery.changed", "session.model.selected", "session.agent.selected", "session.permissions",
            "session.renamed", "session.metadata.updated", "session.viewed", "session.synthetic", "session.skill.activated",
            "session.shell.started", "session.shell.ended", "session.moved", "session.compaction.delta",
            "session.revert.staged", "session.revert.cleared", "session.revert.committed", "session.message.content.updated",
            "session.archived", "session.unarchived", "session.forked", "session.deleted",
        )
    }
}
