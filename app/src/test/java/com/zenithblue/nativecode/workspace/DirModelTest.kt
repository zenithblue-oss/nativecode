package com.zenithblue.nativecode.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class DirModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(rel: String, text: String = "x", mtime: Long? = null): File {
        val f = File(tmp.root, rel)
        f.parentFile!!.mkdirs()
        f.writeText(text)
        mtime?.let { f.setLastModified(it) }
        return f
    }

    @Test fun listPutsFoldersFirstAndHidesDotFiles() {
        file("b.txt"); file("A.txt"); file(".hidden"); File(tmp.root, "zdir").mkdir()
        assertEquals(listOf("zdir", "A.txt", "b.txt"), DirModel.list(tmp.root, false, "", DirSort.NAME).map { it.name })
        assertEquals(listOf("zdir", ".hidden", "A.txt", "b.txt"), DirModel.list(tmp.root, true, "", DirSort.NAME).map { it.name })
    }

    @Test fun filterIsCaseInsensitiveSubstring() {
        file("Readme.md"); file("main.kt"); file("notes.txt")
        assertEquals(listOf("Readme.md"), DirModel.list(tmp.root, false, "READ", DirSort.NAME).map { it.name })
    }

    @Test fun sortByModifiedAndSize() {
        file("old.txt", "a", 1_000_000_000_000L); file("new.txt", "bbbbbb", 1_700_000_000_000L); file("mid.txt", "cc", 1_500_000_000_000L)
        assertEquals(listOf("new.txt", "mid.txt", "old.txt"), DirModel.list(tmp.root, false, "", DirSort.MODIFIED).map { it.name })
        assertEquals(listOf("new.txt", "mid.txt", "old.txt"), DirModel.list(tmp.root, false, "", DirSort.SIZE).map { it.name })
    }

    @Test fun searchWalksSubfoldersButSkipsHeavyDirs() {
        file("src/deep/Target.kt"); file("node_modules/pkg/Target.js"); file(".git/Target"); file("other.txt")
        assertEquals(listOf("Target.kt"), DirModel.search(tmp.root, "target", false, DirSort.NAME).map { it.name })
        assertEquals(emptyList<DirEntry>(), DirModel.search(tmp.root, "  ", false, DirSort.NAME))
    }

    @Test fun searchHonoursLimit() {
        repeat(10) { file("f$it.txt") }
        assertEquals(3, DirModel.search(tmp.root, "f", false, DirSort.NAME, limit = 3).size)
    }

    @Test fun breadcrumbsFromRoot() {
        val sub = File(tmp.root, "a/b").apply { mkdirs() }
        val crumbs = DirModel.breadcrumbs(tmp.root, sub, "proj")
        assertEquals(listOf("proj", "a", "b"), crumbs.map { it.label })
        assertEquals(sub, crumbs.last().dir)
        assertEquals(listOf("proj"), DirModel.breadcrumbs(tmp.root, tmp.root, "proj").map { it.label })
    }

    @Test fun insideAndRelative() {
        val f = File(tmp.root, "a/b.txt")
        assertTrue(DirModel.isInside(tmp.root, f))
        assertFalse(DirModel.isInside(tmp.root, File(tmp.root.absolutePath + "-sibling/x")))
        assertEquals("a/b.txt", DirModel.relativePath(tmp.root, f))
    }

    @Test fun nameValidation() {
        assertTrue(DirModel.validName("ok.txt"))
        assertTrue(DirModel.validName(".env"))
        assertFalse(DirModel.validName(""))
        assertFalse(DirModel.validName("  "))
        assertFalse(DirModel.validName(".."))
        assertFalse(DirModel.validName("a/b"))
    }

    @Test fun gitMarkersMarkFilesAndAncestors() {
        val m = DirModel.markers(listOf("src/app/Main.kt" to 'M', "README.md" to 'U', "src/x.kt" to 'A'))
        assertEquals('M', m["src/app/Main.kt"])
        assertEquals('U', m["README.md"])
        assertEquals('*', m["src/app"])
        assertEquals('*', m["src"])
        assertEquals(null, m["docs"])
    }

    @Test fun kindAndSizeFormatting() {
        assertEquals(FileKind.FOLDER, DirModel.kindOf("x", true))
        assertEquals(FileKind.CODE, DirModel.kindOf("a.kt", false))
        assertEquals(FileKind.SCRIPT, DirModel.kindOf("run.sh", false))
        assertEquals(FileKind.IMAGE, DirModel.kindOf("a.PNG", false))
        assertEquals(FileKind.ARCHIVE, DirModel.kindOf("a.tar.gz", false))
        assertEquals(FileKind.OTHER, DirModel.kindOf("a.bin", false))
        assertEquals("12 B", DirModel.formatSize(12))
        assertEquals("1.5 KB", DirModel.formatSize(1536))
    }

    @Test fun deleteTreeRemovesFolderButNeverFollowsSymlinks() {
        val outside = tmp.newFolder("outside").also { File(it, "keep.txt").writeText("keep") }
        val root = tmp.newFolder("root")
        val victim = File(root, "victim").apply { mkdirs() }
        File(victim, "f.txt").writeText("x")
        Files.createSymbolicLink(File(victim, "link").toPath(), outside.toPath())
        DirModel.deleteTree(root, victim)
        assertFalse(victim.exists())
        assertEquals("keep", File(outside, "keep.txt").readText())
    }

    @Test fun deleteTreeRefusesRootAndOutside() {
        val root = tmp.newFolder("root2")
        val other = file("other.txt")
        for (t in listOf(root, other)) {
            try { DirModel.deleteTree(root, t); fail("should refuse ${t.name}") } catch (_: IOException) { }
        }
        assertTrue(root.exists() && other.exists())
    }
}
