package com.zenithblue.nativecode.onboarding

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The 1.8.0 runtime delta on top of the 1.7.1 rootfs: opencode 2.3.0, GitHub CLI, the rebuilt zsh, OpenSSH 10.6
 * (server + client) and the pinned ccusage 20.0.49, delivered as one Play asset pack (`nativecode_update_core`,
 * about 140 MB) instead of the whole 1.3 GB rootfs.
 *
 * The 1.8.0 `nativecode_runtime` pack is byte-identical to 1.7.1's, so Play has nothing to patch on update and
 * existing users download only this pack. A FRESH install extracts the same 1.7.1 archive and then applies this
 * pack right after (OnboardingActivity), so fresh and upgraded installs end with the same files and the same marker.
 *
 * Applied with plain Kotlin file I/O: stream-verify the pinned sha256, then unpack a tar.gz into the
 * rootfs, every file written to a temp name and renamed over the old one. No shell, no guest command.
 */
object RuntimeUpdatePack {
    private const val TAG = "RuntimeUpdatePack"
    const val ARCHIVE_NAME = "nativecode_update.tgz"
    const val SHA_NAME = "$ARCHIVE_NAME.sha256"
    const val PINNED_SHA256 = "2ddd44b20ac446a41186189912772f86b34871a0bb9f7345535d522b05de3611"
    const val MARKER_REL = "etc/nativecode/update-pack-1.8.0.sha256"

    private const val MIN_FREE_BYTES = 400L * 1024L * 1024L // opencode alone unpacks to ~254 MB
    private var promptedThisProcess = false

    data class ApplyResult(val success: Boolean, val message: String)

    fun isApplied(rootfs: File): Boolean =
        runCatching { File(rootfs, MARKER_REL).readText().trim() }.getOrDefault("")
            .equals(PINNED_SHA256, ignoreCase = true)

    fun isNeeded(context: Context): Boolean {
        val root = ProjectPathResolver.prootRootfsDir(context)
        return root.isDirectory && File(root, "etc").isDirectory && !isApplied(root) && isGlibcRootfs(root)
    }

    /** The pack ships glibc builds (opencode, zsh): an old musl Alpine rootfs cannot run them. */
    internal fun isGlibcRootfs(rootfs: File): Boolean = listOf("lib", "usr/lib", "lib64").any {
        Files.exists(File(rootfs, "$it/ld-linux-aarch64.so.1").toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    fun resolveArchive(context: Context): File? {
        PlayAssetDeliveryManager.getInstalledPackLocation(context, PlayAssetDeliveryManager.PACK_UPDATE_CORE)
            ?.let { File(it, ARCHIVE_NAME) }?.takeIf { it.isFile }?.let { return it }
        return listOf(
            File(context.filesDir, "assets/$ARCHIVE_NAME"),
            File(context.filesDir, ARCHIVE_NAME),
            File("/data/local/tmp", ARCHIVE_NAME),
        ).firstOrNull { it.isFile && it.length() > MIN_ARCHIVE_BYTES }
    }

    private const val MIN_ARCHIVE_BYTES = 1L * 1024L * 1024L

    fun apply(context: Context): ApplyResult {
        val root = ProjectPathResolver.prootRootfsDir(context)
        if (!root.isDirectory) return ApplyResult(false, "Install the Linux runtime first")
        if (isApplied(root)) return ApplyResult(true, "Runtime already up to date").also { removePack(context) }
        if (!isGlibcRootfs(root)) {
            return ApplyResult(false, "Runtime update skipped: it needs the Alpaquita (glibc) Linux runtime, not the old Alpine one")
        }
        val archive = resolveArchive(context)
            ?: return ApplyResult(false, "Runtime update pack is not downloaded")
        return applyTo(root, archive).also { if (it.success) removePack(context) }
    }

    /** Drop the ~140 MB on-demand pack once applied; the marker in the rootfs keeps [isNeeded] false. */
    private fun removePack(context: Context) =
        PlayAssetDeliveryManager.removePack(context, PlayAssetDeliveryManager.PACK_UPDATE_CORE)

    /**
     * 1.7.1 never removed the multi-GB `nativecode_runtime` pack, so it stays on every existing install and Play keeps
     * patching it on updates. Once the rootfs is installed nothing reads it again, so drop it (also frees ~1.3 GB).
     * A reinstall requests it from Play again through the onboarding delivery page.
     */
    fun dropInstalledRuntimePack(context: Context) {
        val root = ProjectPathResolver.prootRootfsDir(context)
        if (!File(root, "etc/nativecode/rootfs-release").isFile) return
        PlayAssetDeliveryManager.removePack(context, PlayAssetDeliveryManager.PACK_RUNTIME)
    }

    /** Pure JVM and idempotent: used by [apply] and by the unit tests. */
    internal fun applyTo(rootfs: File, archive: File, expectedSha256: String = PINNED_SHA256): ApplyResult {
        if (isMarked(rootfs, expectedSha256)) return ApplyResult(true, "Runtime already up to date")
        val actual = try {
            sha256(archive)
        } catch (e: IOException) {
            return ApplyResult(false, "Runtime update unreadable: ${e.message}")
        }
        if (!actual.equals(expectedSha256, ignoreCase = true)) {
            return ApplyResult(false, "Runtime update checksum mismatch")
        }
        if (rootfs.usableSpace < MIN_FREE_BYTES) {
            return ApplyResult(false, "Not enough free storage for the runtime update (need 400 MB)")
        }
        return try {
            val count = extract(archive, rootfs)
            val marker = File(rootfs, MARKER_REL)
            marker.parentFile?.mkdirs()
            marker.writeText("$expectedSha256\n")
            marker.setReadable(true, false)
            ApplyResult(true, "Runtime updated ($count files)")
        } catch (e: IOException) {
            ApplyResult(false, "Runtime update failed: ${e.message}")
        }
    }

    private fun isMarked(rootfs: File, sha: String) =
        runCatching { File(rootfs, MARKER_REL).readText().trim() }.getOrDefault("").equals(sha, ignoreCase = true)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── tar.gz (USTAR: regular files and directories only) ───────────────────────────────────────

    private fun extract(archive: File, rootfs: File): Int {
        val rootCanon = rootfs.canonicalFile
        var files = 0
        GZIPInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 20), 1 shl 16).use { tar ->
            val header = ByteArray(512)
            while (readFully(tar, header)) {
                if (header.all { it == 0.toByte() }) break
                val name = cstr(header, 345, 155).let { p -> cstr(header, 0, 100).let { n -> if (p.isEmpty()) n else "$p/$n" } }
                val size = octal(header, 124, 12)
                val mode = octal(header, 100, 8).toInt()
                val type = header[156].toInt().toChar()
                val padded = (size + 511L) / 512L * 512L
                val target = resolve(rootCanon, name, isDir = type == '5')
                when (type) {
                    '5' -> {
                        target.setReadable(true, false)
                        target.setExecutable(true, false)
                    }
                    '0', '\u0000' -> {
                        writeFile(tar, target, size, mode)
                        skipFully(tar, padded - size)
                        files++
                    }
                    else -> throw IOException("unsupported entry type '$type' for $name")
                }
            }
        }
        return files
    }

    /**
     * Entry path -> file inside [rootCanon]; rejects absolute paths, "..", and folders that link out of the
     * rootfs (checked on the deepest existing folder BEFORE mkdirs, so nothing is created through the link).
     */
    private fun resolve(rootCanon: File, name: String, isDir: Boolean = false): File {
        val rel = name.trimEnd('/')
        if (rel.isEmpty() || rel.startsWith("/") || rel.split('/').any { it == ".." }) {
            throw IOException("unsafe path in update pack: $name")
        }
        val target = File(rootCanon, rel)
        val dir = if (isDir) target else target.parentFile!!
        var probe = dir
        while (!Files.exists(probe.toPath(), LinkOption.NOFOLLOW_LINKS)) probe = probe.parentFile ?: break
        checkInside(rootCanon, probe, rel)
        dir.mkdirs()
        checkInside(rootCanon, dir, rel)
        return target
    }

    private fun checkInside(rootCanon: File, dir: File, rel: String) {
        val canon = dir.canonicalFile
        if (canon != rootCanon && !canon.path.startsWith(rootCanon.path + File.separator)) {
            throw IOException("${dir.relativeToOrSelf(rootCanon)} links outside the Linux runtime ($rel); replace the link with a real folder to update")
        }
    }

    private fun writeFile(input: InputStream, target: File, size: Long, mode: Int) {
        val temp = File(target.parentFile, ".${target.name}.nc-update")
        try {
            temp.outputStream().use { out ->
                val buffer = ByteArray(1 shl 16)
                var left = size
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("update pack truncated: ${target.name}")
                    out.write(buffer, 0, n)
                    left -= n
                }
            }
            temp.setReadable(true, false)
            temp.setWritable(true, true)
            temp.setExecutable(mode and 0b001_000_000 != 0, false)
            // Moves the link itself when the old file is a PRoot .l2s symlink, never what it points at.
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return if (off == 0) false else throw IOException("update pack truncated")
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val sink = ByteArray(512)
        while (left > 0) {
            val n = input.read(sink, 0, minOf(sink.size.toLong(), left).toInt())
            if (n < 0) throw IOException("update pack truncated")
            left -= n
        }
    }

    private fun cstr(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long =
        cstr(b, off, len).trim().ifEmpty { "0" }.toLong(8)

    // ── prompt + download + apply (existing users only) ─────────────────────────────────────────

    // What the Components card shows (no dialog: the update lives on Settings > Components).
    const val TITLE = "Linux runtime update"
    const val FROM_TO = "1.7 runtime to 1.8.0 runtime"
    const val SIZE = "About 140 MB download"
    const val CHANGES = "opencode 2.3.0, GitHub CLI 2.104, a zsh fix, OpenSSH 10.6 and ccusage 20.0.49. Replaces ~/.opencode/bin/opencode, /usr/local/bin/gh, zsh and the OpenSSH files (a newer self-updated opencode is replaced too); your other files are not touched."

    /** App start: a pack already on the device is applied silently; otherwise the Components card offers it. */
    fun applyIfDownloaded(app: Context) {
        if (promptedThisProcess || !isNeeded(app) || resolveArchive(app) == null) return
        promptedThisProcess = true
        Thread {
            val r = apply(app)
            Log.i(TAG, r.message)
            com.zenithblue.nativecode.telemetry.Telemetry.componentUpdate(app, "runtime_pack", r.success)
        }.start()
    }

    sealed interface Progress {
        data class Running(val text: String, val percent: Int?) : Progress
        data class Finished(val ok: Boolean, val message: String) : Progress
    }

    /** Download (if needed) then apply. [onState] runs on the main thread. */
    fun update(activity: Activity, onState: (Progress) -> Unit) {
        val app = activity.applicationContext
        val main = Handler(Looper.getMainLooper())
        download(activity, onState) {
            onState(Progress.Running("Applying the runtime update…", null))
            Thread {
                val r = apply(app)
                Log.i(TAG, r.message)
                main.post { onState(Progress.Finished(r.success, r.message)) }
            }.start()
        }
    }

    /**
     * Makes sure the pack is on the device (asks Play if not), without applying it: the fresh-install delivery page uses
     * this before any rootfs exists. [onReady] runs on the main thread once the archive is available; a failure or
     * cancel is reported as [Progress.Finished] with ok = false.
     */
    fun download(activity: Activity, onState: (Progress) -> Unit, onReady: () -> Unit) {
        val app = activity.applicationContext
        if (resolveArchive(app) != null) return onReady()
        onState(Progress.Running("Requesting the update from Google Play…", null))
        var listener: com.google.android.play.core.assetpacks.AssetPackStateUpdateListener? = null
        listener = PlayAssetDeliveryManager.requestDownload(activity, PlayAssetDeliveryManager.PACK_UPDATE_CORE) { state ->
            when (state) {
                is PlayAssetDeliveryManager.DownloadState.Pending -> onState(Progress.Running("Download pending…", null))
                is PlayAssetDeliveryManager.DownloadState.Downloading ->
                    onState(Progress.Running("Downloading ${state.percentage}%", state.percentage))
                is PlayAssetDeliveryManager.DownloadState.Transferring ->
                    onState(Progress.Running("Unpacking ${state.percentage}%", state.percentage))
                is PlayAssetDeliveryManager.DownloadState.WaitingForWifi -> onState(Progress.Running("Waiting for Wi-Fi approval…", null))
                is PlayAssetDeliveryManager.DownloadState.Completed -> {
                    listener?.let { PlayAssetDeliveryManager.unregisterListener(app, it) }
                    onReady()
                }
                is PlayAssetDeliveryManager.DownloadState.Failed -> {
                    listener?.let { PlayAssetDeliveryManager.unregisterListener(app, it) }
                    onState(Progress.Finished(false, "Download failed (${state.errorCode})"))
                }
                is PlayAssetDeliveryManager.DownloadState.Canceled -> {
                    listener?.let { PlayAssetDeliveryManager.unregisterListener(app, it) }
                    onState(Progress.Finished(false, "Download canceled"))
                }
                else -> Unit
            }
        }
    }
}
