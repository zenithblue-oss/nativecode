package com.zenithblue.nativecode.onboarding

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReleaseDownloaderTest {
    private lateinit var dir: File
    private lateinit var server: HttpServer
    private val ranges = CopyOnWriteArrayList<String?>()
    private val data = ByteArray(300_000) { (it * 31 + 7).toByte() }

    /** What the next request gets: "ok" honours Range, "ignore" always sends 200, "416" always 416, "short" cuts the body, "long" appends junk. */
    @Volatile private var mode = "ok"
    @Volatile private var served: ByteArray = data

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun pin(bytes: ByteArray = data, sha: String = sha(bytes)) =
        ReleaseDownloader.Pin("pack.bin", sha, bytes.size.toLong(), "http://127.0.0.1:${server.address.port}/pack.bin")

    @Before fun setUp() {
        dir = Files.createTempDirectory("rd").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/pack.bin") { ex -> handle(ex) }
        server.start()
    }

    @After fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun handle(ex: HttpExchange) {
        val range = ex.requestHeaders.getFirst("Range")
        ranges.add(range)
        val body = served
        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull()
        when {
            mode == "416" && start != null -> ex.sendResponseHeaders(416, -1)
            mode == "ok" && start != null -> {
                val part = body.copyOfRange(start.toInt(), body.size)
                ex.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                ex.sendResponseHeaders(206, part.size.toLong())
                ex.responseBody.use { it.write(part) }
            }
            mode == "short" -> {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body, 0, body.size / 2) } // closes early: truncated body
            }
            mode == "long" -> {
                val more = body + ByteArray(10)
                ex.sendResponseHeaders(200, more.size.toLong())
                ex.responseBody.use { it.write(more) }
            }
            else -> { // ok without range, "ignore", "416" first request
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
        }
        ex.close()
    }

    @Test fun downloadsAndVerifies() {
        val progress = mutableListOf<Long>()
        val r = ReleaseDownloader.ensure(dir, pin(), onProgress = { d, _ -> progress.add(d) })
        assertEquals(ReleaseDownloader.Result.Ok, r)
        assertTrue(File(dir, "pack.bin").readBytes().contentEquals(data))
        assertFalse(File(dir, "pack.bin.partial").exists())
        assertEquals(data.size.toLong(), progress.last())
    }

    @Test fun resumesFromPartialWithRange() {
        val cut = 123_456
        File(dir, "pack.bin.partial").writeBytes(data.copyOf(cut))
        val r = ReleaseDownloader.ensure(dir, pin())
        assertEquals(ReleaseDownloader.Result.Ok, r)
        assertEquals("bytes=$cut-", ranges.first())
        assertTrue(File(dir, "pack.bin").readBytes().contentEquals(data))
    }

    @Test fun serverIgnoringRangeRestartsCleanly() {
        mode = "ignore"
        File(dir, "pack.bin.partial").writeBytes(data.copyOf(1000))
        assertEquals(ReleaseDownloader.Result.Ok, ReleaseDownloader.ensure(dir, pin()))
        assertTrue(File(dir, "pack.bin").readBytes().contentEquals(data))
    }

    @Test fun http416RestartsOnce() {
        mode = "416"
        File(dir, "pack.bin.partial").writeBytes(data.copyOf(1000))
        assertEquals(ReleaseDownloader.Result.Ok, ReleaseDownloader.ensure(dir, pin()))
        assertEquals(2, ranges.size)
        assertEquals(null, ranges[1]) // second request has no Range
    }

    @Test fun shaMismatchIsRejectedAndNeverExposed() {
        val wrong = data.copyOf().also { it[10] = (it[10] + 1).toByte() } // same size, different bytes
        served = wrong
        val r = ReleaseDownloader.ensure(dir, pin(sha = sha(data)))
        assertTrue(r is ReleaseDownloader.Result.Failed && !r.retryable)
        assertFalse(File(dir, "pack.bin").exists())
        assertFalse(File(dir, "pack.bin.partial").exists()) // corrupt bytes are not kept for resume
    }

    @Test fun corruptPartialIsRejectedOnResume() {
        // Resumed bytes that do not match the pin: the final sha gate catches them.
        File(dir, "pack.bin.partial").writeBytes(ByteArray(50_000) { 1 })
        val r = ReleaseDownloader.ensure(dir, pin())
        assertTrue(r is ReleaseDownloader.Result.Failed)
        assertFalse(File(dir, "pack.bin").exists())
    }

    @Test fun truncatedBodyKeepsPartialAndIsRetryable() {
        mode = "short"
        val r = ReleaseDownloader.ensure(dir, pin())
        assertTrue(r is ReleaseDownloader.Result.Failed && r.retryable)
        assertFalse(File(dir, "pack.bin").exists())
        assertTrue(File(dir, "pack.bin.partial").length() in 1 until data.size.toLong())
        mode = "ok" // retry resumes from where it stopped and completes
        assertEquals(ReleaseDownloader.Result.Ok, ReleaseDownloader.ensure(dir, pin()))
        assertTrue(File(dir, "pack.bin").readBytes().contentEquals(data))
    }

    @Test fun sizeGateRejectsALongerBody() {
        mode = "long"
        val r = ReleaseDownloader.ensure(dir, pin())
        assertTrue(r is ReleaseDownloader.Result.Failed && !r.retryable)
        assertFalse(File(dir, "pack.bin").exists())
    }

    @Test fun freeSpaceGateStopsBeforeWritingAnything() {
        val r = ReleaseDownloader.ensure(dir, pin(), usableSpace = { 1_000L })
        assertTrue(r is ReleaseDownloader.Result.Failed && !r.retryable)
        assertFalse(File(dir, "pack.bin.partial").exists())
        assertFalse(ReleaseDownloader.hasEnoughFreeSpace(100, 100))
        assertTrue(ReleaseDownloader.hasEnoughFreeSpace(100 + ReleaseDownloader.FREE_SPACE_SLACK_BYTES, 100))
    }

    @Test fun alreadyDeployedFileIsNotDownloadedAgain() {
        File(dir, "pack.bin").writeBytes(data)
        assertEquals(ReleaseDownloader.Result.Ok, ReleaseDownloader.ensure(dir, pin()))
        assertTrue(ranges.isEmpty())
    }

    @Test fun cancelStopsAndKeepsPartial() {
        var chunks = 0
        val r = ReleaseDownloader.ensure(dir, pin(), isCancelled = { chunks++ > 1 })
        assertEquals(ReleaseDownloader.Result.Cancelled, r)
        assertFalse(File(dir, "pack.bin").exists())
    }
}
