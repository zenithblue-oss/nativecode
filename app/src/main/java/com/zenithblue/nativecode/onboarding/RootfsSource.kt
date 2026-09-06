package com.zenithblue.nativecode.onboarding

import android.content.Context
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import java.io.File

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
    const val PINNED_ALPAQUITA_SHA256 = "70cf5b9aaa02eff3105876e131c16e68f11f8403ec5ed0bb96c79b688c198257"
    const val ALPAQUITA_NAME = "alpaquita_rootfs.tar.zst"
    const val ALPAQUITA_SHA_NAME = "alpaquita_rootfs.tar.zst.sha256"

    /**
     * Resolves the best available rootfs source in priority order:
     * 1. Debug / local filesystem file (/data/local/tmp, app private files, /sdcard/Download)
     * 2. Play Asset Delivery pack (nativecode_runtime)
     * 3. Bundled assets in APK (fallback)
     */
    fun resolve(context: Context, homeDir: File? = null): RootfsSource? {
        // 1. Debug / local filesystem candidates
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
                val shaFile = File(candidate.parentFile, ALPAQUITA_SHA_NAME)
                val sha = readShaFile(shaFile) ?: PINNED_ALPAQUITA_SHA256
                Log.i(TAG, "Resolved LocalFile rootfs: ${candidate.absolutePath} (${candidate.length()} bytes)")
                return RootfsSource.LocalFile(
                    archive = candidate,
                    checksum = if (shaFile.isFile) shaFile else null,
                    expectedSha256 = sha,
                    distro = "alpaquita"
                )
            }
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
                        val sha = readShaFile(shaFile) ?: PINNED_ALPAQUITA_SHA256
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
                Log.i(TAG, "Resolved BundledAsset alpine_rootfs.tar.gz")
                return RootfsSource.BundledAsset(
                    assetPath = "rootfs/alpine_rootfs.tar.gz",
                    distro = "alpine"
                )
            }
            if (rootfsAssets.contains("alpine_rootfs.tar")) {
                Log.i(TAG, "Resolved BundledAsset alpine_rootfs.tar")
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
