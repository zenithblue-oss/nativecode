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
import com.zenithblue.nativecode.agent.chat.ChatBridge
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
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
        fun isAlertActive(): Boolean = false
        fun dismissActiveAlert(): Boolean = false
    }

    companion object {
        private const val TAG = "AgentBridgeServer"
        const val PRIMARY_SOCKET_NAME = "nativecode.agent.v1"
        const val SOCKET_NAME_PREFIX = "nativecode.agent.v1."
        const val NOTIFICATION_CHANNEL_ID = "agent_notifications"
        const val NOTIFICATION_CHANNEL_NAME = "Agent Alerts & Progress"

        /** `share-file` notification tokens → file (process memory only; a stale tap after a restart does nothing). */
        private val shares = java.util.concurrent.ConcurrentHashMap<String, File>()
        fun takeShare(token: String): File? = shares.remove(token)?.takeIf { it.isFile }

        fun resolveApkFile(context: Context, path: String): File? {
            val f = File(path)
            if (f.isAbsolute && f.isFile && f.exists()) return f

            // Handle guest /tmp paths (mapped to usr/tmp under --shared-tmp)
            if (path.startsWith("/tmp/")) {
                val relTmp = path.removePrefix("/tmp/")
                val sharedTmp = File(context.filesDir, "usr/tmp/$relTmp")
                if (sharedTmp.isFile && sharedTmp.exists()) return sharedTmp
                val prootDir = com.zenithblue.nativecode.terminal.ProjectPathResolver.prootRootfsDir(context)
                val prootTmp = File(prootDir, "tmp/$relTmp")
                if (prootTmp.isFile && prootTmp.exists()) return prootTmp
            }

            // Handle /sdcard/ and /storage/emulated/0/
            if (path.startsWith("/sdcard/")) {
                val relSd = path.removePrefix("/sdcard/")
                val sdFile = File("/storage/emulated/0", relSd)
                if (sdFile.isFile && sdFile.exists()) return sdFile
                val altSd = File("/sdcard", relSd)
                if (altSd.isFile && altSd.exists()) return altSd
            }

            if (path.startsWith("/storage/emulated/0/")) {
                val emulatedFile = File(path)
                if (emulatedFile.isFile && emulatedFile.exists()) return emulatedFile
            }

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
            // Chat thread whose CLI sent this (ChatBridge.ENV in the client's process env); null = terminal → popups.
            val chatThread: String? = runCatching { ChatBridge.threadOf(creds.pid) }.getOrNull()
            fun toChat(card: ItemPayload.Bridge): Boolean = chatThread != null && ChatBridge.post(context, chatThread, card)

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

                            if (toChat(ItemPayload.Bridge("notify", title, message, state = ItemPayload.Bridge.DONE))) {
                                response.put("ok", true)
                                response.put("chat_card", true)
                            } else {
                                val result = postNotification(id, tag, title, message, ongoing, priorityStr)
                                response.put("ok", result.first)
                                if (!result.first) {
                                    response.put("error", result.second)
                                } else {
                                    response.put("posted", true)
                                }
                            }
                        }
                    }
                    "status" -> {
                        val key = request.optString("key", "agent_task")
                        val text = request.optString("text", "")
                        val progress = request.optInt("progress", -1)
                        val id = key.hashCode()

                        if (toChat(ItemPayload.Bridge("status", text.ifBlank { key }, "", detail = progress.toString(),
                                state = if (progress >= 100) ItemPayload.Bridge.DONE else ItemPayload.Bridge.PENDING, key = key))) {
                            response.put("ok", true)
                            response.put("chat_card", true)
                        } else {
                            val result = postStatusUpdate(id, key, text, progress)
                            response.put("ok", result.first)
                            if (!result.first) {
                                response.put("error", result.second)
                            }
                        }
                    }
                    "toast" -> {
                        if (!toastLimiter.tryAcquire()) {
                            response.put("ok", false)
                            response.put("error", "Rate limit exceeded for toasts (max 1 per 2 sec)")
                        } else {
                            val text = request.optString("text", "")
                            if (toChat(ItemPayload.Bridge("toast", text, "", state = ItemPayload.Bridge.DONE))) response.put("chat_card", true)
                            else showToast(text)
                            response.put("ok", true)
                        }
                    }
                    "share_file", "send_file" -> {
                        val path = request.optString("path", "").trim()
                        val file = path.takeIf { it.isNotEmpty() }?.let { resolveApkFile(context, it) }
                        if (!dialogLimiter.tryAcquire()) {
                            response.put("ok", false)
                            response.put("error", "Rate limit exceeded for file shares (max 1/sec)")
                        } else if (file == null) {
                            response.put("ok", false)
                            response.put("error", if (path.isEmpty()) "Missing or empty 'path' parameter" else "File not found: $path")
                        } else {
                            val title = request.optString("title", "").ifBlank { file.name }
                            val message = request.optString("message", "")
                            if (toChat(ItemPayload.Bridge("share_file", title, message, target = file.absolutePath, detail = file.length().toString()))) {
                                response.put("chat_card", true)
                            } else {
                                postShareFileNotification(file, title, message)
                                response.put("notified", true)
                            }
                            response.put("ok", true)
                            response.put("path", file.absolutePath)
                        }
                    }
                    "telemetry" -> {
                        response.put("ok", true)
                        response.put("telemetry", collectTelemetry())
                    }
                    "open_link", "open_url" -> {
                        val chatUrl = request.optString("url", "").trim()
                        if (chatThread != null && chatUrl.isNotEmpty() && dialogLimiter.tryAcquire() &&
                            toChat(ItemPayload.Bridge("open_link", request.optString("title", "").ifBlank { "Web link" }, request.optString("message", ""), target = chatUrl))) {
                            response.put("ok", true)
                            response.put("chat_card", true)
                            response.put("url", chatUrl)
                        } else if (uiDelegate?.isAlertActive() == true) {
                            response.put("ok", false)
                            response.put("error", "alert_already_active")
                            response.put("message", "An alert is already active on screen. Dismiss it first with 'nativecode-agent dismiss-alert' or wait for the user to close it.")
                        } else if (!dialogLimiter.tryAcquire()) {
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
                        if (chatThread == null && uiDelegate?.isAlertActive() == true) { // chat cards never collide with a popup
                            response.put("ok", false)
                            response.put("error", "alert_already_active")
                            response.put("message", "An alert is already active on screen. Dismiss it first with 'nativecode-agent dismiss-alert' or wait for the user to close it.")
                        } else if (!dialogLimiter.tryAcquire()) {
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
                                } else if (apkFile.length() < 100L) {
                                    response.put("ok", false)
                                    response.put("error", "Invalid APK file (size too small or empty): $path")
                                } else {
                                    val pm = context.packageManager
                                    val pkgInfo = try { pm.getPackageArchiveInfo(apkFile.absolutePath, 0) } catch (_: Exception) { null }
                                    val pkgName = pkgInfo?.packageName ?: ""
                                    if (pkgName.isBlank()) {
                                        response.put("ok", false)
                                        response.put("error", "Invalid or corrupted APK file (no valid package manifest found): $path")
                                    } else {
                                        val title = request.optString("title", "Install APK").ifBlank { "Install APK" }
                                        val message = request.optString("message", "")
                                        val verName = pkgInfo?.versionName ?: "1.0"
                                        val appLabel = pkgInfo?.applicationInfo?.let { appInfo ->
                                            appInfo.sourceDir = apkFile.absolutePath
                                            appInfo.publicSourceDir = apkFile.absolutePath
                                            try { appInfo.loadLabel(pm).toString() } catch (_: Exception) { apkFile.name }
                                        } ?: apkFile.name

                                        val card = ItemPayload.Bridge("install_apk", title, message, target = apkFile.absolutePath,
                                            detail = "$appLabel · $pkgName $verName · ${apkFile.length()}")
                                        val chat = toChat(card)
                                        val shown = !chat && uiDelegate?.showInstallApkAlert(apkFile, title, message) == true
                                        if (chat) {
                                            response.put("chat_card", true)
                                        } else if (!shown) {
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
                    }
                    "dismiss_alert", "cancel_alert", "dismiss" -> {
                        val dismissed = if (chatThread != null) ChatBridge.dismissPending(context, chatThread) else uiDelegate?.dismissActiveAlert() == true
                        response.put("ok", true)
                        response.put("dismissed", dismissed)
                        if (!dismissed) {
                            response.put("message", "No active alert on screen to dismiss")
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

    /** Terminal `share-file`: tap → MainActivity share sheet. The intent carries a one-shot token ([takeShare]), never the path. */
    private fun postShareFileNotification(file: File, title: String, message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val token = java.util.UUID.randomUUID().toString()
        shares[token] = file
        val launchIntent = Intent().apply {
            setClassName(context.packageName, "com.zenithblue.nativecode.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("EXTRA_AGENT_SHARE_TOKEN", token)
        }
        val pi = PendingIntent.getActivity(context, token.hashCode(), launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("File from agent: $title")
            .setContentText(message.ifBlank { "${file.name} · tap to share or save" })
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.notify("agent_share_file", 1005, notif)
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

        var batteryPercent = -1
        var isCharging = false
        var tempC: Double? = null

        // Battery
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val temp = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (temp > 0) {
                tempC = temp / 10.0
            }
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            batteryPercent = if (scale > 0) (level * 100) / scale else level
        }

        // Thermals
        var thermalStr = "UNKNOWN"
        if (Build.VERSION.SDK_INT >= 29) {
            val powerMgr = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val thermalStatus = powerMgr?.currentThermalStatus ?: -1
            thermalStr = when (thermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "NONE"
                PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                else -> "UNKNOWN"
            }
        }

        // Memory
        var availMb = -1L
        var totalMb = -1L
        var lowMem = false
        val actMgr = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (actMgr != null) {
            val memInfo = ActivityManager.MemoryInfo()
            actMgr.getMemoryInfo(memInfo)
            availMb = memInfo.availMem / (1024 * 1024)
            totalMb = memInfo.totalMem / (1024 * 1024)
            lowMem = memInfo.lowMemory
        }

        // Power
        val powerMgr = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isPowerSave = powerMgr?.isPowerSaveMode ?: false
        val isInteractive = if (Build.VERSION.SDK_INT >= 20) powerMgr?.isInteractive ?: true else true

        // Flat keys (legacy & script compat)
        root.put("battery_percent", batteryPercent)
        root.put("is_charging", isCharging)
        root.put("thermal_status", thermalStr)
        root.put("avail_mem_mb", availMb)
        root.put("total_mem_mb", totalMb)
        root.put("low_memory", lowMem)

        // Nested telemetry object (matches documentation and schema)
        val telemetryObj = JSONObject().apply {
            put("battery", JSONObject().apply {
                put("level", batteryPercent)
                put("charging", isCharging)
                if (tempC != null) put("temperature_c", tempC)
            })
            put("memory", JSONObject().apply {
                put("avail_mb", availMb)
                put("total_mb", totalMb)
                put("low_memory", lowMem)
            })
            put("power", JSONObject().apply {
                put("power_save_mode", isPowerSave)
                put("interactive", isInteractive)
            })
            put("thermal", JSONObject().apply {
                put("status", thermalStr)
            })
        }
        root.put("telemetry", telemetryObj)

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
