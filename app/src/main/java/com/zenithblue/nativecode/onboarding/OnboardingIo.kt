package com.zenithblue.nativecode.onboarding

import android.content.res.AssetManager
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/**
 * Archive + asset IO extracted from OnboardingActivity.
 * Pure helpers; log lines are emitted via [onLog] so the caller owns UI.
 */
internal object OnboardingIo {

    fun copyAssetWithProgress(
        assets: AssetManager,
        assetName: String,
        dest: File,
        onProgress: (fraction: Float, detail: String) -> Unit
    ) {
        val total = try {
            assets.openFd(assetName).use { it.length }
        } catch (_: Exception) {
            -1L
        }
        assets.open(assetName).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(64 * 1024)
                var readTotal = 0L
                var lastUi = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    readTotal += n
                    val now = System.currentTimeMillis()
                    if (now - lastUi < 100) continue
                    lastUi = now
                    if (total > 0) {
                        val frac = (readTotal.toFloat() / total).coerceIn(0f, 1f)
                        onProgress(
                            frac,
                            "Copied ${formatBytes(readTotal)} / ${formatBytes(total)}"
                        )
                    } else {
                        onProgress(0.3f, tr(R.string.onboarding_copied, formatBytes(readTotal)))
                    }
                }
            }
        }
        if (total > 0) onProgress(1f, "Copied ${formatBytes(total)}")
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    fun moveDirectoryContents(source: File, destination: File) {
        if (!source.exists()) return
        destination.mkdirs()
        val files = source.listFiles() ?: return
        for (file in files) {
            val destFile = File(destination, file.name)
            if (file.isDirectory) {
                moveDirectoryContents(file, destFile)
                file.delete()
            } else {
                file.renameTo(destFile)
            }
        }
        source.delete()
    }

    /**
     * Minimal tar reader for bootstrap.tar (GNU format).
     * Supports GNU LongName/LongLink ('L'/'K'), ustar prefix, symlinks, dirs.
     * System tar is tried first by the caller; this is the fallback.
     */
    fun extractTarStream(
        inputStream: InputStream,
        targetDir: File,
        onLog: (String) -> Unit = {}
    ) {
        val buffer = ByteArray(512)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        fun readExact(buf: ByteArray, off: Int, len: Int): Int {
            var total = 0
            while (total < len) {
                val r = inputStream.read(buf, off + total, len - total)
                if (r == -1) break
                total += r
            }
            return total
        }
        fun readStringData(size: Long): String {
            val baos = java.io.ByteArrayOutputStream()
            var remaining = size
            val copyBuf = ByteArray(8192)
            while (remaining > 0) {
                val toRead = Math.min(copyBuf.size.toLong(), remaining).toInt()
                val r = inputStream.read(copyBuf, 0, toRead)
                if (r == -1) break
                baos.write(copyBuf, 0, r)
                remaining -= r
            }
            val padding = ((512 - (size % 512)) % 512).toInt()
            if (padding > 0) skipBytes(inputStream, padding.toLong())
            val bytes = baos.toByteArray()
            var end = bytes.size
            for (i in bytes.indices) if (bytes[i] == 0.toByte()) { end = i; break }
            return String(bytes, 0, end, Charsets.UTF_8)
        }
        while (true) {
            val bytesRead = readExact(buffer, 0, 512)
            if (bytesRead < 512) break

            var allZero = true
            for (b in buffer) {
                if (b != 0.toByte()) { allZero = false; break }
            }
            if (allZero) {
                // Two consecutive zero blocks end archive; single zero may pad — peek next
                // Simplest: break (matches prior behavior for our bootstrap).
                break
            }

            fun parseString(offset: Int, length: Int): String {
                var len = 0
                while (len < length && buffer[offset + len] != 0.toByte()) { len++ }
                return String(buffer, offset, len, Charsets.UTF_8)
            }

            var name = parseString(0, 100)
            val prefix = parseString(345, 155)
            if (prefix.isNotEmpty()) name = "$prefix/$name"

            val sizeStr = parseString(124, 12).trim().split(" ").firstOrNull().orEmpty()
            val size = try { if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8) } catch (e: Exception) { 0L }
            val type = buffer[156].toInt().toChar()
            var linkName = parseString(157, 100)

            // GNU LongName '././@LongLink' (type L/K) — next header uses this full name
            if (type == 'L') {
                pendingLongName = readStringData(size)
                continue
            }
            if (type == 'K') {
                pendingLongLink = readStringData(size)
                continue
            }
            // pax extended headers (x/g) — skip payload, keep pending longname if any
            if (type == 'x' || type == 'g') {
                skipBytes(inputStream, ((size + 511) / 512) * 512)
                continue
            }
            if (pendingLongName != null) { name = pendingLongName!!; pendingLongName = null }
            if (pendingLongLink != null) { linkName = pendingLongLink!!; pendingLongLink = null }

            val relPath = name.replace("^data/data/${Regex.escape(com.zenithblue.nativecode.terminal.TermuxHostPaths.PACKAGE)}/files/".toRegex(), "").trimStart('/')
            if (relPath.isEmpty() || relPath.contains("..")) {
                val dataBlocks = (size + 511) / 512
                skipBytes(inputStream, dataBlocks * 512L)
                continue
            }

            val outFile = File(targetDir, relPath)

            if (type == '5') {
                // dir exists as file → replace; else mkdirs
                if (outFile.isFile) outFile.delete()
                outFile.mkdirs()
                if (size > 0) skipBytes(inputStream, ((size + 511) / 512) * 512)
            } else if (type == '2') {
                outFile.parentFile?.mkdirs()
                if (outFile.exists()) { try { outFile.deleteRecursively() } catch (_: Exception) {} }
                try {
                    Os.symlink(linkName, outFile.absolutePath)
                } catch (e: Exception) {
                    try {
                        val linkTarget = File(outFile.parentFile, linkName)
                        if (linkTarget.exists() && linkTarget.isFile) {
                            linkTarget.copyTo(outFile, overwrite = true)
                        }
                    } catch (_: Exception) {}
                }
                if (size > 0) skipBytes(inputStream, ((size + 511) / 512) * 512)
            } else if (type == '0' || type == '\u0000') {
                outFile.parentFile?.mkdirs()
                // file path collides with existing dir (truncation/case) → remove dir first
                if (outFile.isDirectory) { try { outFile.deleteRecursively() } catch (_: Exception) {} }
                try {
                    FileOutputStream(outFile).use { fos ->
                        var remaining = size
                        val copyBuf = ByteArray(8192)
                        while (remaining > 0) {
                            val toRead = Math.min(copyBuf.size.toLong(), remaining).toInt()
                            val r = inputStream.read(copyBuf, 0, toRead)
                            if (r == -1) break
                            fos.write(copyBuf, 0, r)
                            remaining -= r
                        }
                    }
                } catch (e: Exception) {
                    // Surface with full path so onboarding log shows cause, then skip payload
                    onLog(">>> extract skip (EISDIR/collision): $relPath: ${e.message}\n")
                    var remaining = size
                    val copyBuf = ByteArray(8192)
                    while (remaining > 0) {
                        val toRead = Math.min(copyBuf.size.toLong(), remaining).toInt()
                        val r = inputStream.read(copyBuf, 0, toRead)
                        if (r == -1) break
                        remaining -= r
                    }
                }
                val padding = ((512 - (size % 512)) % 512).toInt()
                if (padding > 0) {
                    skipBytes(inputStream, padding.toLong())
                }
                val modeStr = parseString(100, 8).trim()
                try {
                    val mode = modeStr.toInt(8)
                    if ((mode and 73) != 0) {
                        outFile.setExecutable(true, false)
                    }
                } catch (_: Exception) {}
            } else {
                val dataBlocks = (size + 511) / 512
                skipBytes(inputStream, dataBlocks * 512L)
            }
        }
    }

    fun skipBytes(inputStream: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = Math.min(buf.size.toLong(), remaining).toInt()
            val r = inputStream.read(buf, 0, toRead)
            if (r == -1) break
            remaining -= r
        }
    }
}
