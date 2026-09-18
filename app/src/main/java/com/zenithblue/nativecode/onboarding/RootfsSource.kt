package com.zenithblue.nativecode.onboarding

import android.content.Context
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.zenithblue.nativecode.BuildConfig
import java.io.File
import java.security.MessageDigest

sealed interface RootfsSource {
    val distro: String
    val archiveFile: File?
    val checksumFile: File?
    val expectedSha256: String?

    data class LocalFile(
        val archive: File,
        val checksum: File? = null,
        override val expectedSha256: String? = null,
        override val distro: String = "alpaquita"
    ) : RootfsSource {
        override val archiveFile: File = archive
        override val checksumFile: File? = checksum
    }

    data class PlayAssetPack(
        val archive: File,
        val checksum: File? = null,
        override val expectedSha256: String? = null,
        override val distro: String = "alpaquita"
    ) : RootfsSource {
        override val archiveFile: File = archive
        override val checksumFile: File? = checksum
    }

    data class BundledAsset(
        val assetPath: String,
        val checksumAssetPath: String? = null,
        override val expectedSha256: String? = null,
        override val distro: String = "alpine"
    ) : RootfsSource {
        override val archiveFile: File? = null
        override val checksumFile: File? = null
    }
}

object RootfsSourceResolver {
    private const val TAG = "RootfsSourceResolver"
    const val PINNED_ALPAQUITA_SHA256 = "209b7d4b92367cf46e5a7b6c03ca3fbf49e2a2428963429d71566dcb6ac9bb11"
    const val ALPAQUITA_NAME = "alpaquita_rootfs.tar.zst"
    const val ALPAQUITA_SHA_NAME = "alpaquita_rootfs.tar.zst.sha256"

    /**
     * Resolves the best available rootfs source in priority order:
     * DEBUG: 1. local filesystem (/data/local/tmp, app private files, /sdcard/Download)
     *        2. Play Asset Delivery pack (nativecode_runtime)
     *        3. Bundled assets in APK (fallback)
     * RELEASE (Play policy-safe): 1. Play Asset Delivery pack
     *                             2. Bundled assets fallback.
     * Local sideload paths are never trusted in release builds.
     * The compiled-in [PINNED_ALPAQUITA_SHA256] is always authoritative;
     * adjacent .sha256 files are consistency-checked only and can never
     * redefine the expected hash.
     */
    fun resolve(context: Context, homeDir: File? = null): RootfsSource? {
        // 1. Debug-only local filesystem candidates (dev/test sideload).
        if (BuildConfig.DEBUG) {
            val localCandidates = mutableListOf<File>()
            localCandidates.add(File("/data/local/tmp", ALPAQUITA_NAME))
            if (homeDir != null) {
                localCandidates.add(File(homeDir, ALPAQUITA_NAME))
                localCandidates.add(File(homeDir, "rootfs/$ALPAQUITA_NAME"))
            }
            localCandidates.add(File(context.filesDir, "home/$ALPAQUITA_NAME"))
            localCandidates.add(File("/sdcard/Download", ALPAQUITA_NAME))
            localCandidates.add(File("/storage/emulated/0/Download", ALPAQUITA_NAME))

            for (candidate in localCandidates) {
                if (candidate.isFile && candidate.length() > 2L * 1024L * 1024L && candidate.canRead()) {
                    if (!verifyArchiveAgainstPin(candidate)) {
                        Log.w(TAG, "Ignoring candidate ${candidate.absolutePath}: SHA-256 does not match pin")
                        continue
                    }
                    val shaFile = File(candidate.parentFile, ALPAQUITA_SHA_NAME)
                    Log.i(TAG, "Resolved LocalFile rootfs (DEBUG only): ${candidate.absolutePath} (${candidate.length()} bytes)")
                    return RootfsSource.LocalFile(
                        archive = candidate,
                        checksum = if (shaFile.isFile) shaFile else null,
                        expectedSha256 = PINNED_ALPAQUITA_SHA256,
                        distro = "alpaquita"
                    )
                }
            }
        } else {
            Log.i(TAG, "Release build: skipping local filesystem rootfs sources (Play-delivered only)")
        }

        // 2. Play Asset Delivery pack: nativecode_runtime
        try {
            val assetPackManager = AssetPackManagerFactory.getInstance(context)
            val packLocation = assetPackManager.getPackLocation("nativecode_runtime")
            if (packLocation != null) {
                val assetsPath = packLocation.assetsPath()
                if (assetsPath != null) {
                    val packFile = File(assetsPath, ALPAQUITA_NAME)
                    if (packFile.isFile && packFile.length() > 2L * 1024L * 1024L) {
                        val shaFile = File(assetsPath, ALPAQUITA_SHA_NAME)
                        val sha = pinnedShaOrWarn(shaFile, packFile.absolutePath)
                        Log.i(TAG, "Resolved PlayAssetPack rootfs: ${packFile.absolutePath} (${packFile.length()} bytes)")
                        return RootfsSource.PlayAssetPack(
                            archive = packFile,
                            checksum = if (shaFile.isFile) shaFile else null,
                            expectedSha256 = sha,
                            distro = "alpaquita"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Play Asset Delivery resolution check failed: ${e.message}")
        }

        // 3. Bundled assets in APK
        try {
            val rootfsAssets = context.assets.list("rootfs")?.toSet() ?: emptySet()
            if (rootfsAssets.contains(ALPAQUITA_NAME)) {
                val shaAsset = if (rootfsAssets.contains(ALPAQUITA_SHA_NAME)) "rootfs/$ALPAQUITA_SHA_NAME" else null
                Log.i(TAG, "Resolved BundledAsset alpaquita rootfs")
                return RootfsSource.BundledAsset(
                    assetPath = "rootfs/$ALPAQUITA_NAME",
                    checksumAssetPath = shaAsset,
                    expectedSha256 = PINNED_ALPAQUITA_SHA256,
                    distro = "alpaquita"
                )
            }
            if (rootfsAssets.contains("alpine_rootfs.tar.gz")) {
                if (!BuildConfig.DEBUG) {
                    Log.i(TAG, "Release build: ignoring legacy alpine_rootfs.tar.gz fallback (immutable Alpaquita runtime only)")
                    return null
                }
                Log.i(TAG, "Resolved BundledAsset alpine_rootfs.tar.gz (DEBUG only)")
                return RootfsSource.BundledAsset(
                    assetPath = "rootfs/alpine_rootfs.tar.gz",
                    distro = "alpine"
                )
            }
            if (rootfsAssets.contains("alpine_rootfs.tar")) {
                if (!BuildConfig.DEBUG) {
                    Log.i(TAG, "Release build: ignoring legacy alpine_rootfs.tar fallback (immutable Alpaquita runtime only)")
                    return null
                }
                Log.i(TAG, "Resolved BundledAsset alpine_rootfs.tar (DEBUG only)")
                return RootfsSource.BundledAsset(
                    assetPath = "rootfs/alpine_rootfs.tar",
                    distro = "alpine"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bundled asset resolution check failed: ${e.message}")
        }

        return null
    }

    /**
     * Compiled-in pin is always authoritative. An adjacent .sha256 file is
     * checked for consistency only: a mismatch is logged but never trusted.
     */
    private fun pinnedShaOrWarn(shaFile: File, archivePath: String): String {
        val onDisk = readShaFile(shaFile)
        if (onDisk != null && !onDisk.equals(PINNED_ALPAQUITA_SHA256, ignoreCase = true)) {
            Log.w(TAG, "Adjacent checksum mismatch for $archivePath (on-disk=${onDisk.take(16)}…, pinned=${PINNED_ALPAQUITA_SHA256.take(16)}…). Using pinned SHA.")
        }
        return PINNED_ALPAQUITA_SHA256
    }

    /**
     * Streams the archive and compares its actual SHA-256 against the pin.
     * Call before proot-distro install / chroot setup regardless of source.
     */
    fun verifyArchiveAgainstPin(archive: File): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            archive.inputStream().buffered(1024 * 1024).use { input ->
                val buf = ByteArray(1024 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val ok = actual.equals(PINNED_ALPAQUITA_SHA256, ignoreCase = true)
            if (!ok) {
                Log.e(TAG, "SHA-256 mismatch for ${archive.absolutePath} (actual=${actual.take(16)}…, pinned=${PINNED_ALPAQUITA_SHA256.take(16)}…)")
            }
            ok
        } catch (e: Exception) {
            Log.e(TAG, "SHA-256 verification failed for ${archive.absolutePath}: ${e.message}")
            false
        }
    }

    private fun readShaFile(shaFile: File): String? {
        if (!shaFile.isFile || !shaFile.canRead()) return null
        return try {
            shaFile.readText().trim().split("\\s+".toRegex()).firstOrNull()?.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading SHA file ${shaFile.absolutePath}: ${e.message}")
            null
        }
    }
}
