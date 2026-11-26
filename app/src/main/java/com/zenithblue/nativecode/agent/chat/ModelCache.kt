package com.zenithblue.nativecode.agent.chat

import android.content.Context
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.RuntimeCaps
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Last-known model list + caps per tool (`filesDir/agent/models-<tool>.json`), so a new thread's model picker
 * works before its first message (t3code lists provider models before a session exists). Written whenever a
 * session reports a non-empty `SessionState.models`; a live list always replaces the cached one. An entry stamped with another
 * CLI version than the installed one (a CLI update changes its model list) is dropped on load: the picker re-lists live.
 */
object ModelCache {
    /** [commands] = last-known provider commands + skills, so the slash popup works before the session starts. */
    data class Entry(val models: List<ModelInfo>, val defaultModel: String?, val caps: RuntimeCaps?, val commands: List<SlashCommand> = emptyList(), val cli: String? = null)

    private fun file(ctx: Context, tool: String) = File(ctx.filesDir, "agent/models-${tool.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    /** [installed] unknown (version not probed yet) = keep; an unstamped (pre-stamp) entry counts as stale once the version is known. */
    fun stale(e: Entry, installed: String?): Boolean = installed != null && e.cli != installed

    /** claude: a list cached from a signed-out or older CLI (bare "Opus", no previous models) is completed on load. */
    fun load(ctx: Context, tool: String): Entry? = runCatching { decode(file(ctx, tool).readText()) }.getOrNull()
        ?.takeUnless { stale(it, com.zenithblue.nativecode.cliauth.CliVersionCheck.cached(tool)?.installed) }
        ?.let { e -> if (tool.startsWith("claude")) e.copy(models = com.zenithblue.nativecode.agent.chat.claude.ClaudeModels.merge(e.models)) else e }

    fun save(ctx: Context, tool: String, e: Entry) {
        if (e.models.isEmpty()) return
        runCatching {
            val f = file(ctx, tool)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(encode(e.copy(cli = e.cli ?: com.zenithblue.nativecode.cliauth.CliVersionCheck.cached(tool)?.installed)))
            tmp.renameTo(f)
        }
    }

    fun encode(e: Entry): String = JSONObject()
        .put("default", e.defaultModel)
        .put("cli", e.cli)
        .put("models", JSONArray(e.models.map { m ->
            JSONObject().put("id", m.id).put("name", m.name).put("provider", m.providerId).put("free", m.free)
                .put("variants", JSONArray(m.variants)).put("ctx", m.contextLimit).put("detail", m.detail)
        }))
        .put("caps", e.caps?.let { c ->
            JSONObject().put("steer", c.steer).put("images", c.images).put("pdf", c.pdf).put("userInput", c.userInput)
                .put("planMode", c.planMode).put("effort", c.effort).put("slash", c.slashCommands).put("resume", c.resume)
                .put("fork", c.fork).put("modes", JSONArray(c.modes.map { it.wire }))
        })
        .put("commands", JSONArray(e.commands.map { c ->
            JSONObject().put("name", c.name).put("description", c.description).put("hint", c.argumentHint).put("kind", c.kind.name)
        }))
        .toString()

    fun decode(s: String): Entry? {
        val o = JSONObject(s)
        val arr = o.optJSONArray("models") ?: return null
        val models = (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            val v = m.optJSONArray("variants")
            ModelInfo(
                id = m.optString("id").ifEmpty { return@mapNotNull null }, name = m.optString("name"),
                providerId = m.optString("provider").ifEmpty { null }, free = m.optBoolean("free"),
                variants = (0 until (v?.length() ?: 0)).map { v!!.getString(it) },
                contextLimit = if (m.has("ctx")) m.optLong("ctx") else null,
                detail = m.optString("detail").ifEmpty { null },
            )
        }
        if (models.isEmpty()) return null
        val caps = o.optJSONObject("caps")?.let { c ->
            val modes = c.optJSONArray("modes")
            RuntimeCaps(
                steer = c.optBoolean("steer"), images = c.optBoolean("images"), pdf = c.optBoolean("pdf"),
                userInput = c.optBoolean("userInput"), planMode = c.optBoolean("planMode"), effort = c.optBoolean("effort"),
                slashCommands = c.optBoolean("slash"), resume = c.optBoolean("resume"), fork = c.optBoolean("fork"),
                modes = (0 until (modes?.length() ?: 0)).map { RuntimeMode.of(modes!!.getString(it)) }.ifEmpty { listOf(RuntimeMode.APPROVAL_REQUIRED) },
            )
        }
        val cmds = o.optJSONArray("commands")
        val commands = (0 until (cmds?.length() ?: 0)).mapNotNull { i ->
            val c = cmds!!.optJSONObject(i) ?: return@mapNotNull null
            SlashCommand(c.optString("name").ifEmpty { return@mapNotNull null }, c.optString("description").ifEmpty { null },
                c.optString("hint").ifEmpty { null }, runCatching { SlashCommand.Kind.valueOf(c.optString("kind")) }.getOrDefault(SlashCommand.Kind.PROVIDER))
        }
        return Entry(models, o.optString("default").ifEmpty { null }, caps, commands, o.optString("cli").ifEmpty { null })
    }
}
