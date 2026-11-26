package com.zenithblue.nativecode.onboarding

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackCandidateTest {
    private val filesDir = File("/files")

    @Test
    fun releaseNeverReadsDevFallbacks() {
        assertEquals(emptyList<File>(), RuntimeUpdatePack.localCandidates(filesDir, debug = false))
        assertEquals(emptyList<File>(), AndroidDevPackManager.localCandidates(filesDir, debug = false))
    }

    @Test
    fun debugKeepsDevFallbacks() {
        assertTrue(RuntimeUpdatePack.localCandidates(filesDir, true).any { it.path.startsWith("/data/local/tmp") })
        assertTrue(AndroidDevPackManager.localCandidates(filesDir, true).any { it.path.startsWith("/data/local/tmp") })
    }

    @Test
    fun mismatchingLocalFallsThroughAndPlayWins() {
        val dir = Files.createTempDirectory("cand").toFile()
        try {
            val stale = File(dir, "stale.tgz").apply { writeBytes(ByteArray(2 * 1024 * 1024 + 10)) }
            val play = File(dir, "play.tgz").apply { writeText("x") }
            // stale local does not match the pin: ignored, so the caller falls through to Play
            assertNull(RuntimeUpdatePack.pickArchive(null, listOf(stale)) { false })
            assertNull(AndroidDevPackManager.pickLocal(listOf(stale)) { false })
            // matching local is used when Play has nothing
            assertEquals(stale, RuntimeUpdatePack.pickArchive(null, listOf(stale)) { true })
            assertEquals(stale, AndroidDevPackManager.pickLocal(listOf(stale)) { true })
            // Play pack always wins over a local one
            assertEquals(play, RuntimeUpdatePack.pickArchive(play, listOf(stale)) { true })
            // real sha check: random bytes never match the pin
            assertNull(RuntimeUpdatePack.pickArchive(null, listOf(stale)))
            assertFalse(AndroidDevPackManager.verifyArchive(stale))
        } finally {
            dir.deleteRecursively()
        }
    }
}
