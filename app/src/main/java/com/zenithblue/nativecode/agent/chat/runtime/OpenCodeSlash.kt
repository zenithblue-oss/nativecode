package com.zenithblue.nativecode.agent.chat.runtime

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.McpEndpoint
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.agent.chat.core.SlashCommand.Kind
import org.json.JSONArray
import org.json.JSONObject

/** opencode 2.3.0 slash commands, skills, MCP config and usage limits (t3code `provider-opencode/src/server/status.ts`, `v2/usageLimits.ts`). */
internal object OpenCodeSlash {
    val COMPACT = SlashCommand("compact", "Compact the conversation context")

    /**
     * `GET /api/command` (`Command.Info{name, description?}`) after t3's injected `compact`, then `GET /api/skill`
     * (`Skill.Info{id, name, description?}`) as [Kind.SKILL] named by id (the mention key). 2.3.0 reports no hints.
     */
    fun commands(commands: JSONArray?, skills: JSONArray?): List<SlashCommand> {
        val out = arrayListOf(COMPACT)
        OpenCodeWire.objects(commands).forEach { c ->
            val name = c.optString("name").trim()
            if (name.isNotEmpty() && out.none { it.name == name }) out += SlashCommand(name, c.optString("description").trim().ifEmpty { null })
        }
        OpenCodeWire.objects(skills).mapNotNull { s ->
            s.optString("id").trim().ifEmpty { null }?.let { SlashCommand(it, s.optString("description").trim().ifEmpty { null }, kind = Kind.SKILL) }
        }.sortedBy { it.name }.distinctBy { it.name }.let { out += it }
        return out
    }

    // t3 SKILL_MENTION_PATTERN with `$` (the composer inserts `$name`): not money like `$5` or `$10k`.
    private val MENTION = Regex("""(?:^|\s)\$(?![0-9][0-9_]*(?:[kKmMbBtT]|[eE][0-9]+)?(?:\s|$))(?=[a-zA-Z0-9:_-]*[a-zA-Z])([a-zA-Z0-9][a-zA-Z0-9:_-]*)(?=\s|$)""")

    /** `$name` skill mentions in a prompt, distinct, in order. */
    fun skillMentions(text: String): List<String> = MENTION.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** Prompt `skills` for the mentions opencode knows (t3 `skillsMentioned`). */
    fun skillAttachments(text: String, known: List<SlashCommand>): JSONArray? {
        val ids = skillMentions(text).filter { m -> known.any { it.kind == Kind.SKILL && it.name == m } }
        return if (ids.isEmpty()) null else JSONArray(ids.map { JSONObject().put("id", it) })
    }

    /** `PUT /api/experimental/mcp/<name>` body: `{config: Mcp.RemoteConfig}`; no OAuth (bearer header). */
    fun mcpBody(e: McpEndpoint): JSONObject = JSONObject().put("config", JSONObject()
        .put("type", "remote").put("url", e.url).put("headers", JSONObject(e.headers)).put("oauth", false))

    /**
     * `GET /api/experimental/usage/limits` `data` (OpenCode Go meters + Console budgets) → quota state; null data =
     * no Console workspace signed in (not reported). Limited when any meter is limited or a budget is spent.
     */
    fun usageLimits(data: JSONObject?): AgentEvent.RateLimit? {
        data ?: return null
        val parts = ArrayList<String>()
        val resets = ArrayList<Long>()
        var limited = false
        data.optJSONObject("go")?.let { go ->
            for ((key, label) in listOf("rolling" to "Go session", "weekly" to "weekly", "monthly" to "monthly")) {
                val m = go.optJSONObject(key) ?: continue
                val full = m.optBoolean("limited")
                limited = limited || full
                parts += "$label ${if (full) 100 else m.optDouble("percent").coerceIn(0.0, 100.0).toInt()}%"
                if (full) resets += m.optLong("resetsAt")
            }
        }
        OpenCodeWire.objects(data.optJSONArray("budgets")).forEach { b ->
            val limit = b.optDouble("limit")
            val spent = b.optDouble("spent")
            val full = limit <= 0 || spent >= limit
            limited = limited || full
            parts += "budget ${"%.2f".format(java.util.Locale.ROOT, spent)}/${"%.2f".format(java.util.Locale.ROOT, limit)}"
            if (full) resets += b.optLong("resetsAt")
        }
        if (parts.isEmpty()) return null
        return AgentEvent.RateLimit(if (limited) "rejected" else "allowed", resets.filter { it > 0 }.minOrNull(), parts.joinToString(" · "))
    }
}
