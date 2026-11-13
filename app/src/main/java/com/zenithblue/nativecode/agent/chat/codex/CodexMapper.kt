package com.zenithblue.nativecode.agent.chat.codex

import com.zenithblue.nativecode.agent.chat.acp.AcpWire
import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.Question
import com.zenithblue.nativecode.agent.chat.core.QuestionOption
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
 * codex app-server notifications + server requests → [AgentEvent] (t3code CodexAdapterV2 handlers). Pure and
 * single-threaded (the runtime calls it under its lock), so recorded fixtures replay through it in tests.
 *
 * Item refs are `<turnId>:<itemId>` (codex item ids are model call ids, unique only together with the turn); a
 * file change with several files becomes one row per file (`…#i`). Request ids carry a per-process [nonce], since
 * the server's request counter restarts with each process.
 */
class CodexMapper(private val nonce: String, private val sink: (AgentEvent) -> Unit) {
    /** A card waiting for the user. [answers] maps our option id → the decision JSON sent back. */
    class Pending(
        val rpcId: Any,
        val method: String,
        val event: AgentEvent.RequestUpsert,
        val answers: Map<String, Any> = emptyMap(),
        val params: JSONObject = JSONObject(),
    )

    var active = false; private set
    var interrupting = false
    var turnId: String? = null; private set
    var usage = Usage(); private set
    /** Latest quota snapshot's reset (ms), for a usage-limit failure's retry time. */
    var limitResetAt: Long? = null

    /** 401 seen on a turn: the runtime shows the sign-in state. */
    var onAuthError: (() -> Unit)? = null

    private val texts = HashMap<String, StringBuilder>()
    private val reasoning = HashMap<String, java.util.TreeMap<Int, StringBuilder>>()
    private val reasoningContent = HashMap<String, java.util.TreeMap<Int, StringBuilder>>()
    private val startedAt = HashMap<String, Long>()
    private val running = LinkedHashMap<String, AgentEvent.ItemUpsert>()
    private val fileItems = HashMap<String, JSONObject>()
    private val pending = LinkedHashMap<String, Pending>()
    /** t3code sessionAllowance: "Approve for session" covers every later command / patch of the thread. */
    val allowance = HashSet<String>()
    private var failure: Failure? = null
    private var retryRef: String? = null
    private var baseline: JSONObject? = null
    private var lastTotal: JSONObject? = null

    fun pending(id: String): Pending? = pending[id]
    fun pendingIds(): List<String> = pending.keys.toList()

    fun beginTurn() {
        active = true
        interrupting = false
        turnId = null
        failure = null
        retryRef = null
        baseline = lastTotal
        usage = Usage(contextUsed = usage.contextUsed, contextSize = usage.contextSize)
        sink(AgentEvent.TurnStarted(null))
    }

    /** turn/start reply or `turn/started`: whichever comes first names the run. */
    fun onTurnId(id: String) {
        if (!active || turnId == id) return
        turnId = id
        sink(AgentEvent.TurnStarted(id))
    }

    /** Ends the turn once. False = already ended. */
    fun endTurn(status: RunStatus, failure: Failure? = null, reusable: Boolean = true): Boolean {
        if (!active) return false
        active = false
        val left = if (status == RunStatus.COMPLETED) ItemStatus.COMPLETED else ItemStatus.INTERRUPTED
        running.values.toList().forEach { sink(it.copy(status = left)) }
        running.clear()
        retryRef?.let { r -> sink(AgentEvent.ItemUpsert(r, ItemStatus.COMPLETED, ItemPayload.SystemNotice(retryText ?: "Reconnected"))) }
        pending.keys.toList().forEach { settle(it, RequestStatus.EXPIRED) }
        texts.clear(); reasoning.clear(); reasoningContent.clear(); startedAt.clear(); fileItems.clear()
        if (usage.inputTokens + usage.outputTokens > 0) sink(AgentEvent.UsageUpdate(usage))
        val st = if (interrupting && status == RunStatus.COMPLETED) RunStatus.INTERRUPTED else status
        sink(AgentEvent.TurnTerminal(st, if (st == RunStatus.FAILED) failure else null, reusable))
        interrupting = false
        turnId = null
        return true
    }

    private var retryText: String? = null

    /** One server notification. False = not consumed (protocol log only). */
    fun notification(method: String, p: JSONObject): Boolean {
        when (method) {
            "turn/started" -> p.optJSONObject("turn")?.optString("id")?.ifEmpty { null }?.let { id ->
                if (!active) beginTurn() // codex started it itself (goal continuation): adopt it as a run
                onTurnId(id)
            }
            "turn/completed" -> turnCompleted(p)
            "item/started", "item/completed" -> item(p, method == "item/completed")
            "item/agentMessage/delta" -> delta(p) { ref -> ItemPayload.AssistantMessage(texts[ref].toString()) }
            "item/plan/delta" -> delta(p) { ref -> ItemPayload.ProposedPlan(texts[ref].toString()) }
            "item/reasoning/summaryTextDelta" -> reasoningDelta(p, p.optInt("summaryIndex"), reasoning)
            "item/reasoning/textDelta" -> reasoningDelta(p, p.optInt("contentIndex"), reasoningContent)
            "item/reasoning/summaryPartAdded" -> Unit
            "item/commandExecution/outputDelta" -> if (active) sink(AgentEvent.CommandOutputDelta(ref(p), p.optString("delta")))
            "turn/plan/updated" -> todo(p)
            "thread/tokenUsage/updated" -> p.optJSONObject("tokenUsage")?.let(::tokens)
            "account/rateLimits/updated" -> rateLimits(p.optJSONObject("rateLimits"))
            "error" -> error(p)
            "model/rerouted" -> if (active) sink(AgentEvent.Notice("model_rerouted", "Model rerouted to ${p.optString("toModel")}" +
                (p.optString("reason").ifEmpty { null }?.let { " ($it)" } ?: "")))
            "serverRequest/resolved" -> p.opt("requestId")?.let { rid -> pending.entries.firstOrNull { it.value.rpcId.toString() == rid.toString() }?.key?.let { settle(it, RequestStatus.EXPIRED) } }
            "mcpServer/startupStatus/updated" -> if (p.optString("status") == "failed")
                sink(AgentEvent.Notice("mcp", p.optString("error").trim().ifEmpty { "MCP server ${p.optString("name")} failed to start." }))
            "thread/started", "thread/status/changed", "thread/readState/changed", "thread/goal/cleared", "thread/goal/updated",
            "thread/settings/updated", "thread/name/updated", "thread/compacted", "warning", "deprecationNotice", "configWarning",
            "remoteControl/status/changed", "account/updated", "skills/changed", "hook/started", "hook/completed",
            "turn/diff/updated", "item/reasoning/summaryPartAdded", "thread/closed", "thread/archived", "thread/unarchived" -> Unit
            else -> return false
        }
        return true
    }

    fun rateLimits(snapshot: JSONObject?) {
        val limitId = snapshot?.optString("limitId").orEmpty()
        if (limitId.isNotEmpty() && limitId != "codex") return
        val r = CodexWire.rateLimit(snapshot) ?: return
        r.resetAt?.let { limitResetAt = it }
        if (r.resetAt == null) snapshot?.let { s -> listOfNotNull(s.optJSONObject("primary"), s.optJSONObject("secondary")) }
            ?.mapNotNull { w -> w.optLong("resetsAt").takeIf { it > 0 }?.times(1000) }?.maxOrNull()?.let { limitResetAt = it }
        sink(r)
    }

    private fun ref(p: JSONObject, itemId: String = p.optString("itemId")) = "${p.optString("turnId")}:$itemId"

    private fun emit(e: AgentEvent.ItemUpsert) {
        if (e.status == ItemStatus.RUNNING || e.status == ItemStatus.PENDING) running[e.nativeRef] = e else running.remove(e.nativeRef)
        sink(e)
    }

    private fun delta(p: JSONObject, payload: (String) -> ItemPayload) {
        if (!active) return
        val ref = ref(p)
        texts.getOrPut(ref) { StringBuilder() }.append(p.optString("delta"))
        emit(AgentEvent.ItemUpsert(ref, ItemStatus.RUNNING, payload(ref)))
    }

    private fun reasoningDelta(p: JSONObject, index: Int, parts: HashMap<String, java.util.TreeMap<Int, StringBuilder>>) {
        if (!active) return
        val ref = ref(p)
        startedAt.putIfAbsent(ref, System.currentTimeMillis())
        parts.getOrPut(ref) { java.util.TreeMap() }.getOrPut(index) { StringBuilder() }.append(p.optString("delta"))
        emit(AgentEvent.ItemUpsert(ref, ItemStatus.RUNNING, ItemPayload.Reasoning(reasoningText(ref))))
    }

    /** Summary when the model gives one (codex default), else the raw reasoning text. */
    private fun reasoningText(ref: String): String =
        (reasoning[ref]?.values?.joinToString("\n\n") { it.toString().trim() }?.trim()?.ifEmpty { null }
            ?: reasoningContent[ref]?.values?.joinToString("\n\n") { it.toString().trim() }?.trim()).orEmpty()

    private fun statusOf(s: String?, completed: Boolean): ItemStatus = when (s) {
        "inProgress" -> if (completed) ItemStatus.COMPLETED else ItemStatus.RUNNING
        "completed" -> ItemStatus.COMPLETED
        "failed" -> if (interrupting) ItemStatus.INTERRUPTED else ItemStatus.FAILED
        "declined" -> ItemStatus.DECLINED
        else -> if (completed) ItemStatus.COMPLETED else ItemStatus.RUNNING
    }

    private fun item(p: JSONObject, completed: Boolean) {
        if (!active) return
        val it = p.optJSONObject("item") ?: return
        val id = it.optString("id")
        val ref = ref(p, id)
        val type = it.optString("type")
        val status = statusOf(it.optString("status").ifEmpty { null }, completed)
        when (type) {
            "agentMessage" -> {
                if (completed && it.optString("delivery") == "async" && (it.optJSONArray("questions")?.length() ?: 0) > 0) {
                    asyncQuestions(ref, it)
                    return
                }
                val text = if (completed) it.optString("text").ifEmpty { texts[ref]?.toString().orEmpty() } else texts[ref]?.toString().orEmpty()
                if (!completed && text.isEmpty()) return // the first delta creates the row
                if (completed && text.isBlank()) { running.remove(ref); return }
                emit(AgentEvent.ItemUpsert(ref, if (completed) ItemStatus.COMPLETED else ItemStatus.RUNNING,
                    ItemPayload.AssistantMessage(text, it.optString("phase").ifEmpty { null })))
            }
            "reasoning" -> {
                if (!completed) { startedAt.putIfAbsent(ref, System.currentTimeMillis()); return }
                val fromItem = strings(it.optJSONArray("summary")).ifEmpty { strings(it.optJSONArray("content")) }.joinToString("\n\n").trim()
                val text = fromItem.ifEmpty { reasoningText(ref) }
                if (text.isEmpty()) { running.remove(ref); return }
                emit(AgentEvent.ItemUpsert(ref, ItemStatus.COMPLETED, ItemPayload.Reasoning(text, startedAt[ref]?.let { System.currentTimeMillis() - it })))
            }
            "plan" -> {
                val text = it.optString("text").ifEmpty { texts[ref]?.toString().orEmpty() }
                if (text.isEmpty()) return
                emit(AgentEvent.ItemUpsert(ref, if (completed) ItemStatus.COMPLETED else ItemStatus.RUNNING, ItemPayload.ProposedPlan(text)))
            }
            "commandExecution" -> emit(AgentEvent.ItemUpsert(ref, status, ItemPayload.CommandExecution(
                CodexWire.unwrapShell(it.optString("command")),
                it.optString("aggregatedOutput").takeIf { _ -> !it.isNull("aggregatedOutput") }.orEmpty().takeLast(OUTPUT_TAIL),
                if (it.isNull("exitCode")) null else it.optInt("exitCode"),
                if (it.isNull("durationMs")) null else it.optLong("durationMs"),
            )))
            "fileChange" -> {
                fileItems[id] = it
                AcpWire.objects(it.optJSONArray("changes")).forEachIndexed { i, c ->
                    val d = CodexWire.change(c)
                    emit(AgentEvent.ItemUpsert("$ref#$i", status, ItemPayload.FileChange(d.path, d.add, d.del, d.diff)))
                }
            }
            "mcpToolCall" -> {
                val out = it.optJSONObject("error")?.optString("message")?.ifEmpty { null }
                    ?: it.optJSONObject("result")?.optJSONArray("content")?.let(::contentText).orEmpty()
                emit(AgentEvent.ItemUpsert(ref, status, ItemPayload.DynamicTool("${it.optString("server")} · ${it.optString("tool")}",
                    it.opt("arguments")?.takeIf { a -> a != JSONObject.NULL }?.toString().orEmpty(), out.take(PREVIEW))))
            }
            "dynamicToolCall" -> {
                val st = if (completed && it.has("success") && !it.optBoolean("success")) ItemStatus.FAILED else status
                emit(AgentEvent.ItemUpsert(ref, st, ItemPayload.DynamicTool(it.optString("tool"),
                    it.opt("arguments")?.takeIf { a -> a != JSONObject.NULL }?.toString().orEmpty(), it.optJSONArray("contentItems")?.let(::contentText).orEmpty().take(PREVIEW))))
            }
            "webSearch" -> emit(AgentEvent.ItemUpsert(ref, if (completed) ItemStatus.COMPLETED else ItemStatus.RUNNING,
                ItemPayload.WebSearch(webQuery(it).ifEmpty { "Web search" }, strings(it.optJSONArray("results")))))
            "imageView" -> emit(AgentEvent.ItemUpsert(ref, ItemStatus.COMPLETED, ItemPayload.DynamicTool("View image " + it.optString("path").substringAfterLast('/'), it.optString("path"))))
            "imageGeneration" -> emit(AgentEvent.ItemUpsert(ref, status, ItemPayload.DynamicTool("Generate image",
                it.optString("revisedPrompt"), it.optString("savedPath"))))
            "contextCompaction" -> emit(AgentEvent.ItemUpsert(ref, if (completed) ItemStatus.COMPLETED else ItemStatus.RUNNING, ItemPayload.Compaction(null)))
            "collabAgentToolCall" -> if (it.optString("tool") == "spawnAgent") {
                val states = it.optJSONObject("agentsStates")
                val result = states?.keys()?.asSequence()?.mapNotNull { k -> states.optJSONObject(k)?.optString("message")?.ifEmpty { null } }?.joinToString("\n\n")?.ifEmpty { null }
                emit(AgentEvent.ItemUpsert(ref, status, ItemPayload.Subagent(it.optString("prompt").lineSequence().firstOrNull()?.take(120)?.ifEmpty { null } ?: "Subagent",
                    it.optJSONArray("receiverThreadIds")?.length() ?: 0, result)))
            }
            "exitedReviewMode" -> if (completed) emit(AgentEvent.ItemUpsert(ref, ItemStatus.COMPLETED, ItemPayload.AssistantMessage(it.optString("review"))))
            else -> Unit // userMessage (the client owns user rows), hookPrompt, functionCallOutput, sleep, subAgentActivity, enteredReviewMode
        }
    }

    private fun webQuery(it: JSONObject): String {
        val a = it.optJSONObject("action")
        val parts = when (a?.optString("type")) {
            "search" -> strings(a.optJSONArray("queries")) + listOf(a.optString("query"), it.optString("query"))
            "openPage" -> listOf(a.optString("url"), it.optString("query"))
            "findInPage" -> listOf(a.optString("pattern"), a.optString("url"), it.optString("query"))
            else -> listOf(it.optString("query"))
        }
        return parts.map { s -> s.trim() }.filter { s -> s.isNotEmpty() && s != "null" }.distinct().joinToString(" · ")
    }

    /** t3code turn/plan/updated → one todo row per turn. */
    private fun todo(p: JSONObject) {
        if (!active) return
        val steps = AcpWire.objects(p.optJSONArray("plan")).mapIndexed { i, s ->
            TodoStep(s.optString("step").trim().ifEmpty { "Step ${i + 1}" }, when (s.optString("status")) {
                "completed" -> "completed"
                "inProgress" -> "in_progress"
                else -> "pending"
            })
        }
        if (steps.isEmpty()) return
        emit(AgentEvent.ItemUpsert("${p.optString("turnId")}:todo", if (steps.all { it.status == "completed" }) ItemStatus.COMPLETED else ItemStatus.RUNNING,
            ItemPayload.TodoList(steps)))
    }

    private fun tokens(t: JSONObject) {
        val total = t.optJSONObject("total")
        if (active && baseline == null) baseline = CodexWire.before(t)
        lastTotal = total
        if (!active) return
        usage = CodexWire.usage(t, baseline)
        sink(AgentEvent.UsageUpdate(usage))
    }

    /** `error`: willRetry = one live "Reconnecting n/m" row per turn (t3code retry item); otherwise the turn's failure. */
    private fun error(p: JSONObject) {
        val e = p.optJSONObject("error") ?: return
        if (!p.optBoolean("willRetry")) {
            failure = CodexWire.failure(e, limitResetAt)
            if (failure?.cls == FailureClass.PERMISSION_ERROR) onAuthError?.invoke()
            return
        }
        if (!active) return
        val ref = retryRef ?: "${p.optString("turnId").ifEmpty { turnId }}:retry".also { retryRef = it }
        retryText = e.optString("message").ifEmpty { "Reconnecting…" }
        sink(AgentEvent.ItemUpsert(ref, ItemStatus.RUNNING, ItemPayload.SystemNotice(retryText!!)))
    }

    private fun turnCompleted(p: JSONObject) {
        val t = p.optJSONObject("turn") ?: return
        if (!active || (turnId != null && t.optString("id") != turnId)) return
        val status = when (t.optString("status")) {
            "completed" -> RunStatus.COMPLETED
            "interrupted" -> RunStatus.INTERRUPTED
            else -> RunStatus.FAILED
        }
        val f = if (status != RunStatus.FAILED) null else {
            val fromTurn = t.optJSONObject("error")?.let { CodexWire.failure(it, limitResetAt) }
            if (fromTurn?.cls == FailureClass.PERMISSION_ERROR) onAuthError?.invoke()
            failure ?: fromTurn ?: Failure(FailureClass.PROVIDER_ERROR, "Codex reported a failure")
        }
        endTurn(status, f)
    }

    // ---------------- server requests ----------------

    /**
     * `item/commandExecution/requestApproval` (+ legacy `execCommandApproval`): buttons from `availableDecisions`
     * (spike: in `untrusted` mode codex offers no acceptForSession/decline), else t3code's four.
     */
    fun commandApproval(rpcId: Any, method: String, p: JSONObject): Pending {
        val legacy = method == "execCommandApproval"
        val cmd = if (legacy) strings(p.optJSONArray("command")).joinToString(" ") else p.optString("command")
        val opts = decisions(if (legacy) null else p.optJSONArray("availableDecisions"), legacy)
        val detail = RequestDetail(
            title = "Run command",
            command = CodexWire.unwrapShell(cmd).ifEmpty { null },
            paths = listOfNotNull(p.optString("cwd").ifEmpty { null }),
            reason = p.optString("reason").takeIf { !p.isNull("reason") }?.ifEmpty { null },
        )
        return register(rpcId, method, RequestKind.COMMAND, opts, detail, p)
    }

    /** `item/fileChange/requestApproval` (+ legacy `applyPatchApproval`): paths and diff from the tracked fileChange item. */
    fun fileApproval(rpcId: Any, method: String, p: JSONObject): Pending {
        val legacy = method == "applyPatchApproval"
        val diffs = if (legacy) p.optJSONObject("fileChanges")?.let { fc -> fc.keys().asSequence().toList().sorted().map { k ->
            val c = fc.optJSONObject(k) ?: JSONObject()
            CodexWire.change(JSONObject().put("path", k).put("kind", JSONObject().put("type", c.optString("type"))).put("diff", c.optString("unified_diff").ifEmpty { c.optString("content") }))
        } }.orEmpty()
        else AcpWire.objects(fileItems[p.optString("itemId")]?.optJSONArray("changes")).map(CodexWire::change)
        val reason = p.optString("reason").takeIf { !p.isNull("reason") }?.trim()?.ifEmpty { null }
            ?: diffs.take(20).joinToString("\n") { "${it.kind} ${it.path}" }.ifEmpty { null }
            ?: p.optString("grantRoot").takeIf { !p.isNull("grantRoot") }?.ifEmpty { null }
        val detail = RequestDetail(
            title = if (diffs.size == 1) "Edit ${diffs[0].path.substringAfterLast('/')}" else "Edit files",
            paths = diffs.map { it.path },
            diff = diffs.mapNotNull { d -> d.diff?.let { "--- ${d.path}\n$it" } }.joinToString("\n").ifEmpty { null },
            reason = reason,
        )
        return register(rpcId, method, RequestKind.FILE_CHANGE, decisions(null, legacy), detail, p)
    }

    /** `item/permissions/requestApproval`: grant the requested permissions for the turn or the session (t3code). */
    fun permissionsApproval(rpcId: Any, p: JSONObject): Pending {
        val fs = p.optJSONObject("permissions")?.optJSONObject("fileSystem")
        fun wants(access: String) = (fs?.optJSONArray(access)?.length() ?: 0) > 0 || AcpWire.objects(fs?.optJSONArray("entries")).any { it.optString("access") == access }
        val kind = when {
            wants("write") -> RequestKind.FILE_CHANGE
            wants("read") -> RequestKind.FILE_READ
            else -> RequestKind.COMMAND
        }
        val paths = (strings(fs?.optJSONArray("write")) + strings(fs?.optJSONArray("read")) + AcpWire.objects(fs?.optJSONArray("entries")).map { it.optString("path") }).filter { it.isNotEmpty() }.distinct()
        val opts = listOf(
            RequestOption("accept", "allow_once", "Allow for this turn"),
            RequestOption("acceptForSession", "allow_session", "Allow for session"),
            RequestOption("decline", "reject", "Decline"),
        )
        val ans = mapOf<String, Any>("accept" to "accept", "acceptForSession" to "acceptForSession", "decline" to "decline")
        val ev = AgentEvent.RequestUpsert("$nonce:$rpcId", kind, RequestStatus.PENDING, opts,
            RequestDetail("Grant permissions", paths = paths, reason = p.optString("reason").takeIf { !p.isNull("reason") }?.ifEmpty { null }))
        return Pending(rpcId, "item/permissions/requestApproval", ev, ans, p).also(::add)
    }

    /** `mcpServer/elicitation/request`: accept / decline / cancel (a URL elicitation can only be declined, t3code). */
    fun elicitation(rpcId: Any, p: JSONObject): Pending {
        val url = p.optString("mode") == "url"
        val opts = listOfNotNull(
            RequestOption("accept", "allow_once", "Accept").takeIf { !url },
            RequestOption("decline", "reject", "Decline"),
            RequestOption("cancel", "cancel", "Cancel"),
        )
        val ev = AgentEvent.RequestUpsert("$nonce:$rpcId", RequestKind.MCP_ELICITATION, RequestStatus.PENDING, opts,
            RequestDetail("${p.optString("serverName").ifEmpty { "MCP server" }} asks", reason = p.optString("message").ifEmpty { null }))
        return Pending(rpcId, "mcpServer/elicitation/request", ev, opts.associate { it.id to it.id }, p).also(::add)
    }

    /** `item/tool/requestUserInput` → question card; answers go back keyed by the native question id. */
    fun userInput(rpcId: Any, p: JSONObject): Pending {
        val qs = AcpWire.objects(p.optJSONArray("questions")).mapIndexed { i, q ->
            val opts = AcpWire.objects(q.optJSONArray("options")).map { QuestionOption(it.optString("label"), it.optString("description").ifBlank { null }) }
            Question(q.optString("id").ifEmpty { "q$i" }, q.optString("header").ifBlank { null }, q.optString("question"), opts,
                multiple = false, custom = q.optBoolean("isOther") || opts.isEmpty())
        }
        val ev = AgentEvent.RequestUpsert("$nonce:$rpcId", RequestKind.USER_INPUT, RequestStatus.PENDING, emptyList(), RequestDetail("Question", questions = qs))
        return Pending(rpcId, "item/tool/requestUserInput", ev, params = p).also(::add)
    }

    /** `/feedback`: nothing is uploaded until the user confirms on this card (owner rule). */
    fun feedbackCard(reason: String): Pending {
        val opts = listOf(RequestOption("upload", "allow_once", "Upload"), RequestOption("cancel", "cancel", "Cancel"))
        val ev = AgentEvent.RequestUpsert("$nonce:feedback:${System.nanoTime()}", RequestKind.PERMISSION, RequestStatus.PENDING, opts,
            RequestDetail("Send feedback to OpenAI", reason = (reason.ifBlank { null }?.let { "\"$it\"\n\n" } ?: "") +
                "Uploads this thread's Codex logs to OpenAI as a bug report."))
        return Pending("feedback", FEEDBACK, ev, opts.associate { it.id to it.id }, JSONObject().put("reason", reason)).also(::add)
    }

    /** Client-side command result (`/goal`, `/feedback`): one assistant row in the running turn. */
    fun note(text: String) {
        if (!active) return
        emit(AgentEvent.ItemUpsert("${turnId ?: "local"}:note:${System.nanoTime()}", ItemStatus.COMPLETED, ItemPayload.AssistantMessage(text)))
    }

    /** codex `agentMessage` with async questions (t3code: answered as the next message, nothing to reply to). */
    private fun asyncQuestions(ref: String, it: JSONObject) {
        val qs = AcpWire.objects(it.optJSONArray("questions")).mapIndexed { i, q ->
            Question(i.toString(), "Question", q.optString("title"), strings(q.optJSONArray("options")).map { QuestionOption(it) }, custom = true)
        }
        it.optString("text").ifBlank { null }?.let { t -> emit(AgentEvent.ItemUpsert(ref, ItemStatus.COMPLETED, ItemPayload.AssistantMessage(t))) }
        sink(AgentEvent.RequestUpsert("async:$ref", RequestKind.USER_INPUT, RequestStatus.PENDING, emptyList(), RequestDetail("Question", questions = qs)))
    }

    /** Reply body for [p] answered with [a]; null = cancelled (the runtime then sends [cancelReply]). */
    fun answer(p: Pending, a: RequestAnswer): JSONObject? {
        if (p.method == "item/tool/requestUserInput") {
            if (a.cancel) return null
            val out = JSONObject()
            p.event.detail.questions.forEach { q -> out.put(q.id, JSONObject().put("answers", JSONArray(a.answers[q.id].orEmpty().filter { it.isNotBlank() }))) }
            return JSONObject().put("answers", out)
        }
        if (a.cancel) return null
        val d = a.optionId?.let { p.answers[it] } ?: return null
        return when (p.method) {
            "item/permissions/requestApproval" ->
                if (d == "accept" || d == "acceptForSession") JSONObject().put("permissions", p.params.optJSONObject("permissions") ?: JSONObject()).put("scope", if (d == "acceptForSession") "session" else "turn")
                else JSONObject().put("permissions", JSONObject()).put("scope", "turn")
            "mcpServer/elicitation/request" -> JSONObject().put("action", d).apply { if (d == "accept") put("content", JSONObject()) }
            else -> JSONObject().put("decision", d)
        }.also {
            if (d == "acceptForSession" || d == "approved_for_session") allowance += if (p.event.kind == RequestKind.FILE_CHANGE) "file-change" else "command"
        }
    }

    /** What a cancelled / interrupted card answers (codex must always get a reply or the turn hangs). */
    fun cancelReply(p: Pending): JSONObject = when (p.method) {
        "item/tool/requestUserInput" -> JSONObject().put("answers", JSONObject())
        "item/permissions/requestApproval" -> JSONObject().put("permissions", JSONObject()).put("scope", "turn")
        "mcpServer/elicitation/request" -> JSONObject().put("action", "cancel")
        "execCommandApproval", "applyPatchApproval" -> JSONObject().put("decision", "abort")
        else -> JSONObject().put("decision", "cancel")
    }

    fun settle(id: String, status: RequestStatus): Pending? {
        val p = pending.remove(id) ?: return null
        sink(p.event.copy(status = status))
        return p
    }

    private fun register(rpcId: Any, method: String, kind: RequestKind, opts: List<Pair<RequestOption, Any>>, detail: RequestDetail, p: JSONObject): Pending {
        val ev = AgentEvent.RequestUpsert("$nonce:$rpcId", kind, RequestStatus.PENDING, opts.map { it.first }, detail)
        return Pending(rpcId, method, ev, opts.associate { it.first.id to it.second }, p).also(::add)
    }

    private fun add(p: Pending) {
        pending[p.event.nativeId] = p
        sink(p.event)
    }

    /** `availableDecisions` → (option, decision sent back). Legacy v1 requests take ReviewDecision strings. */
    private fun decisions(available: JSONArray?, legacy: Boolean): List<Pair<RequestOption, Any>> {
        if (legacy) return listOf(
            RequestOption("approved", "allow_once", "Approve") to "approved",
            RequestOption("approved_for_session", "allow_session", "Approve for session") to "approved_for_session",
            RequestOption("denied", "reject", "Decline") to "denied",
            RequestOption("abort", "cancel", "Stop") to "abort",
        )
        val list: List<Any> = if (available == null || available.length() == 0) listOf("accept", "acceptForSession", "decline", "cancel")
        else (0 until available.length()).mapNotNull { available.opt(it) }
        return list.mapIndexedNotNull { i, d ->
            when {
                d == "accept" -> RequestOption("accept", "allow_once", "Approve") to d
                d == "acceptForSession" -> RequestOption("acceptForSession", "allow_session", "Approve for session") to d
                d == "decline" -> RequestOption("decline", "reject", "Decline") to d
                d == "cancel" -> RequestOption("cancel", "cancel", "Stop") to d
                d is JSONObject && d.has("acceptWithExecpolicyAmendment") -> {
                    val cmd = strings(d.optJSONObject("acceptWithExecpolicyAmendment")?.optJSONArray("execpolicy_amendment")).joinToString(" ")
                    RequestOption("amend$i", "allow_always", "Always allow" + (CodexWire.unwrapShell(cmd).take(40).ifEmpty { null }?.let { " `$it`" } ?: "")) to d
                }
                d is JSONObject && d.has("applyNetworkPolicyAmendment") -> {
                    val a = d.optJSONObject("applyNetworkPolicyAmendment")?.optJSONObject("network_policy_amendment")
                    val allow = a?.optString("action") != "deny"
                    RequestOption("net$i", if (allow) "allow_always" else "reject", (if (allow) "Always allow " else "Always block ") + a?.optString("host").orEmpty()) to d
                }
                else -> null
            }
        }
    }

    companion object {
        const val FEEDBACK = "nc/feedback"
        private const val OUTPUT_TAIL = 64 * 1024
        private const val PREVIEW = 8_000

        fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
            when (val v = a.opt(i)) {
                is String -> v
                is JSONObject -> v.optString("text").ifEmpty { v.optString("title").ifEmpty { v.optString("url") } }.ifEmpty { null }
                null, JSONObject.NULL -> null
                else -> v.toString()
            }
        }

        /** MCP content blocks / dynamic tool content items → text. */
        fun contentText(a: JSONArray): String = AcpWire.objects(a).mapNotNull { c ->
            when (c.optString("type")) {
                "text", "inputText" -> c.optString("text")
                "image", "inputImage" -> "[image]"
                else -> null
            }
        }.joinToString("\n")
    }
}
