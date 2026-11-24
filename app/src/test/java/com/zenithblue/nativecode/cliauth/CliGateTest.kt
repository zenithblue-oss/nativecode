package com.zenithblue.nativecode.cliauth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class CliGateTest {
    private fun result(tool: String, v: String?) = CliVersionCheck.Result(tool, tool, v, true)
    /** Runs the gate with a popup that answers [answer] (null = never asked); returns the trace. */
    private fun gate(installed: String?, min: String?, answer: Boolean?): List<String> {
        val t = mutableListOf<String>()
        CliGate.run(installed, min, ask = { cb -> t += "ask"; answer?.let(cb) }, go = { t += "go" }, stop = { t += "stop" })
        return t
    }
    @Test fun upToDateGoesStraightThrough() {
        assertEquals(listOf("go"), gate("2.1.323", "2.1.0", null))
        assertEquals(listOf("go"), gate("2.1.0", "2.1.0", null)) // exactly the minimum
    }
    @Test fun unknownVersionNeverBlocks() {
        assertEquals(listOf("go"), gate(null, "2.1.0", null)) // offline / Free-gate shim / unreadable
        assertEquals(listOf("go"), gate("1.0.0", null, null)) // no minimum for this need
    }
    @Test fun tooOldAsksThenContinuesAfterASuccessfulUpdate() {
        assertEquals(listOf("ask", "go"), gate("2.0.5", "2.1.0", true))
    }
    @Test fun tooOldStopsOnCancelOrFailedUpdate() {
        assertEquals(listOf("ask", "stop"), gate("2.0.5", "2.1.0", false))
        assertEquals(listOf("ask"), gate("2.0.5", "2.1.0", null)) // popup still open: nothing runs yet
    }
    @Test fun versionsCompareNumerically() {
        assertEquals(listOf("ask", "go"), gate("1.0.9", "1.0.50", true))
        assertEquals(listOf("go"), gate("1.0.92", "1.0.50", null))
    }
    @Test fun chatAndLoginHaveTheirOwnMinimum() {
        val agy = result("agy", "1.5.4") // has `agy acp --login` (1.5.0) but not the 1.5.9 chat fixes
        assertTrue(agy.tooOld(CliNeed.CHAT))
        assertFalse(agy.tooOld(CliNeed.LOGIN))
        assertNotNull(CliToolCatalog.byId("agy")!!.minLoginVersion)
    }
    @Test fun noMinimumMeansNoGate() {
        assertNull(result("opencode", "1.18.27").min(CliNeed.LOGIN)) // terminal-guided sign-in
        assertFalse(result("opencode", "1.18.27").tooOld(CliNeed.LOGIN))
        assertTrue(result("opencode", "1.18.27").tooOld(CliNeed.CHAT))
    }
    /** The versions the 1.7.1 rootfs ships (read from its tarball): the gate trips for the ones that cannot chat / sign in. */
    @Test fun rootfsVersionsAreGated() {
        assertTrue(result("agy", "1.1.26").tooOld(CliNeed.CHAT))
        assertTrue(result("agy", "1.1.26").tooOld(CliNeed.LOGIN))
        assertTrue(result("grok", "1.0.13").tooOld(CliNeed.CHAT))
        assertTrue(result("grok", "1.0.13").tooOld(CliNeed.LOGIN))
        assertTrue(result("opencode", "1.18.27").tooOld(CliNeed.CHAT))
        assertFalse(result("opencode", "2.3.0").tooOld(CliNeed.CHAT)) // the update pack
        assertFalse(result("claude", "2.1.263").tooOld(CliNeed.CHAT)) // new enough
        assertFalse(result("claude", "2.1.263").tooOld(CliNeed.LOGIN))
        assertFalse(result("codex", "0.153.2").tooOld(CliNeed.CHAT))
        assertFalse(result("codex", "0.153.2").tooOld(CliNeed.LOGIN))
        assertTrue(result("claude", "2.1.93").tooOld(CliNeed.CHAT))
        assertTrue(result("codex", "0.130.0").tooOld(CliNeed.CHAT)) // goals still experimental
    }
    @Test fun everyChatCliHasAChatMinimum() {
        CliToolCatalog.ALL.filter { it.chat != null }.forEach { assertNotNull(it.id, it.minChatVersion) }
    }
}
