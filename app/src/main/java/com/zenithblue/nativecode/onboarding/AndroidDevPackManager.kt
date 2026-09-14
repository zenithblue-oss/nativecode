package com.zenithblue.nativecode.onboarding

import android.content.Context
import android.util.Log
import com.zenithblue.nativecode.terminal.HostCommandBuilder
import com.zenithblue.nativecode.terminal.TermuxHostPaths
import java.io.File
import java.security.MessageDigest

/** Installs the Play-delivered Android SDK 36 overlay into the Alpaquita PRoot. */
object AndroidDevPackManager {
    private const val TAG = "AndroidDevPack"
    const val ARCHIVE_NAME = "nativecode_android_dev.tar.xz"
    const val SHA_NAME = "$ARCHIVE_NAME.sha256"
    const val PINNED_SHA256 = "f4d4aad5b8f78cd68b0254718626576c547ca187cb5c682faaa8b2e0fbc10a2d"

    private const val ROOTFS_REL = "usr/var/lib/proot-distro/containers/alpaquita/rootfs"
    private const val PLATFORM_REL = "opt/android-sdk/platforms/android-36/android.jar"
    private const val AAPT2_REL = "opt/android-sdk/build-tools/36.1.0/aapt2"
    private const val MARKER_REL = "etc/nativecode/android-dev-pack.sha256"
    private const val AAPT2_OVERRIDE =
        "android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/36.1.0/aapt2"

    data class ApplyResult(val success: Boolean, val message: String)

    fun resolveArchive(context: Context): File? {
        val dir = PlayAssetDeliveryManager.getInstalledPackLocation(
            context,
            PlayAssetDeliveryManager.PACK_ANDROID_DEV
        ) ?: return null
        return File(dir, ARCHIVE_NAME).takeIf { it.isFile && it.length() > 2L * 1024L * 1024L }
    }

    fun isApplied(context: Context): Boolean {
        val root = File(context.filesDir, ROOTFS_REL)
        val platform = File(root, PLATFORM_REL)
        val aapt2 = File(root, AAPT2_REL)
        val marker = File(root, MARKER_REL)
        val markerSha = runCatching { marker.readText().trim() }.getOrDefault("")
        return platform.isFile && platform.length() > 1_000_000L &&
            aapt2.isFile && aapt2.length() > 1_000_000L && aapt2.canExecute() &&
            marker.isFile && markerSha.equals(PINNED_SHA256, ignoreCase = true)
    }

    fun applyAvailablePack(context: Context): ApplyResult {
        if (isApplied(context)) return ApplyResult(true, "Android SDK 36 ready")
        val archive = resolveArchive(context)
            ?: return ApplyResult(false, "Android SDK asset pack is not downloaded")
        if (!verifyArchive(archive)) {
            return ApplyResult(false, "Android SDK asset checksum mismatch")
        }

        val root = File(context.filesDir, ROOTFS_REL)
        if (!root.isDirectory) return ApplyResult(false, "Install the Alpaquita runtime first")

        val tar = "${TermuxHostPaths.BIN}/tar"
        val xz = "${TermuxHostPaths.BIN}/xz"
        val linker = "/system/bin/linker64"
        // App-private storage is mounted noexec on some devices. Run both Termux
        // binaries through Android's linker instead of executing them directly.
        val command = "$linker ${quote(xz)} -dc ${quote(archive.absolutePath)} | " +
            "$linker ${quote(tar)} -xf - -C ${quote(root.absolutePath)} " +
            "--no-same-owner --no-same-permissions"
        val shell = TermuxHostPaths.libBash(context).absolutePath
        val process = ProcessBuilder("/system/bin/linker64", shell, "-c", command).also {
            HostCommandBuilder.applyTo(context, it)
            it.redirectErrorStream(true)
        }.start()
        val output = process.inputStream.bufferedReader().use { it.readText().takeLast(8_000) }
        val code = process.waitFor()
        if (code != 0) {
            Log.e(TAG, "Overlay extraction failed ($code): $output")
            return ApplyResult(false, "Android SDK extraction failed ($code)")
        }

        val platform = File(root, PLATFORM_REL)
        val aapt2 = File(root, AAPT2_REL)
        if (!platform.isFile || platform.length() <= 1_000_000L ||
            !aapt2.isFile || aapt2.length() <= 1_000_000L || !aapt2.canExecute()
        ) {
            return ApplyResult(false, "Android SDK verification failed after extraction")
        }

        applyGradleAapt2Override(root)
        File(root, MARKER_REL).apply {
            parentFile?.mkdirs()
            writeText("$PINNED_SHA256\n")
        }
        return ApplyResult(true, "Android SDK 36 + ARM64 aapt2 ready")
    }

    fun verifyArchive(archive: File): Boolean = try {
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
            .equals(PINNED_SHA256, ignoreCase = true)
    } catch (e: Exception) {
        Log.e(TAG, "Checksum verification failed", e)
        false
    }

    private fun applyGradleAapt2Override(root: File) {
        val properties = File(root, "home/flux/.gradle/gradle.properties")
        properties.parentFile?.mkdirs()
        val retained = if (properties.isFile && properties.length() < 1_000_000L) {
            properties.readLines().filterNot { it.startsWith("android.aapt2FromMavenOverride=") }
        } else {
            emptyList()
        }
        properties.writeText((retained + AAPT2_OVERRIDE).joinToString("\n", postfix = "\n"))
    }

    private fun quote(value: String): String = "'${value.replace("'", "'\\''")}'"
}
