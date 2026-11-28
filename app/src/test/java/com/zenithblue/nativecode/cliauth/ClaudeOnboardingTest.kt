package com.zenithblue.nativecode.cliauth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClaudeOnboardingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun home(signedIn: Boolean, cfg: String?): File = tmp.newFolder().also { h ->
        if (signedIn) File(h, ".claude").apply { mkdirs() }.resolve(".credentials.json")
            .writeText("""{"claudeAiOauth":{"accessToken":"x"}}""")
        if (cfg != null) File(h, ".claude.json").writeText(cfg)
    }

    @Test fun signedInWithoutOnboardingGetsItKeepingOtherKeys() {
        val h = home(true, """{"userID":"u","numStartups":3}""")
        assertTrue(ClaudeOnboarding.ensureDone(h))
        val j = JSONObject(File(h, ".claude.json").readText())
        assertTrue(j.getBoolean("hasCompletedOnboarding"))
        assertEquals("u", j.getString("userID"))
        assertEquals(3, j.getInt("numStartups"))
        assertFalse(ClaudeOnboarding.ensureDone(h))
    }

    @Test fun missingConfigIsCreated() {
        val h = home(true, null)
        assertTrue(ClaudeOnboarding.ensureDone(h))
        assertTrue(JSONObject(File(h, ".claude.json").readText()).getBoolean("hasCompletedOnboarding"))
    }

    @Test fun signedOutIsLeftAlone() {
        val h = home(false, null)
        assertFalse(ClaudeOnboarding.ensureDone(h))
        assertFalse(File(h, ".claude.json").exists())
    }

    @Test fun brokenConfigIsLeftAlone() {
        val h = home(true, "{not json")
        assertFalse(ClaudeOnboarding.ensureDone(h))
        assertEquals("{not json", File(h, ".claude.json").readText())
    }
}
