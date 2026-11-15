package com.zenithblue.nativecode.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class EditorLogicTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun languageByExtensionAndName() {
        assertEquals("source.kotlin", EditorLanguages.scopeFor("Main.kt"))
        assertEquals("source.kotlin", EditorLanguages.scopeFor("build.gradle.kts"))
        assertEquals("source.java", EditorLanguages.scopeFor("A.JAVA"))
        assertEquals("source.js", EditorLanguages.scopeFor("x.jsx"))
        assertEquals("source.ts", EditorLanguages.scopeFor("x.tsx"))
        assertEquals("source.python", EditorLanguages.scopeFor("a.py"))
        assertEquals("source.json", EditorLanguages.scopeFor("package.json"))
        assertEquals("text.html.markdown", EditorLanguages.scopeFor("README.md"))
        assertEquals("source.shell", EditorLanguages.scopeFor("run.sh"))
        assertEquals("source.shell", EditorLanguages.scopeFor(".bashrc"))
        assertEquals("text.html.basic", EditorLanguages.scopeFor("index.html"))
        assertEquals("source.css", EditorLanguages.scopeFor("a.css"))
        assertEquals("source.c", EditorLanguages.scopeFor("a.h"))
        assertEquals("source.cpp", EditorLanguages.scopeFor("a.hpp"))
        assertEquals("source.go", EditorLanguages.scopeFor("main.go"))
        assertEquals("source.rust", EditorLanguages.scopeFor("lib.rs"))
        assertEquals("source.yaml.1.2", EditorLanguages.scopeFor("ci.yml"))
        assertEquals("source.ini", EditorLanguages.scopeFor("Cargo.toml"))
        assertEquals("source.dockerfile", EditorLanguages.scopeFor("Dockerfile"))
        assertEquals("source.dockerfile", EditorLanguages.scopeFor("Dockerfile.dev"))
    }

    @Test fun unknownOrNoExtensionIsPlainText() {
        assertNull(EditorLanguages.scopeFor("notes.xyz"))
        assertNull(EditorLanguages.scopeFor("LICENSE"))
        assertNull(EditorLanguages.scopeFor("trailing."))
    }

    @Test fun everyMappedScopeIsRegistered() {
        val json = File("src/main/assets/textmate/languages.json").readText()
        val scopes = Regex("\"scopeName\":\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toSet()
        for (name in listOf("a.kt", "a.java", "a.js", "a.ts", "a.py", "a.json", "a.md", "a.sh", "a.html", "a.xml", "a.css", "a.c", "a.cpp", "a.go", "a.rs", "a.yaml", "a.toml", "Dockerfile")) {
            assertTrue("$name -> ${EditorLanguages.scopeFor(name)}", EditorLanguages.scopeFor(name) in scopes)
        }
    }

    @Test fun sizeThresholdDecidesMode() {
        val text = "hello".toByteArray()
        assertEquals(EditMode.EDIT, EditorLimits.modeFor(10, text, canWrite = true))
        assertEquals(EditMode.EDIT, EditorLimits.modeFor(EditorLimits.EDIT_MAX_BYTES, text, canWrite = true))
        assertEquals(EditMode.READ_ONLY_LARGE, EditorLimits.modeFor(EditorLimits.EDIT_MAX_BYTES + 1, text, canWrite = true))
        assertEquals(EditMode.READ_ONLY_NO_WRITE, EditorLimits.modeFor(10, text, canWrite = false))
    }

    @Test fun binaryContentIsReadOnly() {
        assertTrue(EditorLimits.looksBinary(byteArrayOf(0x7f, 'E'.code.toByte(), 0, 1)))
        assertFalse(EditorLimits.looksBinary("plain text\n".toByteArray()))
        assertEquals(EditMode.READ_ONLY_BINARY, EditorLimits.modeFor(4, byteArrayOf(1, 0, 2), canWrite = true))
    }

    @Test fun invalidUtf8IsRejectedSoSaveCannotCorruptIt() {
        assertEquals("héllo", EditorLimits.decodeUtf8("héllo".toByteArray()))
        assertNull(EditorLimits.decodeUtf8(byteArrayOf(0x68, 0xC3.toByte(), 0x28)))
    }

    @Test fun atomicSaveReplacesContentAndLeavesNoTemp() {
        val f = tmp.newFile("a.txt").apply { writeText("old") }
        AtomicSave.write(f, "new content".toByteArray())
        assertEquals("new content", f.readText())
        assertEquals(listOf("a.txt"), tmp.root.list()!!.toList())
    }

    @Test fun atomicSaveKeepsPermissions() {
        val f = tmp.newFile("run.sh").apply { writeText("#!/bin/sh\n") }
        Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rwxr-x---"))
        AtomicSave.write(f, "#!/bin/sh\necho hi\n".toByteArray())
        assertEquals("rwxr-x---", PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath())))
        assertEquals("#!/bin/sh\necho hi\n", f.readText())
    }

    @Test fun atomicSaveWritesThroughSymlinkWithoutReplacingIt() {
        val real = tmp.newFile("real.txt").apply { writeText("x") }
        val link = File(tmp.root, "link.txt")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        AtomicSave.write(link, "via link".toByteArray())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("via link", real.readText())
    }

    @Test fun atomicSaveCreatesMissingFileAndKeepsBytesExact() {
        val f = File(tmp.root, "new.bin")
        val bytes = byteArrayOf(1, 2, 3, 0, -1, 10, 13)
        AtomicSave.write(f, bytes)
        assertArrayEquals(bytes, f.readBytes())
    }

    @Test fun failedSaveKeepsOriginalAndCleansTemp() {
        val dir = tmp.newFolder("d")
        val f = File(dir, "keep.txt").apply { writeText("original") }
        // A directory where the target should be makes the final rename fail.
        val target = File(dir, "target").apply { mkdir(); File(this, "child").writeText("c") }
        try { AtomicSave.write(target, "x".toByteArray()) } catch (_: Exception) { }
        assertEquals("original", f.readText())
        assertEquals(setOf("keep.txt", "target"), dir.list()!!.toSet())
    }

    @Test fun diskStampDetectsChange() {
        val f = tmp.newFile("s.txt").apply { writeText("a") }
        val before = DiskStamp.of(f)
        f.writeText("longer")
        f.setLastModified(before.modified + 5000)
        assertTrue(DiskStamp.of(f) != before)
        assertEquals(DiskStamp.of(f), DiskStamp.of(f))
    }
}
