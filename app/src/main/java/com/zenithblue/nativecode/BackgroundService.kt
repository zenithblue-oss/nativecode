package com.zenithblue.nativecode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class BackgroundService : Service() {

    private var lastTitle: String = TITLE_DESKTOP
    private var lastText: String = TEXT_DESKTOP
    private var tapOnboarding: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            lastTitle = intent.getStringExtra(EXTRA_TITLE) ?: lastTitle
            lastText = intent.getStringExtra(EXTRA_TEXT) ?: lastText
            if (intent.hasExtra(EXTRA_TAP_ONBOARDING)) {
                tapOnboarding = intent.getBooleanExtra(EXTRA_TAP_ONBOARDING, false)
            }
        }
        val notification = createNotification()
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
        // Install has no owner after process death — do not revive as a fake desktop session.
        return if (tapOnboarding) START_NOT_STICKY else START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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

    private fun createNotification(): Notification {
        val tapClass = if (tapOnboarding) OnboardingActivity::class.java else MainActivity::class.java
        val tapIntent = Intent(this, tapClass).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (tapOnboarding) {
                putExtra("force_onboarding", true)
                putExtra("target_page", 6)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, if (tapOnboarding) 1 else 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val channelId = if (tapOnboarding) CHANNEL_ID_SETUP else CHANNEL_ID

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(lastTitle)
            .setContentText(lastText)
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

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "NativeCode Desktop Session",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows while XFCE / graphical desktop is running"
                setShowBadge(false)
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID_SETUP,
                "NativeCode Environment Setup",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows while Debian proot/chroot install and setup scripts run"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "BackgroundService"
        const val CHANNEL_ID = "NativeCodeBackgroundServiceChannel_v2"
        const val CHANNEL_ID_SETUP = "NativeCodeSetupServiceChannel_v1"
        private const val NOTIF_ID = 1
        const val EXTRA_TITLE = "TITLE"
        const val EXTRA_TEXT = "TEXT"
        const val EXTRA_TAP_ONBOARDING = "TAP_ONBOARDING"
        const val TITLE_DESKTOP = "NativeCode — Desktop Session"
        const val TEXT_DESKTOP = "XFCE / GUI host services are active. Open app to stop."
        const val TITLE_INSTALL = "NativeCode — Environment Setup"
        const val TEXT_INSTALL =
            "Debian install / setup scripts are running. Keep this notification until setup finishes."

        fun desktopIntent(context: Context): Intent =
            Intent(context, BackgroundService::class.java).apply {
                putExtra(EXTRA_TITLE, TITLE_DESKTOP)
                putExtra(EXTRA_TEXT, TEXT_DESKTOP)
                putExtra(EXTRA_TAP_ONBOARDING, false)
            }

        fun installIntent(context: Context, detail: String? = null): Intent =
            Intent(context, BackgroundService::class.java).apply {
                putExtra(EXTRA_TITLE, TITLE_INSTALL)
                putExtra(EXTRA_TEXT, detail?.takeIf { it.isNotBlank() } ?: TEXT_INSTALL)
                putExtra(EXTRA_TAP_ONBOARDING, true)
            }
    }
}
