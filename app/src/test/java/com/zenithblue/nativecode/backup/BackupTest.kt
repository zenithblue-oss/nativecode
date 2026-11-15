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
