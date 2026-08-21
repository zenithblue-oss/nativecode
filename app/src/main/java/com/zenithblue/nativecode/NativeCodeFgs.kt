package com.zenithblue.nativecode

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Shared start/stop for specialUse FGS (terminal, XFCE, onboarding install).
 * Desktop and install share BackgroundService; owners are refcounted so one
 * client cannot tear down the other.
 */
object NativeCodeFgs {
    private const val TAG = "NativeCodeFgs"
    const val REQ_POST_NOTIFICATIONS = 4401
    const val OWNER_DESKTOP = "desktop"
    const val OWNER_INSTALL = "install"

    private val lock = Any()
    private val owners = linkedSetOf<String>()
    private val lastIntents = mutableMapOf<String, Intent>()

    fun ensurePostNotificationsPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        activity.requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            REQ_POST_NOTIFICATIONS
        )
    }

    fun acquire(context: Context, owner: String, intent: Intent) {
        synchronized(lock) {
            owners.add(owner)
            lastIntents[owner] = Intent(intent)
        }
        start(context, intent)
    }

    fun release(context: Context, owner: String) {
        val remaining: Intent?
        val empty: Boolean
        synchronized(lock) {
            owners.remove(owner)
            lastIntents.remove(owner)
            empty = owners.isEmpty()
            remaining = owners.lastOrNull()?.let { lastIntents[it] }
        }
        if (empty) {
            stop(context, BackgroundService::class.java)
        } else if (remaining != null) {
            start(context, remaining)
        }
    }

    fun start(context: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FGS ${intent.component?.className}", e)
            try {
                context.startService(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback startService failed", e2)
            }
        }
    }

    fun stop(context: Context, clazz: Class<out Service>) {
        context.stopService(Intent(context, clazz))
    }
}
