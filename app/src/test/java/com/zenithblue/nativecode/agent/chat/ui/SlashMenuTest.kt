package com.zenithblue.nativecode.agent.chat.ui

import com.zenithblue.nativecode.agent.chat.ChatReducer
import com.zenithblue.nativecode.agent.chat.ModelCache
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.agent.chat.core.SlashCommand.Kind
import com.zenithblue.nativecode.agent.chat.ui.SlashMenu.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlashMenuTest {
    private val provider = listOf(
        SlashCommand("compact", "Compact the conversation context"),
        SlashCommand("init", "create/update AGENTS.md"),
        SlashCommand("review", "review changes", "[commit|branch|pr]"),
        SlashCommand("clear", "provider clear"),
        SlashCommand("release-notes", "command shadowed by the skill"),
        SlashCommand("android-build", "Build and install the APK", kind = Kind.SKILL),
        SlashCommand("release-notes", "Write release notes", kind = Kind.SKILL),
    )
    private val all = SlashMenu.Ctx(side = true, fresh = true)
    private fun names(ctx: SlashMenu.Ctx, text: String, bare: Boolean = true) =
        SlashMenu.items(provider, ctx, SlashMenu.trigger(text)!!, bare).map { it.name }

    @Test fun mergeOrderAndDedupe() {
        // built-ins, provider (skill-named + provider /clear dropped), skills last.
        assertEquals(listOf("model", "side", "btw", "clear", "usage-limits", "compact", "init", "review", "android-build", "release-notes"), names(all, "/"))
        // no fork, nothing to clear: /side /btw /clear hidden, the provider's /clear comes back.
        assertEquals(listOf("model", "usage-limits", "compact", "init", "review", "clear", "android-build", "release-notes"),
            names(SlashMenu.Ctx(side = false, fresh = false), "/"))
        // compact only when it would be the whole message.
        assertEquals(false, "compact" in names(all, "/", bare = false))
    }

    @Test fun filterAndRank() {
        // name prefix (command before skill), then description word ("response"), then description substring (built-in first).
        assertEquals(listOf("review", "release-notes", "model", "clear", "init"), names(all, "/re"))
        assertEquals("init", names(all, "/ini").first())
        assertEquals(listOf("android-build", "release-notes"), names(all, "/skill"))
        assertEquals(listOf("android-build"), names(all, "/skill:andr"))
        assertEquals("usage-limits", names(all, "/limits").first()) // word boundary after '-'
        assertEquals(listOf("model"), names(all, "/mdl")) // subsequence
        assertEquals(emptyList<String>(), names(all, "/zzz"))
    }

    @Test fun laterLineOffersOnlyModelAndSkills() {
        assertEquals(listOf("model", "android-build", "release-notes"), names(all, "fix this\n/"))
        assertNull(SlashMenu.trigger("/review now"))
        assertNull(SlashMenu.trigger("hello"))
        assertEquals(SlashMenu.Trigger("rev", atStart = true), SlashMenu.trigger("/rev"))
    }

    @Test fun insertAndGhostHint() {
        val review = provider[2]
        assertEquals("/review ", SlashMenu.insert("/rev", review))
        assertEquals("line one\n/review ", SlashMenu.insert("line one\n/rev", review))
        assertEquals("\$android-build ", SlashMenu.insert("/andr", provider[5]))
        assertEquals("[commit|branch|pr]", SlashMenu.ghostHint("/review ", provider))
        assertNull(SlashMenu.ghostHint("/review main", provider))
        assertNull(SlashMenu.ghostHint("/init ", provider))
        assertEquals("<question>", SlashMenu.ghostHint("/side ", SlashMenu.builtins(all)))
    }

    @Test fun parseBuiltins() {
        assertEquals(Action.Clear, SlashMenu.parse(" /clear "))
        assertEquals(Action.UsageLimits, SlashMenu.parse("/usage-limits"))
        assertEquals(Action.Model(null), SlashMenu.parse("/model"))
        assertEquals(Action.Model("gpt nano"), SlashMenu.parse("/model gpt nano"))
        assertEquals(Action.Side("why is it slow?"), SlashMenu.parse("/btw why is it slow?"))
        assertEquals(Action.Side(""), SlashMenu.parse("/side"))
        assertNull(SlashMenu.parse("/clear the cache"))
        assertNull(SlashMenu.parse("/review"))
        assertNull(SlashMenu.parse("/models"))
    }

    @Test fun modelArgument() {
        val models = listOf(ModelInfo("opencode/big-pickle", "Big Pickle"), ModelInfo("opencode/gpt-5-nano", "GPT-5 Nano"), ModelInfo("openai/gpt-5", "GPT-5"))
        assertEquals("opencode/big-pickle", SlashMenu.matchModel(models, "big-pickle")?.id)
        assertEquals("openai/gpt-5", SlashMenu.matchModel(models, "GPT-5")?.id) // exact name beats substring
        assertEquals("opencode/gpt-5-nano", SlashMenu.matchModel(models, "nano")?.id)
        assertNull(SlashMenu.matchModel(models, "opencode")) // ambiguous -> picker
    }

    @Test fun clearForgetsTheNativeSession() {
        val r = ChatReducer(ChatThread("t", null, "/home/flux", "opencode", "x", nativeId = "ses_1", createdAt = 0, updatedAt = 0), 0, 0)
        r.freshSession()
        assertNull(r.thread.nativeId)
        assertEquals(1, r.state.liveItems.size) // the "New session" notice row
    }

    @Test fun commandsCachedPerTool() {
        val e = ModelCache.Entry(listOf(ModelInfo("m", "M")), "m", null, provider)
        assertEquals(e, ModelCache.decode(ModelCache.encode(e)))
    }
}
