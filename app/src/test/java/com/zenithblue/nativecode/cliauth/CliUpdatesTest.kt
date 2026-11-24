package com.zenithblue.nativecode.cliauth

import com.zenithblue.nativecode.agent.chat.core.ChatFailures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CliUpdatesTest {
    @Test fun parsesNpmLatest() {
        assertEquals("0.172.0", CliUpdates.parseLatest("""{"name":"@openai/codex","version":"0.172.0","dependencies":{"x":"1"}}"""))
    }

    @Test fun parsesOpencodeUpdateApi() {
        assertEquals("2.4.1", CliUpdates.parseLatest("""{"channel":"latest","name":"cli","distribution":"npm","version":"2.4.1","metadata":{"package":"@opencode/cli"}}"""))
    }

    @Test fun parsesAgyAndKiroManifests() {
        assertEquals("1.5.16", CliUpdates.parseLatest("{\n  \"version\": \"1.5.16\",\n  \"url\": \"https://x/1.5.16-5250855658717184/a.tar.gz\"\n}"))
        assertEquals("3.5.0", CliUpdates.parseLatest("{\n  \"version\": \"3.5.0\",\n  \"packages\": [{\"download\": \"3.5.0/k.zip\"}]\n}"))
    }

    @Test fun parsesPlainTextPointers() {
        assertEquals("2.1.331", CliUpdates.parseLatest("2.1.331"))
        assertEquals("1.0.92", CliUpdates.parseLatest("1.0.92\r\n"))
        assertEquals("1.0.92", CliUpdates.parseLatest("\n1.0.92\n"))
    }

    @Test fun rejectsGarbage() {
        assertNull(CliUpdates.parseLatest(""))
        assertNull(CliUpdates.parseLatest("<html>404 Not Found</html>"))
        assertNull(CliUpdates.parseLatest("""{"error":"nope"}"""))
    }

    @Test fun availableComparesNumerically() {
        fun row(i: String?, l: String?) = CliUpdates.Row("x", i, l, 0)
        assertTrue(row("2.3.0", "2.4.1").available)
        assertTrue(row("0.9.0", "0.10.0").available) // numeric, not lexical
        assertTrue(row("1.0.13", "1.0.92").available)
        assertFalse(row("2.4.1", "2.4.1").available)
        assertFalse(row("2.5.0", "2.4.1").available) // installed ahead of the release pointer
        assertFalse(row(null, "2.4.1").available)
        assertFalse(row("2.4.1", null).available)
    }

    @Test fun installedOutputParsing() {
        assertEquals("2.3.0", ChatFailures.parseVersion("opencode v2.3.0"))
        assertEquals("1.0.13", ChatFailures.parseVersion("grok 1.0.13 (5e9a58528b76)"))
        assertEquals("2.21.1", ChatFailures.parseVersion("kiro-cli 2.21.1"))
        assertEquals("0.153.2", ChatFailures.parseVersion("codex-cli 0.153.2"))
    }
}
