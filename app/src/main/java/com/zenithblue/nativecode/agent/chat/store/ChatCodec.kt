package com.zenithblue.nativecode.agent.chat.store

import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.DiffStat
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.Question
import com.zenithblue.nativecode.agent.chat.core.QuestionOption
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RequestDetail
import com.zenithblue.nativecode.agent.chat.core.RequestOption
import com.zenithblue.nativecode.agent.chat.core.TodoStep
import com.zenithblue.nativecode.agent.chat.core.Usage
import org.json.JSONArray
import org.json.JSONObject

/** Row JSON columns <-> models (org.json only; pure, JVM-testable). Decoders never throw on bad rows. */
object ChatCodec {

    // ---- helpers ----
    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)
    private fun JSONObject.lng(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)
    private fun JSONObject.int(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
    private fun strings(a: JSONArray?): List<String> = a?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
    private fun <T> objs(a: JSONArray?, f: (JSONObject) -> T): List<T> =
        a?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(f) } } ?: emptyList()
    private fun arr(list: List<Any?>) = JSONArray().also { a -> list.forEach { a.put(it ?: JSONObject.NULL) } }
    private fun JSONObject.putOpt2(k: String, v: Any?) = put(k, v ?: JSONObject.NULL)

    // ---- attachments ----
    fun attachmentJson(a: ChatAttachment) = JSONObject().put("name", a.name).put("mime", a.mime).put("size", a.sizeBytes)
        .putOpt2("guestPath", a.guestPath).putOpt2("hostPath", a.hostPath)

    fun attachment(o: JSONObject) = ChatAttachment(o.optString("name"), o.optString("mime"), o.optLong("size"), o.str("guestPath"), o.str("hostPath"))

    fun attachments(list: List<ChatAttachment>): String = arr(list.map(::attachmentJson)).toString()
    fun attachments(json: String?): List<ChatAttachment> = runCatching { objs(JSONArray(json ?: "[]"), ::attachment) }.getOrDefault(emptyList())

    // ---- payloads ----
    fun payload(p: ItemPayload): String {
        val o = JSONObject()
        when (p) {
            is ItemPayload.UserMessage -> o.put("text", p.text).put("attachments", arr(p.attachments.map(::attachmentJson))).put("intent", p.intent)
            is ItemPayload.AssistantMessage -> o.put("text", p.text).putOpt2("phase", p.phase)
            is ItemPayload.Reasoning -> o.put("text", p.text).putOpt2("durationMs", p.durationMs)
            is ItemPayload.CommandExecution -> o.put("command", p.command).put("outputTail", p.outputTail).putOpt2("exitCode", p.exitCode).putOpt2("durationMs", p.durationMs)
            is ItemPayload.FileChange -> o.put("path", p.path).put("add", p.add).put("del", p.del).putOpt2("diff", p.diff)
            is ItemPayload.FileSearch -> o.put("tool", p.tool).put("pattern", p.pattern).put("results", arr(p.results))
            is ItemPayload.WebSearch -> o.put("query", p.query).put("results", arr(p.results))
            is ItemPayload.DynamicTool -> o.put("name", p.name).put("input", p.input).put("output", p.output)
            is ItemPayload.Subagent -> o.put("title", p.title).put("childCount", p.childCount).putOpt2("result", p.result)
            is ItemPayload.TodoList -> o.put("steps", arr(p.steps.map { JSONObject().put("text", it.text).put("status", it.status) }))
            is ItemPayload.ProposedPlan -> o.put("markdown", p.markdown)
            is ItemPayload.ApprovalRequest -> o.put("requestId", p.requestId)
            is ItemPayload.UserInputRequest -> o.put("requestId", p.requestId)
            is ItemPayload.Error -> o.put("class", p.cls.wire).put("message", p.message).putOpt2("retryAt", p.retryAt).putOpt2("details", p.details)
            is ItemPayload.Compaction -> o.putOpt2("tokens", p.tokens)
            is ItemPayload.SystemNotice -> o.put("text", p.text)
        }
        return o.toString()
    }

    fun payload(type: String, json: String): ItemPayload {
        val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        val s = { k: String -> o.optString(k) }
        return when (type) {
            "user_message" -> ItemPayload.UserMessage(s("text"), objs(o.optJSONArray("attachments"), ::attachment), o.optString("intent", "start"))
            "assistant_message" -> ItemPayload.AssistantMessage(s("text"), o.str("phase"))
            "reasoning" -> ItemPayload.Reasoning(s("text"), o.lng("durationMs"))
            "command_execution" -> ItemPayload.CommandExecution(s("command"), s("outputTail"), o.int("exitCode"), o.lng("durationMs"))
            "file_change" -> ItemPayload.FileChange(s("path"), o.optInt("add"), o.optInt("del"), o.str("diff"))
            "file_search" -> ItemPayload.FileSearch(s("tool"), s("pattern"), strings(o.optJSONArray("results")))
            "web_search" -> ItemPayload.WebSearch(s("query"), strings(o.optJSONArray("results")))
            "dynamic_tool" -> ItemPayload.DynamicTool(s("name"), s("input"), s("output"))
            "subagent" -> ItemPayload.Subagent(s("title"), o.optInt("childCount"), o.str("result"))
            "todo_list" -> ItemPayload.TodoList(objs(o.optJSONArray("steps")) { TodoStep(it.optString("text"), it.optString("status")) })
            "proposed_plan" -> ItemPayload.ProposedPlan(s("markdown"))
            "approval_request" -> ItemPayload.ApprovalRequest(s("requestId"))
            "user_input_request" -> ItemPayload.UserInputRequest(s("requestId"))
            "error" -> ItemPayload.Error(FailureClass.of(o.str("class")), s("message"), o.lng("retryAt"), o.str("details"))
            "compaction" -> ItemPayload.Compaction(o.lng("tokens"))
            else -> ItemPayload.SystemNotice(o.optString("text", "[$type]"))
        }
    }

    // ---- requests ----
    fun options(list: List<RequestOption>): String = arr(list.map {
        JSONObject().put("id", it.id).put("kind", it.kind).put("label", it.label).putOpt2("warning", it.warning)
    }).toString()

    fun options(json: String?): List<RequestOption> = runCatching {
        objs(JSONArray(json ?: "[]")) { RequestOption(it.optString("id"), it.optString("kind"), it.optString("label"), it.str("warning")) }
    }.getOrDefault(emptyList())

    fun detail(d: RequestDetail): String = JSONObject().put("title", d.title).putOpt2("command", d.command)
        .put("paths", arr(d.paths)).putOpt2("diff", d.diff).putOpt2("reason", d.reason).putOpt2("plan", d.plan)
        .put("questions", arr(d.questions.map { q ->
            JSONObject().put("id", q.id).putOpt2("header", q.header).put("text", q.text).put("multiple", q.multiple).put("custom", q.custom)
                .put("options", arr(q.options.map { JSONObject().put("label", it.label).putOpt2("description", it.description) }))
        })).toString()

    fun detail(json: String?): RequestDetail {
        val o = runCatching { JSONObject(json ?: "{}") }.getOrDefault(JSONObject())
        return RequestDetail(
            title = o.optString("title"), command = o.str("command"), paths = strings(o.optJSONArray("paths")),
            diff = o.str("diff"), reason = o.str("reason"), plan = o.str("plan"),
            questions = objs(o.optJSONArray("questions")) { q ->
                Question(q.optString("id"), q.str("header"), q.optString("text"),
                    objs(q.optJSONArray("options")) { QuestionOption(it.optString("label"), it.str("description")) },
                    q.optBoolean("multiple"), q.optBoolean("custom", true))
            },
        )
    }

    fun answer(a: RequestAnswer): String = JSONObject().putOpt2("optionId", a.optionId).put("cancel", a.cancel).putOpt2("feedback", a.feedback)
        .put("answers", JSONObject().also { o -> a.answers.forEach { (k, v) -> o.put(k, arr(v)) } }).toString()

    fun answer(json: String?): RequestAnswer? = json?.let { j ->
        runCatching {
            val o = JSONObject(j)
            val ans = o.optJSONObject("answers")
            RequestAnswer(o.str("optionId"), ans?.keys()?.asSequence()?.associateWith { strings(ans.optJSONArray(it)) } ?: emptyMap(),
                o.optBoolean("cancel"), o.str("feedback"))
        }.getOrNull()
    }

    // ---- runs ----
    fun usage(u: Usage): String = JSONObject().put("in", u.inputTokens).put("out", u.outputTokens).put("cacheRead", u.cacheReadTokens)
        .put("cacheWrite", u.cacheWriteTokens).put("reasoning", u.reasoningTokens).putOpt2("cost", u.costUsd)
        .putOpt2("contextUsed", u.contextUsed).putOpt2("contextSize", u.contextSize).toString()

    fun usage(json: String?): Usage? = json?.let { j ->
        runCatching {
            val o = JSONObject(j)
            Usage(o.optLong("in"), o.optLong("out"), o.optLong("cacheRead"), o.optLong("cacheWrite"), o.optLong("reasoning"),
                if (o.isNull("cost") || !o.has("cost")) null else o.optDouble("cost"), o.lng("contextUsed"), o.lng("contextSize"))
        }.getOrNull()
    }

    fun failure(f: Failure): String = JSONObject().put("class", f.cls.wire).put("message", f.message).putOpt2("retryAt", f.retryAt).putOpt2("details", f.details).toString()

    fun failure(json: String?): Failure? = json?.let { j ->
        runCatching { JSONObject(j).let { Failure(FailureClass.of(it.str("class")), it.optString("message"), it.lng("retryAt"), it.str("details")) } }.getOrNull()
    }

    fun diffStat(list: List<DiffStat>): String = arr(list.map { JSONObject().put("path", it.path).put("kind", it.kind).put("add", it.add).put("del", it.del) }).toString()

    fun diffStat(json: String?): List<DiffStat>? = json?.let { j ->
        runCatching { objs(JSONArray(j)) { DiffStat(it.optString("path"), it.optString("kind"), it.optInt("add"), it.optInt("del")) } }.getOrNull()
    }
}
