package com.zenithblue.nativecode.agent.chat.acp

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.Question
import com.zenithblue.nativecode.agent.chat.core.QuestionOption
import com.zenithblue.nativecode.agent.chat.core.RequestDetail
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestOption
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.agent.chat.core.Usage
import org.json.JSONObject

/**
 * ACP `session/update` + `session/request_permission` → [AgentEvent] (t3code runtimeModel.ts / adapter.ts).
 * Pure and single-threaded (the runtime calls it under its lock), so fixtures replay through it in tests.
 *
 * Refs carry a per-runtime [nonce] (agent ids such as JSON-RPC request ids restart with each process, but
 * `turn_items.native_ref` is unique per thread). Text is segmented like t3code: a new tool call or a switch
 * between thinking and answering closes the open segment, so text after a tool is a new row.
 */
class AcpMapper(private val profile: AcpProfile, private val nonce: String, private val sink: (AgentEvent) -> Unit) {
    private class Seg(val ref: String, val reasoning: Boolean, val startedAt: Long) { val text = StringBuilder() }

    /** A permission / question request waiting for the user; [rpcId] is the agent's JSON-RPC id. */
    class Pending(val rpcId: Any, val event: AgentEvent.RequestUpsert, val method: String, val optionByLabel: Map<String, String> = emptyMap(), val questionText: Map<String, String> = emptyMap())

    var active = false; private set
    var interrupting = false
    var usage = Usage(); private set
    var contextSize: Long? = null
    var commands: List<SlashCommand> = emptyList(); private set
    var modeId: String? = null; private set

    private var turn = 0
    private var segN = 0
    private var seg: Seg? = null
    private val tools = LinkedHashMap<String, AcpWire.Tool>()
    private val pending = LinkedHashMap<String, Pending>()
    private var lastPlan: String? = null

    val openTools: Int get() = tools.values.count { it.status == "pending" || it.status == "in_progress" }
    fun pending(id: String): Pending? = pending[id]
    fun pendingIds(): List<String> = pending.keys.toList()

    fun beginTurn() {
        turn++
        segN = 0
        seg = null
        active = true
        interrupting = false
        usage = Usage(contextUsed = usage.contextUsed, contextSize = usage.contextSize ?: contextSize)
        sink(AgentEvent.TurnStarted(null))
    }

    /** Ends the turn once (the prompt result and grok's `prompt_complete` race). False = already ended. */
    fun endTurn(status: RunStatus, failure: Failure? = null, reusable: Boolean = true): Boolean {
        if (!active) return false
        active = false
        closeSeg()
        val left = if (status == RunStatus.COMPLETED) ItemStatus.COMPLETED else ItemStatus.INTERRUPTED
        tools.values.filter { (it.status == "pending" || it.status == "in_progress") && it.xaiTool?.optString("kind") != "ask_user" }.forEach { it.status = if (left == ItemStatus.COMPLETED) "completed" else "cancelled"; emitTool(it, left) }
        tools.clear()
        pending.keys.toList().forEach { settle(it, RequestStatus.EXPIRED) }
        if (usage != Usage()) sink(AgentEvent.UsageUpdate(usage))
        sink(AgentEvent.TurnTerminal(if (interrupting && status == RunStatus.COMPLETED) RunStatus.INTERRUPTED else status, failure, reusable))
        interrupting = false
        return true
    }

    fun addUsage(u: Usage) {
        usage = u
        sink(AgentEvent.UsageUpdate(usage))
    }

    /** One `session/update` params. Returns false when it was not consumed (unknown kind). */
    fun update(params: JSONObject): Boolean {
        val u = params.optJSONObject("update") ?: return false
        if (u.optJSONObject("_meta")?.optBoolean("isReplay") == true) return true
        when (u.optString("sessionUpdate")) {
            "agent_message_chunk" -> text(AcpWire.blockText(u.optJSONObject("content")), reasoning = false)
            "agent_thought_chunk" -> u.optJSONObject("content")?.takeIf { it.optString("type") == "text" }?.let { text(it.optString("text"), reasoning = true) }
            "user_message_chunk" -> Unit // the client owns user rows
            "tool_call", "tool_call_update" -> tool(u)
            "plan" -> if (active) {
                val todo = AcpWire.todo(u.optJSONArray("entries"))
                val ref = "$nonce:$turn:plan"
                lastPlan = ref
                sink(AgentEvent.ItemUpsert(ref, if (todo.steps.all { it.status == "completed" }) ItemStatus.COMPLETED else ItemStatus.RUNNING, todo))
            }
            "usage_update" -> {
                val size = u.optLong("size").takeIf { it > 0 }
                contextSize = size ?: contextSize
                val cost = u.optJSONObject("cost")?.takeIf { it.optString("currency", "USD").equals("USD", true) }?.optDouble("amount")
                usage = usage.copy(contextUsed = u.optLong("used"), contextSize = size ?: usage.contextSize, costUsd = cost ?: usage.costUsd)
                sink(AgentEvent.UsageUpdate(usage))
            }
            "available_commands_update" -> {
                commands = AcpWire.commands(u.optJSONArray("availableCommands"), profile.dropCommands) + profile.extraCommands.filter { e -> commands.none { it.name == e.name } }
                commands = commands.distinctBy { it.name }
                onCommands?.invoke(commands)
            }
            "current_mode_update" -> {
                modeId = u.optString("currentModeId").trim().ifEmpty { null }
                onMode?.invoke(modeId)
            }
            "config_option_update" -> onConfig?.invoke(u)
            "session_info_update", "compaction_update", "compaction_summary_chunk" -> Unit
            else -> return false
        }
        return true
    }

    var onCommands: ((List<SlashCommand>) -> Unit)? = null
    var onMode: ((String?) -> Unit)? = null
    var onConfig: ((JSONObject) -> Unit)? = null

    /** Seed commands from `initialize` (grok `_meta.availableCommands`). */
    fun seedCommands(list: List<SlashCommand>) { commands = (list + profile.extraCommands).distinctBy { it.name } }

    private fun text(chunk: String, reasoning: Boolean) {
        if (!active || chunk.isEmpty()) return
        val s = seg?.takeIf { it.reasoning == reasoning } ?: run {
            if (chunk.isBlank()) return
            closeSeg()
            Seg("$nonce:$turn:${if (reasoning) "r" else "a"}${segN++}", reasoning, System.currentTimeMillis()).also { seg = it }
        }
        s.text.append(chunk)
        sink(AgentEvent.ItemUpsert(s.ref, ItemStatus.RUNNING, segPayload(s, null)))
    }

    private fun segPayload(s: Seg, durationMs: Long?): ItemPayload =
        if (s.reasoning) ItemPayload.Reasoning(s.text.toString(), durationMs) else ItemPayload.AssistantMessage(s.text.toString())

    private fun closeSeg() {
        val s = seg ?: return
        seg = null
        sink(AgentEvent.ItemUpsert(s.ref, ItemStatus.COMPLETED, segPayload(s, if (s.reasoning) System.currentTimeMillis() - s.startedAt else null)))
    }

    private fun tool(u: JSONObject) {
        val id = u.optString("toolCallId").ifEmpty { return }
        if (profile.interactionQuestions && id.startsWith("interaction_")) return // shown as the question card
        val t = tools[id] ?: run {
            if (!active) return
            closeSeg()
            AcpWire.Tool(id).also { tools[id] = it }
        }
        t.merge(u)
        if (t.xaiTool?.optString("kind") == "ask_user") return // grok: the x.ai/ask_user_question card shows it
        emitTool(t, statusOf(t.status))
    }

    fun toolKind(id: String): String? = tools[id]?.kind

    /** grok background tasks (`x.ai/task_completed`): finish the card that started them. */
    fun finishTool(id: String, exitCode: Int?, output: String?) {
        val t = tools[id] ?: return
        t.status = if (exitCode == null || exitCode == 0) "completed" else "failed"
        if (output != null) t.rawOutput = JSONObject().put("output", output).put("exitCode", exitCode)
        emitTool(t, statusOf(t.status))
    }

    private fun statusOf(s: String) = when (s) {
        "completed" -> ItemStatus.COMPLETED
        "failed" -> if (interrupting) ItemStatus.INTERRUPTED else ItemStatus.FAILED
        "cancelled" -> ItemStatus.CANCELLED
        else -> ItemStatus.RUNNING
    }

    private fun emitTool(t: AcpWire.Tool, status: ItemStatus) =
        sink(AgentEvent.ItemUpsert("$nonce:tool:${t.id}", status, AcpWire.toolPayload(t)))

    // ---------------- requests ----------------

    /**
     * `session/request_permission` → card. agy `interaction_*` = a single-choice question (AntigravityProtocol.ts:48);
     * otherwise approval options mapped through [AcpProfile.options], plus Cancel. Missing kind/title come from the
     * tracked tool call.
     */
    /**
     * A permission request's `toolCall.rawInput` fills a tracked call that came without one: agy `invoke_subagent`
     * carries its launch (`Subagents[{Role,Prompt}]`) only there (t3code AntigravityChildWork). Also on auto-approve.
     */
    fun adoptInput(call: JSONObject) {
        val t = tools[call.optString("toolCallId")] ?: return
        if (t.rawInput != null || !call.has("rawInput")) return
        t.merge(JSONObject().put("rawInput", call.opt("rawInput")))
        emitTool(t, statusOf(t.status))
    }

    fun permission(rpcId: Any, p: JSONObject): Pending {
        val call = p.optJSONObject("toolCall") ?: JSONObject()
        val callId = call.optString("toolCallId")
        val tracked = tools[callId]
        val id = "$nonce:${rpcId}"
        val options = AcpWire.objects(p.optJSONArray("options"))
        if (profile.interactionQuestions && callId.startsWith("interaction_")) {
            val choices = options.map { QuestionOption(it.optString("name").ifBlank { it.optString("optionId") }) }
            val q = Question("q", null, call.optString("title").ifBlank { "Choose an option." }, choices, multiple = false, custom = false)
            val ev = AgentEvent.RequestUpsert(id, RequestKind.USER_INPUT, RequestStatus.PENDING, emptyList(), RequestDetail("Question", questions = listOf(q)))
            return Pending(rpcId, ev, "permission", options.associate { it.optString("name").ifBlank { it.optString("optionId") } to it.optString("optionId") }).also { register(id, it) }
        }
        val kind = call.optString("kind").ifEmpty { tracked?.kind ?: "other" }
        val tmp = AcpWire.Tool(callId).apply { tracked?.let { o -> this.kind = o.kind; title = o.title; rawInput = o.rawInput; locations = o.locations; content = o.content }; merge(call); this.kind = kind }
        adoptInput(call)
        val reqKind = when (kind) {
            "execute" -> RequestKind.COMMAND
            "read", "search", "fetch" -> RequestKind.FILE_READ
            "edit", "delete", "move" -> RequestKind.FILE_CHANGE
            else -> RequestKind.PERMISSION
        }
        val payload = AcpWire.toolPayload(tmp)
        val detail = RequestDetail(
            title = tmp.title ?: when (reqKind) {
                RequestKind.COMMAND -> "Run command"
                RequestKind.FILE_CHANGE -> "Edit files"
                RequestKind.FILE_READ -> "Read files"
                else -> "Allow tool"
            },
            command = tmp.command.takeIf { reqKind == RequestKind.COMMAND },
            paths = listOfNotNull(tmp.path.takeIf { reqKind != RequestKind.COMMAND }),
            diff = (payload as? ItemPayload.FileChange)?.diff,
        )
        val ev = AgentEvent.RequestUpsert(id, reqKind, RequestStatus.PENDING, profile.options(options), detail)
        return Pending(rpcId, ev, "permission").also { register(id, it) }
    }

    /** grok `x.ai/ask_user_question`: multi-question card; answers go back keyed by question text. */
    fun xaiQuestion(rpcId: Any, p: JSONObject): Pending {
        val id = "$nonce:${rpcId}"
        val qs = AcpWire.objects(p.optJSONArray("questions")).mapIndexed { i, q ->
            val opts = AcpWire.objects(q.optJSONArray("options")).map { QuestionOption(it.optString("label"), it.optString("description").ifBlank { null }) }
            Question(q.optString("id").ifEmpty { "q$i" }, q.optString("header").ifBlank { null }, q.optString("question"),
                opts.ifEmpty { listOf(QuestionOption("OK")) }, multiple = q.optBoolean("multiSelect"), custom = true)
        }
        val ev = AgentEvent.RequestUpsert(id, RequestKind.USER_INPUT, RequestStatus.PENDING, emptyList(), RequestDetail("Question", questions = qs))
        return Pending(rpcId, ev, "x.ai/ask_user_question", questionText = qs.associate { it.id to it.text }).also { register(id, it) }
    }

    /** grok `x.ai/exit_plan_mode`: the plan becomes a proposed-plan row (t3code adapter.ts:161). */
    fun proposedPlan(markdown: String) {
        closeSeg()
        sink(AgentEvent.ItemUpsert("$nonce:$turn:plan-proposal:${segN++}", ItemStatus.COMPLETED, ItemPayload.ProposedPlan(markdown)))
    }

    private fun register(id: String, p: Pending) {
        pending[id] = p
        sink(p.event)
    }

    fun settle(id: String, status: RequestStatus): Pending? {
        val p = pending.remove(id) ?: return null
        sink(p.event.copy(status = status))
        return p
    }
}
