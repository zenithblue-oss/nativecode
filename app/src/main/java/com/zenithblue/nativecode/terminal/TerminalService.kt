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
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.zenithblue.nativecode.MainActivity
import com.zenithblue.nativecode.R

/**
 * Unified foreground service managing terminal sessions (both main app tabs and project workspaces).
 * Displays a dedicated notification for global app terminal sessions, and separate persistent
 * notifications for each individual project running CLI tools or terminals.
 */
class TerminalService : Service() {

    data class ProjectState(
        val name: String,
        val path: String,
        val sessionCount: Int,
        val tabNames: List<String>
    )

    inner class LocalBinder : Binder() {
        val service: TerminalService get() = this@TerminalService
    }

    private val binder = LocalBinder()

    private var appSessionCount: Int = 0
    private val activeProjects = LinkedHashMap<String, ProjectState>()
    private val currentProjectNotifIds = HashSet<Int>()

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        com.zenithblue.nativecode.bridge.NativeCodeBridge.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            if (intent.hasExtra(EXTRA_SYNC_ALL)) {
                appSessionCount = intent.getIntExtra(EXTRA_APP_COUNT, 0)
                activeProjects.clear()
                val bundleList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(EXTRA_PROJECTS, Bundle::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Bundle>(EXTRA_PROJECTS)
                }
                bundleList?.forEach { b ->
                    val path = b.getString("path", "")
                    val name = b.getString("name", "Project")
                    val count = b.getInt("count", 0)
                    val tabs = b.getStringArrayList("tabs") ?: arrayListOf()
                    if (path.isNotEmpty() && count > 0) {
                        activeProjects[path] = ProjectState(name, path, count, tabs)
                    }
                }
            } else {
                appSessionCount = intent.getIntExtra(EXTRA_APP_COUNT, appSessionCount)
                val pCount = intent.getIntExtra(EXTRA_PROJECT_COUNT, 0)
                val pName = intent.getStringExtra(EXTRA_PROJECT_NAME)
                val pPath = intent.getStringExtra(EXTRA_PROJECT_PATH)
                if (!pPath.isNullOrEmpty()) {
                    if (pCount > 0) {
                        val tabs = intent.getStringArrayListExtra("EXTRA_TAB_NAMES") ?: arrayListOf()
                        activeProjects[pPath] = ProjectState(pName ?: "Project", pPath, pCount, tabs)
                    } else {
                        activeProjects.remove(pPath)
                    }
                }
            }
        }

        updateAllNotifications()
        return START_STICKY
    }

    private fun updateAllNotifications() {
        val totalSessions = appSessionCount + activeProjects.values.sumOf { it.sessionCount }
        if (totalSessions <= 0) {
            for (id in currentProjectNotifIds) {
                notificationManager.cancel(id)
            }
            currentProjectNotifIds.clear()
            notificationManager.cancel(NOTIF_ID_APP_TERMINAL)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val newProjectNotifIds = HashSet<Int>()

        if (appSessionCount > 0) {
            val appNotif = createAppTerminalNotification(appSessionCount)
            startAsForeground(NOTIF_ID_APP_TERMINAL, appNotif)

            for (proj in activeProjects.values) {
                val notifId = projectNotifId(proj.path)
                val notif = createProjectNotification(proj, notifId)
                notificationManager.notify(notifId, notif)
                newProjectNotifIds.add(notifId)
            }
        } else {
            notificationManager.cancel(NOTIF_ID_APP_TERMINAL)

            val projectList = activeProjects.values.toList()
            if (projectList.isNotEmpty()) {
                val primaryProj = projectList.first()
                val primaryId = projectNotifId(primaryProj.path)
                val primaryNotif = createProjectNotification(primaryProj, primaryId)
                startAsForeground(primaryId, primaryNotif)
                newProjectNotifIds.add(primaryId)

                for (proj in projectList.drop(1)) {
                    val notifId = projectNotifId(proj.path)
                    val notif = createProjectNotification(proj, notifId)
                    notificationManager.notify(notifId, notif)
                    newProjectNotifIds.add(notifId)
                }
            }
        }

        val removed = currentProjectNotifIds - newProjectNotifIds
        for (id in removed) {
            notificationManager.cancel(id)
        }
        currentProjectNotifIds.clear()
        currentProjectNotifIds.addAll(newProjectNotifIds)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startAsForeground(notifId: Int, notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    notifId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(notifId, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            try {
                startForeground(notifId, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "fallback startForeground failed", e2)
            }
        }
    }

    private fun createAppTerminalNotification(count: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_TARGET_PAGE, 3) // ID_TERMINAL
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            NOTIF_ID_APP_TERMINAL,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // count = terminal tabs + agent chats (MainActivity.syncTerminalServices)
        val text = if (count == 1) {
            "1 session running (terminal + chat)"
        } else {
            "$count sessions running (terminal + chat)"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID_APP)
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

    private fun createProjectNotification(proj: ProjectState, notifId: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_TARGET_PAGE, 10) // ID_PROJECT_WORKSPACE
            putExtra("PROJECT_PATH", proj.path)
            putExtra("PROJECT_NAME", proj.name)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notifId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val tabsText = if (proj.tabNames.isNotEmpty()) {
            proj.tabNames.joinToString(", ")
        } else {
            "terminal"
        }

        val contentText = if (proj.sessionCount == 1) {
            "1 session running ($tabsText)"
        } else {
            "${proj.sessionCount} sessions running ($tabsText)"
        }

        val bigText = buildString {
            append("Project: ").append(proj.name).append("\n")
            append("Active sessions (${proj.sessionCount}):\n")
            for (tab in proj.tabNames) {
                append(" • ").append(tab).append("\n")
            }
        }.trimEnd()

        return NotificationCompat.Builder(this, CHANNEL_ID_PROJECT)
            .setContentTitle("NativeCode — ${proj.name}")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appChannel = NotificationChannel(
                CHANNEL_ID_APP,
                "NativeCode Terminal Sessions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows while app terminal sessions stay alive in the background"
                setShowBadge(false)
            }
            val projectChannel = NotificationChannel(
                CHANNEL_ID_PROJECT,
                "NativeCode Project Terminals",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows while project workspace CLI tools and terminals stay alive in the background"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(appChannel)
            notificationManager.createNotificationChannel(projectChannel)
        }
    }

    companion object {
        private const val TAG = "TerminalService"
        const val CHANNEL_ID_APP = "NativeCodeTerminalServiceChannel_v1"
        const val CHANNEL_ID_PROJECT = "ProjectTerminalServiceChannel_v2"
        const val NOTIF_ID_APP_TERMINAL = 1001
        private const val NOTIF_ID_PROJECT_BASE = 2000

        const val EXTRA_SYNC_ALL = "EXTRA_SYNC_ALL"
        const val EXTRA_PROJECTS = "EXTRA_PROJECTS"
        const val EXTRA_APP_COUNT = "APP_SESSION_COUNT"
        const val EXTRA_PROJECT_COUNT = "PROJECT_SESSION_COUNT"
        const val EXTRA_PROJECT_NAME = "PROJECT_NAME"
        const val EXTRA_PROJECT_PATH = "PROJECT_PATH"
        const val EXTRA_TARGET_PAGE = "EXTRA_TARGET_PAGE"

        fun projectNotifId(projectPath: String): Int {
            val hash = projectPath.hashCode() and 0x7FFFFFFF
            return NOTIF_ID_PROJECT_BASE + (hash % 100000)
        }

        fun syncState(
            context: Context,
            appCount: Int,
            projects: List<ProjectState>
        ) {
            val total = appCount + projects.sumOf { it.sessionCount }
            val intent = Intent(context, TerminalService::class.java).apply {
                putExtra(EXTRA_SYNC_ALL, true)
                putExtra(EXTRA_APP_COUNT, appCount)
                val bundleList = ArrayList<Bundle>()
                for (p in projects) {
                    bundleList.add(Bundle().apply {
                        putString("name", p.name)
                        putString("path", p.path)
                        putInt("count", p.sessionCount)
                        putStringArrayList("tabs", ArrayList(p.tabNames))
                    })
                }
                putParcelableArrayListExtra(EXTRA_PROJECTS, bundleList)
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

        fun updateService(
            context: Context,
            appCount: Int,
            projectCount: Int = 0,
            projectName: String? = null,
            projectPath: String? = null,
            targetPage: Int = 3
        ) {
            val list = if (!projectPath.isNullOrEmpty() && projectCount > 0) {
                listOf(ProjectState(projectName ?: "Project", projectPath, projectCount, emptyList()))
            } else {
                emptyList()
            }
            syncState(context, appCount, list)
        }
    }
}
