package com.zenithblue.nativecode.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Brutalist on/off toggle — single source of truth, shared by the terminal
 * settings card, the X11 settings page, and anywhere else a switch is needed.
 * Green filled track + dark thumb when on, dark track + gray thumb when off.
 */
class BrutalistToggle(
    context: Context,
    initialChecked: Boolean = false,
    private val onCheckedChange: ((Boolean) -> Unit)? = null
) : LinearLayout(context) {

    var isChecked: Boolean = initialChecked
        set(value) {
            field = value
            updateState(animate = false)
        }

    private val trackView = FrameLayout(context)
    private val thumbView = View(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true

        val trackWidth = dp(46)
        val trackHeight = dp(24)
        val thumbSize = dp(18)
        val margin = dp(3)

        trackView.layoutParams = LayoutParams(trackWidth, trackHeight)

        val thumbParams = FrameLayout.LayoutParams(thumbSize, thumbSize).apply {
            gravity = Gravity.CENTER_VERTICAL
            leftMargin = margin
        }
        thumbView.layoutParams = thumbParams

        trackView.addView(thumbView)
        addView(trackView)

        updateState(animate = false)

        setOnClickListener {
            isChecked = !isChecked
            updateState(animate = true)
            onCheckedChange?.invoke(isChecked)
        }
    }

    private fun updateState(animate: Boolean) {
        val trackWidth = dp(46)
        val thumbSize = dp(18)
        val margin = dp(3)

        val trackColor = if (isChecked) Color.parseColor("#3DDC84") else Color.parseColor("#1E1E1E")
        val trackBorder = if (isChecked) Color.parseColor("#3DDC84") else Color.parseColor("#333333")
        val thumbColor = if (isChecked) Color.parseColor("#0A0A0A") else Color.parseColor("#A0A0A0")

        trackView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(trackColor)
            setStroke(dp(2), trackBorder)
            cornerRadius = 0f
        }

        thumbView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(thumbColor)
            cornerRadius = 0f
        }

        val targetMargin = if (isChecked) {
            trackWidth - thumbSize - margin
        } else {
            margin
        }

        val params = thumbView.layoutParams as FrameLayout.LayoutParams
        if (animate) {
            val anim = ValueAnimator.ofInt(params.leftMargin, targetMargin)
            anim.duration = 150
            anim.addUpdateListener { va ->
                params.leftMargin = va.animatedValue as Int
                thumbView.layoutParams = params
            }
            anim.start()
        } else {
            params.leftMargin = targetMargin
            thumbView.layoutParams = params
        }
    }

    private fun dp(dpValue: Int): Int {
        return (dpValue * context.resources.displayMetrics.density).toInt()
    }
}
