package com.zenithblue.nativecode.onboarding

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.tr
import java.io.File

/**
 * F-Droid flavor of the pack delivery: same name and surface as the play object (callers are shared), but the three
 * packs are downloaded from GitHub Releases ([ReleasePins]) into `filesDir/packs/<pack>/` after the user's consent
 * ([DownloadConsent]), resumed with HTTP Range and verified against the existing sha256 pins by [ReleaseDownloader].
 */
object PlayAssetDeliveryManager {
    private const val TAG = "PackDelivery"
    const val PACK_RUNTIME = "nativecode_runtime"
    const val PACK_ANDROID_DEV = "nativecode_android_dev"
    const val PACK_UPDATE_CORE = "nativecode_update_core"

    private const val MAX_ATTEMPTS = 3
    private val main = Handler(Looper.getMainLooper())

    sealed interface DownloadState {
        object Idle : DownloadState
        object Pending : DownloadState
        data class Downloading(val bytesDownloaded: Long, val totalBytes: Long, val percentage: Int) : DownloadState
        data class Transferring(val percentage: Int) : DownloadState
        data class Completed(val assetsDir: File, val archiveFile: File, val shaFile: File?) : DownloadState
        object WaitingForWifi : DownloadState
        data class Failed(val errorCode: Int, val message: String) : DownloadState
        object Canceled : DownloadState
    }

    /** Opaque handle (shared callers hold it as Any). */
    private class Handle

    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val metered = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun packDir(context: Context, packName: String): File = File(context.filesDir, "packs/$packName")

    private fun archive(context: Context, packName: String): File {
        val pin = ReleasePins.pin(context, packName)
        return File(packDir(context, packName), pin.fileName)
    }

    fun isPackInstalled(context: Context, packName: String = PACK_RUNTIME): Boolean =
        getInstalledPackLocation(context, packName) != null

    fun getInstalledPackLocation(context: Context, packName: String = PACK_RUNTIME): File? {
        val pin = ReleasePins.pin(context, packName)
        val f = archive(context, packName)
        // Only a verified, atomically renamed file ever carries the final name, so size is a sufficient cheap check.
        return if (f.isFile && f.length() == pin.sizeBytes) packDir(context, packName) else null
    }

    fun requestDownload(
        activity: Activity,
        packName: String = PACK_RUNTIME,
        onStateChange: (DownloadState) -> Unit
    ): Any {
        val app = activity.applicationContext
        val handle = Handle()
        fun post(s: DownloadState) = main.post { onStateChange(s) }

        if (!DownloadConsent.granted(app)) {
            post(DownloadState.Failed(-2, tr(R.string.fd_consent_required)))
            return handle
        }
        getInstalledPackLocation(app, packName)?.let { dir ->
            val pin = ReleasePins.pin(app, packName)
            post(DownloadState.Completed(dir, File(dir, pin.fileName), null))
            return handle
        }
        if (!running.add(packName)) { // one download per pack; the UI of the running one keeps reporting
            post(DownloadState.Pending)
            return handle
        }
        post(DownloadState.Pending)
        if (isMetered(app) && metered.add(packName)) { // ask once per pack and process
            val pin = ReleasePins.pin(app, packName)
            main.post {
                AlertDialog.Builder(activity)
                    .setTitle(tr(R.string.fd_mobile_data_title))
                    .setMessage(tr(R.string.fd_mobile_data_message, formatMb(pin.sizeBytes)))
                    .setCancelable(false)
                    .setPositiveButton(tr(R.string.fd_mobile_data_continue)) { _, _ -> start(app, packName, ::post) }
                    .setNegativeButton(tr(R.string.fd_mobile_data_wait)) { _, _ ->
                        running.remove(packName); metered.remove(packName)
                        onStateChange(DownloadState.WaitingForWifi)
                    }
                    .show()
            }
        } else {
            start(app, packName, ::post)
        }
        return handle
    }

    private fun start(app: Context, packName: String, post: (DownloadState) -> Unit) {
        Thread({
            try {
                val pin = ReleasePins.pin(app, packName)
                val dir = packDir(app, packName)
                var lastPct = -1
                var lastMs = 0L
                var result: ReleaseDownloader.Result = ReleaseDownloader.Result.Failed("not started", false)
                for (attempt in 1..MAX_ATTEMPTS) {
                    result = ReleaseDownloader.ensure(
                        dir, pin,
                        onProgress = { done, total ->
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            val now = System.currentTimeMillis()
                            if (pct != lastPct && now - lastMs >= 250L) {
                                lastPct = pct; lastMs = now
                                post(DownloadState.Downloading(done, total, pct))
                            }
                        },
                        onVerify = { pct -> if (pct % 5 == 0) post(DownloadState.Transferring(pct)) },
                    )
                    val retry = (result as? ReleaseDownloader.Result.Failed)?.retryable == true
                    if (!retry) break
                    Log.w(TAG, "$packName attempt $attempt failed: ${(result as ReleaseDownloader.Result.Failed).message}")
                    Thread.sleep(2000L * attempt)
                }
                when (val r = result) {
                    is ReleaseDownloader.Result.Ok ->
                        post(DownloadState.Completed(dir, File(dir, pin.fileName), null))
                    is ReleaseDownloader.Result.Cancelled -> post(DownloadState.Canceled)
                    is ReleaseDownloader.Result.Failed -> {
                        Log.e(TAG, "$packName failed: ${r.message}")
                        post(DownloadState.Failed(-1, r.message))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "$packName crashed: ${e.message}", e)
                post(DownloadState.Failed(-1, e.message ?: "download error"))
            } finally {
                running.remove(packName)
            }
        }, "pack-download-$packName").start()
    }

    private fun isMetered(ctx: Context): Boolean =
        runCatching { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered }
            .getOrDefault(false)

    private fun formatMb(bytes: Long): String = "${bytes / (1024L * 1024L)} MB"

    /** Deletes the pack (and any partial) to free the space once nothing reads it again. */
    fun removePack(context: Context, packName: String) {
        runCatching { packDir(context, packName).deleteRecursively() }
            .onFailure { Log.w(TAG, "removePack($packName) failed: ${it.message}") }
    }

    @Suppress("UNUSED_PARAMETER")
    fun unregisterListener(context: Context, listener: Any) = Unit
}
