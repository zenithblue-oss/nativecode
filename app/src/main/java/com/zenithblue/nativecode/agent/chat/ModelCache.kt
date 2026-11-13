package com.zenithblue.nativecode.agent.chat

import android.content.Context
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.RuntimeCaps
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Last-known model list + caps per tool (`filesDir/agent/models-<tool>.json`), so a new thread's model picker
 * works before its first message (t3code lists provider models before a session exists). Written whenever a
 * session reports a non-empty `SessionState.models`; a live list always replaces the cached one.
 */
object ModelCache {
    data class Entry(val models: List<ModelInfo>, val defaultModel: String?, val caps: RuntimeCaps?)

    private fun file(ctx: Context, tool: String) = File(ctx.filesDir, "agent/models-${tool.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    fun load(ctx: Context, tool: String): Entry? = runCatching { decode(file(ctx, tool).readText()) }.getOrNull()

    fun save(ctx: Context, tool: String, e: Entry) {
        if (e.models.isEmpty()) return
        runCatching {
            val f = file(ctx, tool)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(encode(e))
            tmp.renameTo(f)
        }
    }

    fun encode(e: Entry): String = JSONObject()
        .put("default", e.defaultModel)
        .put("models", JSONArray(e.models.map { m ->
            JSONObject().put("id", m.id).put("name", m.name).put("provider", m.providerId).put("free", m.free)
                .put("variants", JSONArray(m.variants)).put("ctx", m.contextLimit)
        }))
        .put("caps", e.caps?.let { c ->
            JSONObject().put("steer", c.steer).put("images", c.images).put("pdf", c.pdf).put("userInput", c.userInput)
                .put("planMode", c.planMode).put("effort", c.effort).put("slash", c.slashCommands).put("resume", c.resume)
                .put("modes", JSONArray(c.modes.map { it.wire }))
        })
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
            )
        }
        if (models.isEmpty()) return null
        val caps = o.optJSONObject("caps")?.let { c ->
            val modes = c.optJSONArray("modes")
            RuntimeCaps(
                steer = c.optBoolean("steer"), images = c.optBoolean("images"), pdf = c.optBoolean("pdf"),
                userInput = c.optBoolean("userInput"), planMode = c.optBoolean("planMode"), effort = c.optBoolean("effort"),
                slashCommands = c.optBoolean("slash"), resume = c.optBoolean("resume"),
                modes = (0 until (modes?.length() ?: 0)).map { RuntimeMode.of(modes!!.getString(it)) }.ifEmpty { listOf(RuntimeMode.APPROVAL_REQUIRED) },
            )
        }
        return Entry(models, o.optString("default").ifEmpty { null }, caps)
    }
}
