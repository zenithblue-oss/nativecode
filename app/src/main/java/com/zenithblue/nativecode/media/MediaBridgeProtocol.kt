package com.zenithblue.nativecode.media

import android.content.Context
import android.os.Environment
import com.zenithblue.nativecode.BuildConfig
import com.zenithblue.nativecode.terminal.ChrootCommandBuilder
import com.zenithblue.nativecode.terminal.ProjectPathResolver
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
     * Default list of roots permitted for media decode inputs and encode outputs.
     */
    fun defaultAllowedRoots(context: Context): List<File> {
        val roots = mutableListOf<File>(
            context.filesDir,
            context.cacheDir
        )
        val ext = Environment.getExternalStorageDirectory()
        if (ext != null) roots.add(ext)
        val emulated = File("/storage/emulated/0")
        if (emulated.exists()) roots.add(emulated)
        val sdcard = File("/sdcard")
        if (sdcard.exists()) roots.add(sdcard)

        try {
            context.getExternalFilesDirs(null).filterNotNull().forEach { roots.add(it) }
            context.externalCacheDirs.filterNotNull().forEach { roots.add(it) }
        } catch (_: Throwable) {}

        if (!BuildConfig.PLAY_STORE_BUILD) {
            roots.add(File(ChrootCommandBuilder.CHROOT_PATH))
        }
        return roots
    }

    /**
     * Resolves a guest path (/home/flux/..., /tmp/..., /sdcard/..., or relative)
     * to the corresponding File on the Android host filesystem.
     */
    fun resolveGuestPath(context: Context, requestedPath: String, mustExist: Boolean): File? {
        if (requestedPath.isBlank() || requestedPath.contains("\u0000")) return null
        val f = File(requestedPath)
        if (mustExist && f.isAbsolute && f.isFile && f.exists()) return f

        val prootDir = ProjectPathResolver.prootRootfsDir(context)

        // Handle guest /tmp paths (mapped to usr/tmp under --shared-tmp)
        if (requestedPath.startsWith("/tmp/")) {
            val relTmp = requestedPath.removePrefix("/tmp/")
            val sharedTmp = File(context.filesDir, "usr/tmp/$relTmp")
            if (mustExist) {
                if (sharedTmp.isFile && sharedTmp.exists()) return sharedTmp
                val prootTmp = File(prootDir, "tmp/$relTmp")
                if (prootTmp.isFile && prootTmp.exists()) return prootTmp
            } else {
                return sharedTmp
            }
        }

        // Handle /sdcard/ and /storage/emulated/0/
        if (requestedPath.startsWith("/sdcard/")) {
            val relSd = requestedPath.removePrefix("/sdcard/")
            val emulatedFile = File("/storage/emulated/0", relSd)
            if (mustExist) {
                if (emulatedFile.isFile && emulatedFile.exists()) return emulatedFile
                val sdFile = File("/sdcard", relSd)
                if (sdFile.isFile && sdFile.exists()) return sdFile
            } else {
                return emulatedFile
            }
        }

        if (requestedPath.startsWith("/storage/emulated/0/")) {
            val emulatedFile = File(requestedPath)
            if (!mustExist || (emulatedFile.isFile && emulatedFile.exists())) {
                return emulatedFile
            }
        }

        // Handle /data/local/tmp/
        if (requestedPath.startsWith("/data/local/tmp/")) {
            val relLocal = requestedPath.removePrefix("/data/local/tmp/")
            val hostTmp = File(context.filesDir, "usr/tmp/$relLocal")
            if (!mustExist || (hostTmp.isFile && hostTmp.exists())) {
                return hostTmp
            }
        }

        // Handle /home/... or other guest absolute paths
        if (requestedPath.startsWith("/")) {
            val relRoot = requestedPath.removePrefix("/")
            val prootFile = File(prootDir, relRoot)
            if (mustExist) {
                if (prootFile.isFile && prootFile.exists()) return prootFile
                val guestResolved = ProjectPathResolver.resolve(context, requestedPath)
                if (guestResolved.isFile && guestResolved.exists()) return guestResolved
                if (f.isFile && f.exists()) return f
            } else {
                return prootFile
            }
        } else {
            // Relative path -> resolve in /home/flux/
            val prootHome = File(prootDir, "home/flux/$requestedPath")
            if (mustExist) {
                if (prootHome.isFile && prootHome.exists()) return prootHome
                val guestResolved = ProjectPathResolver.resolve(context, requestedPath)
                if (guestResolved.isFile && guestResolved.exists()) return guestResolved
            } else {
                return prootHome
            }
        }

        return if (mustExist) null else f
    }

    /**
     * Path security validation: ensures the target file is inside allowed
     * app storage paths and does not escape via ".." or symlinks.
     */
    fun isPathAllowed(targetFile: File, allowedRoots: List<File>): Boolean {
        val canonical = try {
            targetFile.canonicalFile
        } catch (e: Exception) {
            return false
        }

        return allowedRoots.any { root ->
            try {
                val rootCanon = root.canonicalFile
                canonical.startsWith(rootCanon)
            } catch (e: Exception) {
                false
            }
        }
    }

    fun isPathAllowed(requestedPath: String, allowedRoots: List<File>): Boolean {
        if (requestedPath.isBlank() || requestedPath.contains("\u0000")) return false
        return isPathAllowed(File(requestedPath), allowedRoots)
    }
}
