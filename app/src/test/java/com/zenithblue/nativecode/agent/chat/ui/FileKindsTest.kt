package com.zenithblue.nativecode.agent.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindsTest {
    private fun k(n: String) = FileKinds.kindOf(n)

    @Test fun table() {
        listOf("a.png", "a.JPG", "a.jpeg", "a.webp", "a.gif", "a.bmp", "a.heic", "a.heif", "a.avif").forEach { assertEquals(it, FileType.IMAGE, k(it)) }
        assertEquals(FileType.SVG, k("logo.svg"))
        listOf("a.mp4", "a.webm", "a.mkv", "a.mov", "a.3gp").forEach { assertEquals(it, FileType.VIDEO, k(it)) }
        listOf("a.mp3", "a.m4a", "a.wav", "a.ogg", "a.flac").forEach { assertEquals(it, FileType.AUDIO, k(it)) }
        assertEquals(FileType.PDF, k("Report.PDF"))
        assertEquals(FileType.MARKDOWN, k("README.md"))
        assertEquals(FileType.MARKDOWN, k("a.markdown"))
        assertEquals(FileType.HTML, k("index.html"))
        assertEquals(FileType.HTML, k("index.HTM"))
        listOf("a.docx", "a.xlsx", "a.pptx", "a.odt").forEach { assertEquals(it, FileType.OFFICE, k(it)) }
        assertEquals(FileType.APK, k("app-release.apk"))
        listOf("a.kt", "a.json", "a.csv", "a.tsv", "a.txt", "a.log", "Dockerfile", "Makefile", ".env", ".gitignore").forEach { assertEquals(it, FileType.TEXT, k(it)) }
    }

    @Test fun unknownAndOddNames() {
        assertEquals(FileType.OTHER, k("archive.tar.gz"))
        assertEquals(FileType.OTHER, k("noext"))
        assertEquals(FileType.OTHER, k(""))
        // The last extension wins; a dot in a directory-like name is not an extension.
        assertEquals(FileType.IMAGE, k("shot.final.png"))
        assertEquals(FileType.OTHER, k("png"))
    }

    @Test fun inlinePreviewKinds() {
        val inline = FileType.values().filter { FileKinds.hasInlinePreview(it) }.toSet()
        assertEquals(setOf(FileType.IMAGE, FileType.SVG, FileType.VIDEO, FileType.AUDIO, FileType.PDF), inline)
    }

    @Test fun mime() {
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", FileKinds.mimeOf("a.docx"))
        assertEquals("application/vnd.oasis.opendocument.text", FileKinds.mimeOf("a.odt"))
        assertEquals("application/pdf", FileKinds.mimeOf("a.pdf"))
        assertEquals("image/*", FileKinds.mimeOf("a.png"))
        assertEquals("*/*", FileKinds.mimeOf("a.bin"))
    }

    @Test fun markdownFileRefs() {
        // image syntax: any local target, strict
        assertEquals(Md.Node.FileRef("![chart](out/chart.png)", "out/chart.png", true), Md.fileRef("![chart](out/chart.png)"))
        assertEquals(Md.Node.FileRef("![](/home/flux/a.pdf)", "/home/flux/a.pdf", true), Md.fileRef("![](/home/flux/a.pdf)"))
        assertEquals(true, Md.fileRef("![x](file:///home/flux/a.png)")?.strict)
        // remote images stay plain links
        assertNull(Md.fileRef("![x](https://example.com/a.png)"))
        assertNull(Md.fileRef("![x](data:image/png;base64,AAAA)"))
        // plain link or backticked path: only previewable kinds, not strict
        assertEquals(false, Md.fileRef("[the chart](out/chart.png)")?.strict)
        assertEquals(false, Md.fileRef("`/home/flux/demo.mp4`")?.strict)
        assertNull(Md.fileRef("[notes](docs/notes.md)"))
        assertNull(Md.fileRef("`src/Main.kt`"))
        assertNull(Md.fileRef("[site](https://example.com/a.png)"))
        // more than the file on the line is a paragraph
        assertNull(Md.fileRef("see ![x](a.png) here"))
        assertTrue(Md.block("![x](a.png)", false) is Md.Node.FileRef)
        assertFalse(Md.block("![x](a.png)\nsecond line", false) is Md.Node.FileRef)
    }

    @Test fun sampleSizeIsBounded() {
        assertEquals(1, FileThumbs.sampleSize(0, 0, 128))
        assertEquals(1, FileThumbs.sampleSize(100, 100, 128))
        assertEquals(4, FileThumbs.sampleSize(1024, 1024, 256)) // shorter side stays >= 256
        // 12 MP photo at a 720 target: pixel cap (4 MP) forces at least 2
        assertTrue(FileThumbs.sampleSize(4000, 3000, 720) >= 2)
        // a 1 x 20000 strip cannot stay at full size
        val s = FileThumbs.sampleSize(2000, 20000, 128)
        assertTrue((2000L / s) * (20000L / s) <= 4_000_000L)
    }
}
