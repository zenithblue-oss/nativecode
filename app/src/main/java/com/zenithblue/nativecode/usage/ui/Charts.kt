package com.zenithblue.nativecode.usage.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.usage.Bar
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Small Canvas charts for the usage page. No chart library: each view is one `onDraw` and a 0..1 [progress] animation. */
internal abstract class ChartView(ctx: Context) : View(ctx) {
    protected var progress = 1f
    private var anim: ValueAnimator? = null

    protected fun dpf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    protected fun spf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    /** Replays the draw-in animation (the system animator scale applies, so "remove animations" makes it instant). */
    protected fun play(ms: Long = 650) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    protected fun text(sizeSp: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = spf(sizeSp); textAlign = align
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        }
}

internal class Slice(val label: String, val value: Double, val color: Int, val valueText: String)

/** Donut of [Slice]s. Tap a slice to select it; the centre then shows that slice, else the total. */
internal class DonutView(ctx: Context) : ChartView(ctx) {
    private var slices: List<Slice> = emptyList()
    private var centerBig = ""
    private var centerSmall = ""
    private var selected = -1
    var onSelect: ((Int) -> Unit)? = null
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = NC.SURFACE_HIGH }
    private val big = text(20f, NC.ON_SURFACE, bold = true, align = Paint.Align.CENTER)
    private val small = text(11f, NC.ON_SURF_VAR, align = Paint.Align.CENTER)
    private val rect = RectF()

    fun set(s: List<Slice>, totalBig: String, totalSmall: String, description: String) {
        slices = s.filter { it.value > 0 }
        centerBig = totalBig; centerSmall = totalSmall; selected = -1
        contentDescription = description
        play(); invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = min(MeasureSpec.getSize(w), dpf(200f).toInt())
        setMeasuredDimension(s, s)
    }

    private fun geometry(): Triple<Float, Float, Float> {
        val stroke = dpf(26f)
        val r = min(width, height) / 2f - stroke / 2f - dpf(4f)
        return Triple(width / 2f, height / 2f, r)
    }

    override fun onDraw(c: Canvas) {
        val (cx, cy, r) = geometry()
        val stroke = dpf(26f)
        track.strokeWidth = stroke
        rect.set(cx - r, cy - r, cx + r, cy + r)
        c.drawCircle(cx, cy, r, track)
        val total = slices.sumOf { it.value }
        if (total > 0) {
            var a = -90f
            val gap = if (slices.size > 1) 2.5f else 0f
            slices.forEachIndexed { i, s ->
                val sweep = (s.value / total * 360f).toFloat()
                arc.color = s.color
                arc.strokeWidth = if (i == selected) stroke + dpf(8f) else stroke
                val draw = ((sweep - gap) * progress).coerceAtLeast(0f)
                if (i != selected && selected >= 0) arc.alpha = 120 else arc.alpha = 255
                c.drawArc(rect, a + gap / 2f, draw, false, arc)
                a += sweep
            }
        }
        val s = slices.getOrNull(selected)
        val t1 = s?.valueText ?: centerBig
        val t2 = s?.label ?: centerSmall
        big.textSize = spf(if (t1.length > 7) 16f else 20f)
        c.drawText(t1, cx, cy + dpf(2f), big)
        c.drawText(t2, cx, cy + dpf(2f) + small.textSize * 1.3f, small)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val (cx, cy, r) = geometry()
        val d = hypot(e.x - cx, e.y - cy)
        val hit = d in (r - dpf(30f))..(r + dpf(30f))
        var idx = -1
        if (hit) {
            var ang = Math.toDegrees(atan2((e.y - cy).toDouble(), (e.x - cx).toDouble())).toFloat() + 90f
            if (ang < 0) ang += 360f
            val total = slices.sumOf { it.value }
            var a = 0f
            slices.forEachIndexed { i, s -> val sw = (s.value / total * 360f).toFloat(); if (ang >= a && ang < a + sw) idx = i; a += sw }
        }
        selected = if (idx == selected) -1 else idx
        onSelect?.invoke(selected)
        invalidate()
        performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Stacked bars (one segment per CLI). Tap a bar to select it. */
internal class BarsView(ctx: Context, private val colorOf: (String) -> Int) : ChartView(ctx) {
    private var bars: List<Bar> = emptyList()
    private var maxLabel = ""
    private var selected = -1
    var onSelect: ((Int) -> Unit)? = null
    private val grid = Paint().apply { color = NC.OUTLINE_VAR; strokeWidth = dpf(1f) }
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = text(10f, NC.ON_SURF_VAR, align = Paint.Align.CENTER)
    private val top = text(10f, NC.ON_SURF_VAR)
    private val rr = RectF()

    fun set(b: List<Bar>, maxText: (Double) -> String, description: String) {
        bars = b; selected = -1
        maxLabel = maxText(b.maxOfOrNull { it.total } ?: 0.0)
        contentDescription = description
        play(); invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        label.textSize = spf(10f); top.textSize = spf(10f)
        setMeasuredDimension(MeasureSpec.getSize(w), (dpf(150f) + label.textSize * 2.4f).toInt())
    }

    private fun slot() = width / max(bars.size, 1).toFloat()

    override fun onDraw(c: Canvas) {
        val labelH = label.textSize * 1.4f
        val plotTop = top.textSize * 1.4f
        val plotBottom = height - labelH - dpf(4f)
        for (i in 0..2) { val y = plotTop + (plotBottom - plotTop) * i / 2f; c.drawLine(0f, y, width.toFloat(), y, grid) }
        c.drawText(maxLabel, 0f, top.textSize, top)
        val max = bars.maxOfOrNull { it.total }?.takeIf { it > 0 } ?: return
        val slot = slot()
        val bw = slot * 0.62f
        val every = if (bars.size > 8 && slot < dpf(26f)) 2 else 1
        bars.forEachIndexed { i, b ->
            val x0 = i * slot + (slot - bw) / 2f
            var y = plotBottom
            val dim = selected >= 0 && i != selected
            b.parts.forEachIndexed { k, (agent, v) ->
                val hgt = (((plotBottom - plotTop) * (v / max)).toFloat() * progress).coerceAtLeast(if (v > 0) dpf(2f) else 0f)
                seg.color = colorOf(agent); seg.alpha = if (dim) 110 else 255
                rr.set(x0, y - hgt, x0 + bw, y)
                if (k == b.parts.lastIndex) c.drawRoundRect(rr, dpf(3f), dpf(3f), seg) else c.drawRect(rr, seg)
                y -= hgt
            }
            if (i == selected) {
                label.color = NC.PRIMARY
                c.drawText(b.label, i * slot + slot / 2f, height - dpf(2f), label)
                label.color = NC.ON_SURF_VAR
            } else if (i % every == 0 || i == bars.size - 1) c.drawText(b.label, i * slot + slot / 2f, height - dpf(2f), label)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val i = (e.x / slot()).toInt().coerceIn(0, max(bars.size - 1, 0))
        selected = if (i == selected) -1 else i
        onSelect?.invoke(selected)
        invalidate(); performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Trend: a smooth line with a soft fill under it and a dot per point. Tap to select the nearest point. */
internal class AreaView(ctx: Context) : ChartView(ctx) {
    private var values: List<Double> = emptyList()
    private var labels: List<String> = emptyList()
    private var selected = -1
    var onSelect: ((Int) -> Unit)? = null
    var color = NC.PRIMARY
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply { color = NC.OUTLINE_VAR; strokeWidth = dpf(1f) }
    private val lab = text(10f, NC.ON_SURF_VAR, align = Paint.Align.CENTER)

    fun set(v: List<Double>, l: List<String>, description: String) {
        values = v; labels = l; selected = -1; contentDescription = description
        play(800); invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        lab.textSize = spf(10f)
        setMeasuredDimension(MeasureSpec.getSize(w), (dpf(110f) + lab.textSize * 2f).toInt())
    }

    private fun xAt(i: Int, left: Float, w: Float) = if (values.size < 2) left + w / 2f else left + w * i / (values.size - 1)

    override fun onDraw(c: Canvas) {
        val pad = dpf(8f)
        val left = pad; val w = width - 2 * pad
        val top = dpf(8f); val bottom = height - lab.textSize * 1.6f
        for (i in 0..2) { val y = top + (bottom - top) * i / 2f; c.drawLine(0f, y, width.toFloat(), y, grid) }
        if (values.isEmpty()) return
        val max = (values.maxOrNull() ?: 0.0).takeIf { it > 0 } ?: 1.0
        fun yOf(v: Double) = bottom - ((bottom - top) * (v / max)).toFloat() * progress
        val pts = values.mapIndexed { i, v -> xAt(i, left, w) to yOf(v) }
        val path = Path()
        pts.forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x, y) else { val (px, py) = pts[i - 1]; val mx = (px + x) / 2f; path.cubicTo(mx, py, mx, y, x, y) }
        }
        val area = Path(path).apply { lineTo(pts.last().first, bottom); lineTo(pts.first().first, bottom); close() }
        fill.shader = LinearGradient(0f, top, 0f, bottom, (color and 0x00FFFFFF) or 0x66000000, (color and 0x00FFFFFF), Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        line.color = color; line.strokeWidth = dpf(2.5f)
        c.drawPath(path, line)
        pts.forEachIndexed { i, (x, y) ->
            val sel = i == selected
            if (sel || values.size <= 16) {
                dot.color = NC.SURFACE_LOW; c.drawCircle(x, y, dpf(if (sel) 6f else 4f), dot)
                dot.color = color; c.drawCircle(x, y, dpf(if (sel) 4f else 2.5f), dot)
            }
        }
        val every = if (values.size > 8) 2 else 1
        labels.forEachIndexed { i, s ->
            if (i % every == 0 || i == labels.lastIndex || i == selected) {
                lab.color = if (i == selected) NC.PRIMARY else NC.ON_SURF_VAR
                val x = xAt(i, left, w).coerceIn(dpf(10f), width - dpf(10f))
                c.drawText(s, x, height - dpf(2f), lab)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        val pad = dpf(8f); val w = width - 2 * pad
        var best = 0; var bd = Float.MAX_VALUE
        values.indices.forEach { i -> val d = kotlin.math.abs(xAt(i, pad, w) - e.x); if (d < bd) { bd = d; best = i } }
        selected = if (best == selected) -1 else best
        onSelect?.invoke(selected)
        invalidate(); performClick()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Progress ring for one limit window: the percent in the centre (a label, so colour is never the only signal). */
internal class RingView(ctx: Context) : ChartView(ctx) {
    private var percent = 0.0
    private var caption = ""
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = NC.SURFACE_HIGH }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pct = text(15f, NC.ON_SURFACE, bold = true, align = Paint.Align.CENTER)
    private val cap = text(10f, NC.ON_SURF_VAR, align = Paint.Align.CENTER)
    private val rect = RectF()

    fun set(p: Double, label: String, description: String) {
        percent = p; caption = label; contentDescription = description
        play(900); invalidate()
    }

    override fun onMeasure(w: Int, h: Int) { val s = dpf(92f).toInt(); setMeasuredDimension(s, s) }

    override fun onDraw(c: Canvas) {
        val stroke = dpf(9f)
        val r = min(width, height) / 2f - stroke / 2f - dpf(2f)
        val cx = width / 2f; val cy = height / 2f
        track.strokeWidth = stroke; arc.strokeWidth = stroke
        c.drawCircle(cx, cy, r, track)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        arc.color = limitColor(percent)
        c.drawArc(rect, -90f, (percent / 100.0 * 360.0 * progress).toFloat().coerceAtLeast(if (percent > 0) 4f else 0f), false, arc)
        pct.textSize = spf(15f); cap.textSize = spf(10f)
        c.drawText("${(percent * progress).toInt().coerceAtMost(100)}%", cx, cy + pct.textSize * 0.1f, pct)
        c.drawText(caption, cx, cy + pct.textSize * 0.1f + cap.textSize * 1.35f, cap)
    }
}

/** Green below 75%, amber to 90%, red above: always shown next to the percent text. */
internal fun limitColor(p: Double): Int = when { p >= 90 -> NC.ERROR; p >= 75 -> NC.TERTIARY_CON; else -> NC.PRIMARY }

/**
 * Sparkline across the full width: straight segments over every bucket (gaps are 0), a light fill under the line, an end dot,
 * vertical padding so the stroke never clips, and a flat baseline when every value is 0. The y-scale starts at 0.
 */
internal class SparkView(ctx: Context, private val color: Int) : ChartView(ctx) {
    private var values: List<Double> = emptyList()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(v: List<Double>) { values = v; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; play(700); invalidate() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dpf(40f).toInt())

    override fun onDraw(c: Canvas) {
        if (values.isEmpty()) return
        val sw = dpf(2f)
        val padX = dpf(5f); val top = dpf(6f); val bottom = height - dpf(5f)
        val max = values.maxOf { if (it.isFinite()) it else 0.0 }
        val n = values.size
        fun x(i: Int) = if (n == 1) padX else padX + (width - 2 * padX) * i / (n - 1)
        fun y(v: Double): Float {
            val f = if (max > 0 && v.isFinite()) (v / max).toFloat().coerceIn(0f, 1f) else 0f
            return bottom - (bottom - top) * f * progress
        }
        val p = Path()
        if (n == 1) { p.moveTo(padX, y(values[0])); p.lineTo(width - padX, y(values[0])) }
        else values.forEachIndexed { i, v -> if (i == 0) p.moveTo(x(0), y(v)) else p.lineTo(x(i), y(v)) }
        val endX = if (n == 1) width - padX else x(n - 1)
        val area = Path(p).apply { lineTo(endX, bottom); lineTo(padX, bottom); close() }
        fill.shader = LinearGradient(0f, top, 0f, bottom, (color and 0x00FFFFFF) or 0x55000000, (color and 0x00FFFFFF), Shader.TileMode.CLAMP)
        c.drawPath(area, fill)
        line.color = color; line.strokeWidth = sw
        c.drawPath(p, line)
        dot.color = color
        c.drawCircle(endX, y(values.last()), dpf(3f), dot)
    }
}
