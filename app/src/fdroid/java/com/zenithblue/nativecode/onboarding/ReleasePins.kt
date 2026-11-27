package com.zenithblue.nativecode.onboarding

import android.content.Context
import com.zenithblue.nativecode.BuildConfig
import java.io.File

/**
 * Where the F-Droid flavor downloads the three packs from, and the exact bytes it accepts. The files are the SAME ones
 * the Play asset packs carry; the sha256 pins are the existing ones (RootfsSourceResolver / RuntimeUpdatePack /
 * AndroidDevPackManager), sizes are exact. New bytes => new file name and new pins, never overwrite a published asset.
 */
object ReleasePins {
    /** Single constant: the GitHub release that holds the packs for this app version (repo name still TBD). */
    const val BASE_URL = "https://github.com/ZenithBlue-Dev/nativecode-runtime/releases/download/runtime-1.8.0"
    const val HOST = "github.com"

    private const val ROOTFS_BYTES = 1_356_467_533L
    private const val UPDATE_BYTES = 316_974_152L
    private const val DEV_BYTES = 69_451_708L

    /** Debug builds only: first line of `files/debug_pack_base_url` replaces [BASE_URL] (e.g. http://127.0.0.1:8099). */
    private fun baseUrl(ctx: Context): String {
        if (!BuildConfig.DEBUG) return BASE_URL
        val f = File(ctx.filesDir, "debug_pack_base_url")
        val override = runCatching { f.readLines().firstOrNull()?.trim() }.getOrNull()
        return override?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.trimEnd('/') ?: BASE_URL
    }

    fun pin(ctx: Context, pack: String): ReleaseDownloader.Pin = pinFor(pack, baseUrl(ctx))

    internal fun pinFor(pack: String, base: String): ReleaseDownloader.Pin = when (pack) {
        PlayAssetDeliveryManager.PACK_ANDROID_DEV -> ReleaseDownloader.Pin(
            AndroidDevPackManager.ARCHIVE_NAME, AndroidDevPackManager.PINNED_SHA256, DEV_BYTES,
            "$base/${AndroidDevPackManager.ARCHIVE_NAME}"
        )
        PlayAssetDeliveryManager.PACK_UPDATE_CORE -> ReleaseDownloader.Pin(
            RuntimeUpdatePack.ARCHIVE_NAME, RuntimeUpdatePack.PINNED_SHA256, UPDATE_BYTES,
            "$base/${RuntimeUpdatePack.ARCHIVE_NAME}"
        )
        else -> ReleaseDownloader.Pin(
            RootfsSourceResolver.ALPAQUITA_NAME, RootfsSourceResolver.PINNED_ALPAQUITA_SHA256, ROOTFS_BYTES,
            "$base/${RootfsSourceResolver.ALPAQUITA_NAME}"
        )
    }
}
