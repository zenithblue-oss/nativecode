package com.zenithblue.nativecode.workspace

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R

/**
 * Project workspace top bar: back, icon, title, phone stats, tool rail.
 *
 * One slim row when the full title, every enabled stat and the rail fit (landscape, short names).
 * Otherwise two slim rows: the title owns row 1, stats + rail share row 2. The title always wins:
 * the stats strip scrolls sideways (thin position bar) before the title loses a pixel; only a title longer
 * than its whole row is middle-ellipsized (keeps the distinguishing suffix).
 */
class WorkspaceTopBar(context: Context, onBack: () -> Unit) : ViewGroup(context) {
    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    val backBtn = ImageView(context).apply {
        setImageResource(R.drawable.ic_lucide_arrow_left)
        setColorFilter(ToolRail.ICON_TINT)
        contentDescription = "Back"
        val p = dp((36 - ToolRail.ICON_DP) / 2)
        setPadding(p, p, p, p)
        setOnClickListener { onBack() }
    }
    val iconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val titleView = TextView(context).apply {
        textSize = 16f
        setTextColor(NC.ON_SURFACE)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MIDDLE
        gravity = Gravity.CENTER_VERTICAL
    }
    val stats = StatStrip(context)
    val rail = ToolRail(context)

    private var single = true

    init {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(NC.SURFACE_LOWEST)
            setStroke(dp(1), Color.parseColor("#3C4A3F"))
        }
        addView(backBtn); addView(iconView); addView(titleView); addView(stats); addView(rail)
        stats.onStatsChanged = { requestLayout() }
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), MeasureSpec.EXACTLY)
    private fun atMost(px: Int) = MeasureSpec.makeMeasureSpec(px.coerceAtLeast(0), MeasureSpec.AT_MOST)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val pad = dp(PAD_DP)
        val gap = dp(GAP_DP)
        val un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        backBtn.measure(exactly(dp(36)), exactly(dp(36)))
        val iconW = if (iconView.visibility == View.GONE) 0 else dp(24) + gap
        if (iconW > 0) iconView.measure(exactly(dp(24)), exactly(dp(24)))
        rail.measure(un, exactly(dp(ToolRail.CELL_H_DP)))
        val railW = rail.measuredWidth
        val fixed = pad * 2 + backBtn.measuredWidth + iconW
        val titleW = Math.ceil(titleView.paint.measureText(titleView.text.toString()).toDouble()).toInt() + dp(2)
        val statsFull = stats.fullWidth().let { if (it > 0) it + gap * 2 else 0 }

        single = TopBarStats.singleRow(titleW, statsFull, railW, fixed, w)
        if (single) {
            stats.measure(atMost(w - fixed - railW - titleW - gap * 2), exactly(dp(24)))
            titleView.measure(exactly(w - fixed - railW - stats.measuredWidth - gap * 2), exactly(dp(ROW1_DP)))
            setMeasuredDimension(w, dp(ROW1_DP))
        } else {
            titleView.measure(exactly(w - fixed), exactly(dp(ROW1_DP)))
            stats.measure(atMost(w - pad * 2 - railW - gap), exactly(dp(24)))
            setMeasuredDimension(w, dp(ROW1_DP) + dp(ROW2_DP))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val pad = dp(PAD_DP)
        val gap = dp(GAP_DP)
        val row1 = dp(ROW1_DP)
        fun place(v: View, x: Int, rowTop: Int, rowH: Int): Int {
            val top = rowTop + (rowH - v.measuredHeight) / 2
            v.layout(x, top, x + v.measuredWidth, top + v.measuredHeight)
            return x + v.measuredWidth
        }
        var x = place(backBtn, pad, 0, row1)
        if (iconView.visibility != View.GONE) x = place(iconView, x, 0, row1) + gap
        place(titleView, x, 0, row1)
        if (single) {
            place(rail, w - pad - rail.measuredWidth, 0, row1)
            place(stats, w - pad - rail.measuredWidth - gap - stats.measuredWidth, 0, row1)
        } else {
            val row2 = dp(ROW2_DP)
            place(stats, pad, row1, row2)
            place(rail, w - pad - rail.measuredWidth, row1, row2)
        }
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
        titleView.contentDescription = text
        requestLayout()
    }

    companion object {
        const val ROW1_DP = 40
        const val ROW2_DP = 36
        private const val PAD_DP = 8
        private const val GAP_DP = 6
    }
}
