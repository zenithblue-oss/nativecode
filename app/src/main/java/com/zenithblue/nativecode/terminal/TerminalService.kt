package com.zenithblue.nativecode.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zenithblue.nativecode.MainActivity
import com.zenithblue.nativecode.R
import com.termux.terminal.TerminalSession

/**
 * Unified foreground service managing terminal sessions (both main app tabs and project workspaces).
 * Replaces separate AppTerminalService and ProjectTerminalService with a single Termux-style service.
 */
class TerminalService : Service() {

    inner class LocalBinder : Binder() {
        val service: TerminalService get() = this@TerminalService
    }

    private val binder = LocalBinder()

    private var appSessionCount: Int = 0
    private var projectSessionCount: Int = 0
    private var activeProjectName: String? = null
    private var activeProjectPath: String? = null
    private var targetPage: Int = 3 // Default to ID_TERMINAL (3)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            appSessionCount = intent.getIntExtra(EXTRA_APP_COUNT, appSessionCount)
            projectSessionCount = intent.getIntExtra(EXTRA_PROJECT_COUNT, projectSessionCount)
            if (intent.hasExtra(EXTRA_PROJECT_NAME)) {
                activeProjectName = intent.getStringExtra(EXTRA_PROJECT_NAME)
            }
            if (intent.hasExtra(EXTRA_PROJECT_PATH)) {
                activeProjectPath = intent.getStringExtra(EXTRA_PROJECT_PATH)
            }
            if (intent.hasExtra(EXTRA_TARGET_PAGE)) {
                targetPage = intent.getIntExtra(EXTRA_TARGET_PAGE, targetPage)
            }
            if (intent.hasExtra(EXTRA_SESSION_COUNT)) {
                val count = intent.getIntExtra(EXTRA_SESSION_COUNT, 0)
                if (targetPage == 10) { // Workspace page
                    projectSessionCount = count
                } else {
                    appSessionCount = count
                }
            }
        }

        val total = appSessionCount + projectSessionCount
        if (total <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification(total)
        try {
            startAsForeground(notification)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            try {
                startForeground(NOTIF_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "fallback startForeground failed", e2)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotification(totalSessions: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("EXTRA_TARGET_PAGE", targetPage)
            if (activeProjectPath != null) {
                putExtra("PROJECT_PATH", activeProjectPath)
            }
            if (activeProjectName != null) {
                putExtra("PROJECT_NAME", activeProjectName)
            }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, NOTIF_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = when {
            projectSessionCount > 0 && !activeProjectName.isNullOrBlank() && appSessionCount > 0 ->
                "$appSessionCount app, $projectSessionCount project ($activeProjectName) sessions"
            projectSessionCount > 0 && !activeProjectName.isNullOrBlank() ->
                if (projectSessionCount == 1) "1 session for $activeProjectName"
                else "$projectSessionCount sessions for $activeProjectName"
            totalSessions == 1 -> "1 terminal session running"
            else -> "$totalSessions terminal sessions running"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NativeCode — Terminal")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "NativeCode Terminal Sessions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows while app and project terminal sessions stay alive in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "TerminalService"
        const val CHANNEL_ID = "NativeCodeTerminalServiceChannel_v1"
        private const val NOTIF_ID = 1001

        const val EXTRA_SESSION_COUNT = "SESSION_COUNT"
        const val EXTRA_APP_COUNT = "APP_SESSION_COUNT"
        const val EXTRA_PROJECT_COUNT = "PROJECT_SESSION_COUNT"
        const val EXTRA_PROJECT_NAME = "PROJECT_NAME"
        const val EXTRA_PROJECT_PATH = "PROJECT_PATH"
        const val EXTRA_TARGET_PAGE = "EXTRA_TARGET_PAGE"

        fun updateService(
            context: Context,
            appCount: Int,
            projectCount: Int = 0,
            projectName: String? = null,
            projectPath: String? = null,
            targetPage: Int = 3
        ) {
            val total = appCount + projectCount
            val intent = Intent(context, TerminalService::class.java).apply {
                putExtra(EXTRA_APP_COUNT, appCount)
                putExtra(EXTRA_PROJECT_COUNT, projectCount)
                putExtra(EXTRA_PROJECT_NAME, projectName)
                putExtra(EXTRA_PROJECT_PATH, projectPath)
                putExtra(EXTRA_TARGET_PAGE, targetPage)
            }
            if (total > 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        context.startForegroundService(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "startForegroundService failed, falling back to startService", e)
                        context.startService(intent)
                    }
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}
