package com.zenithblue.nativecode.agent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBridgeProtocolTest {

    @Test
    fun testControlStringRoundTrip() {
        val out = ByteArrayOutputStream()
        val original = """{"v":1,"cmd":"notify","title":"Test","message":"Hello"}"""

        AgentBridgeProtocol.writeControlString(out, original)
        val bytes = out.toByteArray()

        val parsed = AgentBridgeProtocol.readControlString(ByteArrayInputStream(bytes))
        assertEquals(original, parsed)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testOversizedControlStringRejected() {
        val out = ByteArrayOutputStream()
        val huge = "X".repeat(AgentBridgeProtocol.MAX_CONTROL_FRAME_SIZE + 10)
        AgentBridgeProtocol.writeControlString(out, huge)
    }

    @Test
    fun testTokenBucketRateLimiter() {
        val bucket = AgentBridgeProtocol.TokenBucket(capacity = 2.0, refillRatePerSecond = 0.0)
        assertTrue(bucket.tryAcquire(1.0))
        assertTrue(bucket.tryAcquire(1.0))
        assertFalse(bucket.tryAcquire(1.0))
    }

    @Test
    fun testOpenLinkPayloadRoundTrip() {
        val out = ByteArrayOutputStream()
        val original = """{"v":1,"cmd":"open_link","url":"http://localhost:3000","title":"Web Preview","message":"Running"}"""

        AgentBridgeProtocol.writeControlString(out, original)
        val bytes = out.toByteArray()

        val parsed = AgentBridgeProtocol.readControlString(ByteArrayInputStream(bytes))
        assertEquals(original, parsed)
    }

    @Test
    fun testInstallApkPayloadRoundTrip() {
        val out = ByteArrayOutputStream()
        val original = """{"v":1,"cmd":"install_apk","path":"/home/flux/app-debug.apk","title":"Install","message":"Ready"}"""

        AgentBridgeProtocol.writeControlString(out, original)
        val bytes = out.toByteArray()

        val parsed = AgentBridgeProtocol.readControlString(ByteArrayInputStream(bytes))
        assertEquals(original, parsed)
    }

    @Test
    fun testDismissAlertPayloadRoundTrip() {
        val out = ByteArrayOutputStream()
        val original = """{"v":1,"cmd":"dismiss_alert"}"""

        AgentBridgeProtocol.writeControlString(out, original)
        val bytes = out.toByteArray()

        val parsed = AgentBridgeProtocol.readControlString(ByteArrayInputStream(bytes))
        assertEquals(original, parsed)
    }
}
