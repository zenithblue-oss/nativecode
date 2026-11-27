package com.zenithblue.nativecode.agent

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guest CLIs must not assume the Play package/socket: F-Droid (.fdroid) runs next to Play and binds "@nativecode.<kind>.v1.<uid>". */
class GuestBridgeCliTest {
    private fun script(name: String) = File("src/main/assets/agents/$name").readText()

    @Test
    fun cliScriptsHaveNoHardcodedPackagePath() {
        for (n in listOf("nativecode-agent", "nativecode-media")) {
            assertFalse("$n hard-codes the Play package", script(n).contains("/data/data/com.zenithblue"))
        }
    }

    @Test
    fun cliScriptsReadGuestRunRendezvous() {
        for (n in listOf("nativecode-agent", "nativecode-media")) assertTrue(script(n).contains("\"/run/nativecode_bridge.json\""))
    }

    @Test
    fun rendezvousNamesUidSockets() {
        val j = JSONObject(BridgeRendezvous.json(10370))
        assertEquals(10370, j.getInt("host_uid"))
        assertEquals("@nativecode.agent.v1.10370", j.getString("agent_socket"))
        assertEquals("@nativecode.media.v1.10370", j.getString("media_socket"))
    }

    @Test
    fun shareFileAcceptsPositionalPath() {
        assertTrue(script("nativecode-agent").contains("""add_argument("pos_path", nargs="?""""))
    }
}
