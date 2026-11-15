package com.zenithblue.nativecode.workspace

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.zenithblue.nativecode.NC

/**
 * All-visible compact icon rail (replaces the workspace "more" popup). Every tool is one tap away;
 * each button carries a content description and a long-press tooltip. "On" is shown by a filled
 * tile + outline, not by colour alone.
 */
class ToolRail(context: Context) : LinearLayout(context) {
    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private class Item(val view: ImageView, val icon: () -> Int, val desc: () -> String, val active: () -> Boolean,
                       val visible: () -> Boolean)

    private val items = ArrayList<Item>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /**
     * @param active filled when true (toggles); plain icon otherwise.
     * @param onLongClick optional; without it long-press shows the tooltip.
     */
    fun add(
        icon: () -> Int,
        desc: () -> String,
        active: () -> Boolean = { false },
        onLongClick: (() -> Unit)? = null,
        visible: () -> Boolean = { true },
        onClick: () -> Unit,
    ): ImageView {
        val v = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9), dp(8), dp(9), dp(8))
            layoutParams = LayoutParams(dp(CELL_W_DP), dp(CELL_H_DP))
            isFocusable = true
            setOnClickListener { onClick(); refresh() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
        items += Item(v, icon, desc, active, visible)
        addView(v)
        refresh()
        return v
    }

    /** Any other compact control (desktop pill) that lives in the rail. */
    fun addControl(view: View) {
        addView(view)
    }

    /** Re-read every button's icon / description / on-state. */
    fun refresh() {
        items.forEach { item ->
            item.view.visibility = if (item.visible()) VISIBLE else GONE
            val on = item.active()
            val d = item.desc()
            item.view.setImageResource(item.icon())
            item.view.setColorFilter(if (on) NC.PRIMARY else NC.ON_SURF_VAR)
            item.view.background = if (!on) null else InsetDrawable(
                GradientDrawable().apply {
                    setColor((NC.PRIMARY and 0x00FFFFFF) or 0x33000000)
                    setStroke(dp(1), NC.PRIMARY)
                },
                dp(3), dp(2), dp(3), dp(2)
            )
            item.view.contentDescription = d
            item.view.tooltipText = d
            item.view.isSelected = on
        }
    }

    companion object {
        const val CELL_W_DP = 38
        const val CELL_H_DP = 36
    }
}
