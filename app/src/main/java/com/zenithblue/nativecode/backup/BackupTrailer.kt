package com.zenithblue.nativecode.backup

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * One-file backups: the manifest (with the sha256 of every byte before it) is appended to the `.tar.zst` as a zstd
 * skippable frame, which every zstd / `tar --zstd` reader ignores. Frame = magic 0x184D2A50, payload size, then
 * payload = manifest json + json length (u32) + [END]. Old archives have no trailer and keep their sidecar `.json`.
 */
object BackupTrailer {
    private const val SKIPPABLE_MAGIC = 0x184D2A50
    private val END = "NCBKPTRL".toByteArray(Charsets.US_ASCII)
    private const val MAX_JSON = 48 * 1024
    private const val OVERHEAD = 8 + 4 + 8 // frame header + json length + END
    /** Bytes the scanner holds back from the digest until it knows whether they are the trailer. */
    private const val HOLD = MAX_JSON + OVERHEAD

    fun encode(manifestJson: String): ByteArray {
        val j = manifestJson.toByteArray(Charsets.UTF_8)
        require(j.size <= MAX_JSON) { "manifest too large" }
        val b = ByteBuffer.allocate(OVERHEAD + j.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(SKIPPABLE_MAGIC).putInt(j.size + 12).put(j).putInt(j.size).put(END)
        return b.array()
    }

    class Found(val sha256: String, val json: String?)

    /**
     * Streaming reader: [update] with every chunk of the file, then [finish]. The digest covers everything except a
     * valid trailer at the very end, so a new archive hashes to the sha its manifest holds and a legacy archive to the
     * hash of the whole file.
     */
    class Scanner {
        private val md = MessageDigest.getInstance("SHA-256")
        private var tail = ByteArray(0)

        fun update(buf: ByteArray, off: Int, n: Int) {
            val all = ByteArray(tail.size + n)
            System.arraycopy(tail, 0, all, 0, tail.size)
            System.arraycopy(buf, off, all, tail.size, n)
            val keep = minOf(HOLD, all.size)
            md.update(all, 0, all.size - keep)
            tail = all.copyOfRange(all.size - keep, all.size)
        }

        fun finish(): Found {
            var json: String? = null
            var cut = tail.size
            val n = tail.size
            if (n >= OVERHEAD && (0 until END.size).all { tail[n - END.size + it] == END[it] }) {
                val b = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
                val len = b.getInt(n - 12)
                val start = n - OVERHEAD - len
                if (len in 2..MAX_JSON && start >= 0 && b.getInt(start) == SKIPPABLE_MAGIC && b.getInt(start + 4) == len + 12) {
                    json = String(tail, start + 8, len, Charsets.UTF_8)
                    cut = start
                }
            }
            md.update(tail, 0, cut)
            return Found(md.digest().joinToString("") { "%02x".format(it) }, json)
        }
    }
}
