package com.zenithblue.nativecode.agent.chat.claude

import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.McpEndpoint
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.Question
import com.zenithblue.nativecode.agent.chat.core.QuestionOption
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.agent.chat.core.TodoStep
import com.zenithblue.nativecode.agent.chat.core.TurnInput
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * claude-code stream-json + control channel, the wire `@anthropic-ai/claude-agent-sdk` speaks (t3code
 * `ClaudeAdapterV2.ts`; plan 9.2.4, verified on 2.1.323 in SPIKES-M2-M5 §1). Pure helpers, no I/O.
 */
internal object ClaudeWire {
    /** Oldest CLI whose control channel was verified (spike). */
    const val MIN_VERSION = "2.1"

    /** t3code `ClaudeAdapterV2.ts:11627`: ExitPlanMode is denied once the plan card has it. */
    const val PLAN_CAPTURED = "The client captured your proposed plan. Stop here and wait for the user's feedback or implementation request in a later turn."
    const val DECLINED = "User declined tool execution."
    const val CANCELLED = "User cancelled tool execution."
    const val SIGNED_OUT = "Not signed in to Claude Code"

    val ENV = mapOf("CLAUDE_CODE_ENTRYPOINT" to "sdk-ts")
    /** Task result bookkeeping the CLI appends (`<tools-used-before-summary>`, `agentId: …` usage footer). */
    private val AGENT_META = Regex("""<tools-used-before-summary>[\s\S]*?</tools-used-before-summary>|<usage>[\s\S]*?</usage>|(?m)^agentId: \S+.*$""")
    private val IMAGE_MIMES = setOf("image/gif", "image/jpeg", "image/png", "image/webp")

    /**
     * The SDK's ProcessTransport argv. `--permission-prompt-tool stdio` routes every ask to `can_use_tool`
     * (without it `-p` auto-denies); `--allow-dangerously-skip-permissions` only makes Full access selectable live;
     * `showThinkingSummaries` as t3code (`ClaudeAdapterV2.ts:2100`), else thinking blocks arrive empty.
     * New threads pass `--session-id` so the resume key is known before the first turn.
     */
    fun argv(model: String?, effort: String?, mode: String, resume: String?, sessionId: String?, mcp: McpEndpoint? = null): List<String> = buildList {
        addAll(listOf("claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
            "--include-partial-messages", "--permission-prompt-tool", "stdio", "--permission-mode", mode,
            "--allow-dangerously-skip-permissions", "--setting-sources", "user,project,local",
            "--settings", """{"showThinkingSummaries":true}""", "--thinking-display", "summarized")) // t3code ClaudeAdapterV2 requestThinkingSummaries
        model?.takeIf { it.isNotBlank() && it != DEFAULT_MODEL }?.let { add("--model"); add(it) }
        effort?.takeIf { it.isNotBlank() }?.let { add("--effort"); add(it) }
        if (resume != null) { add("--resume"); add(resume) } else if (sessionId != null) { add("--session-id"); add(sessionId) }
        if (mcp != null) {
            add("--mcp-config"); add(mcpConfig(mcp))
            add("--allowedTools"); add("mcp__${mcp.name}") // the app's own tools never prompt (t3code allowedTools mcp__t3-code__*)
            mcp.instructions?.let { add("--append-system-prompt"); add(it) }
        }
    }

    /**
     * M7 server as an `--mcp-config` JSON string. Header values are `${NC_MCP_Hn}` references that claude expands
     * from [mcpEnv] (checked on 2.1.323), so the bearer token is in the process env, never in argv / cmdline.
     */
    fun mcpConfig(mcp: McpEndpoint): String {
        val headers = JSONObject()
        mcp.headers.keys.forEachIndexed { i, k -> headers.put(k, "\${NC_MCP_H$i}") }
        val server = JSONObject().put("type", "http").put("url", mcp.url).put("headers", headers)
        return JSONObject().put("mcpServers", JSONObject().put(mcp.name, server)).toString()
    }

    fun mcpEnv(mcp: McpEndpoint?): Map<String, String> =
        ENV + (mcp?.headers?.values?.mapIndexed { i, v -> "NC_MCP_H$i" to v }?.toMap() ?: emptyMap())

    const val DEFAULT_MODEL = "default"

    /** t3code runtime policy → permissionMode (`ClaudeAdapterV2.ts:3056-3110`). */
    fun permissionMode(mode: RuntimeMode, interaction: InteractionMode): String = when {
        interaction == InteractionMode.PLAN -> "plan"
        mode == RuntimeMode.AUTO_ACCEPT_EDITS -> "acceptEdits"
        mode == RuntimeMode.AUTO -> "auto"
        mode == RuntimeMode.FULL_ACCESS -> "bypassPermissions"
        else -> "default"
    }

    /** Agent-reported permissionMode (`system/status`, `system/init`) → chips; null runtime mode = unchanged. */
    fun modesOf(pm: String?): Pair<RuntimeMode?, InteractionMode>? = when (pm) {
        "plan" -> null to InteractionMode.PLAN
        "default" -> RuntimeMode.APPROVAL_REQUIRED to InteractionMode.DEFAULT
        "acceptEdits" -> RuntimeMode.AUTO_ACCEPT_EDITS to InteractionMode.DEFAULT
        "auto" -> RuntimeMode.AUTO to InteractionMode.DEFAULT
        "bypassPermissions" -> RuntimeMode.FULL_ACCESS to InteractionMode.DEFAULT
        else -> null
    }

    fun control(id: String, request: JSONObject): JSONObject =
        JSONObject().put("type", "control_request").put("request_id", id).put("request", request)

    fun success(id: String, response: JSONObject): JSONObject = JSONObject().put("type", "control_response")
        .put("response", JSONObject().put("subtype", "success").put("request_id", id).put("response", response))

    fun error(id: String, message: String): JSONObject = JSONObject().put("type", "control_response")
        .put("response", JSONObject().put("subtype", "error").put("request_id", id).put("error", message))

    /**
     * One SDKUserMessage. Images (gif/jpeg/png/webp) and PDFs go inline as base64 blocks; other files get
     * t3code's path line. [read] returns the bytes of a host path (null = unreadable → path line).
     */
    fun userMessage(turn: TurnInput, steer: Boolean, read: (String) -> ByteArray?): JSONObject {
        val content = JSONArray()
        val lines = StringBuilder(turn.text)
        for (a in turn.attachments) {
            val block = inlineBlock(a, read)
            if (block != null) content.put(block)
            else a.guestPath?.let { lines.append("\n\n[Attached file \"").append(a.name).append("\" is saved at: ").append(it).append(']') }
        }
        val text = lines.toString().trim()
        val blocks = JSONArray()
        if (text.isNotEmpty()) blocks.put(JSONObject().put("type", "text").put("text", text))
        for (i in 0 until content.length()) content.optJSONObject(i)?.let { blocks.put(it) }
        val msg = JSONObject().put("type", "user").put("parent_tool_use_id", JSONObject.NULL)
            .put("message", JSONObject().put("role", "user").put("content", blocks))
        if (steer) msg.put("priority", "now")
        return msg
    }

    private fun inlineBlock(a: ChatAttachment, read: (String) -> ByteArray?): JSONObject? {
        val mime = a.mime.lowercase()
        val type = when {
            mime in IMAGE_MIMES -> "image"
            mime == "application/pdf" -> "document"
            else -> return null
        }
        val bytes = a.hostPath?.let(read) ?: return null
        return JSONObject().put("type", type).put("source", JSONObject().put("type", "base64").put("media_type", mime)
            .put("data", Base64.getEncoder().encodeToString(bytes)))
    }

    /** `initialize.models[]`: value is the id the CLI takes back; effort levels become the variants. */
    fun parseModels(a: JSONArray?): List<ModelInfo> = objects(a).mapNotNull { m ->
        val id = m.optString("value").ifBlank { return@mapNotNull null }
        ModelInfo(id, m.optString("displayName").ifBlank { id }, "anthropic",
            variants = if (m.optBoolean("supportsEffort")) strings(m.optJSONArray("supportedEffortLevels")) else emptyList())
    }

    /**
     * initialize `commands[]` (built-ins, user/project commands, plugins, skills) with `argumentHint`, all sent as
     * plain `/name args` text (t3code `ClaudeProvider.ts:347-365`); `/compact` is always offered (t3 COMPACT_SLASH_COMMAND).
     */
    fun parseCommands(a: JSONArray?): List<SlashCommand> {
        val list = objects(a).mapNotNull { c ->
            c.optString("name").ifBlank { null }?.let {
                SlashCommand(it, c.optString("description").ifBlank { null }, c.optString("argumentHint").ifBlank { null })
            }
        }
        return if (list.any { it.name == "compact" }) list else listOf(COMPACT) + list
    }

    private val COMPACT = SlashCommand("compact", "Free up context by summarizing the conversation so far")

    /** Account line for thread settings: email · plan, else the API provider. Never tokens. */
    fun account(a: JSONObject?): String? {
        a ?: return null
        val email = a.optString("email").ifBlank { null }
        val plan = a.optString("subscriptionType").ifBlank { null }?.let { if (it.startsWith("Claude", true)) it else "Claude " + it.replaceFirstChar { c -> c.uppercase() } }
        return listOfNotNull(email, plan).joinToString(" · ").ifBlank { null }
    }

    /** `AskUserQuestion.input.questions[]` (t3code `claudeUserInputQuestions`); id = the question text, which answers key on. */
    fun questions(input: JSONObject): List<Question> = objects(input.optJSONArray("questions")).mapIndexedNotNull { i, q ->
        val text = q.optString("question").trim().ifEmpty { return@mapIndexedNotNull null }
        Question(text, q.optString("header").trim().ifEmpty { "Question ${i + 1}" }, text,
            objects(q.optJSONArray("options")).mapNotNull { o ->
                o.optString("label").trim().ifEmpty { null }?.let { QuestionOption(it, o.optString("description").trim().ifEmpty { null }) }
            }, multiple = q.optBoolean("multiSelect"), custom = true)
    }

    fun allow(input: JSONObject, toolUseId: String?, classification: String?, permissions: JSONArray? = null): JSONObject =
        JSONObject().put("behavior", "allow").put("updatedInput", input).putOpt("toolUseID", toolUseId)
            .putOpt("decisionClassification", classification).putOpt("updatedPermissions", permissions)

    fun deny(message: String, toolUseId: String?, interrupt: Boolean = false, classification: String? = "user_reject"): JSONObject =
        JSONObject().put("behavior", "deny").put("message", message).putOpt("toolUseID", toolUseId)
            .putOpt("decisionClassification", classification).apply { if (interrupt) put("interrupt", true) }

    /**
     * "Allow for session" = only the CLI's scoped `addRules` suggestions, moved to `destination:"session"`; never
     * `setMode` (it flipped the whole session to acceptEdits on the spike) nor `addDirectories`, and never the
     * default `localSettings` (that would persist to `.claude/settings.local.json`). No addRules → the tool itself.
     */
    fun sessionRules(toolName: String, suggestions: JSONArray?): JSONArray {
        val out = JSONArray()
        objects(suggestions).filter { it.optString("type") == "addRules" && it.optString("behavior", "allow") == "allow" }
            .forEach { out.put(JSONObject(it.toString()).put("destination", "session")) }
        if (out.length() == 0) out.put(JSONObject().put("type", "addRules").put("behavior", "allow").put("destination", "session")
            .put("rules", JSONArray().put(JSONObject().put("toolName", toolName))))
        return out
    }

    fun sessionLabel(toolName: String, suggestions: JSONArray?): String {
        val rule = sessionRules(toolName, suggestions).optJSONObject(0)?.optJSONArray("rules")?.optJSONObject(0)
        val content = rule?.optString("ruleContent")?.trim().orEmpty()
        return if (content.isEmpty()) "Allow ${toolLabel(rule?.optString("toolName")?.ifBlank { null } ?: toolName)} this session"
        else "Allow `${content.take(60)}` this session"
    }

    /** t3code `CLAUDE_KNOWN_TOOL_CLASSIFICATIONS` request kinds. */
    fun requestKind(tool: String): RequestKind = when (norm(tool)) {
        "bash" -> RequestKind.COMMAND
        "edit", "multiedit", "write", "notebookedit" -> RequestKind.FILE_CHANGE
        "read", "glob", "grep", "ls" -> RequestKind.FILE_READ
        else -> if (tool.startsWith("mcp__")) RequestKind.PERMISSION else RequestKind.COMMAND
    }

    /** `mcp__server__tool` → `server · tool`; built-ins unchanged. */
    fun toolLabel(tool: String): String =
        if (tool.startsWith("mcp__")) tool.removePrefix("mcp__").replace("claude_ai_", "").split("__", limit = 2).joinToString(" · ") else tool

    fun pathOf(input: JSONObject): String? = s(input, "file_path", "notebook_path", "path", "filename")

    /** Tool card payload from the tool_use input and (once known) its result. */
    fun toolPayload(name: String, input: JSONObject, output: String?, structured: JSONObject?): ItemPayload {
        val out = output.orEmpty()
        return when (norm(name)) {
            "bash" -> ItemPayload.CommandExecution(s(input, "command").orEmpty(),
                (structured?.let { listOfNotNull(it.optString("stdout").ifEmpty { null }, it.optString("stderr").ifEmpty { null }).joinToString("\n") }?.ifEmpty { null } ?: out).takeLast(OUTPUT_TAIL))
            "edit", "multiedit", "write", "notebookedit" -> {
                val path = pathOf(input).orEmpty()
                val diff = patchDiff(path, structured?.optJSONArray("structuredPatch")) ?: inputDiff(name, input)
                val (add, del) = diff?.let(::stat) ?: (0 to 0)
                ItemPayload.FileChange(path, add, del, diff)
            }
            "glob", "grep" -> ItemPayload.FileSearch(name, s(input, "pattern").orEmpty(),
                strings(structured?.optJSONArray("filenames")).ifEmpty { out.lines().filter { it.isNotBlank() } }.take(200))
            "webfetch" -> ItemPayload.WebSearch(s(input, "url").orEmpty(), listOfNotNull(out.ifBlank { null }?.take(2000)))
            "websearch" -> ItemPayload.WebSearch(s(input, "query").orEmpty(),
                objects(structured?.optJSONArray("results")).flatMap { r -> objects(r.optJSONArray("content")).mapNotNull { it.optString("title").ifBlank { null } } }.take(50))
            "task", "agent" -> ItemPayload.Subagent(s(input, "description") ?: s(input, "subagent_type") ?: name, 0,
                out.replace(AGENT_META, "").trim().ifBlank { null }?.take(4000))
            "todowrite" -> ItemPayload.TodoList(objects(input.optJSONArray("todos")).map { TodoStep(it.optString("content"), it.optString("status")) })
            "read" -> ItemPayload.DynamicTool("Read " + (pathOf(input)?.substringAfterLast('/') ?: ""), input.toString().take(2000), out.take(OUTPUT_TAIL))
            else -> ItemPayload.DynamicTool(toolLabel(name), input.toString().take(2000), out.take(OUTPUT_TAIL))
        }
    }

    /** `tool_use_result.structuredPatch` hunks → unified diff. */
    fun patchDiff(path: String, hunks: JSONArray?): String? {
        val hs = objects(hunks)
        if (hs.isEmpty()) return null
        return buildString {
            append("--- a/").append(path).append("\n+++ b/").append(path).append('\n')
            for (h in hs) {
                append("@@ -").append(h.optInt("oldStart")).append(',').append(h.optInt("oldLines"))
                    .append(" +").append(h.optInt("newStart")).append(',').append(h.optInt("newLines")).append(" @@\n")
                strings(h.optJSONArray("lines")).forEach { append(it).append('\n') }
            }
        }
    }

    /** Proposed change from the tool input (approval card, or no structured result): Edit/MultiEdit old→new, Write = new file. */
    fun inputDiff(name: String, input: JSONObject): String? {
        val path = pathOf(input) ?: return null
        val edits = when (norm(name)) {
            "edit" -> listOf(input.optString("old_string") to input.optString("new_string"))
            "multiedit" -> objects(input.optJSONArray("edits")).map { it.optString("old_string") to it.optString("new_string") }
            "write" -> listOf("" to input.optString("content"))
            "notebookedit" -> listOf("" to input.optString("new_source"))
            else -> return null
        }
        return buildString {
            append(if (norm(name) == "write") "--- /dev/null" else "--- a/$path").append("\n+++ b/").append(path).append('\n')
            for ((old, new) in edits) {
                val o = if (old.isEmpty()) emptyList() else old.lines()
                val n = if (new.isEmpty()) emptyList() else new.lines().take(DIFF_LINES)
                append("@@ -").append(if (o.isEmpty()) 0 else 1).append(',').append(o.size).append(" +1,").append(n.size).append(" @@\n")
                o.forEach { append('-').append(it).append('\n') }
                n.forEach { append('+').append(it).append('\n') }
            }
        }
    }

    fun stat(diff: String): Pair<Int, Int> {
        var add = 0; var del = 0
        for (l in diff.lines()) when {
            l.startsWith("+++") || l.startsWith("---") -> {}
            l.startsWith("+") -> add++
            l.startsWith("-") -> del++
        }
        return add to del
    }

    /** `tool_result.content`: a string or text blocks. */
    fun resultText(c: Any?): String = when (c) {
        is String -> c
        is JSONArray -> objects(c).filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
        else -> ""
    }

    /** "2.1.323" ≥ [MIN_VERSION]. Unknown = allowed. */
    fun versionOk(v: String?): Boolean {
        val have = v?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return true
        val need = MIN_VERSION.split('.').map { it.toInt() }
        for (i in need.indices) { val h = have.getOrNull(i) ?: 0; if (h != need[i]) return h > need[i] }
        return true
    }

    fun norm(tool: String) = tool.lowercase().replace(Regex("[\\s_-]"), "")
    fun s(o: JSONObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } }
    fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    const val OUTPUT_TAIL = 64 * 1024
    private const val DIFF_LINES = 400
}
