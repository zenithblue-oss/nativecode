package com.zenithblue.nativecode.agent

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zenithblue.nativecode.media.MediaBridgeServer
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

class AgentBridgeServer(
    private val context: Context
) : AutoCloseable {

    interface UiDelegate {
        fun showOpenLinkAlert(url: String, title: String?, message: String?): Boolean
        fun showInstallApkAlert(apkFile: File, title: String?, message: String?): Boolean
    }

    companion object {
        private const val TAG = "AgentBridgeServer"
        const val PRIMARY_SOCKET_NAME = "nativecode.agent.v1"
        const val SOCKET_NAME_PREFIX = "nativecode.agent.v1."
        const val NOTIFICATION_CHANNEL_ID = "agent_notifications"
        const val NOTIFICATION_CHANNEL_NAME = "Agent Alerts & Progress"

        fun resolveApkFile(context: Context, path: String): File? {
            val f = File(path)
            if (f.isAbsolute && f.isFile && f.exists()) return f

            val guestResolved = com.zenithblue.nativecode.terminal.ProjectPathResolver.resolve(context, path)
            if (guestResolved.isFile && guestResolved.exists()) return guestResolved

            val homeFile = File(context.filesDir, "home/$path")
            if (homeFile.isFile && homeFile.exists()) return homeFile

            val appFile = File(context.filesDir, path)
            if (appFile.isFile && appFile.exists()) return appFile

            val prootDir = com.zenithblue.nativecode.terminal.ProjectPathResolver.prootRootfsDir(context)
            val prootFile = File(prootDir, path.removePrefix("/"))
            if (prootFile.isFile && prootFile.exists()) return prootFile

            return null
        }
    }

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null
    private var workerThread: Thread? = null
    private val activeClients = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<LocalSocket, Boolean>())

    @Volatile
    var boundSocketName: String = PRIMARY_SOCKET_NAME
        private set

    val socketName: String get() = boundSocketName

    @Volatile
    var uiDelegate: UiDelegate? = null

    // Rate limiters
    private val notificationLimiter = AgentBridgeProtocol.TokenBucket(capacity = 5.0, refillRatePerSecond = 5.0)
    private val toastLimiter = AgentBridgeProtocol.TokenBucket(capacity = 1.0, refillRatePerSecond = 0.5)
    private val dialogLimiter = AgentBridgeProtocol.TokenBucket(capacity = 3.0, refillRatePerSecond = 1.0)

    fun start() {
        if (isRunning.getAndSet(true)) return

        createNotificationChannel()

        val thread = Thread({
            try {
                val server = try {
                    LocalServerSocket(PRIMARY_SOCKET_NAME).also { boundSocketName = PRIMARY_SOCKET_NAME }
                } catch (e: IOException) {
                    val fallback = "$SOCKET_NAME_PREFIX${Process.myUid()}"
                    LocalServerSocket(fallback).also { boundSocketName = fallback }
                }
                serverSocket = server
                Log.i(TAG, "AgentBridgeServer listening on abstract socket @$boundSocketName")
                writeRendezvousFile()

                while (isRunning.get()) {
                    val client: LocalSocket = try {
                        server.accept()
                    } catch (e: IOException) {
                        if (!isRunning.get()) break
                        Log.e(TAG, "accept error", e)
                        continue
                    }

                    val handlerThread = Thread({ handleClient(client) }, "AgentBridgeClientHandler")
                    handlerThread.isDaemon = true
                    handlerThread.start()
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Server error", e)
                }
            } finally {
                close()
            }
        }, "AgentBridgeServerThread")

        workerThread = thread
        thread.isDaemon = true
        thread.start()
    }

    private fun handleClient(client: LocalSocket) {
        activeClients.add(client)
        try {
            // Kernel peer credential validation
            val creds = client.peerCredentials
            if (creds == null || creds.uid != Process.myUid()) {
                Log.w(TAG, "Rejected unauthorized agent client UID: ${creds?.uid}, expected ${Process.myUid()}")
                return
            }

            client.soTimeout = 10000 // 10s bounded timeout

            val input = client.inputStream
            val output = client.outputStream

            while (isRunning.get()) {
                val reqStr: String = try {
                    AgentBridgeProtocol.readControlString(input)
                } catch (e: Exception) {
                    break
                }

                val request = JSONObject(reqStr)
                val cmd = request.optString("cmd", "")
                val response = JSONObject().apply {
                    put("v", AgentBridgeProtocol.PROTOCOL_VERSION)
                }

                when (cmd) {
                    "ping" -> {
                        response.put("ok", true)
                        response.put("host_uid", Process.myUid())
                        response.put("client_id", request.optString("client_id", "agent"))
                        response.put("ts", System.currentTimeMillis())
                    }
                    "notify" -> {
                        if (!notificationLimiter.tryAcquire()) {
                            response.put("ok", false)
                            response.put("error", "Rate limit exceeded for notifications (max 5/sec)")
                        } else {
                            val id = request.optInt("id", 1001)
                            val tag = request.optString("tag", "agent_task")
                            val title = request.optString("title", "Agent Notification")
                            val message = request.optString("message", "")
                            val ongoing = request.optBoolean("ongoing", false)
                            val priorityStr = request.optString("priority", "default")

                            val result = postNotification(id, tag, title, message, ongoing, priorityStr)
                            response.put("ok", result.first)
                            if (!result.first) {
                                response.put("error", result.second)
                            } else {
                                response.put("posted", true)
                            }
                        }
                    }
                    "status" -> {
                        val key = request.optString("key", "agent_task")
                        val text = request.optString("text", "")
                        val progress = request.optInt("progress", -1)
                        val id = key.hashCode()

                        val result = postStatusUpdate(id, key, text, progress)
                        response.put("ok", result.first)
                        if (!result.first) {
                            response.put("error", result.second)
                        }
                    }
                    "toast" -> {
                        if (!toastLimiter.tryAcquire()) {
                            response.put("ok", false)
                            response.put("error", "Rate limit exceeded for toasts (max 1 per 2 sec)")
                        } else {
                            val text = request.optString("text", "")
                            showToast(text)
                            response.put("ok", true)
                        }
                    }
                    "telemetry" -> {
                        response.put("ok", true)
                        response.put("telemetry", collectTelemetry())
                    }
                    "open_link", "open_url" -> {
                        if (!dialogLimiter.tryAcquire()) {
                            response.put("ok", false)
                            response.put("error", "Rate limit exceeded for link alerts (max 1/sec)")
                        } else {
                            val url = request.optString("url", "").trim()
                            if (url.isEmpty()) {
                                response.put("ok", false)
                                response.put("error", "Missing or empty 'url' parameter")
                            } else {
                                val title = request.optString("title", "Agent Web Link").ifBlank { "Agent Web Link" }
                                val message = request.optString("message", "")
                                val shown = uiDelegate?.showOpenLinkAlert(url, title, message) == true
                                if (!shown) {
                                    postOpenLinkNotification(url, title, message)
                                    response.put("notified", true)
                                } else {
                                    response.put("alert_shown", true)
                                }
                                response.put("ok", true)
                                response.put("url", url)
                            }
                        }
                    }
                    "install_apk", "open_apk" -> {
                        if (!dialogLimiter.tryAcquire()) {
                            response.put("ok", false)
                            response.put("error", "Rate limit exceeded for APK alerts (max 1/sec)")
                        } else {
                            val path = request.optString("path", "").trim()
                            if (path.isEmpty()) {
                                response.put("ok", false)
                                response.put("error", "Missing or empty 'path' parameter")
                            } else {
                                val apkFile = resolveApkFile(context, path)
                                if (apkFile == null || !apkFile.exists() || !apkFile.isFile) {
                                    response.put("ok", false)
                                    response.put("error", "APK file not found: $path")
                                } else {
                                    val title = request.optString("title", "Install APK").ifBlank { "Install APK" }
                                    val message = request.optString("message", "")
                                    val pm = context.packageManager
                                    val pkgInfo = try { pm.getPackageArchiveInfo(apkFile.absolutePath, 0) } catch (_: Exception) { null }
                                    val pkgName = pkgInfo?.packageName ?: ""
                                    val verName = pkgInfo?.versionName ?: "1.0"
                                    val appLabel = pkgInfo?.applicationInfo?.let { appInfo ->
                                        appInfo.sourceDir = apkFile.absolutePath
                                        appInfo.publicSourceDir = apkFile.absolutePath
                                        try { appInfo.loadLabel(pm).toString() } catch (_: Exception) { apkFile.name }
                                    } ?: apkFile.name

                                    val shown = uiDelegate?.showInstallApkAlert(apkFile, title, message) == true
                                    if (!shown) {
                                        postInstallApkNotification(apkFile, appLabel, pkgName, verName, title, message)
                                        response.put("notified", true)
                                    } else {
                                        response.put("alert_shown", true)
                                    }
                                    response.put("ok", true)
                                    response.put("path", apkFile.absolutePath)
                                    response.put("app_label", appLabel)
                                    response.put("package_name", pkgName)
                                    response.put("version_name", verName)
                                }
                            }
                        }
                    }
                    else -> {
                        response.put("ok", false)
                        response.put("error", "Unknown command: $cmd")
                    }
                }

                AgentBridgeProtocol.writeControlString(output, response.toString())
                java.lang.ref.Reference.reachabilityFence(client)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Agent client session finished: ${e.message}")
        } finally {
            activeClients.remove(client)
            try {
                client.close()
            } catch (ignored: Throwable) {}
        }
    }

    private fun postNotification(
        id: Int,
        tag: String,
        title: String,
        message: String,
        ongoing: Boolean,
        priorityStr: String
    ): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return Pair(false, "POST_NOTIFICATIONS permission not granted")
            }
        }

        val priority = when (priorityStr.lowercase()) {
            "high" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(ongoing)
            .setPriority(priority)
            .setAutoCancel(!ongoing)
            .build()

        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return Pair(false, "NotificationManager unavailable")

        mgr.notify(tag, id, notification)
        return Pair(true, "OK")
    }

    private fun postStatusUpdate(
        id: Int,
        tag: String,
        text: String,
        progress: Int
    ): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return Pair(false, "POST_NOTIFICATIONS permission not granted")
            }
        }

        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("NativeCode Agent Task")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress in 0..100) {
            builder.setProgress(100, progress, false)
        } else if (progress < 0) {
            builder.setProgress(0, 0, true) // Indeterminate
        }

        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return Pair(false, "NotificationManager unavailable")

        mgr.notify(tag, id, builder.build())
        return Pair(true, "OK")
    }

    private fun postOpenLinkNotification(url: String, title: String, message: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
        }
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (message.isNotBlank()) message else url)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        mgr?.notify("agent_open_link", 1003, notif)
    }

    private fun postInstallApkNotification(
        apkFile: File,
        appLabel: String,
        pkgName: String,
        verName: String,
        title: String,
        message: String
    ) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
        }
        val launchIntent = Intent().apply {
            setClassName(context.packageName, "com.zenithblue.nativecode.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("EXTRA_AGENT_INSTALL_APK", apkFile.absolutePath)
            putExtra("EXTRA_AGENT_TITLE", title)
            putExtra("EXTRA_AGENT_MESSAGE", message)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            System.currentTimeMillis().toInt(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Install APK: $appLabel")
            .setContentText(if (message.isNotBlank()) message else "Build finished ($pkgName v$verName). Tap to view and install.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        mgr?.notify("agent_install_apk", 1004, notif)
    }

    private fun showToast(text: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show toast: ${e.message}")
            }
        }
    }

    private fun collectTelemetry(): JSONObject {
        val root = JSONObject()

        // Battery
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            root.put("battery_percent", if (scale > 0) (level * 100) / scale else level)
            root.put("is_charging", isCharging)
        }

        // Thermals
        if (Build.VERSION.SDK_INT >= 29) {
            val powerMgr = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val thermalStatus = powerMgr?.currentThermalStatus ?: -1
            val thermalStr = when (thermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "NONE"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "UNKNOWN"
            }
            root.put("thermal_status", thermalStr)
        }

        // Memory
        val actMgr = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (actMgr != null) {
            val memInfo = ActivityManager.MemoryInfo()
            actMgr.getMemoryInfo(memInfo)
            root.put("avail_mem_mb", memInfo.availMem / (1024 * 1024))
            root.put("low_memory", memInfo.lowMemory)
        }

        return root
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows alerts and progress updates from background tasks and agents"
            }
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr?.createNotificationChannel(channel)
        }
    }

    private fun writeRendezvousFile() {
        try {
            val runDir = File(context.filesDir, "run").apply { mkdirs() }
            val rendezvousFile = File(runDir, "nativecode_bridge.json")
            val json = JSONObject().apply {
                put("v", 1)
                put("host_uid", Process.myUid())
                put("media_socket", "@${MediaBridgeServer.SOCKET_NAME_PREFIX}${Process.myUid()}")
                put("agent_socket", "@$socketName")
                put("timestamp", System.currentTimeMillis())
            }
            rendezvousFile.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write rendezvous file: ${e.message}")
        }
    }

    override fun close() {
        if (!isRunning.getAndSet(false)) return
        try {
            serverSocket?.close()
        } catch (ignored: Throwable) {}
        serverSocket = null
        workerThread?.interrupt()
        workerThread = null
    }
}
