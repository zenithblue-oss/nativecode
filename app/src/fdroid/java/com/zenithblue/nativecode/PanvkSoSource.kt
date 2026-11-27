package com.zenithblue.nativecode

import android.content.Context
import com.zenithblue.nativecode.onboarding.DownloadConsent
import com.zenithblue.nativecode.onboarding.PlayAssetDeliveryManager
import com.zenithblue.nativecode.onboarding.ReleaseDownloader
import com.zenithblue.nativecode.onboarding.ReleasePins
import java.io.File
import java.io.InputStream

/** F-Droid: the PanVK (Mali) .so is not bundled; it is fetched from GitHub Releases (size + sha256 gated) on first update. */
object PanvkSoSource {
    private val src = PackSo(PlayAssetDeliveryManager.PACK_GPU_DRIVER)
    fun ensure(ctx: Context): String? = src.ensure(ctx)
    fun open(ctx: Context): InputStream = src.open(ctx)
}

/** F-Droid: the Turnip (Adreno) .so, same mechanism. */
object TurnipSoSource {
    private val src = PackSo(PlayAssetDeliveryManager.PACK_TURNIP_DRIVER)
    fun ensure(ctx: Context): String? = src.ensure(ctx)
    fun open(ctx: Context): InputStream = src.open(ctx)
}

internal class PackSo(private val pack: String) {
    private fun file(ctx: Context): File {
        val pin = ReleasePins.pin(ctx, pack)
        return File(PlayAssetDeliveryManager.packDir(ctx, pack), pin.fileName)
    }

    /** Blocking (call off the main thread). Null = verified file present; else a user-facing error. */
    fun ensure(ctx: Context): String? {
        if (!DownloadConsent.granted(ctx)) return tr(R.string.fd_consent_required)
        val pin = ReleasePins.pin(ctx, pack)
        var last: ReleaseDownloader.Result = ReleaseDownloader.Result.Failed("not started", false)
        for (attempt in 1..3) {
            last = ReleaseDownloader.ensure(file(ctx).parentFile!!, pin)
            if ((last as? ReleaseDownloader.Result.Failed)?.retryable != true) break
            Thread.sleep(2000L * attempt)
        }
        return when (val r = last) {
            is ReleaseDownloader.Result.Ok -> null
            is ReleaseDownloader.Result.Cancelled -> tr(R.string.fd_gpu_driver_failed, "cancelled")
            is ReleaseDownloader.Result.Failed -> tr(R.string.fd_gpu_driver_failed, r.message)
        }
    }

    fun open(ctx: Context): InputStream = file(ctx).inputStream()
}
