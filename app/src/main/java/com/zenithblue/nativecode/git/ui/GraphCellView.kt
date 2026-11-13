package com.zenithblue.nativecode.git.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.git.GraphRow

/**
 * Draws one graph row (lanes, edges, node) from a precomputed [GraphRow].
 * No allocation in onDraw. Lanes at or beyond [capLanes] - 1 collapse into the last column with a "+n" marker.
 */
internal class GraphCellView(context: Context) : View(context) {
    var row: GraphRow? = null
        set(v) { field = v; invalidate() }
    var capLanes = MAX_LANES

    private val d = resources.displayMetrics.density
    private val laneW = LANE_DP * d
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * d; color = NC.ON_SURFACE; typeface = android.graphics.Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()

    private fun x(lane: Int) = (minOf(lane, capLanes - 1) + 0.5f) * laneW + 4f * d
    private fun color(id: Int) = PALETTE[id % PALETTE.size]

    override fun onDraw(c: Canvas) {
        val r = row ?: return
        val h = height.toFloat()
        val cy = h / 2f
        val xc = x(r.col)

        // Lanes passing through, and the lane that enters the node from above.
        val n = maxOf(r.before.size, r.after.size)
        for (i in 0 until n) {
            val top = if (i < r.before.size) r.before[i] else -1
            val bot = if (i < r.after.size) r.after[i] else -1
            if (i == r.col) {
                if (top >= 0) line(c, x(i), 0f, xc, cy, top)
                if (bot >= 0) line(c, xc, cy, xc, h, bot)
            } else if (top >= 0 && bot >= 0 && top == bot && !isJoin(r, i)) {
                line(c, x(i), 0f, x(i), h, top)
            } else {
                if (top >= 0) curve(c, x(i), 0f, xc, cy, top)       // lane ends here (merge into node)
                if (bot >= 0 && !isFork(r, i)) line(c, x(i), cy, x(i), h, bot) // lane begins mid-row
            }
        }
        for (j in r.forks) {
            val b = if (j < r.after.size) r.after[j] else -1
            if (b >= 0 && j != r.col) curve(c, xc, cy, x(j), h, b)
        }

        // Node.
        val nr = (if (r.commit.isHead) 5.5f else 4f) * d
        fill.color = color(r.nodeColor)
        c.drawCircle(xc, cy, nr, fill)
        if (r.isMerge) {
            fill.color = NC.BG
            c.drawCircle(xc, cy, nr * 0.45f, fill)
        }
        if (r.commit.isHead) {
            ring.color = Color.WHITE
            c.drawCircle(xc, cy, nr + 1.5f * d, ring)
        }

        val extra = r.laneCount - capLanes
        if (extra >= 0 && r.laneCount > capLanes) {
            val label = "+${extra + 1}"
            val tx = x(capLanes - 1)
            fill.color = NC.BG
            c.drawRect(tx - laneW * 0.5f, 2f * d, tx + laneW * 0.5f + 4f * d, 14f * d, fill)
            c.drawText(label, tx + 2f * d, 12f * d, text)
        }
    }

    private fun isJoin(r: GraphRow, i: Int): Boolean { for (j in r.joins) if (j == i) return true; return false }
    private fun isFork(r: GraphRow, i: Int): Boolean { for (j in r.forks) if (j == i) return true; return false }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, col: Int) {
        stroke.color = color(col)
        c.drawLine(x1, y1, x2, y2, stroke)
    }

    private fun curve(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, col: Int) {
        if (x1 == x2) { line(c, x1, y1, x2, y2, col); return }
        stroke.color = color(col)
        path.rewind()
        path.moveTo(x1, y1)
        val my = (y1 + y2) / 2f
        path.cubicTo(x1, my, x2, my, x2, y2)
        c.drawPath(path, stroke)
    }

    companion object {
        const val LANE_DP = 12
        const val MAX_LANES = 6
        /** Obsidian-theme friendly, distinct on #131313. */
        val PALETTE = intArrayOf(
            0xFF60F99E.toInt(), 0xFF7FB4FF.toInt(), 0xFFFFB175.toInt(), 0xFFC9A0FF.toInt(),
            0xFFFF8FB1.toInt(), 0xFF5EEAD4.toInt(), 0xFFF5E27A.toInt(), 0xFFB0B8C4.toInt()
        )
        fun widthPx(density: Float, lanes: Int): Int =
            ((minOf(lanes, MAX_LANES).coerceAtLeast(1) * LANE_DP + 8) * density).toInt()
    }
}
