package com.zenithblue.nativecode.media

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONObject

object MediaBridgeProtocol {

    const val PROTOCOL_VERSION = 1
    const val MAX_CONTROL_FRAME_SIZE = 64 * 1024 // 64 KiB
    const val MAX_PACKET_SIZE = 16 * 1024 * 1024 // 16 MiB
    const val MAX_QUEUED_BYTES = 16 * 1024 * 1024 // 16 MiB

    const val MAGIC_NCM1 = 0x4E434D31 // "NCM1"

    const val TYPE_INIT = 0x01
    const val TYPE_PACKET = 0x02
    const val TYPE_EOS = 0x03
    const val TYPE_CLOSE = 0x04
    const val TYPE_ACK = 0x80
    const val TYPE_ERROR = 0x81

    const val FLAG_KEYFRAME = 0x0001
    const val FLAG_CSD = 0x0002

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

    fun readControlFrame(input: InputStream): JSONObject {
        return JSONObject(readControlString(input))
    }

    fun writeControlFrame(output: OutputStream, json: JSONObject) {
        writeControlString(output, json.toString())
    }

    data class NcmHeader(
        val magic: Int,
        val version: Int,
        val type: Int,
        val flags: Int,
        val streamId: Int,
        val ptsUs: Long,
        val payloadLength: Int
    )

    fun readNcmHeader(input: InputStream): NcmHeader {
        val dis = DataInputStream(input)
        val magic = dis.readInt()
        if (magic != MAGIC_NCM1) {
            throw IllegalArgumentException("Invalid packet magic: 0x${Integer.toHexString(magic)}")
        }
        val version = dis.readUnsignedByte()
        if (version != PROTOCOL_VERSION) {
            throw IllegalArgumentException("Unsupported NCM version: $version")
        }
        val type = dis.readUnsignedByte()
        val flags = dis.readUnsignedShort()
        val streamId = dis.readInt()
        val ptsUs = dis.readLong()
        val payloadLength = dis.readInt()
        if (payloadLength < 0 || payloadLength > MAX_PACKET_SIZE) {
            throw IllegalArgumentException("Packet payload size $payloadLength exceeds limit ($MAX_PACKET_SIZE)")
        }
        return NcmHeader(magic, version, type, flags, streamId, ptsUs, payloadLength)
    }

    fun writeNcmHeader(output: OutputStream, header: NcmHeader) {
        val dos = DataOutputStream(output)
        dos.writeInt(header.magic)
        dos.writeByte(header.version)
        dos.writeByte(header.type)
        dos.writeShort(header.flags)
        dos.writeInt(header.streamId)
        dos.writeLong(header.ptsUs)
        dos.writeInt(header.payloadLength)
        dos.flush()
    }

    /**
     * Path security validation: ensures the target file is inside allowed
     * app storage paths and does not escape via ".." or symlinks.
     */
    fun isPathAllowed(requestedPath: String, allowedRoots: List<File>): Boolean {
        if (requestedPath.isBlank() || requestedPath.contains("\u0000")) return false
        val file = File(requestedPath)
        val canonical = try {
            file.canonicalFile
        } catch (e: Exception) {
            return false
        }

        // Must match at least one allowed root
        return allowedRoots.any { root ->
            try {
                val rootCanon = root.canonicalFile
                canonical.startsWith(rootCanon)
            } catch (e: Exception) {
                false
            }
        }
    }
}
