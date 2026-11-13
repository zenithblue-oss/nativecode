package com.zenithblue.nativecode.terminal.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import androidx.compose.ui.unit.Dp
import com.zenithblue.nativecode.agent.chat.ui.Space

/**
 * TERMINAL page tab strip (item 12 M1; replaces the drawer): one flush rail, cells split by hairlines, the active
 * cell lit by a green top edge. MA feeds [submit]. Long-press a tab: Rename · Close others. "+" is pinned at the
 * end, outside the scroll. Icons are vector drawables / drawn shapes only (§9.2.6 UI rules).
 */
@SuppressLint("ViewConstructor")
class TermTabStrip(context: Context, onAdd: (View) -> Unit) : LinearLayout(context) {
    /** [home] = the leading "back to chat" cell (no close, no menu). [icon] null → ic_terminal. */
    data class Tab(val key: String, val label: String, val icon: Bitmap?, val live: Boolean, val selected: Boolean, val home: Boolean = false)

    var onTap: (Tab) -> Unit = {}
    var onClose: (Tab) -> Unit = {}
    var onRename: (Tab, String) -> Unit = { _, _ -> }
    var onCloseOthers: (Tab) -> Unit = {}
    /** Called every 1.5 s while the strip is on screen (running/idle dots). */
    var onTick: () -> Unit = {}

    private val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        addView(row, FrameLayout.LayoutParams(WRAP, MATCH))
    }
    /** Pinned leading cell (back to chat): never scrolls away. */
    private val lead = LinearLayout(context).apply { orientation = HORIZONTAL }
    private var last: List<Tab>? = null
    private val ticker = object : Runnable {
        override fun run() { if (isShown) onTick(); postDelayed(this, 1500) }
    }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(NC.SURFACE_LOWEST)
        addView(lead, LayoutParams(WRAP, MATCH))
        addView(scroll, LayoutParams(0, MATCH, 1f))
        addView(View(context).apply { setBackgroundColor(NC.OUTLINE_VAR) }, LayoutParams(dp(1), MATCH))
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(NC.ON_PRIMARY)
            setBackgroundColor(NC.PRIMARY)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(px(Space.m), 0, px(Space.m), 0)
            contentDescription = "New tab"
            setOnClickListener { onAdd(it) }
        }, LayoutParams(dp(48), MATCH))
    }

    /** Thin fixed-height rail (36 dp × font scale): MATCH-height cells in a WRAP parent otherwise grow to fill the page. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (dp(36) * resources.configuration.fontScale.coerceAtLeast(1f)).toInt()
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(ticker); postDelayed(ticker, 1500) }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); super.onDetachedFromWindow() }

    /** Rebuilds the cells only when something changed (the tick calls this every 1.5 s). */
    fun submit(tabs: List<Tab>) {
        if (tabs == last) return
        last = tabs
        lead.removeAllViews()
        row.removeAllViews()
        tabs.filter { it.home }.forEach { lead.addView(cell(it, 0)) }
        val list = tabs.filterNot { it.home }
        list.forEachIndexed { i, t -> row.addView(cell(t, i + 1)) }
        val sel = list.indexOfFirst { it.selected }
        if (sel >= 0) scroll.post { row.getChildAt(sel)?.let { c -> scroll.smoothScrollTo((c.left - px(Space.xl)).coerceAtLeast(0), 0) } }
    }

    private fun cell(t: Tab, n: Int): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Spacing = chat Space tokens (§9.2.6 UI rules).
        setPadding(px(Space.inset), 0, if (t.home) px(Space.l) else px(Space.xs), 0)
        // Hairline on the right; the selected cell gets a raised fill + 2 dp green top edge.
        background = LayerDrawable(arrayOf(
            ColorDrawable(if (t.selected) NC.SURFACE_HIGH else NC.SURFACE_LOWEST),
            ColorDrawable(if (t.selected) NC.PRIMARY else 0),
            ColorDrawable(NC.OUTLINE_VAR),
        )).apply {
            setLayerGravity(1, Gravity.TOP); setLayerHeight(1, dp(2))
            setLayerGravity(2, Gravity.END); setLayerWidth(2, dp(1))
        }
        layoutParams = LayoutParams(WRAP, MATCH)
        contentDescription = if (t.home) "Back to chat" else t.label + if (t.live) ", running" else ", idle"
        setOnClickListener { onTap(t) }
        if (!t.home) setOnLongClickListener { menu(this, t); true }

        val tint = if (t.selected) NC.PRIMARY else NC.ON_SURF_VAR
        val iconLp = LayoutParams(dp(16), dp(16)).apply { rightMargin = px(Space.s) }
        if (t.home) {
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_tab_chat); setColorFilter(NC.PRIMARY) }, iconLp)
            addView(text(t.label, 11f, NC.PRIMARY, bold = true))
            return@apply
        }
        addView(text("%02d".format(n), 10f, NC.OUTLINE).apply { setPadding(0, 0, px(Space.s), 0) })
        // Provider logos sit on a light square tile: dark logos (grok) keep >= 3:1 contrast on the obsidian rail.
        addView(ImageView(context).apply {
            if (t.icon != null) { setImageBitmap(t.icon); setBackgroundColor(NC.ON_SURFACE); setPadding(dp(2), dp(2), dp(2), dp(2)) }
            else { setImageResource(R.drawable.ic_terminal); setColorFilter(tint) }
        }, LayoutParams(dp(20), dp(20)).apply { rightMargin = px(Space.s) })
        addView(text(t.label, 12f, if (t.selected) NC.ON_SURFACE else NC.ON_SURF_VAR, bold = t.selected).apply {
            maxWidth = dp(150); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        // Running/idle: a drawn dot (filled green = running, outlined = idle).
        addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (t.live) setColor(NC.PRIMARY) else setStroke(dp(1), NC.OUTLINE)
            }
        }, LayoutParams(dp(6), dp(6)).apply { leftMargin = px(Space.s) })
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(if (t.selected) NC.ON_SURFACE else NC.OUTLINE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            px(Space.s).let { setPadding(it, it, it, it) }
            contentDescription = "Close ${t.label}"
            setOnClickListener { onClose(t) }
        }, LayoutParams(dp(32), MATCH))
    }

    private fun menu(anchor: View, t: Tab) = PopupMenu(context, anchor).apply {
        menu.add(0, 1, 0, "Rename")
        menu.add(0, 2, 1, "Close others")
        setOnMenuItemClickListener {
            if (it.itemId == 1) rename(t) else onCloseOthers(t)
            true
        }
    }.show()

    private fun rename(t: Tab) {
        val input = EditText(context).apply { setText(t.label); setSelectAllOnFocus(true); isSingleLine = true }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Rename tab")
            .setView(FrameLayout(context).apply { setPadding(px(Space.xl), px(Space.s), px(Space.xl), 0); addView(input) })
            .setPositiveButton("Rename") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { onRename(t, it) } }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s
        textSize = sp
        setTextColor(color)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
    }

    private fun px(d: Dp) = dp(d.value.toInt())
    private fun dp(v: Int) =TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT
    }
}
