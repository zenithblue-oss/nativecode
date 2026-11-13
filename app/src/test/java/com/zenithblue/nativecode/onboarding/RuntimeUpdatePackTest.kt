package com.zenithblue.nativecode.onboarding

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeUpdatePackTest {
    private class Entry(val name: String, val data: ByteArray?, val mode: Int = 0b111_101_101) // null = directory

    private fun header(name: String, size: Int, type: Char, mode: Int): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
        put(0, name)
        put(100, "%07o".format(mode)); put(108, "0000000"); put(116, "0000000")
        put(124, "%011o".format(size)); put(136, "00000000000")
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        h[156] = type.code.toByte()
        put(257, "ustar"); put(263, "00")
        put(148, "%06o".format(h.sumOf { it.toInt() and 0xff }))
        h[154] = 0; h[155] = ' '.code.toByte()
        return h
    }

    private fun tgz(file: File, entries: List<Entry>, truncateBy: Int = 0) {
        val tar = ByteArrayOutputStream()
        for (e in entries) {
            if (e.data == null) { tar.write(header(e.name, 0, '5', 0b111_101_101)); continue }
            tar.write(header(e.name, e.data.size, '0', e.mode))
            tar.write(e.data)
            tar.write(ByteArray((512 - e.data.size % 512) % 512))
        }
        tar.write(ByteArray(1024))
        val bytes = tar.toByteArray().let { it.copyOf(it.size - truncateBy) }
        GZIPOutputStream(file.outputStream()).use { it.write(bytes) }
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private val files = listOf(
        Entry("home/flux/.opencode", null),
        Entry("home/flux/.opencode/bin", null),
        Entry("home/flux/.opencode/bin/opencode", "opencode-2".toByteArray()),
        Entry("usr/bin/zsh", ByteArray(700) { 7 }),
    )

    @Test
    fun appliesReplacesSymlinkAndIsIdempotent() {
        val root = Files.createTempDirectory("rt").toFile()
        val pack = File(root.parentFile, "pack-${root.name}.tgz")
        try {
            // old rootfs: usr/bin/zsh is a PRoot-style symlink to a link2symlink file
            File(root, "usr/bin").mkdirs(); File(root, ".l2s").mkdirs()
            File(root, ".l2s/zsh.0001").writeText("old zsh")
            Files.createSymbolicLink(File(root, "usr/bin/zsh").toPath(), File(root, ".l2s/zsh.0001").toPath())
            tgz(pack, files)
            val digest = sha(pack)

            val r = RuntimeUpdatePack.applyTo(root, pack, digest)
            assertTrue(r.message, r.success)
            assertEquals("opencode-2", File(root, "home/flux/.opencode/bin/opencode").readText())
            assertTrue(File(root, "home/flux/.opencode/bin/opencode").canExecute())
            val zsh = File(root, "usr/bin/zsh")
            assertFalse(Files.isSymbolicLink(zsh.toPath()))
            assertEquals(700L, zsh.length())
            assertEquals("old zsh", File(root, ".l2s/zsh.0001").readText()) // link target untouched
            assertTrue(File(root, RuntimeUpdatePack.MARKER_REL).readText().startsWith(digest))
            assertEquals(emptyList<String>(), root.walk().filter { it.name.endsWith(".nc-update") }.map { it.path }.toList())

            // second run: marker short-circuits, even if the archive is gone
            pack.delete()
            assertTrue(RuntimeUpdatePack.applyTo(root, pack, digest).success)
        } finally {
            pack.delete(); root.deleteRecursively()
        }
    }

    @Test
    fun wrongChecksumTouchesNothing() {
        val root = Files.createTempDirectory("rt2").toFile()
        val pack = File(root.parentFile, "pack-${root.name}.tgz")
        try {
            tgz(pack, files)
            val r = RuntimeUpdatePack.applyTo(root, pack, "0".repeat(64))
            assertFalse(r.success)
            assertEquals(emptyList<String>(), root.walk().filter { it.isFile }.map { it.path }.toList())
        } finally {
            pack.delete(); root.deleteRecursively()
        }
    }

    @Test
    fun rejectsPathTraversalAndAbsolutePaths() {
        for (bad in listOf("../evil", "usr/../../evil", "/abs/evil")) {
            val root = Files.createTempDirectory("rt3").toFile()
            val pack = File(root.parentFile, "pack-${root.name}.tgz")
            try {
                tgz(pack, listOf(Entry(bad, "x".toByteArray())))
                val r = RuntimeUpdatePack.applyTo(root, pack, sha(pack))
                assertFalse(bad, r.success)
                assertFalse(File(root.parentFile, "evil").exists())
                assertFalse(File(root, RuntimeUpdatePack.MARKER_REL).exists())
            } finally {
                pack.delete(); root.deleteRecursively()
            }
        }
    }

    @Test
    fun rejectsParentSymlinkEscape() {
        val root = Files.createTempDirectory("rt4").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        val pack = File(root.parentFile, "pack-${root.name}.tgz")
        try {
            Files.createSymbolicLink(File(root, "usr").toPath(), outside.toPath())
            tgz(pack, listOf(Entry("usr/bin/gh", "gh".toByteArray())))
            assertFalse(RuntimeUpdatePack.applyTo(root, pack, sha(pack)).success)
            assertEquals(0, outside.walk().filter { it.isFile }.count())
        } finally {
            pack.delete(); root.deleteRecursively(); outside.deleteRecursively()
        }
    }

    @Test
    fun truncatedArchiveKeepsOldFileAndNoMarker() {
        val root = Files.createTempDirectory("rt5").toFile()
        val pack = File(root.parentFile, "pack-${root.name}.tgz")
        try {
            File(root, "home/flux/.opencode/bin").mkdirs()
            File(root, "home/flux/.opencode/bin/opencode").writeText("v1")
            tgz(pack, listOf(Entry("home/flux/.opencode/bin/opencode", ByteArray(5000) { 1 })), truncateBy = 3000)
            val r = RuntimeUpdatePack.applyTo(root, pack, sha(pack))
            assertFalse(r.message, r.success)
            assertEquals("v1", File(root, "home/flux/.opencode/bin/opencode").readText())
            assertFalse(File(root, RuntimeUpdatePack.MARKER_REL).exists())
            assertEquals(emptyList<String>(), root.walk().filter { it.name.endsWith(".nc-update") }.map { it.path }.toList())
        } finally {
            pack.delete(); root.deleteRecursively()
        }
    }
}
