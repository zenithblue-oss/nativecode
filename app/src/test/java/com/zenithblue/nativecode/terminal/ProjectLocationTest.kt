package com.zenithblue.nativecode.terminal

import com.zenithblue.nativecode.backup.BackupPlan
import com.zenithblue.nativecode.backup.BackupScope
import com.zenithblue.nativecode.terminal.ProjectLocation.Error
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ProjectLocationTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var rootfs: File

    private fun dir(guest: String) = File(rootfs, guest.removePrefix("/")).also { it.mkdirs() }
    private fun link(guest: String, target: String) {
        val l = File(rootfs, guest.removePrefix("/"))
        l.parentFile.mkdirs()
        Files.createSymbolicLink(l.toPath(), java.nio.file.Paths.get(target))
    }

    @Before fun setUp() {
        rootfs = tmp.newFolder("rootfs")
        dir("/home/flux/repos"); dir("/home/flux/work"); dir("/etc"); dir("/home/other")
    }

    @Test fun expandsTildeRelativeAndAbsolute() {
        assertEquals("/home/flux", ProjectLocation.expand("~"))
        assertEquals("/home/flux/repos", ProjectLocation.expand(" ~/repos "))
        assertEquals("/home/flux/a/b", ProjectLocation.expand("a/b"))
        assertEquals("/home/flux/x", ProjectLocation.expand("/home/flux/x"))
        assertNull(ProjectLocation.expand("  "))
    }

    @Test fun insideHomeAccepted() {
        val c = ProjectLocation.check(rootfs, "~/work", emptySet(), true)
        assertTrue(c.ok)
        assertEquals("/home/flux/work", c.path)
        assertFalse(c.isHome)
    }

    @Test fun homeItselfIsAcceptedWithWarningFlag() {
        val c = ProjectLocation.check(rootfs, "~", emptySet(), true)
        assertTrue(c.ok)
        assertTrue(c.isHome)
    }

    @Test fun outsideHomeRejected() {
        for (p in listOf("/etc", "/", "/home", "/home/other", "/home/fluxx", "/sdcard", "/data/data")) {
            assertEquals(p, Error.OUTSIDE_HOME, ProjectLocation.check(rootfs, p, emptySet(), false).error)
        }
    }

    @Test fun dotDotTraversalRejected() {
        assertEquals(Error.TRAVERSAL, ProjectLocation.check(rootfs, "~/work/../../../etc", emptySet(), false).error)
        assertEquals(Error.TRAVERSAL, ProjectLocation.check(rootfs, "..", emptySet(), false).error)
        assertEquals(Error.TRAVERSAL, ProjectLocation.check(rootfs, "/home/flux/../other", emptySet(), false).error)
    }

    @Test fun symlinkEscapeRejected() {
        link("/home/flux/evil-abs", "/etc")
        link("/home/flux/evil-rel", "../../etc")
        link("/home/flux/evil-other", "/home/other")
        for (p in listOf("~/evil-abs", "~/evil-rel", "~/evil-other", "~/evil-abs/sub")) {
            assertEquals(p, Error.OUTSIDE_HOME, ProjectLocation.check(rootfs, p, emptySet(), false).error)
        }
        assertNull(ProjectLocation.resolve(rootfs, "/home/flux/evil-abs"))
    }

    @Test fun symlinkInsideHomeResolvesToCanonical() {
        link("/home/flux/shortcut", "/home/flux/work")
        val c = ProjectLocation.check(rootfs, "~/shortcut", emptySet(), true)
        assertTrue(c.ok)
        assertEquals("/home/flux/work", c.path)
    }

    @Test fun symlinkLoopRejected() {
        link("/home/flux/a", "/home/flux/b")
        link("/home/flux/b", "/home/flux/a")
        assertNull(ProjectLocation.resolve(rootfs, "/home/flux/a"))
    }

    @Test fun duplicatesRejected() {
        val known = setOf("/home/flux/work/", "/home/flux/repos/app")
        assertEquals(Error.DUPLICATE, ProjectLocation.check(rootfs, "~/work", known, true).error)
        dir("/home/flux/repos/app")
        assertEquals(Error.DUPLICATE, ProjectLocation.checkDest(rootfs, "~/repos", "app", known, false).error)
        assertTrue(ProjectLocation.checkDest(rootfs, "~/repos", "other", known, false).ok)
    }

    @Test fun existenceAndKind() {
        assertEquals(Error.NOT_FOUND, ProjectLocation.check(rootfs, "~/nope", emptySet(), true).error)
        assertTrue(ProjectLocation.check(rootfs, "~/nope/deeper", emptySet(), false).ok) // created later
        File(rootfs, "home/flux/file.txt").writeText("x")
        assertEquals(Error.NOT_DIR, ProjectLocation.check(rootfs, "~/file.txt", emptySet(), true).error)
        assertEquals(Error.NOT_DIR, ProjectLocation.check(rootfs, "~/file.txt/sub", emptySet(), false).error)
    }

    @Test fun notWritableRejectedUnlessProbeOff() {
        val ro = dir("/home/flux/ro")
        if (!ro.setWritable(false) || ro.canWrite()) return // running as root: nothing to assert
        assertEquals(Error.NOT_WRITABLE, ProjectLocation.check(rootfs, "~/ro", emptySet(), true).error)
        assertEquals(Error.NOT_WRITABLE, ProjectLocation.check(rootfs, "~/ro/new", emptySet(), false).error)
        assertTrue(ProjectLocation.check(rootfs, "~/ro", emptySet(), true, checkWrite = false).ok)
        ro.setWritable(true)
    }

    @Test fun destNameAndCloneTarget() {
        assertEquals(Error.BAD_NAME, ProjectLocation.checkDest(rootfs, "~/repos", "", emptySet(), false).error)
        assertEquals(Error.BAD_NAME, ProjectLocation.checkDest(rootfs, "~/repos", "a/b", emptySet(), false).error)
        assertEquals(Error.BAD_NAME, ProjectLocation.checkDest(rootfs, "~/repos", "..", emptySet(), false).error)
        File(dir("/home/flux/repos/full"), "f").writeText("x")
        assertEquals(Error.NOT_EMPTY, ProjectLocation.checkDest(rootfs, "~/repos", "full", emptySet(), true).error)
        assertTrue(ProjectLocation.checkDest(rootfs, "~/repos", "full", emptySet(), false).ok)
        dir("/home/flux/repos/empty")
        assertTrue(ProjectLocation.checkDest(rootfs, "~/repos", "empty", emptySet(), true).ok)
        assertEquals("/home/flux/work/demo", ProjectLocation.checkDest(rootfs, "~/work", "demo", emptySet(), true).path)
    }

    @Test fun slugOfFolderName() {
        assertEquals("my-cool-app", ProjectLocation.slug("My Cool_App"))
        assertEquals("my-app", ProjectLocation.slug("  my   app  "))
        assertEquals("a-b", ProjectLocation.slug("a -- b"))
        assertEquals("helloworld", ProjectLocation.slug("Hello!@#World"))
        assertEquals("hello-world", ProjectLocation.slug("Hello! @# World"))
        assertEquals("helloworld", ProjectLocation.slug("hello/../world"))
        assertEquals("café-déjà", ProjectLocation.slug("Café Déjà"))
        assertEquals("プロジェクト-1", ProjectLocation.slug("プロジェクト 1"))
        assertEquals("v1-2-3", ProjectLocation.slug("v1.2.3".replace('.', '-')))
        assertEquals("project", ProjectLocation.slug(""))
        assertEquals("project", ProjectLocation.slug("!!! ###"))
        assertEquals("project", ProjectLocation.slug("-_-"))
        assertEquals("x", ProjectLocation.slug("", "x"))
    }

    @Test fun openFolderMode() {
        assertEquals(Error.NOT_FOUND, ProjectLocation.checkFolder(rootfs, "~/nope", emptySet(), false).error)
        assertEquals(Error.IS_HOME, ProjectLocation.checkFolder(rootfs, "~", emptySet(), false).error)
        assertEquals(Error.OUTSIDE_HOME, ProjectLocation.checkFolder(rootfs, "/etc", emptySet(), false).error)
        assertEquals(Error.DUPLICATE, ProjectLocation.checkFolder(rootfs, "~/work", setOf("/home/flux/work"), false).error)
        assertEquals("/home/flux/work", ProjectLocation.checkFolder(rootfs, "~/work", emptySet(), false).path)
        File(dir("/home/flux/full2"), "f").writeText("x")
        assertEquals(Error.NOT_EMPTY, ProjectLocation.checkFolder(rootfs, "~/full2", emptySet(), true).error)
        assertTrue(ProjectLocation.checkFolder(rootfs, "~/full2", emptySet(), false).ok)
    }

    @Test fun displayCollapsesHome() {
        assertEquals("~", ProjectLocation.display("/home/flux"))
        assertEquals("~/a/b", ProjectLocation.display("/home/flux/a/b"))
        assertEquals("/etc", ProjectLocation.display("/etc"))
    }

    @Test fun backupCoversCustomProjectFolders() {
        val extra = BackupPlan.extraProjectDirs(
            listOf("/home/flux/repos/a", "/home/flux/work/demo", "/home/flux/work/demo/sub", "/home/flux/nc test/", "/etc/x", "/home/flux"),
        )
        assertEquals(listOf("nc test", "work/demo"), extra)
        val c = BackupPlan.createCommand(BackupScope.PROJECTS, emptyList(), extra)
        assertTrue(c.contains("'repos' 'nc test' 'work/demo'"))
        assertTrue(c.contains("exit 3"))
        assertTrue(c.endsWith("-- \"\$@\""))
    }
}
