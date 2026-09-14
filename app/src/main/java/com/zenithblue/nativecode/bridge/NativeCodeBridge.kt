package com.zenithblue.nativecode.bridge

import android.content.Context
import android.util.Log
import com.zenithblue.nativecode.agent.AgentBridgeServer
import com.zenithblue.nativecode.media.MediaBridgeServer

/**
 * Lifecycle coordinator for host-side AF_UNIX bridge servers.
 * Pure native daemon; never mutates or executes scripts on the guest rootfs at runtime.
 * All guest utilities remain strictly prepackaged in the golden rootfs.
 */
object NativeCodeBridge {
    private const val TAG = "NativeCodeBridge"

    @Volatile
    private var mediaServer: MediaBridgeServer? = null

    @Volatile
    private var agentServer: AgentBridgeServer? = null

    @Synchronized
    fun start(context: Context) {
        val appContext = context.applicationContext
        if (mediaServer == null) {
            try {
                mediaServer = MediaBridgeServer(appContext).apply { start() }
                Log.i(TAG, "MediaBridgeServer started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MediaBridgeServer", e)
            }
        }
        if (agentServer == null) {
            try {
                agentServer = AgentBridgeServer(appContext).apply { start() }
                Log.i(TAG, "AgentBridgeServer started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AgentBridgeServer", e)
            }
        }
    }

    @Synchronized
    fun stop() {
        try {
            mediaServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing MediaBridgeServer", e)
        }
        mediaServer = null

        try {
            agentServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing AgentBridgeServer", e)
        }
        agentServer = null
    }
}
