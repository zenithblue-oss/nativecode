package com.zenithblue.nativecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class TurnipDriverUpdateTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun run(root: File, stage: File, sha: String): String {
        val p = ProcessBuilder("sh", "-c", TurnipDriverUpdate.installScript(root.path, stage.path, sha)).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }

    private fun fixture(newSo: ByteArray): Pair<File, File> {
        val root = tmp.newFolder("rootfs")
        File(root, "opt/nativecode-turnip/lib").mkdirs()
        File(root, "opt/nativecode-turnip/icd").mkdirs()
        File(root, "opt/nativecode-turnip/lib/libvulkan_freedreno.so").writeText("old")
        File(root, "opt/nativecode-turnip/icd/freedreno_icd.aarch64.json").writeText("{old}")
        val stage = tmp.newFolder("stage")
        File(stage, "libvulkan_freedreno.so").writeBytes(newSo)
        File(stage, "freedreno_icd.aarch64.json").writeText("{new}")
        return root to stage
    }

    @Test fun swapsFilesKeepsBackup() {
        assumeTrue(File("/usr/bin/sha256sum").exists() || File("/bin/sha256sum").exists())
        val so = "new-so".toByteArray()
        val (root, stage) = fixture(so)
        assertTrue(run(root, stage, sha(so)).contains("UPDATE_OK"))
        assertEquals("new-so", File(root, "opt/nativecode-turnip/lib/libvulkan_freedreno.so").readText())
        assertEquals("old", File(root, "opt/nativecode-turnip/lib/libvulkan_freedreno.so.bak").readText())
        assertEquals("{new}", File(root, "opt/nativecode-turnip/icd/freedreno_icd.aarch64.json").readText())
    }

    @Test fun shaMismatchLeavesOldDriver() {
        assumeTrue(File("/usr/bin/sha256sum").exists() || File("/bin/sha256sum").exists())
        val (root, stage) = fixture("new-so".toByteArray())
        val out = run(root, stage, sha("other".toByteArray()))
        assertTrue(out.contains("SHA_MISMATCH"))
        assertFalse(out.contains("UPDATE_OK"))
        assertEquals("old", File(root, "opt/nativecode-turnip/lib/libvulkan_freedreno.so").readText())
        assertFalse(File(root, "opt/nativecode-turnip/lib/libvulkan_freedreno.so.new").exists())
    }
}
