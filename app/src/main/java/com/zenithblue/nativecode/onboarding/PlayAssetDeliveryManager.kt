package com.zenithblue.nativecode.onboarding

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File

object PlayAssetDeliveryManager {
    private const val TAG = "PlayAssetDelivery"
    const val PACK_RUNTIME = "nativecode_runtime"
    const val PACK_ANDROID_DEV = "nativecode_android_dev"

    private fun archiveName(packName: String): String = when (packName) {
        PACK_ANDROID_DEV -> AndroidDevPackManager.ARCHIVE_NAME
        else -> RootfsSourceResolver.ALPAQUITA_NAME
    }

    private fun checksumName(packName: String): String = when (packName) {
        PACK_ANDROID_DEV -> AndroidDevPackManager.SHA_NAME
        else -> RootfsSourceResolver.ALPAQUITA_SHA_NAME
    }

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

    fun isPackInstalled(context: Context, packName: String = PACK_RUNTIME): Boolean {
        return try {
            val manager = AssetPackManagerFactory.getInstance(context)
            val location = manager.getPackLocation(packName)
            val path = location?.assetsPath() ?: return false
            val archive = File(path, archiveName(packName))
            archive.isFile && archive.length() > 2L * 1024L * 1024L
        } catch (e: Exception) {
            Log.w(TAG, "isPackInstalled error: ${e.message}")
            false
        }
    }

    fun getInstalledPackLocation(context: Context, packName: String = PACK_RUNTIME): File? {
        return try {
            val manager = AssetPackManagerFactory.getInstance(context)
            val location = manager.getPackLocation(packName)
            val path = location?.assetsPath() ?: return null
            val dir = File(path)
            if (File(dir, archiveName(packName)).isFile) dir else null
        } catch (e: Exception) {
            null
        }
    }

    fun requestDownload(
        activity: Activity,
        packName: String = PACK_RUNTIME,
        onStateChange: (DownloadState) -> Unit
    ): AssetPackStateUpdateListener {
        val manager = AssetPackManagerFactory.getInstance(activity)

        val listener = object : AssetPackStateUpdateListener {
            override fun onStateUpdate(state: AssetPackState) {
                if (state.name() != packName) return

                when (state.status()) {
                    AssetPackStatus.PENDING -> {
                        Log.i(TAG, "$packName: PENDING")
                        activity.runOnUiThread { onStateChange(DownloadState.Pending) }
                    }
                    AssetPackStatus.DOWNLOADING -> {
                        val downloaded = state.bytesDownloaded()
                        val total = state.totalBytesToDownload()
                        val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                        Log.i(TAG, "$packName: DOWNLOADING $downloaded / $total ($pct%)")
                        activity.runOnUiThread {
                            onStateChange(DownloadState.Downloading(downloaded, total, pct))
                        }
                    }
                    AssetPackStatus.TRANSFERRING -> {
                        val pct = state.transferProgressPercentage()
                        Log.i(TAG, "$packName: TRANSFERRING $pct%")
                        activity.runOnUiThread { onStateChange(DownloadState.Transferring(pct)) }
                    }
                    AssetPackStatus.COMPLETED -> {
                        Log.i(TAG, "$packName: COMPLETED")
                        val loc = manager.getPackLocation(packName)
                        val path = loc?.assetsPath()
                        if (path != null) {
                            val assetsDir = File(path)
                            val archive = File(assetsDir, archiveName(packName))
                            val sha = File(assetsDir, checksumName(packName))
                            activity.runOnUiThread {
                                onStateChange(DownloadState.Completed(assetsDir, archive, if (sha.isFile) sha else null))
                            }
                        } else {
                            activity.runOnUiThread {
                                onStateChange(DownloadState.Failed(-1, "Pack location missing after completion"))
                            }
                        }
                    }
                    AssetPackStatus.WAITING_FOR_WIFI -> {
                        Log.i(TAG, "$packName: WAITING_FOR_WIFI")
                        activity.runOnUiThread { onStateChange(DownloadState.WaitingForWifi) }
                        try {
                            manager.showCellularDataConfirmation(activity)
                        } catch (e: Exception) {
                            Log.w(TAG, "Cellular confirmation prompt failed: ${e.message}")
                        }
                    }
                    AssetPackStatus.FAILED -> {
                        val code = state.errorCode()
                        Log.e(TAG, "$packName: FAILED (errorCode=$code)")
                        activity.runOnUiThread {
                            onStateChange(DownloadState.Failed(code, "Asset pack download failed (error $code)"))
                        }
                    }
                    AssetPackStatus.CANCELED -> {
                        Log.i(TAG, "$packName: CANCELED")
                        activity.runOnUiThread { onStateChange(DownloadState.Canceled) }
                    }
                    else -> {}
                }
            }
        }

        manager.registerListener(listener)
        manager.fetch(listOf(packName)).addOnFailureListener { e ->
            Log.e(TAG, "fetch failed for $packName: ${e.message}", e)
            activity.runOnUiThread {
                onStateChange(DownloadState.Failed(-1, e.message ?: "Failed to start download"))
            }
        }

        return listener
    }

    fun unregisterListener(context: Context, listener: AssetPackStateUpdateListener) {
        try {
            AssetPackManagerFactory.getInstance(context).unregisterListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "unregisterListener error: ${e.message}")
        }
    }
}
