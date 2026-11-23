package com.zenithblue.nativecode.agent.chat.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** ChatFiles.resolve: the one path to host-file resolution of the tap handler and the inline preview cards. */
class ChatFilesTest {
    private lateinit var rootfs: File
    private val cwd = "/home/flux/repos/app"

    @Before fun setUp() {
        rootfs = Files.createTempDirectory("rootfs").toFile()
        File(rootfs, "home/flux/repos/app/out").mkdirs()
        File(rootfs, "home/flux/repos/app/out/chart.png").writeText("x")
        File(rootfs, "home/flux/notes.md").writeText("x")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/passwd").writeText("secret")
    }

    @After fun tearDown() { rootfs.deleteRecursively() }

    private fun r(raw: String) = ChatFiles.resolve(rootfs, raw, cwd)

    @Test fun relativeAbsoluteTildeAndFileUri() {
        val want = File(rootfs, "home/flux/repos/app/out/chart.png")
        assertEquals(want, r("out/chart.png"))
        assertEquals(want, r("./out/chart.png"))
        assertEquals(want, r("/home/flux/repos/app/out/chart.png"))
        assertEquals(want, r("file:///home/flux/repos/app/out/chart.png"))
        assertEquals(want, r("out/chart.png:12"))
        assertEquals(File(rootfs, "home/flux/notes.md"), r("~/notes.md"))
    }

    @Test fun missingAndDirectoriesAreNull() {
        assertNull(r("out/nope.png"))
        assertNull(r("out")) // a directory is not a file
    }

    @Test fun neverLeavesTheGuestHome() {
        assertNull(r("/etc/passwd"))
        assertNull(r("../../../../etc/passwd"))
        assertNull(r("file:///etc/passwd"))
        assertNull(r("https://example.com/a.png"))
        assertNull(r("../../../../../../../../etc/passwd"))
    }

    @Test fun symlinksAreFoldedInsideTheGuest() {
        // Absolute link targets are relative to the rootfs, not the Android root.
        Files.createSymbolicLink(File(rootfs, "home/flux/link-to-chart.png").toPath(), java.nio.file.Paths.get("/home/flux/repos/app/out/chart.png"))
        assertEquals(File(rootfs, "home/flux/repos/app/out/chart.png"), r("/home/flux/link-to-chart.png"))
        // A link out of the home is refused.
        Files.createSymbolicLink(File(rootfs, "home/flux/evil").toPath(), java.nio.file.Paths.get("/etc/passwd"))
        assertNull(r("/home/flux/evil"))
    }
}
