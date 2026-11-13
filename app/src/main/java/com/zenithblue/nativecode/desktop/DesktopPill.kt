package com.zenithblue.nativecode.desktop

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R

/**
 * Compact desktop control for the top bars / tool rail. Paints DesktopController.pill():
 *
 *   off        [▢]  /  free: [▢ 24m]      tap = start (gated)
 *   starting   [▢ … ■]                    tap = log, ■ = cancel
 *   running    [▣ ON ■] / free [▣ 24m ■]  tap = view, ■ = stop, hold = View / Stop / Log
 *   budget out [🔒 0m · PRO]              tap = upgrade sheet
 */
class DesktopPill(context: Context, private val desktop: DesktopController) : LinearLayout(context) {
    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val icon = ImageView(context).apply {
        layoutParams = LayoutParams(dp(18), dp(18))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val label = TextView(context).apply {
        textSize = 11f
        typeface = Typeface.MONOSPACE
        paint.isFakeBoldText = true
        maxLines = 1
        includeFontPadding = false
        setPadding(dp(4), 0, 0, 0)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val stopBtn = ImageView(context).apply {
        setImageResource(R.drawable.ic_stop_thick)
        setColorFilter(NC.ERROR)
        contentDescription = "Stop desktop"
        tooltipText = "Stop desktop"
        setPadding(dp(7), dp(6), dp(5), dp(6))
        layoutParams = LayoutParams(dp(28), dp(28))
        setOnClickListener { desktop.stop() }
    }
    private val repaint: () -> Unit = { refresh() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(28)
        setPadding(dp(8), 0, dp(2), 0)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(28)).apply { leftMargin = dp(4) }
        isFocusable = true
        addView(icon); addView(label); addView(stopBtn)
        setOnClickListener { desktop.primaryAction() }
        setOnLongClickListener { showMenu(); true }
        refresh()
    }

    private fun showMenu() {
        val running = desktop.isRunning
        PopupMenu(context, this).apply {
            if (running) menu.add("View desktop").setOnMenuItemClickListener { desktop.view(); true }
            else menu.add("Start desktop").setOnMenuItemClickListener { desktop.start(); true }
            if (running || desktop.state == DesktopController.State.STARTING) {
                menu.add("Stop desktop").setOnMenuItemClickListener { desktop.stop(); true }
            }
            menu.add("Desktop log").setOnMenuItemClickListener { desktop.openLogs(); true }
        }.show()
    }

    fun refresh() {
        val m = desktop.pill()
        val tint = when {
            m.locked -> NC.TERTIARY
            m.filled -> NC.PRIMARY
            else -> NC.ON_SURF_VAR
        }
        icon.setImageResource(if (m.locked) R.drawable.ic_lock else R.drawable.ic_display)
        icon.setColorFilter(tint)
        label.text = m.label
        label.setTextColor(tint)
        label.visibility = if (m.label.isEmpty()) View.GONE else View.VISIBLE
        stopBtn.visibility = if (m.stoppable) View.VISIBLE else View.GONE
        setPadding(dp(8), 0, if (m.stoppable) dp(2) else dp(8), 0)
        background = GradientDrawable().apply {
            setColor(if (m.filled) (NC.PRIMARY and 0x00FFFFFF) or 0x33000000 else NC.SURFACE_LOW)
            setStroke(dp(1), if (m.filled) NC.PRIMARY else NC.OUTLINE_VAR)
        }
        contentDescription = m.description
        tooltipText = m.description
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        desktop.listeners += repaint
        refresh()
    }

    override fun onDetachedFromWindow() {
        desktop.listeners -= repaint
        super.onDetachedFromWindow()
    }
}
