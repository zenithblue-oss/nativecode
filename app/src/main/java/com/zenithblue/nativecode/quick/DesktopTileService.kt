package com.zenithblue.nativecode.quick

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.zenithblue.nativecode.R

/** "Linux desktop" tile: mirrors the persisted desktop state, tap opens the app to start or stop it. */
class DesktopTileService : TileService() {

    override fun onStartListening() = paint()

    override fun onClick() {
        val action = QuickActions.desktopQuick(this).action
        if (isLocked) unlockAndRun { open(action) } else open(action)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(action: String) {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(QuickActions.pending(this, action))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(QuickActions.launchIntent(this, action))
        }
    }

    private fun paint() {
        val tile = qsTile ?: return
        val q = QuickActions.desktopQuick(this)
        tile.label = getString(R.string.quick_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_display)
        tile.state = if (q.ui == QuickActions.DeskUi.STOP || q.ui == QuickActions.DeskUi.STARTING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val sub = quickSubtitle(this, q)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = sub
        tile.contentDescription = "${tile.label}, $sub"
        tile.updateTile()
    }
}

/** Short state line shared by the tile and the widget. */
fun quickSubtitle(ctx: android.content.Context, q: QuickActions.DesktopQuick): String = when (q.ui) {
    QuickActions.DeskUi.START -> q.minutes?.let { ctx.getString(R.string.quick_start_min, it.toInt()) } ?: ctx.getString(R.string.quick_start)
    QuickActions.DeskUi.STARTING -> ctx.getString(R.string.quick_starting)
    QuickActions.DeskUi.STOP -> q.minutes?.let { ctx.getString(R.string.quick_stop_min, it.toInt()) } ?: ctx.getString(R.string.quick_stop)
    QuickActions.DeskUi.UPGRADE -> ctx.getString(R.string.quick_upgrade)
}
