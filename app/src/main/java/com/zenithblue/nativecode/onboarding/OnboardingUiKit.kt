package com.zenithblue.nativecode.onboarding

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R

/**
 * Onboarding UI builders extracted from OnboardingActivity.
 * All builders take an explicit [Context]; the Activity keeps
 * one-line delegates so existing call sites are unchanged.
 */
internal object OnboardingUiKit {

    fun dp(context: Context, v: Int) =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    fun roundedBg(context: Context, fill: Int, stroke: Int, r: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(dp(context, 1), stroke)
            cornerRadius = r.toFloat()
        }

    fun textBadge(context: Context, text: String, bg: Int, fg: Int) =
        TextView(context).apply {
            this.text = text
            textSize = 10f
            setTextColor(fg)
            typeface = Typeface.MONOSPACE
            background = roundedBg(context, bg, fg, dp(context, 4))
            setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3))
        }

    fun smallHeader(
        context: Context,
        title: String,
        iconRes: Int = R.drawable.ic_terminal
    ): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, 16))
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)).apply {
                rightMargin = dp(context, 10)
            }
        }
        val text = TextView(context).apply {
            this.text = title
            textSize = 20f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(icon); root.addView(text)
        return root
    }

    fun primaryButton(
        context: Context,
        label: String,
        iconRes: Int? = null,
        onClick: () -> Unit
    ): LinearLayout {
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(
                context,
                fillColor = NC.PRIMARY_CON,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(context, 4).toFloat()
                        v.translationY = dp(context, 4).toFloat()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                    }
                }
                false
            }
        }
        val tv = TextView(context).apply {
            text = label
            textSize = 16f
            setTextColor(NC.ON_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        btn.addView(tv)
        if (iconRes != null) {
            val iv = ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(NC.ON_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)).apply {
                    leftMargin = dp(context, 8)
                }
            }
            btn.addView(iv)
        }
        return btn
    }

    fun secondaryButton(
        context: Context,
        label: String,
        onClick: () -> Unit
    ): LinearLayout {
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = cyberBrutalistBg(
                context,
                fillColor = NC.SURFACE_CONTAINER,
                strokeColor = NC.BORDER,
                shadowColor = NC.SURFACE_BRIGHT,
                offsetDp = 6,
                cornerRadiusDp = 0,
                rightFaceColor = NC.OUTLINE_VAR
            )
            setPadding(dp(context, 20), dp(context, 16), dp(context, 20), dp(context, 16))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.translationX = dp(context, 4).toFloat()
                        v.translationY = dp(context, 4).toFloat()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.translationX = 0f
                        v.translationY = 0f
                    }
                }
                false
            }
        }
        val tv = TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        btn.addView(tv)
        return btn
    }

    fun featureBadge(context: Context, iconRes: Int, textStr: String): LinearLayout {
        val badge = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg(context, NC.SURFACE_CONTAINER, NC.BORDER, 0)
            setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7))
        }
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(context, 14), dp(context, 14)).apply {
                rightMargin = dp(context, 6)
            }
        }
        val tv = TextView(context).apply {
            text = textStr
            textSize = 11f
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
        }
        badge.addView(icon)
        badge.addView(tv)
        return badge
    }

    fun cyberBrutalistBg(
        context: Context,
        fillColor: Int,
        strokeColor: Int = NC.BORDER,
        shadowColor: Int = NC.SURFACE_BRIGHT,
        offsetDp: Int = 6,
        cornerRadiusDp: Int = 0,
        rightFaceColor: Int = NC.OUTLINE_VAR
    ): LayerDrawable {
        val bottomShadow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(shadowColor)
            if (cornerRadiusDp > 0) cornerRadius = dp(context, cornerRadiusDp).toFloat()
        }
        val rightShadow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(rightFaceColor)
            if (cornerRadiusDp > 0) cornerRadius = dp(context, cornerRadiusDp).toFloat()
        }
        val frontDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            setStroke(dp(context, 1), strokeColor)
            if (cornerRadiusDp > 0) cornerRadius = dp(context, cornerRadiusDp).toFloat()
        }
        val layers = arrayOf<Drawable>(bottomShadow, rightShadow, frontDrawable)
        val off = dp(context, offsetDp)
        return LayerDrawable(layers).apply {
            setLayerInset(0, off, off, 0, 0)
            setLayerInset(1, off, 0, 0, off)
            setLayerInset(2, 0, 0, off, off)
        }
    }

    fun pulseView(v: View) {
        val anim = ObjectAnimator.ofFloat(v, "alpha", 0.45f, 1f).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        anim.start()
    }

    class CustomBrutalistToggle(
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

            val trackWidth = OnboardingUiKit.dp(context, 46)
            val trackHeight = OnboardingUiKit.dp(context, 24)
            val thumbSize = OnboardingUiKit.dp(context, 18)
            val margin = OnboardingUiKit.dp(context, 3)

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
                if (!isEnabled) return@setOnClickListener
                isChecked = !isChecked
                updateState(animate = true)
                onCheckedChange?.invoke(isChecked)
            }
        }

        private fun updateState(animate: Boolean) {
            val trackWidth = OnboardingUiKit.dp(context, 46)
            val thumbSize = OnboardingUiKit.dp(context, 18)
            val margin = OnboardingUiKit.dp(context, 3)

            val trackColor = if (isChecked) Color.parseColor("#3DDC84") else Color.parseColor("#1E1E1E")
            val trackBorder = if (isChecked) Color.parseColor("#3DDC84") else Color.parseColor("#333333")
            val thumbColor = if (isChecked) Color.parseColor("#0A0A0A") else Color.parseColor("#A0A0A0")

            trackView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(trackColor)
                setStroke(OnboardingUiKit.dp(context, 2), trackBorder)
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
    }
}
