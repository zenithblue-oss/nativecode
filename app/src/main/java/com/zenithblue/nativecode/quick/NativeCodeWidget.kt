package com.zenithblue.nativecode.quick

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.zenithblue.nativecode.R

/**
 * 2x1 widget: Agent and Desktop. No periodic updates (updatePeriodMillis=0): the desktop controller
 * calls [updateAll] on every state change; a cold update reads the persisted state.
 */
class NativeCodeWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context)) }
    }

    companion object {
        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, NativeCodeWidget::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx))
        }

        private fun build(ctx: Context): RemoteViews {
            val q = QuickActions.desktopQuick(ctx)
            val sub = quickSubtitle(ctx, q)
            return RemoteViews(ctx.packageName, R.layout.widget).apply {
                setTextViewText(R.id.widget_agent_sub, QuickActions.agentTool(ctx))
                setTextViewText(R.id.widget_desktop_sub, sub)
                setContentDescription(R.id.widget_desktop, ctx.getString(R.string.quick_widget_desktop) + ", " + sub)
                setImageViewResource(
                    R.id.widget_desktop_icon,
                    if (q.ui == QuickActions.DeskUi.STOP || q.ui == QuickActions.DeskUi.STARTING) R.drawable.ic_stop_thick else R.drawable.ic_display
                )
                setOnClickPendingIntent(R.id.widget_agent, QuickActions.pending(ctx, QuickActions.AGENT))
                setOnClickPendingIntent(R.id.widget_desktop, QuickActions.pending(ctx, q.action))
            }
        }
    }
}
