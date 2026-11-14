package com.zenithblue.nativecode.agent.chat.acp

import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.agent.chat.core.TodoStep
import com.zenithblue.nativecode.agent.chat.core.TurnInput
import com.zenithblue.nativecode.agent.chat.core.Usage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** Pure ACP v1 helpers (t3code `provider-acp` runtimeModel.ts / adapter.ts), shared by every profile. */
object AcpWire {
    const val CANCEL = "__cancel"
    private const val OUTPUT_TAIL = 64 * 1024
    private const val PREVIEW = 8_000
    private val SUBAGENT_VERB = Regex("""^Run(ning)? (invoke|start|spawn)_subagent\??$""")
    private const val IMAGE_MAX = 10L shl 20
    private const val TEXT_MAX = 1L shl 20
    private val TEXT_MIME = Regex("""^(text/.*|application/(json|xml|yaml|x-yaml|javascript|x-sh|toml))$""")
    private val EFFORT_ORDER = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    fun str(o: JSONObject?, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> o?.opt(k)?.takeIf { it is String && it.isNotBlank() } as String? }

    /** Display text of one ACP content block (`text`; resource text; else empty). */
    fun blockText(c: JSONObject?): String = when (c?.optString("type")) {
        "text" -> c.optString("text")
        "resource" -> c.optJSONObject("resource")?.optString("text").orEmpty()
        else -> ""
    }

    // ---------------- session setup ----------------

    /** Models: `configOptions[category=model]` first, else `models.availableModels` (t3code AntigravityProvider.ts:73). */
    fun models(session: JSONObject?): Pair<List<ModelInfo>, String?> {
        val opt = modelOption(session)
        if (opt != null) {
            val list = selectChoices(opt).map { (v, n) -> ModelInfo(v, n) }
            return list to opt.optString("currentValue").ifEmpty { null }
        }
        val m = session?.optJSONObject("models") ?: return emptyList<ModelInfo>() to null
        val list = objects(m.optJSONArray("availableModels")).map { ModelInfo(it.optString("modelId"), it.optString("name").ifBlank { it.optString("modelId") }) }
        return list to m.optString("currentModelId").ifEmpty { null }
    }

    fun modelOption(session: JSONObject?): JSONObject? = objects(session?.optJSONArray("configOptions"))
        .firstOrNull { it.optString("category") == "model" || it.optString("id") == "model" }

    fun effortOption(configOptions: JSONArray?): JSONObject? = objects(configOptions).firstOrNull { it.optString("category") == "thought_level" }

    /** Select options, flattening groups: (value, name). */
    fun selectChoices(opt: JSONObject): List<Pair<String, String>> = objects(opt.optJSONArray("options")).flatMap { o ->
        if (o.has("options")) objects(o.optJSONArray("options")) else listOf(o)
    }.mapNotNull { o -> o.optString("value").ifEmpty { null }?.let { it to o.optString("name").ifBlank { it } } }

    /** Effort ids sorted weakest first (grok lists the strongest first). */
    fun sortEfforts(ids: List<String>): List<String> = ids.distinct().sortedBy { EFFORT_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }

    /** grok `initialize._meta.modelState` (available before auth): models with effort levels and context size. */
    fun xaiModels(state: JSONObject?): Pair<List<ModelInfo>, String?> {
        state ?: return emptyList<ModelInfo>() to null
        val list = objects(state.optJSONArray("availableModels")).map { m ->
            val meta = m.optJSONObject("_meta")
            val efforts = objects(meta?.optJSONArray("reasoningEfforts")).mapNotNull { e -> (e.optString("value").ifEmpty { e.optString("id") }).ifEmpty { null } }
            ModelInfo(m.optString("modelId"), m.optString("name").ifBlank { m.optString("modelId") }, variants = sortEfforts(efforts),
                contextLimit = meta?.optLong("totalContextTokens")?.takeIf { it > 0 })
        }
        return list to state.optString("currentModelId").ifEmpty { null }
    }

    fun xaiDefaultEffort(state: JSONObject?, model: String?): String? = objects(state?.optJSONArray("availableModels"))
        .firstOrNull { it.optString("modelId") == model }?.optJSONObject("_meta")?.let { meta ->
            objects(meta.optJSONArray("reasoningEfforts")).firstOrNull { it.optBoolean("default") }?.let { it.optString("value").ifEmpty { it.optString("id") } }
                ?: meta.optString("reasoningEffort").ifEmpty { null }
        }

    fun commands(a: JSONArray?, drop: Set<String> = emptySet()): List<SlashCommand> = objects(a)
        .mapNotNull { c ->
            c.optString("name").trim().removePrefix("/").ifEmpty { null }?.takeIf { it !in drop }?.let {
                SlashCommand(it, c.optString("description").ifBlank { null }, c.optJSONObject("input")?.optString("hint")?.ifBlank { null })
            }
        }
        .distinctBy { it.name }

    /**
     * M7 hook: the app's NC tools MCP server as an ACP `mcpServers` http entry, only when the agent advertises
     * `mcpCapabilities.http` (R6 4.2); else none.
     */
    fun mcpServers(agentCaps: JSONObject?, nc: com.zenithblue.nativecode.agent.chat.core.McpEndpoint?): JSONArray {
        val out = JSONArray()
        if (nc == null || agentCaps?.optJSONObject("mcpCapabilities")?.optBoolean("http") != true) return out
        val headers = JSONArray().apply { nc.headers.forEach { (k, v) -> put(JSONObject().put("name", k).put("value", v)) } }
        out.put(JSONObject().put("type", "http").put("name", nc.name).put("url", nc.url).put("headers", headers))
        return out
    }

    /** Protocol-log copy of a line without bearer tokens. */
    fun redact(line: String): String = line.replace(Regex("""Bearer [^"\s]+"""), "Bearer <redacted>")

    // ---------------- prompt ----------------

    /**
     * Prompt blocks (t3code AntigravityAcpSupport.ts:300): text first; images inline when the agent takes them;
     * small text files as embedded `resource`, PDFs as `resource_link` when [embed]; anything else = path line.
     */
    fun promptBlocks(turn: TurnInput, images: Boolean, embed: Boolean, read: (String) -> ByteArray? = ::readFile): JSONArray {
        val extra = JSONArray()
        val lines = ArrayList<String>()
        for (a in turn.attachments) {
            val bytes by lazy { a.hostPath?.let(read) }
            when {
                a.isImage && images && a.sizeBytes <= IMAGE_MAX && bytes != null ->
                    extra.put(JSONObject().put("type", "image").put("mimeType", a.mime).put("data", Base64.getEncoder().encodeToString(bytes)))
                embed && a.guestPath != null && a.mime == "application/pdf" ->
                    extra.put(JSONObject().put("type", "resource_link").put("uri", fileUri(a.guestPath)).put("name", a.name).put("mimeType", a.mime).put("size", a.sizeBytes))
                embed && a.guestPath != null && TEXT_MIME.matches(a.mime) && a.sizeBytes <= TEXT_MAX && bytes?.let(::utf8) != null ->
                    extra.put(JSONObject().put("type", "resource").put("resource",
                        JSONObject().put("uri", fileUri(a.guestPath)).put("mimeType", a.mime).put("text", utf8(bytes!!))))
                else -> lines += pathLine(a)
            }
        }
        val text = (listOf(turn.text.trim()) + lines).filter { it.isNotEmpty() }.joinToString("\n\n")
        val out = JSONArray()
        if (text.isNotEmpty()) out.put(JSONObject().put("type", "text").put("text", text))
        for (i in 0 until extra.length()) out.put(extra.get(i))
        return out
    }

    fun pathLine(a: ChatAttachment): String {
        val kind = if (a.isImage) "image" else "file"
        return if (a.guestPath == null) "[Attached $kind \"${a.name}\" is unavailable: could not resolve a saved path.]"
        else "[Attached $kind \"${a.name}\" is saved at: ${a.guestPath}]"
    }

    fun fileUri(path: String): String = java.net.URI("file", "", path, null).toASCIIString()

    private fun readFile(p: String): ByteArray? = runCatching { File(p).readBytes() }.getOrNull()

    private fun utf8(b: ByteArray): String? {
        if (b.any { it == 0.toByte() }) return null
        return runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString() }.getOrNull()
    }

    // ---------------- turn end ----------------

    /** stopReason → run status (t3code adapter.ts:8645). `refusal` = agy quota exhausted (AntigravityTurnOutcome.ts:114). */
    fun stop(reason: String?, interrupting: Boolean, refusalIsQuota: Boolean): Pair<RunStatus, Failure?> = when (reason) {
        "cancelled" -> RunStatus.INTERRUPTED to null
        "refusal" -> if (refusalIsQuota) RunStatus.FAILED to Failure(FailureClass.USAGE_LIMIT, "You have reached your quota for this period.") else RunStatus.COMPLETED to null
        "rate_limit" -> RunStatus.FAILED to Failure(FailureClass.USAGE_LIMIT, "Usage limit reached. Try again later.")
        "error" -> RunStatus.FAILED to Failure(FailureClass.PROVIDER_ERROR, "The agent stopped this turn because of an error.")
        else -> (if (interrupting) RunStatus.INTERRUPTED else RunStatus.COMPLETED) to null
    }

    fun failureOf(e: Throwable, signIn: String): Failure = when {
        e is AcpError && e.authRequired -> Failure(FailureClass.PERMISSION_ERROR, signIn)
        e is AcpError && e.code == AcpError.RATE_LIMITED -> Failure(FailureClass.USAGE_LIMIT, e.message ?: "Usage limit reached")
        e is AcpError -> Failure(FailureClass.PROVIDER_ERROR, ((e.data as? JSONObject)?.optString("message")?.ifBlank { null } ?: e.message ?: "agent error"), details = "code ${e.code}")
        else -> Failure(FailureClass.TRANSPORT_ERROR, e.message ?: "agent connection failed")
    }

    /**
     * Turn tokens from the prompt result: ACP `usage` (agy) or grok `_meta.usage`. Gemini counts cached input and
     * thought tokens apart (t3code adapter.ts:298); grok `inputTokens` already includes cache and cost is in ticks.
     */
    fun promptUsage(result: JSONObject?, base: Usage): Usage {
        val u = result?.optJSONObject("usage") ?: result?.optJSONObject("_meta")?.optJSONObject("usage") ?: return base
        return usageOf(u, base)
    }

    fun usageOf(u: JSONObject, base: Usage): Usage {
        val ticks = u.optLong("costUsdTicks", -1)
        val xai = ticks >= 0 || u.has("cacheCreationTokens")
        val cacheRead = u.optLong("cachedReadTokens", u.optLong("cacheReadTokens"))
        val cacheWrite = u.optLong("cachedWriteTokens", u.optLong("cacheCreationTokens"))
        val thought = u.optLong("thoughtTokens", u.optLong("reasoningTokens"))
        val input = u.optLong("inputTokens") - if (xai) cacheRead + cacheWrite else 0
        return base.copy(
            inputTokens = input.coerceAtLeast(0),
            outputTokens = u.optLong("outputTokens"),
            cacheReadTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
            reasoningTokens = thought,
            costUsd = if (ticks >= 0) ticks / 1e10 else base.costUsd,
        )
    }

    // ---------------- tool calls ----------------

    /** One tool call, merged across `tool_call` / `tool_call_update` (a missing field keeps its value). */
    class Tool(val id: String) {
        var kind: String? = null
        var title: String? = null
        var status: String = "pending"
        var rawInput: JSONObject? = null
        var rawOutput: Any? = null
        var content: JSONArray? = null
        var locations: JSONArray? = null
        var meta: JSONObject? = null

        fun merge(u: JSONObject) {
            if (u.has("kind")) kind = u.optString("kind").ifEmpty { null }
            if (u.has("title")) title = u.optString("title").ifEmpty { null }
            if (u.has("status")) status = u.optString("status").ifEmpty { status }
            if (u.has("rawInput")) rawInput = u.optJSONObject("rawInput") ?: u.optString("rawInput").takeIf { it.isNotEmpty() }?.let { JSONObject().put("input", it) }
            if (u.has("rawOutput")) rawOutput = u.opt("rawOutput")
            if (u.has("content")) content = u.optJSONArray("content")
            if (u.has("locations")) locations = u.optJSONArray("locations")
            if (u.has("_meta")) meta = u.optJSONObject("_meta")
        }

        val command: String? get() = rawInput?.let { i ->
            str(i, "CommandLine", "command_line", "commandLine", "cmd") ?: (i.opt("command") as? JSONArray)?.let { a -> (0 until a.length()).joinToString(" ") { a.optString(it) } }
                ?: str(i, "command")
        } ?: title?.takeIf { kind == "execute" && it != "terminal" && it != "tool call" }

        val path: String? get() = objects(locations).firstNotNullOfOrNull { it.optString("path").ifEmpty { null } }
            ?: str(rawInput, "path", "filePath", "file_path", "TargetFile", "AbsolutePath", "url", "Url", "query", "Query", "pattern")
            ?: objects(content).firstNotNullOfOrNull { if (it.optString("type") == "diff") it.optString("path").ifEmpty { null } else null }

        val output: String get() {
            val ro = rawOutput
            val fromRaw = when (ro) {
                is String -> ro
                is JSONObject -> str(ro, "combinedOutput", "combined_output", "output", "stdout", "Output", "result", "content")
                    ?: (ro.optJSONObject("output")?.let { str(it, "OkayOutput", "Error") }) ?: ""
                else -> ""
            }
            if (fromRaw.isNotEmpty()) return fromRaw
            return objects(content).filter { it.optString("type") == "content" }.joinToString("\n") { blockText(it.optJSONObject("content")) }
        }

        val exitCode: Int? get() = (rawOutput as? JSONObject)?.let { o ->
            listOf("exitCode", "exit_code").firstNotNullOfOrNull { k -> o.opt(k) as? Number }?.toInt()
        }

        val isSubagent: Boolean get() = meta?.optBoolean("is_mcp_tool_call") != true && (title.orEmpty().let { t ->
            t.contains("invoke_subagent") || t.contains("start_subagent") || t.contains("spawn_subagent")
        } || xaiTool?.optString("name") == "spawn_subagent" || rawInput?.has("subagent_type") == true || rawInput?.has("Subagents") == true)

        val xaiTool: JSONObject? get() = meta?.optJSONObject("x.ai/tool")
    }

    /** Tool → timeline payload (t3code adapter.ts:4290 projection order). */
    fun toolPayload(t: Tool): ItemPayload {
        val out = t.output
        val title = t.title ?: t.kind ?: "Tool"
        if (t.id.startsWith("acp-compaction:")) return ItemPayload.Compaction(null)
        val diffs = objects(t.content).filter { it.optString("type") == "diff" }
        if (t.isSubagent) {
            val roles = objects(t.rawInput?.optJSONArray("Subagents")).mapNotNull { it.optString("Role").ifEmpty { null } }
            val name = roles.joinToString(", ").ifEmpty { str(t.rawInput, "description", "subagent_type") ?: title.replace(SUBAGENT_VERB, "Subagent") }
            return ItemPayload.Subagent(name, roles.size.coerceAtLeast(if (roles.isEmpty()) 0 else 1), out.ifBlank { null }?.take(PREVIEW))
        }
        val xai = t.xaiTool
        if (t.meta?.optBoolean("is_mcp_tool_call") == true || xai?.optString("name") == "use_tool") {
            val name = str(t.rawInput, "tool_name")?.replace("__", ".") ?: title
            return ItemPayload.DynamicTool(name, t.rawInput?.toString().orEmpty(), out.take(PREVIEW))
        }
        val kind = t.kind ?: xai?.optString("kind")?.ifEmpty { null } ?: if (t.command != null && t.rawInput != null && str(t.rawInput, "CommandLine", "command_line", "commandLine", "command") != null) "execute" else null
        return when {
            diffs.isNotEmpty() || kind == "edit" || kind == "delete" || kind == "move" -> {
                val path = t.path ?: title
                val diff = diffs.joinToString("\n") { d ->
                    unifiedDiff(d.optString("path").ifEmpty { path }, if (d.isNull("oldText")) null else d.optString("oldText"), d.optString("newText"))
                }.ifBlank { snippetDiff(path, t.rawInput) }
                val (add, del) = diffStat(diff.orEmpty())
                ItemPayload.FileChange(path, add, del, diff?.ifBlank { null })
            }
            kind == "execute" || (t.rawOutput as? JSONObject)?.optString("type") == "bash" || str(t.rawInput, "variant") == "monitor" ->
                // agy prefixes "Created At… Command exited with code N.\n\nCommand output:\n"
                ItemPayload.CommandExecution(t.command ?: title, out.substringAfter("Command output:\n", out).takeLast(OUTPUT_TAIL), t.exitCode)
            kind == "search" -> {
                val q = t.path ?: title
                if (str(t.rawInput, "variant") in setOf("xsearch", "websearch") || (t.rawOutput as? JSONObject)?.optJSONObject("action")?.optString("type") == "search")
                    ItemPayload.WebSearch(q, listOfNotNull(out.ifBlank { null }?.take(PREVIEW)))
                else ItemPayload.FileSearch(title, q, out.lines().filter { it.isNotBlank() }.take(200))
            }
            kind == "fetch" -> ItemPayload.WebSearch(t.path ?: title, listOfNotNull(out.ifBlank { null }?.take(PREVIEW)))
            // agy `ask_question`: headless ACP prints the question as text and waits for the next message
            t.rawInput?.has("questions") == true -> ItemPayload.DynamicTool(
                "Question: " + (objects(t.rawInput?.optJSONArray("questions")).firstOrNull()?.optString("question")?.ifBlank { null } ?: title),
                t.rawInput.toString(), out.take(PREVIEW))
            else -> ItemPayload.DynamicTool(title, t.rawInput?.toString().orEmpty(), out.take(PREVIEW))
        }
    }

    /** agy `replace_file_content` / `multi_replace_file_content` without a diff block: snippet hunks, line unknown. */
    private fun snippetDiff(path: String, input: JSONObject?): String? {
        input ?: return null
        val chunks = objects(input.optJSONArray("ReplacementChunks") ?: input.optJSONArray("replacement_chunks")).ifEmpty { listOf(input) }
        val parts = chunks.mapNotNull { c ->
            val old = str(c, "TargetContent", "target_content")
            val new = c.opt("ReplacementContent") as? String ?: c.opt("replacement_content") as? String ?: str(c, "CodeContent", "code_content")
            if (old == null && new == null) null
            else "@@ snippet, line unknown @@\n" + (old?.lines()?.joinToString("\n") { "-$it" }?.plus("\n") ?: "") + (new?.lines()?.joinToString("\n") { "+$it" } ?: "")
        }
        return if (parts.isEmpty()) null else "--- a/$path\n+++ b/$path\n" + parts.joinToString("\n")
    }

    /** Minimal unified diff: one hunk around the changed middle (common prefix/suffix trimmed), 3 lines of context. */
    fun unifiedDiff(path: String, old: String?, new: String): String {
        val a = old?.let { if (it.isEmpty()) emptyList() else it.removeSuffix("\n").split("\n") } ?: emptyList()
        val b = if (new.isEmpty()) emptyList() else new.removeSuffix("\n").split("\n")
        var p = 0
        while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0
        while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        if (p == a.size && p == b.size) return ""
        val c0 = maxOf(0, p - 3)
        val c1 = minOf(s, 3)
        val aLen = (a.size - s - c0) + c1
        val bLen = (b.size - s - c0) + c1
        val sb = StringBuilder()
        sb.append("--- ").append(if (old == null) "/dev/null" else "a/$path").append('\n')
        sb.append("+++ b/").append(path).append('\n')
        sb.append("@@ -").append(if (aLen == 0) c0 else c0 + 1).append(',').append(aLen)
            .append(" +").append(if (bLen == 0) c0 else c0 + 1).append(',').append(bLen).append(" @@\n")
        for (i in c0 until p) sb.append(' ').append(a[i]).append('\n')
        for (i in p until a.size - s) sb.append('-').append(a[i]).append('\n')
        for (i in p until b.size - s) sb.append('+').append(b[i]).append('\n')
        for (i in b.size - s until b.size - s + c1) sb.append(' ').append(b[i]).append('\n')
        return sb.toString().trimEnd('\n')
    }

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

    fun todo(entries: JSONArray?): ItemPayload.TodoList = ItemPayload.TodoList(objects(entries).mapIndexed { i, e ->
        TodoStep(e.optString("content").trim().ifEmpty { "Step ${i + 1}" }, when (e.optString("status")) {
            "in_progress" -> "in_progress"
            "completed", "cancelled" -> "completed"
            else -> "pending"
        })
    })
}
