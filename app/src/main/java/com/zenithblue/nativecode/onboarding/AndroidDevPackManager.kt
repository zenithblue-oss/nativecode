package com.zenithblue.nativecode.onboarding

import android.content.Context
import android.util.Log
import com.zenithblue.nativecode.BuildConfig
import com.zenithblue.nativecode.terminal.HostCommandBuilder
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import com.zenithblue.nativecode.terminal.TermuxHostPaths
import java.io.File
import java.security.MessageDigest
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/** Installs the Play-delivered Android SDK 36 overlay into the Linux PRoot. */
object AndroidDevPackManager {
    private const val TAG = "AndroidDevPack"
    const val ARCHIVE_NAME = "nativecode_android_dev.tar.xz"
    const val SHA_NAME = "$ARCHIVE_NAME.sha256"
    const val PINNED_SHA256 = "f4d4aad5b8f78cd68b0254718626576c547ca187cb5c682faaa8b2e0fbc10a2d"

    private const val PLATFORM_REL = "opt/android-sdk/platforms/android-36/android.jar"
    private const val AAPT2_REL = "opt/android-sdk/build-tools/36.1.0/aapt2"
    private const val MARKER_REL = "etc/nativecode/android-dev-pack.sha256"
    private const val AAPT2_OVERRIDE =
        "android.aapt2FromMavenOverride=/opt/android-sdk/build-tools/36.1.0/aapt2"

    private const val PREFS_NAME = "nativecode_android_sdk"
    private const val KEY_APPLIED = "sdk_applied"

    data class ApplyResult(val success: Boolean, val message: String)

    fun resolveArchive(context: Context): File? {
        val dir = PlayAssetDeliveryManager.getInstalledPackLocation(
            context,
            PlayAssetDeliveryManager.PACK_ANDROID_DEV
        )
        if (dir != null) {
            val f = File(dir, ARCHIVE_NAME)
            if (f.isFile && f.length() > 2L * 1024L * 1024L) return f
        }

        pickLocal(localCandidates(context.filesDir, BuildConfig.DEBUG && !BuildConfig.FDROID))?.let { return it }

        try {
            val list = context.assets.list("") ?: emptyArray()
            if (ARCHIVE_NAME in list) {
                val dest = File(context.filesDir, "assets/$ARCHIVE_NAME")
                if (!dest.isFile || dest.length() < 2L * 1024L * 1024L) {
                    dest.parentFile?.mkdirs()
                    context.assets.open(ARCHIVE_NAME).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                if (dest.isFile && dest.length() > 2L * 1024L * 1024L) return dest
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bundled asset resolve failed: ${e.message}")
        }
        return null
    }

    /** Dev sideload paths, DEBUG only: release never reads /data/local/tmp or Download (the APK asset fallback below stays). */
    internal fun localCandidates(filesDir: File, debug: Boolean): List<File> =
        if (!debug) emptyList() else listOf(
            File(filesDir, "assets/$ARCHIVE_NAME"),
            File(filesDir, ARCHIVE_NAME),
            File("/data/local/tmp", ARCHIVE_NAME),
            File("/sdcard/Download", ARCHIVE_NAME),
            File("/storage/emulated/0/Download", ARCHIVE_NAME),
        )

    /** A local candidate counts only if it matches the pin, so a stale one falls through to Play. */
    internal fun pickLocal(local: List<File>, matchesPin: (File) -> Boolean = ::matchesPinCached): File? =
        local.firstOrNull { it.isFile && it.length() > 2L * 1024L * 1024L && matchesPin(it) }

    private val pinCache = HashMap<String, Boolean>()

    // Memoised per file identity: resolveArchive is called often (DEBUG candidates only).
    private fun matchesPinCached(f: File): Boolean = synchronized(pinCache) {
        pinCache.getOrPut("${f.path}|${f.length()}|${f.lastModified()}") { verifyArchive(f) }
    }

    fun isApplied(context: Context): Boolean {
        val root = ProjectPathResolver.prootRootfsDir(context)
        val platform = File(root, PLATFORM_REL)
        val aapt2 = File(root, AAPT2_REL)
        if (aapt2.isFile && !aapt2.canExecute()) {
            runCatching { aapt2.setExecutable(true, false) }
        }
        val marker = File(root, MARKER_REL)
        val markerSha = runCatching { marker.readText().trim() }.getOrDefault("")

        val filesPresent = platform.isFile && platform.length() > 1_000_000L &&
            aapt2.isFile && aapt2.length() > 1_000_000L

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_APPLIED, false) && filesPresent) {
            return true
        }

        val verified = filesPresent &&
            marker.isFile && markerSha.equals(PINNED_SHA256, ignoreCase = true)

        if (verified) {
            prefs.edit().putBoolean(KEY_APPLIED, true).apply()
        }
        return verified
    }

    fun applyAvailablePack(context: Context, force: Boolean = false): ApplyResult {
        if (!force && isApplied(context)) return ApplyResult(true, tr(R.string.onb_android_sdk_36_ready))
        val archive = resolveArchive(context)
            ?: return ApplyResult(false, tr(R.string.onb_android_sdk_asset_pack_is))
        if (!verifyArchive(archive)) {
            return ApplyResult(false, tr(R.string.onb_android_sdk_asset_checksum_mismatch))
        }

        val root = ProjectPathResolver.prootRootfsDir(context)
        if (!root.isDirectory) return ApplyResult(false, tr(R.string.onb_install_the_linux_runtime_first))

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
            return ApplyResult(false, tr(R.string.onb_android_sdk_extraction_failed, code))
        }

        val platform = File(root, PLATFORM_REL)
        val aapt2 = File(root, AAPT2_REL)
        runCatching { aapt2.setExecutable(true, false) }
        if (!platform.isFile || platform.length() <= 1_000_000L ||
            !aapt2.isFile || aapt2.length() <= 1_000_000L
        ) {
            return ApplyResult(false, tr(R.string.onb_android_sdk_verification_failed_afte))
        }

        applyGradleAapt2Override(root)
        File(root, MARKER_REL).apply {
            parentFile?.mkdirs()
            writeText("$PINNED_SHA256\n")
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_APPLIED, true)
            .putLong("applied_timestamp", System.currentTimeMillis())
            .apply()

        return ApplyResult(true, tr(R.string.onb_android_sdk_36_arm64_aapt2))
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
