package com.zenithblue.nativecode.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.zenithblue.nativecode.MainActivity
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.tr

/**
 * Keeps the app process alive with a visible notification (and a Stop action) while the SSH server runs.
 * The server itself is [SshServerController]; this service only owns the notification and the Wi-Fi watch:
 * when the LAN address disappears the server stops, unless "Keep running" is on.
 */
class SshService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var net: ConnectivityManager.NetworkCallback? = null
    private val refresh: () -> Unit = { main.post { notifyNow() } }

    override fun onCreate() {
        super.onCreate()
        channel()
        SshServerController.addListener(refresh)
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                // Wi-Fi may be replaced by the hotspot: stop only when no LAN address is left.
                main.postDelayed({
                    if (SshServerController.status.state == SshState.RUNNING && SshServerController.lanAddress() == null &&
                        !SshServerController.keepRunning(this@SshService)
                    ) SshServerController.stop(this@SshService)
                }, 1500)
            }
        }
        try { cm?.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb); net = cb } catch (_: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SshServerController.stop(this)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIF_ID, n)
        } catch (_: Exception) { }
        // No owner after process death: never revive a dead server.
        return START_NOT_STICKY
    }

    private fun notifyNow() {
        if (SshServerController.status.state == SshState.STOPPED || SshServerController.status.state == SshState.ERROR) return
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notification())
    }

    private fun notification(): Notification {
        val st = SshServerController.status
        val text = when (st.state) {
            SshState.RUNNING -> SshModel.connectCommand(st.address ?: "?")
            else -> tr(R.string.ssh_starting)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SSH server on port ${SshModel.PORT}")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentIntent(open)
            .addAction(0, tr(R.string.quick_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun channel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tr(R.string.ssh_nativecode_ssh_server), NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows while the SSH server is running"
                setShowBadge(false)
            }
        )
    }

    override fun onDestroy() {
        SshServerController.removeListener(refresh)
        net?.let { runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.zenithblue.nativecode.action.SSH_STOP"
        private const val CHANNEL_ID = "NativeCodeSshServiceChannel_v1"
        private const val NOTIF_ID = 4711

        fun start(ctx: Context) {
            val i = Intent(ctx, SshService::class.java)
            try { ctx.startForegroundService(i) } catch (_: Exception) { }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SshService::class.java))
        }
    }
}
