package com.zenithblue.nativecode.editor

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import com.zenithblue.nativecode.NC

/** Shows the dialog in the obsidian theme: sharp dark card, readable accent buttons. [danger] buttons turn red. */
internal fun AlertDialog.showThemed(context: Context, danger: Set<Int> = emptySet()): AlertDialog {
    show()
    val dp = context.resources.displayMetrics.density
    window?.setBackgroundDrawable(GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke((1 * dp).toInt(), NC.OUTLINE_VAR) })
    val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
    findViewById<TextView>(titleId)?.apply { setTextColor(NC.PRIMARY); typeface = Typeface.MONOSPACE }
    findViewById<TextView>(android.R.id.message)?.setTextColor(NC.ON_SURF_VAR)
    for (b in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE)) {
        getButton(b)?.apply {
            setTextColor(if (b in danger) NC.ERROR else if (b == AlertDialog.BUTTON_NEGATIVE) NC.ON_SURF_VAR else NC.PRIMARY)
            typeface = Typeface.MONOSPACE
        }
    }
    return this
}
