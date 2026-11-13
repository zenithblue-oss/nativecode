package com.zenithblue.nativecode.workspace

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.zenithblue.nativecode.CpuUtilizationProvider
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.ui.BrutalistToggle
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

private fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

private fun Context.statPrefs(): SharedPreferences =
    getSharedPreferences("nativecode_prefs", Context.MODE_PRIVATE)

/** Short chip label + value for one stat. */
data class StatValue(val label: String, val value: String)

/** Reads every stat once per tick, shared by all strips (off the main thread). */
object StatReader {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "stat-reader").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cache: Map<Stat, StatValue> = emptyMap()
    @Volatile private var cacheAt = 0L
    private var netBytes = -1L
    private var netAt = 0L
    private var netRate = 0L

    private val gpuNodes = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/kernel/ged/hal/gpu_utilization",
    )

    /** Probed once; null when no node is readable (chip hidden, customise row disabled). */
    private val gpuNode: File? by lazy {
        gpuNodes.map(::File).firstOrNull { f ->
            try { f.canRead() && f.readText().isNotBlank() } catch (_: Exception) { false }
        }
    }

    fun gpuReadable(): Boolean = gpuNode != null

    fun latest(): Map<Stat, StatValue> = cache

    /** Sample (at most once a second across all strips) then deliver on the main thread. */
    fun refresh(context: Context, done: (Map<Stat, StatValue>) -> Unit) {
        val app = context.applicationContext
        io.execute {
            val now = SystemClock.elapsedRealtime()
            if (now - cacheAt >= 1000 || cache.isEmpty()) {
                cache = sample(app)
                cacheAt = now
            }
            val out = cache
            main.post { done(out) }
        }
    }

    private fun sample(c: Context): Map<Stat, StatValue> {
        val out = LinkedHashMap<Stat, StatValue>()
        fun put(s: Stat, label: String, f: () -> String?) {
            try { f()?.let { out[s] = StatValue(label, it) } } catch (_: Exception) { }
        }
        val battery = try { c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        put(Stat.BATTERY, if (bm?.isCharging == true) "B+" else "B") {
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level in 0..100) "$level%" else null
        }
        put(Stat.CPU, "C") { "${CpuUtilizationProvider.getCpuSnapshot().overallPercent.toInt()}%" }
        put(Stat.RAM, "R") {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
            String.format(Locale.US, "%.1fG", (mi.totalMem - mi.availMem) / 1073741824.0)
        }
        put(Stat.TEMP, "T") {
            val t = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (t > 0) "${t / 10}°" else null
        }
        put(Stat.GPU, "G") {
            // Nodes print "37", "37 %" or "37 100 ..." — first number is the load.
            gpuNode?.readText()?.trim()?.split(Regex("[^0-9]+"))?.firstOrNull { it.isNotEmpty() }?.let { "$it%" }
        }
        put(Stat.NET, "N") {
            val total = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
            val now = SystemClock.elapsedRealtime()
            if (netBytes >= 0 && now > netAt) netRate = (total - netBytes).coerceAtLeast(0) * 1000 / (now - netAt)
            netBytes = total
            netAt = now
            TopBarStats.rate(netRate)
        }
        put(Stat.DISK, "D") { "${StatFs(c.filesDir.path).availableBytes / 1073741824}G" }
        return out
    }
}

/**
 * Compact phone-stat chips. Shows the user's ordered list; chips that do not fit the width it is
 * given are dropped from the end (never scrolls, never wraps, never squeezes its neighbours).
 * Tap a chip = full name + value. Long-press = customise sheet.
 */
class StatStrip(context: Context) : ViewGroup(context), SharedPreferences.OnSharedPreferenceChangeListener {
    private val gap = context.dp(4)
    private val font: Typeface =
        try { Typeface.createFromAsset(context.assets, "fonts/font.ttf") } catch (_: Exception) { Typeface.MONOSPACE }
    private var enabled: List<Stat> = emptyList()
    private val chips = LinkedHashMap<Stat, TextView>()
    private var shown = 0

    /** Extra cap on top of the parent's width (single-row bars reserve room for the title). */
    var maxWidthPx: Int = Int.MAX_VALUE
        set(value) { if (field != value) { field = value; requestLayout() } }

    /** Right-align chips inside a stretched strip (app header). */
    var alignEnd = false

    /** Fired when the enabled list (not the values) changes, so a host can re-flow its rows. */
    var onStatsChanged: (() -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            // Hidden page or stopped Activity: stop polling; onVisibilityAggregated restarts it.
            if (!visibleNow()) return
            StatReader.refresh(context) { if (isAttachedToWindow) bind(it) }
            postDelayed(this, 2000)
        }
    }

    private fun visibleNow() = isShown && windowVisibility == VISIBLE

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(tick)
        if (isVisible && isAttachedToWindow) post(tick)
    }

    /** Long-press: open the "Top bar" page (set by the host). */
    var onCustomize: (() -> Unit)? = null

    init {
        contentDescription = "Phone stats. Long press to customise."
        setOnLongClickListener { onCustomize?.invoke(); onCustomize != null }
        reload()
    }

    private fun reload() {
        enabled = TopBarStats.parse(context.statPrefs().getString(TopBarStats.PREF, null))
        removeAllViews()
        chips.clear()
        enabled.forEach { stat ->
            val chip = TextView(context).apply {
                textSize = 11f
                typeface = font
                fontFeatureSettings = "tnum"
                maxLines = 1
                includeFontPadding = false
                gravity = Gravity.CENTER
                setPadding(context.dp(6), 0, context.dp(6), 0)
                background = GradientDrawable().apply {
                    setColor(NC.SURFACE_LOW)
                    setStroke(context.dp(1), NC.OUTLINE_VAR)
                }
                visibility = View.GONE // until a value arrives (unreadable stats stay hidden)
                setOnClickListener { Toast.makeText(context, contentDescription, Toast.LENGTH_SHORT).show() }
                setOnLongClickListener { onCustomize?.invoke(); onCustomize != null }
            }
            chips[stat] = chip
            addView(chip)
        }
        bind(StatReader.latest())
    }

    private fun bind(values: Map<Stat, StatValue>) {
        chips.forEach { (stat, chip) ->
            val v = values[stat]
            chip.visibility = if (v == null) View.GONE else View.VISIBLE
            if (v == null) return@forEach
            chip.text = SpannableString("${v.label} ${v.value}").apply {
                setSpan(ForegroundColorSpan(NC.ON_SURF_VAR), 0, v.label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(Color.WHITE), v.label.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            chip.contentDescription = "${stat.title} ${v.value}"
        }
    }

    private fun live(): List<TextView> = chips.values.filter { it.visibility != View.GONE }

    /** Width needed to show every enabled stat. */
    fun fullWidth(): Int {
        val un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val l = live()
        l.forEach { it.measure(un, un) }
        return l.sumOf { it.measuredWidth } + gap * (l.size - 1).coerceAtLeast(0)
    }

    val isEmpty: Boolean get() = enabled.isEmpty()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val chipH = context.dp(24)
        val l = live()
        l.forEach { it.measure(un, MeasureSpec.makeMeasureSpec(chipH, MeasureSpec.EXACTLY)) }
        val limit = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) maxWidthPx
        else minOf(MeasureSpec.getSize(widthSpec), maxWidthPx)
        shown = TopBarStats.fitCount(l.map { it.measuredWidth }, gap, limit.coerceAtLeast(0))
        val used = l.take(shown).sumOf { it.measuredWidth } + gap * (shown - 1).coerceAtLeast(0)
        val w = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthSpec) else used
        setMeasuredDimension(w, resolveSize(chipH, heightSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val h = b - t
        val all = live()
        val used = all.take(shown).sumOf { it.measuredWidth } + gap * (shown - 1).coerceAtLeast(0)
        var x = if (alignEnd) (r - l - used).coerceAtLeast(0) else 0
        all.forEachIndexed { i, chip ->
            if (i < shown) {
                val top = (h - chip.measuredHeight) / 2
                chip.layout(x, top, x + chip.measuredWidth, top + chip.measuredHeight)
                x += chip.measuredWidth + gap
            } else {
                chip.layout(0, 0, 0, 0) // dropped: does not fit
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.statPrefs().registerOnSharedPreferenceChangeListener(this)
        reload()
        removeCallbacks(tick)
        if (visibleNow()) post(tick)
    }

    override fun onDetachedFromWindow() {
        context.statPrefs().unregisterOnSharedPreferenceChangeListener(this)
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key != TopBarStats.PREF) return
        reload()
        onStatsChanged?.invoke()
    }
}

/** Compact checklist with up/down reorder, shown on the dedicated "Top bar" page. */
object StatsEditor {
    /**
     * Body of the dedicated "Top bar" page: live preview of the workspace bar, then the list.
     * [fillRail] puts the real tools on the preview rail; the preview itself is not interactive.
     */
    fun buildPage(context: Context, previewTitle: String, fillRail: (ToolRail) -> Unit): LinearLayout {
        fun caption(text: String) = TextView(context).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(NC.ON_SURF_VAR)
            setPadding(0, context.dp(12), 0, context.dp(6))
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(caption("// LIVE PREVIEW"))
            addView(android.widget.FrameLayout(context).apply {
                val bar = WorkspaceTopBar(context) {}
                bar.setTitle(previewTitle)
                bar.iconView.visibility = View.GONE
                fillRail(bar.rail)
                addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                // Swallow touches: the preview shows the bar, it does not operate it.
                addView(View(context).apply {
                    isClickable = true
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
            addView(caption("// STATS · ORDER = PRIORITY. WHEN SPACE IS SHORT THE LAST ONES DROP FIRST; THE TITLE IS NEVER SQUEEZED."))
            addView(buildList(context))
        }
    }

    /** Self-rebuilding list: every change is saved at once; strips follow via the pref listener. */
    fun buildList(context: Context): LinearLayout {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun save(stats: List<Stat>) {
            context.statPrefs().edit().putString(TopBarStats.PREF, TopBarStats.serialize(stats)).apply()
        }
        fun render() {
            list.removeAllViews()
            val enabled = TopBarStats.parse(context.statPrefs().getString(TopBarStats.PREF, null))
            val values = StatReader.latest()
            TopBarStats.allOrdered(enabled).forEach { stat ->
                val on = stat in enabled
                val readable = stat != Stat.GPU || StatReader.gpuReadable()
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = context.dp(44)
                    alpha = if (readable) 1f else 0.45f
                }
                row.addView(BrutalistToggle(context, on) { save(TopBarStats.toggle(enabled, stat)) }.apply {
                    isEnabled = readable
                    contentDescription = "Show ${stat.title}"
                })
                row.addView(TextView(context).apply {
                    val v = values[stat]?.value
                    text = when {
                        !readable -> "${stat.title} · not readable on this device"
                        v != null -> "${stat.title} · $v"
                        else -> stat.title
                    }
                    textSize = 13f
                    setTextColor(if (on) NC.ON_SURFACE else NC.ON_SURF_VAR)
                    setPadding(context.dp(12), 0, context.dp(8), 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                fun arrow(icon: Int, desc: String, delta: Int) = ImageView(context).apply {
                    val can = on && TopBarStats.move(enabled, stat, delta) !== enabled
                    setImageResource(icon)
                    setColorFilter(if (can) NC.PRIMARY else NC.OUTLINE_VAR)
                    contentDescription = "$desc ${stat.title}"
                    isEnabled = can
                    setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
                    layoutParams = LinearLayout.LayoutParams(context.dp(44), context.dp(44))
                    setOnClickListener { save(TopBarStats.move(enabled, stat, delta)) }
                }
                row.addView(arrow(R.drawable.ic_arrow_up, "Move up", -1))
                row.addView(arrow(R.drawable.ic_arrow_down, "Move down", 1))
                list.addView(row)
            }
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, context.dp(6), 0, 0)
            }
            fun action(label: String, click: () -> Unit) = TextView(context).apply {
                text = label
                textSize = 12f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setTextColor(NC.PRIMARY)
                minimumHeight = context.dp(40)
                background = GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke(context.dp(1), NC.OUTLINE_VAR) }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = context.dp(6)
                }
                setOnClickListener { click() }
            }
            actions.addView(action("HIDE ALL") { save(emptyList()) })
            actions.addView(action("RESET") { save(TopBarStats.DEFAULT) })
            list.addView(actions)
        }
        // Re-render from the pref so the Settings card and the sheet never disagree.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == TopBarStats.PREF) render()
        }
        list.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                context.statPrefs().registerOnSharedPreferenceChangeListener(listener)
                render()
            }
            override fun onViewDetachedFromWindow(v: View) {
                context.statPrefs().unregisterOnSharedPreferenceChangeListener(listener)
            }
        })
        render()
        return list
    }
}
