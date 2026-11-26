package com.zenithblue.nativecode.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun createCommandShape() {
        val c = BackupPlan.createCommand(BackupScope.HOME, listOf("node_modules"))
        assertTrue(c.startsWith("cd /home/flux"))
        assertTrue(c.contains("tar --zstd -cvf -"))
        assertTrue(c.contains("--exclude='node_modules'"))
        assertTrue(c.endsWith("-- '.'"))
        assertTrue(BackupPlan.createCommand(BackupScope.PROJECTS, emptyList()).contains("exit 3"))
    }

    @Test fun credentialsExcludedUnlessIncluded() {
        val sel = BackupPlan.defaultExcludeIds()
        assertTrue(".ssh" in BackupPlan.excludePatterns(sel, false))
        assertFalse(".ssh" in BackupPlan.excludePatterns(sel, true))
        assertTrue("node_modules" in BackupPlan.excludePatterns(sel, true))
        assertFalse("node_modules" in BackupPlan.excludePatterns(emptySet(), true))
        assertTrue(".restore-*" in BackupPlan.excludePatterns(emptySet(), true))
    }

    @Test fun memberSafety() {
        assertTrue(BackupPlan.isSafeMember("./repos/a/b.txt"))
        assertTrue(BackupPlan.isSafeMember("repos/a..b"))
        assertFalse(BackupPlan.isSafeMember("/etc/passwd"))
        assertFalse(BackupPlan.isSafeMember("../x"))
        assertFalse(BackupPlan.isSafeMember("a/../../x"))
        assertFalse(BackupPlan.isSafeMember(""))
        assertTrue(BackupPlan.isSafeMember("repos/üntracked.txt"))
        assertFalse(BackupPlan.isSafeMember("a\u0000b"))
    }

    @Test fun topLevelNames() {
        assertEquals("repos", BackupPlan.topLevel("./repos/a/b"))
        assertEquals("repos", BackupPlan.topLevel("repos/"))
        assertEquals(".bashrc", BackupPlan.topLevel("./.bashrc"))
        assertNull(BackupPlan.topLevel("./"))
        assertNull(BackupPlan.topLevel("."))
    }

    @Test fun estimateParsing() {
        assertEquals(2048L * 1024, BackupPlan.parseEstimateKb("2048\ttotal\n"))
        assertNull(BackupPlan.parseEstimateKb("garbage"))
        assertNull(BackupPlan.parseEstimateKb(""))
    }

    @Test fun manifestRoundTrip() {
        val m = Manifest(1, "1.8.0", "2026-11-15T10:00:00Z", "ABCDEF", 123, 4, "HOME", listOf("a", "b"), mapOf("guest" to "alpaquita"))
        val back = BackupPlan.parseManifest(BackupPlan.manifestJson(m))
        assertNotNull(back)
        assertEquals(m.copy(sha256 = "abcdef"), back)
        assertNull(BackupPlan.parseManifest("not json"))
        assertNull(BackupPlan.parseManifest("{}"))
    }

    // ---- one-file backups, chat data, credentials default, CLI versions ----

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun scan(data: ByteArray, chunk: Int): BackupTrailer.Found {
        val s = BackupTrailer.Scanner()
        var i = 0
        while (i < data.size) { val n = minOf(chunk, data.size - i); s.update(data, i, n); i += n }
        return s.finish()
    }

    @Test fun chatDataPathIsArchivedInBothScopes() {
        assertEquals("nativecode-app", BackupPlan.APP_DIR)
        val projects = BackupPlan.createCommand(BackupScope.PROJECTS, emptyList(), app = true)
        assertTrue(projects.endsWith("\"\$@\" 'nativecode-app'"))
        assertTrue(projects.contains("exit 3")) // still no repos = nothing to back up
        assertFalse(BackupPlan.createCommand(BackupScope.PROJECTS, emptyList()).contains("nativecode-app"))
        // the home scope archives "." which holds the staged folder
        assertTrue(BackupPlan.createCommand(BackupScope.HOME, emptyList(), app = true).endsWith("-- '.'"))
        // the chat folder is never excluded
        assertTrue(BackupPlan.excludePatterns(BackupPlan.defaultExcludeIds(), true).none { it.contains("nativecode-app") })
    }

    @Test fun credentialsAreIncludedByDefault() {
        assertTrue(BackupPlan.INCLUDE_CREDENTIALS_DEFAULT)
        val pat = BackupPlan.excludePatterns(BackupPlan.defaultExcludeIds(), BackupPlan.INCLUDE_CREDENTIALS_DEFAULT)
        assertFalse(".ssh" in pat); assertFalse(".claude/.credentials.json" in pat); assertFalse(".codex/auth.json" in pat)
        assertTrue("node_modules" in pat)
        // the toggle still works
        assertTrue(".ssh" in BackupPlan.excludePatterns(BackupPlan.defaultExcludeIds(), false))
    }

    @Test fun manifestKeepsCliVersionsAndChatFlag() {
        val comps = mapOf("guest" to "alpaquita", BackupPlan.cliKey("claude") to "2.1.263", BackupPlan.COMPONENT_CHAT to "1")
        val m = Manifest(1, "1.8.1", "2026-11-26T10:00:00Z", "ab12", 10, 2, "HOME", listOf("x"), comps)
        assertEquals(m, BackupPlan.parseManifest(BackupPlan.manifestJson(m)))
    }

    @Test fun outdatedClisOnlyOlderOnesWithAKnownVersion() {
        val m = Manifest(1, "1.8.1", "t", "s", 0, 0, "HOME", emptyList(), mapOf(
            "guest" to "x", "cli.claude" to "2.1.263", "cli.codex" to "0.167.1", "cli.grok" to "1.0.92", "cli.agy" to "1.5.16",
        ))
        val d = BackupPlan.outdatedClis(m, mapOf("claude" to "2.1.94", "codex" to "0.167.1", "grok" to "1.0.100", "agy" to null))
        assertEquals(listOf(BackupPlan.CliDelta("claude", "2.1.94", "2.1.263")), d)
        assertTrue(BackupPlan.outdatedClis(m, emptyMap()).isEmpty())
    }

    @Test fun trailerRoundTripForAnyChunking() {
        val body = ByteArray(300_000) { (it * 31).toByte() }
        val json = BackupPlan.manifestJson(Manifest(1, "1.8.1", "t", sha(body), body.size.toLong(), 1, "HOME", emptyList(), mapOf("cli.claude" to "2.1.263")))
        val file = body + BackupTrailer.encode(json)
        for (chunk in listOf(1, 7, 4096, 262_144, file.size)) {
            val f = scan(file, chunk)
            assertEquals("chunk $chunk", sha(body), f.sha256)
            assertEquals(json, f.json)
        }
        assertEquals(sha(body), BackupPlan.parseManifest(scan(file, 4096).json!!)!!.sha256)
    }

    @Test fun trailerStaysAValidSkippableZstdFrame() {
        val t = BackupTrailer.encode("{\"a\":1}")
        // magic 0x184D2A50 little endian, then payload size = json + u32 length + 8 byte end marker
        assertEquals(listOf<Byte>(0x50, 0x2A, 0x4D, 0x18), t.take(4))
        assertEquals(7 + 12, t[4].toInt() and 0xff)
        assertEquals(8 + 7 + 12, t.size)
    }

    @Test fun legacyArchiveWithoutTrailerIsAccepted() {
        val old = ByteArray(1000) { it.toByte() }
        val f = scan(old, 100)
        assertNull(f.json)
        assertEquals(sha(old), f.sha256) // whole-file sha: matches an old sidecar json
        // short and empty inputs
        assertEquals(sha(ByteArray(0)), scan(ByteArray(0), 1).sha256)
        assertEquals(sha(byteArrayOf(1, 2, 3)), scan(byteArrayOf(1, 2, 3), 1).sha256)
        // an old sidecar (no components, no cli versions) still parses
        val legacy = BackupPlan.parseManifest("""{"format":1,"appVersion":"1.8.0","createdAt":"t","sha256":"ABCD","bytes":5,"fileCount":1,"scope":"HOME","excludes":[]}""")
        assertNotNull(legacy)
        assertEquals("abcd", legacy!!.sha256)
        assertTrue(BackupPlan.outdatedClis(legacy, mapOf("claude" to "1.0")).isEmpty())
    }

    @Test fun damagedOrForgedTrailerIsNotTrusted() {
        val body = ByteArray(5000) { 7 }
        val t = BackupTrailer.encode("{\"a\":1}")
        val bad = t.copyOf().also { it[4] = 99 } // size field disagrees with the payload
        val f = scan(body + bad, 512)
        assertNull(f.json)
        assertEquals(sha(body + bad), f.sha256)
        // truncated trailer: end marker gone
        val cut = scan(body + t.copyOf(t.size - 3), 512)
        assertNull(cut.json)
    }

    private fun file(rel: String, text: String): File = File(tmp.root, rel).also { it.parentFile!!.mkdirs(); it.writeText(text) }

    @Test fun swapReplaceMovesOldAside() {
        val home = tmp.newFolder("home"); val staging = tmp.newFolder("home", ".restore-1")
        File(home, "a").mkdir(); File(home, "a/old.txt").writeText("old")
        File(staging, "a").mkdir(); File(staging, "a/new.txt").writeText("new")
        val r = RestoreSwap.apply(home, staging, listOf(EntryPlan("a", EntryMode.REPLACE)), "1")
        assertTrue(r.error, r.ok)
        assertEquals("new", File(home, "a/new.txt").readText())
        assertFalse(File(home, "a/old.txt").exists())
        assertEquals("old", File(home, ".replaced-1/a/old.txt").readText())
        assertFalse(staging.exists())
    }

    @Test fun swapMergeKeepsExistingFiles() {
        val home = tmp.newFolder("home"); val staging = tmp.newFolder("home", ".restore-2")
        File(home, "a").mkdir(); File(home, "a/keep.txt").writeText("mine")
        File(staging, "a").mkdir(); File(staging, "a/keep.txt").writeText("theirs"); File(staging, "a/add.txt").writeText("added")
        File(staging, "b.txt").writeText("b")
        val r = RestoreSwap.apply(home, staging, listOf(EntryPlan("a", EntryMode.MERGE), EntryPlan("b.txt", EntryMode.MERGE)), "2")
        assertTrue(r.error, r.ok)
        assertEquals("mine", File(home, "a/keep.txt").readText())
        assertEquals("added", File(home, "a/add.txt").readText())
        assertEquals("b", File(home, "b.txt").readText())
        assertEquals(1, r.kept)
        assertEquals(2, r.moved)
    }

    @Test fun swapRollsBackOnFailure() {
        val home = tmp.newFolder("home"); val staging = tmp.newFolder("home", ".restore-3")
        File(home, "a").mkdir(); File(home, "a/old.txt").writeText("old")
        File(staging, "a").mkdir(); File(staging, "a/new.txt").writeText("new")
        File(staging, "b").writeText("b")
        // second plan has an invalid name, so it throws after the first entry was already swapped
        val r = RestoreSwap.apply(home, staging, listOf(EntryPlan("a", EntryMode.REPLACE), EntryPlan("../evil", EntryMode.REPLACE)), "3")
        assertFalse(r.ok)
        assertEquals("old", File(home, "a/old.txt").readText())
        assertFalse(File(home, "a/new.txt").exists())
        assertTrue(File(staging, "a/new.txt").exists())
        assertFalse(File(home, ".replaced-3").exists())
    }
}
