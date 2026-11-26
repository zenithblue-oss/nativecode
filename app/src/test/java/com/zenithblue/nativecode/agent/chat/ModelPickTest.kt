package com.zenithblue.nativecode.agent.chat

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.RuntimeCaps
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** New-thread model picker: cached list before a session, pick persisted on the thread, applied at session create. */
class ModelPickTest {
    private val thread = ChatThread("t", null, "/home/flux", "opencode", ChatReducer.DEFAULT_TITLE, createdAt = 0, updatedAt = 0)
    private val caps = RuntimeCaps(steer = true, images = true, effort = true, planMode = true, modes = RuntimeMode.entries.toList())
    private val cached = ModelCache.Entry(
        listOf(ModelInfo("opencode/big-pickle", "Big Pickle", "opencode", free = true, contextLimit = 200_000),
            ModelInfo("opencode/gpt-5-nano", "GPT-5 Nano", "opencode", free = true, variants = listOf("low", "medium", "high"))),
        "opencode/big-pickle", caps,
    )

    @Test fun cacheRoundTrip() {
        assertEquals(cached, ModelCache.decode(ModelCache.encode(cached)))
        val bare = ModelCache.Entry(cached.models, null, null)
        assertEquals(bare, ModelCache.decode(ModelCache.encode(bare)))
        assertNull(ModelCache.decode("""{"models":[]}"""))
    }

    @Test fun cacheStaleWhenCliVersionChanged() {
        val e = cached.copy(cli = "1.5.8")
        assertEquals(e, ModelCache.decode(ModelCache.encode(e)))
        assertTrue(!ModelCache.stale(e, null)) // version not probed yet: keep
        assertTrue(!ModelCache.stale(e, "1.5.8"))
        assertTrue(ModelCache.stale(e, "1.5.16")) // agy 1.5.8 -> 1.5.16 added Gemini 4 Hydrogen
        assertTrue(ModelCache.stale(cached, "1.5.16")) // unstamped legacy entry
    }

    @Test fun unseedDropsStaleSeed() {
        val r = ChatReducer(thread, 0, 0)
        r.seedModels(cached)
        r.unseedModels()
        assertTrue(r.state.models.isEmpty())
    }

    @Test fun seededPickerBeforeSessionAndLiveListWins() {
        val r = ChatReducer(thread, 0, 0)
        assertTrue(r.state.models.isEmpty())
        r.seedModels(cached)
        assertEquals(cached.models, r.state.models)
        assertEquals("opencode/big-pickle", r.state.defaultModel)
        assertEquals(true, r.state.caps?.effort)
        assertEquals(LivePhase.IDLE, r.state.phase) // no session spawned by seeding

        val fresh = listOf(ModelInfo("opencode/minimax-free", "MiniMax", "opencode", free = true))
        r.reduce(AgentEvent.SessionState(AgentEvent.Phase.READY, models = fresh))
        assertEquals(fresh, r.state.models)
        r.seedModels(cached) // never overrides a live list
        assertEquals(fresh, r.state.models)
    }

    @Test fun pickBeforeFirstSendReachesSessionConfig() {
        val r = ChatReducer(thread, 0, 0)
        r.seedModels(cached)
        r.configure(ConfigChange(model = "opencode/gpt-5-nano"))
        r.configure(ConfigChange(effort = "high"))
        val cfg = r.sessionConfig()
        assertEquals("opencode/gpt-5-nano", cfg.model)
        assertEquals("high", cfg.effort)
        // A READY that reports no current model keeps the pick.
        r.reduce(AgentEvent.SessionState(AgentEvent.Phase.READY, models = cached.models, defaultModel = "opencode/big-pickle"))
        assertEquals("opencode/gpt-5-nano", r.thread.model)
    }
}
