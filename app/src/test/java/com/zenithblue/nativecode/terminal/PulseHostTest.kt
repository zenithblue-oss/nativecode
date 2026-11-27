package com.zenithblue.nativecode.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseHostTest {
    @Test
    fun playKeepsStockPortAndStrings() {
        assertEquals(4713, PulseHost.portFor("com.zenithblue.nativecode"))
        assertEquals("127.0.0.1", PulseHost.server(4713))
        assertEquals("auth-ip-acl=127.0.0.1 auth-anonymous=1", PulseHost.tcpArgs(4713))
    }

    @Test
    fun fdroidGetsItsOwnDeterministicPort() {
        val p = PulseHost.portFor("com.zenithblue.nativecode.fdroid")
        assertNotEquals(4713, p)
        assertTrue(p in 4714..4913)
        assertEquals(p, PulseHost.portFor("com.zenithblue.nativecode.fdroid"))
        assertEquals("127.0.0.1:$p", PulseHost.server(p))
        assertTrue(PulseHost.tcpArgs(p).endsWith(" port=$p"))
    }

    @Test
    fun retargetRewritesOnlyPulseServerAssignments() {
        val p = 4800
        val src = "export PULSE_SERVER='127.0.0.1'\nexport PULSE_SERVER=127.0.0.1\nexport PULSE_SERVER=tcp:127.0.0.1\nexport PULSE_SERVER=\"127.0.0.1:4713\"\nexport OTHER=127.0.0.1\n"
        val out = PulseHost.retarget(src, p)
        assertEquals(
            "export PULSE_SERVER='127.0.0.1:4800'\nexport PULSE_SERVER=127.0.0.1:4800\nexport PULSE_SERVER=tcp:127.0.0.1:4800\nexport PULSE_SERVER=\"127.0.0.1:4800\"\nexport OTHER=127.0.0.1\n",
            out,
        )
        assertEquals(out, PulseHost.retarget(out, p)) // idempotent
    }

    @Test
    fun hostEnvGainsPulseLinesOnlyForNonPlay() {
        val base = "export A=\"1\"\nexport B=\"2\"\n"
        assertEquals(base, TermuxHostPaths.withPulseEnv(base, 4713))
        val fd = TermuxHostPaths.withPulseEnv(base, 4813)
        assertEquals(base + "export NC_PULSE_PORT=\"4813\"\nexport NC_PULSE_SERVER=\"127.0.0.1:4813\"\n", fd)
        assertEquals(fd, TermuxHostPaths.withPulseEnv(fd, 4813)) // idempotent
        assertEquals(base, TermuxHostPaths.withPulseEnv(fd, 4713)) // back to Play
    }

    @Test
    fun pulseArgvMatchesThisBuildsPort() {
        val argv = HostServerController.pulseArgv("/x/pulseaudio")
        assertTrue(argv.contains("module-native-protocol-tcp " + PulseHost.tcpArgs()))
        assertEquals(PulseHost.USES_AAUDIO, argv.contains("module-aaudio-sink"))
        assertEquals(PulseHost.PORT != PulseHost.DEFAULT_PORT, PulseHost.USES_AAUDIO)
    }
}
