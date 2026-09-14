package com.zenithblue.nativecode.agent

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

object AgentBridgeProtocol {

    const val PROTOCOL_VERSION = 1
    const val MAX_CONTROL_FRAME_SIZE = 64 * 1024 // 64 KiB

    fun readControlString(input: InputStream): String {
        val dis = DataInputStream(input)
        val length = dis.readInt()
        if (length < 0 || length > MAX_CONTROL_FRAME_SIZE) {
            throw IllegalArgumentException("Invalid control frame length: $length (max $MAX_CONTROL_FRAME_SIZE)")
        }
        val bytes = ByteArray(length)
        dis.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun writeControlString(output: OutputStream, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_CONTROL_FRAME_SIZE) {
            throw IllegalArgumentException("Content exceeds max control frame size: ${bytes.size}")
        }
        val dos = DataOutputStream(output)
        dos.writeInt(bytes.size)
        dos.write(bytes)
        dos.flush()
    }

    class TokenBucket(
        private val capacity: Double,
        private val refillRatePerSecond: Double
    ) {
        private var tokens: Double = capacity
        private var lastRefillTimestamp: Long = System.nanoTime()

        @Synchronized
        fun tryAcquire(requested: Double = 1.0): Boolean {
            val now = System.nanoTime()
            val elapsedSec = (now - lastRefillTimestamp) / 1_000_000_000.0
            lastRefillTimestamp = now

            tokens = (tokens + elapsedSec * refillRatePerSecond).coerceAtMost(capacity)
            return if (tokens >= requested) {
                tokens -= requested
                true
            } else {
                false
            }
        }
    }
}
