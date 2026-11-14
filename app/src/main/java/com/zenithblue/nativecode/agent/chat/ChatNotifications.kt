package com.zenithblue.nativecode.agent.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zenithblue.nativecode.MainActivity
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.terminal.TerminalService

/**
 * Agent chat notifications (plan 9.2.8): approval needed, input needed, turn done, turn failed. Suppressed while
 * that thread is on screen ([AgentChats.visibleThreadId]). Tap → MainActivity on the TERMINAL page (3) or the
 * project workspace (10 + PROJECT_PATH), plus [EXTRA_THREAD_ID]; MainActivity.handleIntent opens the thread.
 */
object ChatNotifications {
    const val CHANNEL_ID = "agent_notifications"
    const val EXTRA_THREAD_ID = "EXTRA_THREAD_ID"
    const val PAGE_TERMINAL = 3
    const val PAGE_WORKSPACE = 10

    enum class Kind(val title: String) { APPROVAL("Approval needed"), INPUT("Input needed"), DONE("Done"), FAILED("Turn failed"), BRIDGE("From agent") }

    fun notify(ctx: Context, thread: ChatThread, kind: Kind, text: String? = null) {
        if (AgentChats.visibleThreadId == thread.id) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Agent chat", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Approvals, questions and finished turns of AI agent chats"
        })
        val id = notificationId(thread.id)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_THREAD_ID, thread.id)
            if (thread.projectPath != null) {
                putExtra(TerminalService.EXTRA_TARGET_PAGE, PAGE_WORKSPACE)
                putExtra(TerminalService.EXTRA_PROJECT_PATH, thread.projectPath)
            } else {
                putExtra(TerminalService.EXTRA_TARGET_PAGE, PAGE_TERMINAL)
            }
        }
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle("${kind.title} · ${thread.title}")
            .setContentText(text ?: thread.tool)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(if (kind == Kind.APPROVAL || kind == Kind.INPUT) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { nm.notify(id, n) }
    }

    fun cancel(ctx: Context, threadId: String) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(notificationId(threadId)) }
    }

    /** Stable per thread, clear of TerminalService / AgentBridge ids. */
    fun notificationId(threadId: String) = 0x4C000000 or (threadId.hashCode() and 0x00FFFFFF)
}
