package com.zenithblue.nativecode.agent.chat.claude

import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeModelsTest {
    private fun parse(json: String) = ClaudeWire.parseModels(JSONArray(json))

    // What a signed-out (or older) CLI answers: bare aliases, no resolvedModel, no numbers.
    private val BARE = """[{"value":"default","displayName":"Default (recommended)","supportsEffort":true,"supportedEffortLevels":["low","high"]},
        {"value":"opus","displayName":"Opus"},{"value":"fable","displayName":"Fable"},{"value":"sonnet","displayName":"Sonnet"},{"value":"haiku","displayName":"Haiku"}]"""

    @Test fun namesFromFullIds() {
        assertEquals("Opus 5.5", ClaudeModels.nameOf("claude-opus-5-5"))
        assertEquals("Sonnet 5", ClaudeModels.nameOf("claude-sonnet-5"))
        assertEquals("Haiku 4.5", ClaudeModels.nameOf("claude-haiku-4-5-20251001"))
        assertEquals("Fable 5.2", ClaudeModels.nameOf("claude-fable-5-2"))
        assertNull(ClaudeModels.nameOf("opus"))
        assertNull(ClaudeModels.nameOf("default"))
    }

    @Test fun bareAliasesGetVersionsAndPreviousModels() {
        val m = ClaudeModels.merge(parse(BARE))
        val byId = m.associateBy { it.id }
        assertEquals("default", m.first().id)
        assertEquals("Opus 5.5", byId["opus"]!!.name)
        assertEquals("claude-opus-5-5", byId["opus"]!!.detail)
        assertEquals("Sonnet 5.5", byId["sonnet"]!!.name)
        assertEquals("Haiku 5.5", byId["haiku"]!!.name)
        assertEquals("Fable 5.2", byId["fable"]!!.name)
        // previous generations are there, picked by their full id
        assertEquals("Opus 4.8", byId["claude-opus-4-8"]!!.name)
        assertEquals("Sonnet 4.6", byId["claude-sonnet-4-6"]!!.name)
        assertEquals("Haiku 4.5", byId["claude-haiku-4-5-20251001"]!!.name)
        assertEquals(m.size, m.map { it.id }.toSet().size) // no duplicates
        // the live default row keeps its own label and effort levels
        assertEquals("Default (recommended)", m.first().name)
        assertEquals(listOf("low", "high"), m.first().variants)
    }

    @Test fun liveVersionedListWinsAndResolvesAliases() {
        val live = parse("""[{"value":"default","resolvedModel":"claude-opus-5-6","displayName":"Default (recommended)"},
            {"value":"opus","resolvedModel":"claude-opus-5-6","displayName":"Opus"},
            {"value":"claude-opus-4-8","resolvedModel":"claude-opus-4-8","displayName":"Opus 4.8","supportsEffort":true,"supportedEffortLevels":["low","max"]}]""")
        val m = ClaudeModels.merge(live).associateBy { it.id }
        assertEquals("Opus 5.6", m["opus"]!!.name) // from the live resolved id, not the static 5.5
        assertEquals("claude-opus-5-6", m["opus"]!!.detail)
        assertEquals(listOf("low", "max"), m["claude-opus-4-8"]!!.variants) // live effort levels kept
        assertTrue(m.containsKey("claude-sonnet-4-6")) // missing ones are filled from the maintained list
    }

    @Test fun emptyLiveListIsTheMaintainedList() {
        val m = ClaudeModels.merge(emptyList())
        assertEquals("default", m.first().id)
        assertTrue(m.size >= 10)
        assertFalse(m.any { it.name.isBlank() })
    }

    @Test fun cachedBareListIsCompletedAndMergeIsIdempotent() {
        val cached = listOf(ModelInfo("default", "Default (recommended)"), ModelInfo("opus", "Opus"), ModelInfo("haiku", "Haiku"))
        val once = ClaudeModels.merge(cached)
        assertEquals("Opus 5.5", once.first { it.id == "opus" }.name)
        assertEquals(once, ClaudeModels.merge(once))
    }

    @Test fun realInitResponseKeepsVersionedNames() {
        val models = ClaudeWire.parseModels(JSONArray("""[{"value":"opus","resolvedModel":"claude-opus-5-5","displayName":"Opus 5.5"},
            {"value":"claude-opus-4-7","resolvedModel":"claude-opus-4-7","displayName":"Opus 4.7"}]"""))
        assertEquals("claude-opus-5-5", models[0].detail)
        assertNull(models[1].detail) // value == resolved: the id is the subtitle already
        val m = ClaudeModels.merge(models)
        assertEquals(1, m.count { it.id == "opus" })
        assertEquals("Opus 4.7", m.first { it.id == "claude-opus-4-7" }.name)
    }
}
