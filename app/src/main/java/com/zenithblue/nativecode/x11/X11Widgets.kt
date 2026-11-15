package com.zenithblue.nativecode.x11

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.ui.BrutalistToggle
import com.zenithblue.nativecode.tr

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

/** ── NC brutalist dialog kit (replaces stock Material AlertDialog) ── */

private fun Context.ncPressable(
    label: String,
    fill: Int,
    stroke: Int,
    textColor: Int,
    onClick: () -> Unit
): TextView {
    return TextView(this).apply {
        text = label
        textSize = 12f
        setTextColor(textColor)
        typeface = Typeface.MONOSPACE
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        background = brutalistBg(fillColor = fill, strokeColor = stroke, offsetDp = 4)
        setPadding(dp(16), dp(11), dp(16), dp(11))
        setOnClickListener { onClick() }
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.translationX = dp(2).toFloat()
                    v.translationY = dp(2).toFloat()
                    v.background = brutalistBg(fillColor = fill, strokeColor = stroke, offsetDp = 2)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.translationX = 0f
                    v.translationY = 0f
                    v.background = brutalistBg(fillColor = fill, strokeColor = stroke, offsetDp = 4)
                }
            }
            false
        }
    }
}

private fun Context.ncDialogShell(title: String): Triple<Dialog, LinearLayout, LinearLayout> {
    val dialog = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
    dialog.setCancelable(true)
    val scrim = FrameLayout(this).apply {
        setBackgroundColor(Color.parseColor("#CC0A0A0A"))
        layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        setOnClickListener { dialog.dismiss() }
    }
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = brutalistBg(fillColor = NC.SURFACE_LOW)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(24)
            rightMargin = dp(24)
        }
        isClickable = true
    }
    card.addView(TextView(this).apply {
        text = title.uppercase()
        textSize = 15f
        setTextColor(Color.WHITE)
        typeface = Typeface.MONOSPACE
        paint.isFakeBoldText = true
        setPadding(0, 0, 0, dp(12))
    })
    card.addView(View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(8) }
        setBackgroundColor(NC.OUTLINE_VAR)
    })
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }
    card.addView(content)
    scrim.addView(card)
    dialog.setContentView(scrim)
    dialog.window?.apply {
        setLayout(MATCH, MATCH)
        setBackgroundDrawableResource(android.R.color.transparent)
        statusBarColor = Color.TRANSPARENT
        navigationBarColor = Color.TRANSPARENT
    }
    return Triple(dialog, card, content)
}

/** NC single-choice sheet used by every listRow submenu. */
private fun Context.showNcSingleChoice(
    title: String,
    entries: Array<String>,
    checked: Int,
    onPick: (Int) -> Unit
) {
    val (dialog, card, content) = ncDialogShell(title)
    val scroll = ScrollView(this).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(4)
            bottomMargin = dp(12)
        }
    }
    val list = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }
    val updateRowViews = mutableListOf<() -> Unit>()
    var selectedIndex = checked

    entries.forEachIndexed { i, label ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            minimumHeight = dp(48)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            isClickable = true
            isFocusable = true
        }
        val radio = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(14) }
        }
        val outerCircle = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)
        }
        val innerDot = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(NC.PRIMARY)
            }
        }
        radio.addView(outerCircle)
        radio.addView(innerDot)

        val labelTv = TextView(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        row.addView(radio)
        row.addView(labelTv)

        val updateVisuals = {
            val isSelected = (i == selectedIndex)
            outerCircle.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), if (isSelected) NC.PRIMARY else NC.OUTLINE)
            }
            innerDot.visibility = if (isSelected) View.VISIBLE else View.GONE
            labelTv.setTextColor(if (isSelected) NC.PRIMARY else NC.ON_SURFACE)
            labelTv.paint.isFakeBoldText = isSelected
            row.setBackgroundColor(if (isSelected) Color.parseColor("#154EE085") else Color.TRANSPARENT)
        }
        updateRowViews.add(updateVisuals)
        updateVisuals()

        row.setOnClickListener {
            selectedIndex = i
            updateRowViews.forEach { it() }
            row.postDelayed({
                dialog.dismiss()
                onPick(i)
            }, 120)
        }
        list.addView(row)
    }
    scroll.addView(list)
    content.addView(scroll)
    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    btnRow.addView(ncPressable(tr(R.string.x11_cancel), NC.SURFACE_CONTAINER, NC.OUTLINE_VAR, Color.WHITE) { dialog.dismiss() })
    card.addView(btnRow)
    dialog.show()
}

/** NC text-input sheet used by textRow (custom resolution). */
private fun Context.showNcTextInput(
    title: String,
    initial: String,
    errorText: String,
    validate: (String) -> Boolean,
    onSave: (String) -> Unit
) {
    val (dialog, card, content) = ncDialogShell(title)
    val errorTv = TextView(this).apply {
        text = errorText
        textSize = 11f
        setTextColor(NC.ERROR)
        typeface = Typeface.MONOSPACE
        visibility = View.GONE
        setPadding(0, dp(8), 0, 0)
    }
    val input = EditText(this).apply {
        setText(initial)
        setTextColor(Color.WHITE)
        setHintTextColor(NC.ON_SURF_VAR)
        textSize = 14f
        typeface = Typeface.MONOSPACE
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(8)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(NC.SURFACE_LOWEST)
            setStroke(dp(1), NC.OUTLINE_VAR)
        }
        setSingleLine(true)
        setOnClickListener { errorTv.visibility = View.GONE }
    }
    content.addView(input)
    content.addView(errorTv)
    val btnRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(16), 0, 0)
    }
    btnRow.addView(ncPressable(tr(R.string.x11_cancel), NC.SURFACE_CONTAINER, NC.OUTLINE_VAR, Color.WHITE) { dialog.dismiss() }.apply {
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(10) }
    })
    btnRow.addView(ncPressable(tr(R.string.x11_save), NC.PRIMARY, NC.PRIMARY, Color.parseColor("#0A0A0A")) {
        val v = input.text.toString().trim()
        if (validate(v)) {
            dialog.dismiss()
            onSave(v)
        } else {
            errorTv.visibility = View.VISIBLE
        }
    }.apply {
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
    })
    card.addView(btnRow)
    dialog.show()
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
        showNcSingleChoice(title, entries, checked) { which ->
            set(valueOf(which))
            valueTv.text = getSummary()
        }
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
        showNcTextInput(title, get(), errorText, validate) { v ->
            set(v)
            valueTv.text = v
        }
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
