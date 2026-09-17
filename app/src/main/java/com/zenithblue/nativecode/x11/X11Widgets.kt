package com.zenithblue.nativecode.x11

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.ui.BrutalistToggle

/** Local UI kit for the X11 settings page — mirrors MainActivity brutalist styling. */
internal fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

internal const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
internal const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

internal fun Context.brutalistBg(
    fillColor: Int,
    strokeColor: Int = NC.OUTLINE_VAR,
    shadowColor: Int = NC.SHADOW_DARK,
    offsetDp: Int = 6
): LayerDrawable {
    val bottom = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(shadowColor) }
    val right = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(strokeColor) }
    val front = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fillColor)
        setStroke(dp(1), strokeColor)
    }
    val off = dp(offsetDp)
    return LayerDrawable(arrayOf<Drawable>(bottom, right, front)).apply {
        setLayerInset(0, off, off, 0, 0)
        setLayerInset(1, off, 0, 0, off)
        setLayerInset(2, 0, 0, off, off)
    }
}

internal fun Context.sectionCard(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = brutalistBg(fillColor = NC.SURFACE_LOW)
    setPadding(dp(16), dp(16), dp(16), dp(16))
    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(16) }
}

internal fun Context.sectionHeader(title: String): TextView = TextView(this).apply {
    text = title
    textSize = 13f
    setTextColor(NC.PRIMARY)
    typeface = Typeface.MONOSPACE
    paint.isFakeBoldText = true
    setPadding(0, 0, 0, dp(10))
}

internal fun Context.divider(): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
        topMargin = dp(10); bottomMargin = dp(10)
    }
    setBackgroundColor(NC.OUTLINE_VAR)
}

private fun Context.rowContainer(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
        topMargin = dp(6); bottomMargin = dp(6)
    }
    setPadding(0, dp(4), 0, dp(4))
}

private fun Context.rowTexts(title: String, summary: String): LinearLayout {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(12) }
    }
    col.addView(TextView(this).apply {
        text = title
        textSize = 14f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
    })
    col.addView(TextView(this).apply {
        text = summary
        textSize = 11f
        setTextColor(NC.ON_SURF_VAR)
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(2), 0, 0)
    })
    return col
}

/** Boolean row wired to a getter + broadcast put. Returns a refresh lambda. */
internal fun Context.switchRow(
    title: String,
    summary: String,
    get: () -> Boolean,
    set: (Boolean) -> Unit
): LinearLayout {
    val row = rowContainer()
    row.addView(rowTexts(title, summary))
    val toggle = BrutalistToggle(this, get()) { checked -> set(checked) }
    row.addView(toggle)
    row.tag = { toggle.isChecked = get() }
    return row
}

/** List row opening a single-choice dialog. Returns a refresh lambda via tag. */
internal fun Context.listRow(
    title: String,
    getSummary: () -> String,
    entries: Array<String>,
    currentValue: () -> String,
    valueOf: (Int) -> String,
    indexOf: (String) -> Int,
    set: (String) -> Unit
): LinearLayout {
    val row = rowContainer()
    val texts = rowTexts(title, getSummary())
    row.addView(texts)
    val valueTv = texts.getChildAt(1) as TextView
    val chevron = ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron_right)
        setColorFilter(NC.PRIMARY)
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
    }
    row.addView(chevron)
    row.isClickable = true
    row.isFocusable = true
    row.setOnClickListener {
        val checked = indexOf(currentValue())
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar)
            .setTitle(title)
            .setSingleChoiceItems(entries, checked) { dialog, which ->
                set(valueOf(which))
                valueTv.text = getSummary()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    row.tag = { valueTv.text = getSummary() }
    return row
}

/** Seek row with value label. Returns refresh via tag. */
internal fun Context.seekRow(
    title: String,
    min: Int,
    max: Int,
    step: Int,
    get: () -> Int,
    set: (Int) -> Unit,
    format: (Int) -> String
): LinearLayout {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        }
    }
    col.addView(TextView(this).apply {
        text = title
        textSize = 14f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
    })
    val valueTv = TextView(this).apply {
        text = format(get())
        textSize = 12f
        setTextColor(NC.PRIMARY)
        typeface = Typeface.MONOSPACE
        setPadding(0, dp(2), 0, dp(4))
    }
    col.addView(valueTv)
    val seek = SeekBar(this).apply {
        this.max = (max - min) / step
        progress = ((get() - min) / step).coerceIn(0, this.max)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }
    seek.progressTintList = ColorStateList.valueOf(NC.PRIMARY_CON)
    seek.thumbTintList = ColorStateList.valueOf(NC.PRIMARY_CON)
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) {
                val v = min + progress * step
                set(v)
                valueTv.text = format(v)
            }
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {
            val v = min + seek.progress * step
            valueTv.text = format(v)
        }
    })
    col.addView(seek)
    col.tag = {
        val v = get()
        valueTv.text = format(v)
        seek.progress = ((v - min) / step).coerceIn(0, seek.max)
    }
    return col
}

/** Validated text row (custom resolution). Refresh via tag. */
internal fun Context.textRow(
    title: String,
    summary: String,
    get: () -> String,
    validate: (String) -> Boolean,
    set: (String) -> Unit,
    errorText: String
): LinearLayout {
    val row = rowContainer()
    val texts = rowTexts(title, summary)
    row.addView(texts)
    val valueTv = texts.getChildAt(1) as TextView
    val chevron = ImageView(this).apply {
        setImageResource(R.drawable.ic_chevron_right)
        setColorFilter(NC.PRIMARY)
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
    }
    row.addView(chevron)
    row.isClickable = true
    row.isFocusable = true
    row.setOnClickListener {
        val input = EditText(this).apply {
            setText(get())
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_NoActionBar)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim()
                if (validate(v)) {
                    set(v)
                    valueTv.text = v
                } else {
                    input.error = errorText
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    row.tag = { valueTv.text = get() }
    return row
}

/** Runs every stored refresh lambda in a container tree. */
internal fun refreshRows(view: View) {
    (view.tag as? () -> Unit)?.invoke()
    if (view is LinearLayout) {
        for (i in 0 until view.childCount) refreshRows(view.getChildAt(i))
    } else if (view is FrameLayout) {
        for (i in 0 until view.childCount) refreshRows(view.getChildAt(i))
    } else if (view is android.widget.ScrollView) {
        for (i in 0 until view.childCount) refreshRows(view.getChildAt(i))
    }
}
