package com.zenithblue.nativecode.agent.chat

import com.zenithblue.nativecode.agent.chat.core.ChatFailures
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFailuresTest {
    private fun cls(msg: String, raw: String? = null, base: FailureClass = FailureClass.TRANSPORT_ERROR, details: String? = null) =
        ChatFailures.refine(Failure(base, msg, details = details), raw).cls

    @Test fun claudeSignedOutEarlyExitIsNotSignedIn() {
        // Bug: shell shows "Not logged in · Please run /login", the chat showed "Session ended unexpectedly (exit 0)".
        assertEquals(FailureClass.PERMISSION_ERROR, cls("Session ended unexpectedly (exit 0)", details = "Not logged in · Please run /login"))
        assertEquals(FailureClass.PERMISSION_ERROR, cls("Session ended unexpectedly (exit 0)", details = """{"type":"assistant","error":"authentication_failed"}"""))
        assertEquals(FailureClass.PERMISSION_ERROR, cls("x", raw = "Please sign in"))
        assertEquals(FailureClass.PERMISSION_ERROR, cls("HTTP 401 unauthorized", base = FailureClass.PROVIDER_ERROR))
    }

    @Test fun processExitWithoutReasonIsCrashed() {
        assertEquals(FailureClass.CRASHED, cls("Session ended unexpectedly (exit 137)"))
        assertEquals(FailureClass.CRASHED, cls("codex exited (code 1)", details = "thread 'main' panicked"))
        assertEquals(FailureClass.CRASHED, cls("opencode serve did not start"))
        assertEquals(FailureClass.CRASHED, cls("Claude Code is not running"))
    }

    @Test fun notInstalled() {
        assertEquals(FailureClass.CLI_MISSING, cls("Session ended unexpectedly (exit 127)", details = "bash: codex: command not found"))
        assertEquals(FailureClass.CLI_MISSING, cls("x", raw = "spawn claude ENOENT"))
    }

    @Test fun limitsAndGate() {
        assertEquals(FailureClass.USAGE_LIMIT, cls("You have hit your usage limit", base = FailureClass.PROVIDER_ERROR))
        assertEquals(FailureClass.USAGE_LIMIT, cls("x", raw = "HTTP 429 Too Many Requests"))
        assertEquals(FailureClass.GATE_DENIED, cls("Session ended unexpectedly (exit 1)", details = "NativeCode: Pro required for claude"))
    }

    @Test fun versionRelatedFailuresAreProtocolErrors() {
        val cases = listOf(
            "error: unknown option '--permission-prompt-tool'",
            "unknown flag: --no-daemon",
            "Unrecognized arguments: --foo",
            """{"code":-32601,"message":"Method not found"}""",
            "Invalid request: missing field `model`",
            "unknown variant `xhigh`, expected one of",
            "ACP initialize failed: unsupported protocolVersion 2",
            "HTTP 404 Not Found",
            "Chat not available for Claude Code 2.0.1 (needs 2.1+)",
        )
        for (c in cases) {
            val f = ChatFailures.refine(Failure(FailureClass.PROVIDER_ERROR, c))
            assertEquals(c, FailureClass.PROTOCOL_ERROR, f.cls)
            assertTrue(c, ChatFailures.versionRelated(f))
        }
    }

    @Test fun otherProtocolAndNetworkErrorsDoNotTriggerVersionCheck() {
        val f = ChatFailures.refine(Failure(FailureClass.TRANSPORT_ERROR, "HTTP 400 bad body"))
        assertEquals(FailureClass.PROTOCOL_ERROR, f.cls)
        assertFalse(ChatFailures.versionRelated(f))
        val n = ChatFailures.refine(Failure(FailureClass.TRANSPORT_ERROR, "Lost the connection to opencode"))
        assertEquals(FailureClass.NETWORK_ERROR, n.cls)
        assertFalse(ChatFailures.versionRelated(n))
        assertEquals(FailureClass.NETWORK_ERROR, cls("x", raw = "ECONNREFUSED 127.0.0.1:4096"))
        assertEquals(FailureClass.NETWORK_ERROR, cls("HTTP 503 Service Unavailable"))
    }

    @Test fun specificClassesAreKept() {
        assertEquals(FailureClass.PERMISSION_ERROR, cls("unknown option", base = FailureClass.PERMISSION_ERROR))
        assertEquals(FailureClass.USAGE_LIMIT, cls("Not logged in", base = FailureClass.USAGE_LIMIT))
        // Nothing recognisable: the original class survives.
        assertEquals(FailureClass.PROVIDER_ERROR, cls("The model refused", base = FailureClass.PROVIDER_ERROR))
    }

    @Test fun detailsKeepTheRawTailWhenReclassified() {
        val f = ChatFailures.refine(Failure(FailureClass.TRANSPORT_ERROR, "Session ended unexpectedly (exit 0)"), raw = "Not logged in · Please run /login")
        assertEquals(FailureClass.PERMISSION_ERROR, f.cls)
        assertEquals("Not logged in · Please run /login", f.details)
    }

    @Test fun reasonIsTrimmed() {
        assertEquals("first", ChatFailures.reason("\n first \nsecond"))
        assertEquals(160, ChatFailures.reason("a".repeat(500)).length)
        assertTrue(ChatFailures.reason("a".repeat(500)).endsWith("…"))
        assertEquals("", ChatFailures.reason("  \n "))
    }

    @Test fun parsesVersionOutput() {
        assertEquals("2.1.323", ChatFailures.parseVersion("2.1.323 (Claude Code)"))
        assertEquals("0.167.1", ChatFailures.parseVersion("codex-cli 0.167.1"))
        assertEquals("1.0.77", ChatFailures.parseVersion("grok v1.0.77\n"))
        assertEquals("2.3.0", ChatFailures.parseVersion("opencode 2.3.0"))
        assertNull(ChatFailures.parseVersion("bash: claude: command not found"))
        assertNull(ChatFailures.parseVersion(""))
    }

    @Test fun comparesVersions() {
        assertEquals(0, ChatFailures.compareVersions("2.1", "2.1.0"))
        assertTrue(ChatFailures.compareVersions("2.0.9", "2.1.0") < 0)
        assertTrue(ChatFailures.compareVersions("2.10.0", "2.9.9") > 0)
        assertTrue(ChatFailures.compareVersions("0.167.1", "0.130.0") > 0)
        assertTrue(ChatFailures.compareVersions("1.0.50", "1.0.9") > 0)
        assertEquals(0, ChatFailures.compareVersions("1.2.3-beta", "1.2.3"))
    }

    @Test fun tooOldOnlyWithKnownVersions() {
        assertTrue(ChatFailures.versionTooOld("2.0.5", "2.1.0"))
        assertFalse(ChatFailures.versionTooOld("2.1.0", "2.1.0"))
        assertFalse(ChatFailures.versionTooOld("3.0", "2.1.0"))
        assertFalse(ChatFailures.versionTooOld(null, "2.1.0")) // never block on a guess
        assertFalse(ChatFailures.versionTooOld("1.0", null))
    }
}
