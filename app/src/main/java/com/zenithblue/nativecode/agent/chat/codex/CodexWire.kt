package com.zenithblue.nativecode.agent.chat.codex

import com.zenithblue.nativecode.agent.chat.acp.AcpError
import com.zenithblue.nativecode.agent.chat.acp.AcpWire
import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.McpEndpoint
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.SessionConfig
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.agent.chat.core.TurnInput
import com.zenithblue.nativecode.agent.chat.core.Usage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Pure codex app-server wire helpers (t3code `CodexAdapterV2.ts`, `CodexProvider.ts`; schema
 * `effect-codex-app-server/_generated`). No state: [CodexMapper] and [CodexAppServerRuntime] use them, tests call them.
 */
object CodexWire {
    const val NAME = "Codex"
    const val SIGN_IN = "Sign in to Codex to chat"

    /** In-process stdio app-server: never reaches the pid-managed daemon (BusyBox `ps` lacks `-p`). */
    val ARGV = listOf("codex", "app-server", "-c", "thread_unload_delay_secs=0")

    /** t3code CODEX_THREAD_CONFIG: the todo tool (`turn/plan/updated`) and summaries that do not block tools. */
    private fun threadConfig() = JSONObject().put("tools.update_plan.enabled", true).put("features.concurrent_reasoning_summaries", true)

    /** t3code's codex list (CodexProvider.ts:1076): run as RPCs, never as model text (R6 1.3). */
    val COMMANDS = listOf(
        SlashCommand("compact", "Compact the conversation context"),
        SlashCommand("goal", "Show or set the thread goal: /goal <objective> | pause | resume | clear"),
        SlashCommand("feedback", "Send feedback with this thread's logs to OpenAI"),
    )

    /** `/goal …` (t3code parseGoalCommand): show | clear | pause | resume | set(objective). Null = not a goal command. */
    fun goal(text: String): Pair<String, String?>? {
        val m = Regex("""^/goal(?:\s+([\s\S]*))?$""").find(text.trim()) ?: return null
        val arg = m.groupValues[1].trim()
        return when (arg.lowercase()) {
            "" -> "show" to null
            "clear", "pause", "resume" -> arg.lowercase() to null
            else -> "set" to arg
        }
    }

    fun describeGoal(goal: JSONObject?): String = if (goal == null) "No goal is set." else
        "Goal ${goal.optString("status").replace(Regex("([A-Z])")) { " " + it.value.lowercase() }}: ${goal.optString("objective")}"

    fun initParams(version: String): JSONObject = JSONObject()
        .put("clientInfo", JSONObject().put("name", "nativecode").put("title", "NativeCode").put("version", version))
        .put("capabilities", JSONObject().put("experimentalApi", true).put("optOutNotificationMethods", JSONArray().put("turn/diff/updated")))

    /**
     * Supervision lives in the approval policy only: under PRoot codex's bubblewrap sandbox fails every command
     * (spike D6), so the sandbox is always full access. Supervised and Auto-accept edits = `untrusted` (asks before
     * any untrusted command and every patch; Auto-accept edits answers patches itself), Auto = `on-request` with
     * codex's auto reviewer (t3code), Full = `never`.
     */
    fun approvalPolicy(mode: RuntimeMode): String = when (mode) {
        RuntimeMode.APPROVAL_REQUIRED, RuntimeMode.AUTO_ACCEPT_EDITS -> "untrusted"
        RuntimeMode.AUTO -> "on-request"
        RuntimeMode.FULL_ACCESS -> "never"
    }

    fun reviewer(mode: RuntimeMode) = if (mode == RuntimeMode.AUTO) "auto_review" else "user"

    /** [mcp] = M7's NC tools server for this thread (t3code `mcp_servers.t3-code` + developer instructions). */
    fun threadParams(cfg: SessionConfig, model: String?, mcp: McpEndpoint? = null): JSONObject {
        val config = threadConfig()
        if (mcp != null) config.put("mcp_servers.${mcp.name}", JSONObject().put("url", mcp.url)
            .put("http_headers", JSONObject(mcp.headers)).put("tool_timeout_sec", MCP_TIMEOUT_S))
        return JSONObject()
            .put("cwd", cfg.cwd)
            .put("approvalPolicy", approvalPolicy(cfg.runtimeMode))
            .put("approvalsReviewer", reviewer(cfg.runtimeMode))
            .put("sandbox", "danger-full-access")
            .put("config", config)
            .putOpt("model", model)
            .putOpt("developerInstructions", mcp?.instructions)
    }

    /** t3code CODEX_T3_MCP_TOOL_TIMEOUT_SEC: waits on delegated tasks can block for an hour. */
    private const val MCP_TIMEOUT_S = 65 * 60

    /** t3code buildCodexTurnStartParams: policy, model, effort and collaboration mode ride on every turn. */
    fun turnParams(threadId: String, input: JSONArray, cfg: SessionConfig, model: String?, effort: String?): JSONObject {
        val settings = JSONObject().putOpt("model", model).putOpt("reasoning_effort", effort)
        return JSONObject()
            .put("threadId", threadId)
            .put("input", input)
            .put("cwd", cfg.cwd)
            .putOpt("model", model)
            .putOpt("effort", effort)
            .put("summary", "auto")
            .put("approvalPolicy", approvalPolicy(cfg.runtimeMode))
            .put("approvalsReviewer", reviewer(cfg.runtimeMode))
            .put("sandboxPolicy", JSONObject().put("type", "dangerFullAccess"))
            .put("collaborationMode", JSONObject().put("mode", if (cfg.interactionMode == InteractionMode.PLAN) "plan" else "default").put("settings", settings))
    }

    /**
     * User input (t3code toCodexInput): text (+ path lines for documents), then images inline as data URLs read
     * from the host copy; an image that cannot be read falls back to `localImage` at its guest path.
     */
    fun input(turn: TurnInput, text: String, read: (String) -> ByteArray? = ::readFile): JSONArray {
        val out = JSONArray()
        val images = ArrayList<JSONObject>()
        val lines = ArrayList<String>()
        for (a in turn.attachments) {
            val bytes = if (a.isImage && a.sizeBytes <= IMAGE_MAX) a.hostPath?.let(read) else null
            when {
                bytes != null -> images += JSONObject().put("type", "image").put("url", "data:${a.mime};base64," + Base64.getEncoder().encodeToString(bytes))
                a.isImage && a.guestPath != null -> images += JSONObject().put("type", "localImage").put("path", a.guestPath)
                else -> lines += AcpWire.pathLine(a)
            }
        }
        val full = (listOf(text.trim()) + lines).filter { it.isNotEmpty() }.joinToString("\n\n")
        if (full.isNotEmpty()) out.put(JSONObject().put("type", "text").put("text", full))
        images.forEach { out.put(it) }
        return out
    }

    /** `/skill rest` → `$skill rest` for a known skill (codex parses only `$name` mentions; t3code codexSkillMentionText). */
    fun skillText(text: String, skills: Collection<String>): String {
        val m = Regex("""^/([\w.:-]+)(\s[\s\S]*)?$""").find(text.trimStart()) ?: return text
        return if (m.groupValues[1] in skills) "$" + m.groupValues[1] + m.groupValues[2] else text
    }

    /** `model/list` page → models (slug = `model`, t3code parseCodexModelListResponse); hidden ones dropped. */
    fun models(res: JSONObject): List<Pair<ModelInfo, Boolean>> = AcpWire.objects(res.optJSONArray("data"))
        .filter { !it.optBoolean("hidden") }
        .mapNotNull { m ->
            val slug = m.optString("model").ifEmpty { m.optString("id") }.ifEmpty { return@mapNotNull null }
            val efforts = AcpWire.objects(m.optJSONArray("supportedReasoningEfforts")).map { it.optString("reasoningEffort") }.filter { it.isNotEmpty() }
            ModelInfo(slug, m.optString("displayName").ifEmpty { slug }, "openai", variants = efforts) to m.optBoolean("isDefault")
        }

    fun defaultEffort(res: JSONObject, slug: String): String? = AcpWire.objects(res.optJSONArray("data"))
        .firstOrNull { it.optString("model") == slug }?.optString("defaultReasoningEffort")?.ifEmpty { null }

    /** `account/read`: (signed out, label). Signed out = no account while OpenAI auth is required. */
    fun account(res: JSONObject): Pair<Boolean, String?> {
        val a = res.optJSONObject("account")
        if (a == null) return res.optBoolean("requiresOpenaiAuth", true) to null
        val label = when (a.optString("type")) {
            "chatgpt" -> "ChatGPT" + (a.optString("planType").ifEmpty { null }?.let { " " + it.replaceFirstChar(Char::uppercase) } ?: "")
            "apiKey" -> "API key"
            "amazonBedrock" -> "Amazon Bedrock"
            else -> a.optString("type").ifEmpty { null }
        }
        return false to label
    }

    fun skills(res: JSONObject, cwd: String): List<SlashCommand> {
        val entries = AcpWire.objects(res.optJSONArray("data"))
        val list = (entries.firstOrNull { it.optString("cwd") == cwd }?.let { listOf(it) } ?: entries)
            .flatMap { AcpWire.objects(it.optJSONArray("skills")) }
            .filter { it.optBoolean("enabled", true) }
        return list.mapNotNull { s ->
            val name = s.optString("name").ifEmpty { return@mapNotNull null }
            val desc = s.optString("shortDescription").ifEmpty { null } ?: s.optJSONObject("interface")?.optString("shortDescription")?.ifEmpty { null }
                ?: s.optString("description").ifEmpty { null }
            SlashCommand(name, desc)
        }.distinctBy { it.name }.filter { s -> s.name !in RESERVED } // a skill never shadows a built-in
    }

    private val RESERVED = setOf("compact", "goal", "feedback", "model", "clear")

    /** `thread/tokenUsage/updated.tokenUsage` → this turn's usage: [base] = the thread total before the turn. */
    fun usage(tokenUsage: JSONObject, base: JSONObject?): Usage {
        val total = tokenUsage.optJSONObject("total") ?: JSONObject()
        val last = tokenUsage.optJSONObject("last") ?: JSONObject()
        fun d(k: String) = (total.optLong(k) - (base?.optLong(k) ?: 0)).coerceAtLeast(0)
        val input = d("inputTokens")
        val cached = d("cachedInputTokens")
        return Usage(
            inputTokens = (input - cached).coerceAtLeast(0),
            outputTokens = d("outputTokens"),
            cacheReadTokens = cached,
            cacheWriteTokens = d("cacheWriteInputTokens"),
            reasoningTokens = d("reasoningOutputTokens"),
            contextUsed = last.optLong("totalTokens").takeIf { it > 0 },
            contextSize = tokenUsage.optLong("modelContextWindow").takeIf { it > 0 },
        )
    }

    /** Total minus last: the thread total before this model call (baseline when the turn's first update arrives). */
    fun before(tokenUsage: JSONObject): JSONObject {
        val total = tokenUsage.optJSONObject("total") ?: JSONObject()
        val last = tokenUsage.optJSONObject("last") ?: JSONObject()
        val o = JSONObject()
        for (k in total.keys()) o.put(k, (total.optLong(k) - last.optLong(k)).coerceAtLeast(0))
        return o
    }

    /**
     * `account/rateLimits/updated` / `account/rateLimits/read` snapshot → quota state. Limited when codex says the
     * limit was reached or a window is full; warning from 80 %. [detail] = "5h 42% · weekly 13%".
     */
    fun rateLimit(snapshot: JSONObject?): AgentEvent.RateLimit? {
        if (snapshot == null) return null
        val windows = listOfNotNull(snapshot.optJSONObject("primary"), snapshot.optJSONObject("secondary"))
        if (windows.isEmpty() && snapshot.isNull("rateLimitReachedType")) return null
        val full = windows.filter { it.optDouble("usedPercent", 0.0) >= 100.0 }
        val reached = !snapshot.isNull("rateLimitReachedType") && snapshot.has("rateLimitReachedType") || full.isNotEmpty()
        val worst = windows.maxOfOrNull { it.optDouble("usedPercent", 0.0) } ?: 0.0
        val status = when {
            reached -> "rate_limited"
            worst >= 80.0 -> "allowed_warning"
            else -> "allowed"
        }
        val resetAt = (full.ifEmpty { if (reached) windows else emptyList() })
            .mapNotNull { w -> w.optLong("resetsAt").takeIf { it > 0 }?.times(1000) }.maxOrNull()
        val detail = windows.joinToString(" · ") { w -> "${windowLabel(w.optLong("windowDurationMins"))} ${w.optDouble("usedPercent", 0.0).toInt()}%" }
            .ifEmpty { null }?.let { d -> snapshot.optString("planType").ifEmpty { null }?.let { "$d ($it)" } ?: d }
        return AgentEvent.RateLimit(status, resetAt, detail)
    }

    private fun windowLabel(mins: Long): String = when {
        mins <= 0 -> "limit"
        mins == 10080L -> "weekly"
        mins % 1440 == 0L -> "${mins / 1440}d"
        mins % 60 == 0L -> "${mins / 60}h"
        else -> "${mins}m"
    }

    /** Key of a `codexErrorInfo` (`"usageLimitExceeded"` or `{"httpConnectionFailed":{…}}`). */
    fun errorCode(info: Any?): String? = when (info) {
        is String -> info
        is JSONObject -> info.keys().asSequence().firstOrNull()
        else -> null
    }

    fun httpStatus(info: Any?): Int? = (info as? JSONObject)?.let { o -> errorCode(o)?.let { o.optJSONObject(it) } }
        ?.takeIf { it.has("httpStatusCode") && !it.isNull("httpStatusCode") }?.optInt("httpStatusCode")

    /** t3code codexUsageLimitError + classes: 429 exhaustion and usage/rate limits = usage limit; 401 = sign in. */
    fun failure(error: JSONObject?, retryAt: Long?): Failure {
        val info = error?.opt("codexErrorInfo")
        val code = errorCode(info)
        val status = httpStatus(info)
        val msg = error?.optString("additionalDetails")?.trim()?.ifEmpty { null } ?: error?.optString("message")?.ifEmpty { null } ?: "Codex reported an error"
        return when {
            code == "usageLimitExceeded" || code == "rateLimitExceeded" || (code == "responseTooManyFailedAttempts" && status == 429) ->
                Failure(FailureClass.USAGE_LIMIT, msg, retryAt)
            status == 401 || code == "unauthorized" -> Failure(FailureClass.PERMISSION_ERROR, SIGN_IN, details = msg)
            code != null && (code.startsWith("http") || code.startsWith("responseStream")) -> Failure(FailureClass.TRANSPORT_ERROR, msg)
            else -> Failure(FailureClass.PROVIDER_ERROR, msg)
        }
    }

    /** A failed JSON-RPC call (turn/start rejected, stdin closed…). */
    fun failureOf(e: Throwable): Failure = when {
        e is AcpError && e.message.orEmpty().contains("auth", ignoreCase = true) -> Failure(FailureClass.PERMISSION_ERROR, SIGN_IN, details = e.message)
        e is AcpError -> Failure(FailureClass.PROVIDER_ERROR, e.message ?: "Codex rejected the request")
        else -> Failure(FailureClass.TRANSPORT_ERROR, e.message ?: "Codex is not running")
    }

    /** `/bin/bash -lc 'cmd'` → `cmd` for display (codex wraps every shell command). */
    fun unwrapShell(command: String): String {
        val m = Regex("""^(?:/usr)?(?:/bin/)?(?:ba|z)?sh\s+-l?c\s+'([\s\S]*)'$""").find(command.trim()) ?: return command
        return m.groupValues[1].replace("""'\''""", "'").replace("""'"'"'""", "'")
    }

    /** One `fileChange.changes[]` entry → (path label, +, −, unified-ish diff). Add/delete carry the whole file. */
    fun change(c: JSONObject): FileDiff {
        val kind = c.optJSONObject("kind")
        val type = kind?.optString("type") ?: c.optString("kind")
        val path = c.optString("path")
        val move = kind?.optString("move_path")?.ifEmpty { null } ?: kind?.optString("movePath")?.ifEmpty { null }
        val raw = c.optString("diff")
        val diff = when (type) {
            "add" -> raw.lineSequence().let { s -> if (raw.endsWith("\n")) s.toList().dropLast(1) else s.toList() }.joinToString("\n") { "+$it" }
            "delete" -> raw.lineSequence().let { s -> if (raw.endsWith("\n")) s.toList().dropLast(1) else s.toList() }.joinToString("\n") { "-$it" }
            else -> raw
        }
        var add = 0
        var del = 0
        diff.lineSequence().forEach { l ->
            if (l.startsWith("+") && !l.startsWith("+++")) add++
            else if (l.startsWith("-") && !l.startsWith("---")) del++
        }
        return FileDiff(if (move != null) "$path → $move" else path, add, del, diff.ifEmpty { null }, type)
    }

    data class FileDiff(val path: String, val add: Int, val del: Int, val diff: String?, val kind: String)

    private const val IMAGE_MAX = 10L shl 20
    private fun readFile(p: String): ByteArray? = runCatching { File(p).readBytes() }.getOrNull()
}
