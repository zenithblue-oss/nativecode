package com.zenithblue.nativecode.onboarding

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Pinned GitHub-release download for the F-Droid flavor (port of FluxLinux RootfsDownloader, HttpURLConnection instead
 * of OkHttp). Pure JVM: no Android classes, so the resume / sha / size gates are unit-tested against a local server.
 *
 * Bytes land in `<name>.partial` (HTTP Range resume), pass an exact-size gate and a sha256 gate against the pin, and
 * only then are renamed atomically to `<name>`. A file that fails the gate is never exposed under the final name.
 */
object ReleaseDownloader {
    /** [sizeBytes] is exact: the gate rejects anything shorter or longer. */
    data class Pin(val fileName: String, val sha256: String, val sizeBytes: Long, val url: String)

    sealed interface Result {
        data object Ok : Result
        data object Cancelled : Result
        /** [retryable] = transient (network drop, 5xx): the `.partial` is kept so a retry resumes. */
        data class Failed(val message: String, val retryable: Boolean) : Result
    }

    const val FREE_SPACE_SLACK_BYTES = 8L * 1024L * 1024L
    private const val CHUNK_BYTES = 64 * 1024

    fun hasEnoughFreeSpace(usableSpace: Long, remaining: Long): Boolean =
        usableSpace >= remaining + FREE_SPACE_SLACK_BYTES

    fun isValid(file: File, pin: Pin, onVerify: (percent: Int) -> Unit = {}): Boolean =
        file.isFile && file.length() == pin.sizeBytes && sha256(file, onVerify).equals(pin.sha256, ignoreCase = true)

    fun sha256(file: File, onProgress: (percent: Int) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val total = file.length().coerceAtLeast(1L)
        var done = 0L
        var lastPct = -1
        file.inputStream().use { input ->
            val buf = ByteArray(CHUNK_BYTES * 4)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                done += n
                val pct = (done * 100 / total).toInt()
                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun openConnection(url: String, rangeFrom: Long): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            // No transparent gzip: it would hide Content-Length and break the exact-size gate.
            setRequestProperty("Accept-Encoding", "identity")
            if (rangeFrom > 0L) setRequestProperty("Range", "bytes=$rangeFrom-")
        }

    /**
     * Makes `destDir/<pin.fileName>` exist and verified. [onProgress] gets (downloadedBytes, totalBytes);
     * [onVerify] gets the sha pass percent. Safe to call again after any failure: it resumes.
     */
    fun ensure(
        destDir: File,
        pin: Pin,
        isCancelled: () -> Boolean = { false },
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        onVerify: (percent: Int) -> Unit = {},
        open: (url: String, rangeFrom: Long) -> HttpURLConnection = ::openConnection,
        usableSpace: () -> Long = { destDir.usableSpace },
    ): Result {
        if (!destDir.isDirectory && !destDir.mkdirs()) return Result.Failed("Cannot create ${destDir.path}", false)
        val dest = File(destDir, pin.fileName)
        val partial = File(destDir, pin.fileName + ".partial")

        if (dest.isFile) {
            if (isValid(dest, pin, onVerify)) return Result.Ok
            dest.delete()
        }
        // A full-size partial (killed during verify/rename) needs no network.
        if (partial.length() == pin.sizeBytes) return finish(partial, dest, pin, onVerify)
        if (partial.length() > pin.sizeBytes) partial.delete()

        for (attempt in 0..1) {
            if (isCancelled()) return Result.Cancelled
            val existing = if (partial.isFile) partial.length() else 0L
            val conn = try {
                open(pin.url, existing)
            } catch (e: IOException) {
                return Result.Failed("Connection failed: ${e.message}", true)
            }
            try {
                val code = try { conn.responseCode } catch (e: IOException) {
                    return Result.Failed("Connection failed: ${e.message}", true)
                }
                val append: Boolean
                when {
                    code == 416 -> { // our offset is not valid for this file: start over once
                        partial.delete()
                        if (attempt == 1) return Result.Failed("HTTP 416 twice", false)
                        continue
                    }
                    code == 206 -> {
                        val start = conn.getHeaderField("Content-Range")
                            ?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                        if (start != existing) { // server resumed somewhere else: unusable, restart clean
                            partial.delete()
                            if (attempt == 1) return Result.Failed("Server returned a different range", false)
                            continue
                        }
                        append = true
                    }
                    code == 200 -> append = false // range ignored: full body, restart the partial
                    else -> return Result.Failed("HTTP $code", code >= 500 || code == 429)
                }
                val base = if (append) existing else 0L
                val bodyLen = conn.contentLengthLong
                if (bodyLen >= 0L && base + bodyLen != pin.sizeBytes) {
                    if (!append) partial.delete()
                    return Result.Failed("Size gate: server offers ${base + bodyLen} bytes, pinned ${pin.sizeBytes}", false)
                }
                val remaining = pin.sizeBytes - base
                val usable = usableSpace()
                if (usable > 0L && !hasEnoughFreeSpace(usable, remaining)) {
                    return Result.Failed("Not enough free space: need $remaining bytes plus slack, have $usable", false)
                }
                var downloaded = base
                try {
                    FileOutputStream(partial, append).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(CHUNK_BYTES)
                            while (true) {
                                if (isCancelled()) return Result.Cancelled
                                val n = input.read(buf)
                                if (n < 0) break
                                if (downloaded + n > pin.sizeBytes) {
                                    out.flush()
                                    partial.delete()
                                    return Result.Failed("Size gate: body longer than pinned ${pin.sizeBytes}", false)
                                }
                                out.write(buf, 0, n)
                                downloaded += n
                                onProgress(downloaded, pin.sizeBytes)
                            }
                        }
                    }
                } catch (e: IOException) {
                    return Result.Failed("Download interrupted: ${e.message}", true) // partial kept: next call resumes
                }
                if (partial.length() != pin.sizeBytes) {
                    return Result.Failed("Size gate: got ${partial.length()} of ${pin.sizeBytes} bytes", true)
                }
                return finish(partial, dest, pin, onVerify)
            } finally {
                conn.disconnect()
            }
        }
        return Result.Failed("Download failed", false)
    }

    private fun finish(partial: File, dest: File, pin: Pin, onVerify: (Int) -> Unit): Result {
        if (!isValid(partial, pin, onVerify)) {
            partial.delete() // corrupt bytes must never be resumed or exposed
            return Result.Failed("SHA-256 mismatch for ${pin.fileName}", false)
        }
        return try {
            Files.move(partial.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Result.Ok
        } catch (e: IOException) {
            Result.Failed("Cannot finalize ${dest.name}: ${e.message}", false)
        }
    }
}
