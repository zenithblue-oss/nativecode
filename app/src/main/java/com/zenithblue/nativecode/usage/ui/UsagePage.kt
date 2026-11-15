package com.zenithblue.nativecode.usage.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.usage.AgentUse
import com.zenithblue.nativecode.usage.Bar
import com.zenithblue.nativecode.usage.ChatThreadUsage
import com.zenithblue.nativecode.usage.Granularity
import com.zenithblue.nativecode.usage.UsageModel
import com.zenithblue.nativecode.usage.UsageReport
import com.zenithblue.nativecode.usage.UsageRepository
import java.util.concurrent.Executors

internal interface UsageHostApi {
    /** True when usage of [agent] (a ccusage name such as `claude`) may be shown to this user. */
    fun allowed(agent: String): Boolean
    /** Shows the Upgrade sheet. */
    fun upgrade()
}

/** Colour of a CLI in charts and legends. */
internal fun agentColor(agent: String): Int = when (agent) {
    "claude" -> NC.TERTIARY_CON
    "codex" -> NC.PRIMARY
    "opencode" -> Color.parseColor("#8ab4ff")
    "grok" -> NC.ON_SURFACE
    "antigravity" -> Color.parseColor("#c58cff")
    else -> NC.OUTLINE
}

/** Stacked bar chart drawn on a Canvas: one bar per period, one segment per CLI. Labels use sp, so they follow font scale. */
internal class BarChartView(ctx: Context) : View(ctx) {
    private var bars: List<Bar> = emptyList()
    private var maxLabel = ""
    private val grid = Paint().apply { color = NC.OUTLINE_VAR; strokeWidth = dpf(1f) }
    private val seg = Paint()
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NC.ON_SURF_VAR; textSize = sp(10f); typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val top = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NC.ON_SURF_VAR; textSize = sp(10f); typeface = Typeface.MONOSPACE }

    private fun dpf(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun set(b: List<Bar>, maxText: (Double) -> String, description: String) {
        bars = b
        maxLabel = maxText(b.maxOfOrNull { it.total } ?: 0.0)
        contentDescription = description
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        label.textSize = sp(10f); top.textSize = sp(10f)
        setMeasuredDimension(MeasureSpec.getSize(w), (dpf(150f) + label.textSize * 2.4f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val labelH = label.textSize * 1.4f
        val topH = top.textSize * 1.4f
        val plotTop = topH
        val plotBottom = height - labelH - dpf(4f)
        for (i in 0..2) { val y = plotTop + (plotBottom - plotTop) * i / 2f; c.drawLine(0f, y, width.toFloat(), y, grid) }
        c.drawText(maxLabel, 0f, top.textSize, top)
        if (bars.isEmpty()) return
        val max = bars.maxOf { it.total }.takeIf { it > 0 } ?: return
        val slot = width / bars.size.toFloat()
        val bw = slot * 0.62f
        val every = if (bars.size > 8 && slot < dpf(26f)) 2 else 1
        bars.forEachIndexed { i, b ->
            val x0 = i * slot + (slot - bw) / 2f
            var y = plotBottom
            for ((agent, v) in b.parts) {
                val hgt = ((plotBottom - plotTop) * (v / max)).toFloat().coerceAtLeast(if (v > 0) dpf(2f) else 0f)
                seg.color = agentColor(agent)
                c.drawRect(x0, y - hgt, x0 + bw, y, seg)
                y -= hgt
            }
            if (i % every == 0 || i == bars.size - 1) c.drawText(b.label, i * slot + slot / 2f, height - dpf(2f), label)
        }
    }
}

/**
 * AI usage page (plan 18): per-CLI tokens and cost by day, week and month from the pinned `ccusage` (offline), drawn natively.
 * Cost is an estimate from ccusage's bundled price table, not a bill.
 */
internal class UsagePage(private val ctx: Context, private val host: UsageHostApi) {
    val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var report: UsageReport? = null
    private var chat: List<ChatThreadUsage> = emptyList()
    private var gran = Granularity.DAY
    private var byCost = false
    private var busy = false
    private var error: String? = null

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    private fun text(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(ctx).apply {
        textSize = size; setTextColor(color)
        if (bold) paint.isFakeBoldText = true
        if (mono) typeface = Typeface.MONOSPACE
    }
    private fun button(label: String, primary: Boolean, selected: Boolean = false, accent: Int = NC.PRIMARY) = TextView(ctx).apply {
        text = label; textSize = 13f; typeface = Typeface.MONOSPACE; paint.isFakeBoldText = true; gravity = Gravity.CENTER
        val on = primary || selected
        setTextColor(if (on) Color.parseColor("#0A0A0A") else accent)
        background = GradientDrawable().apply { setColor(if (on) NC.PRIMARY_CON else Color.TRANSPARENT); setStroke(dp(1), if (on) NC.PRIMARY_CON else if (accent != NC.PRIMARY) accent else NC.OUTLINE_VAR) }
        minHeight = dp(48); setPadding(dp(10), dp(10), dp(10), dp(10)); isClickable = true; isFocusable = true
    }
    private fun card(title: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke(dp(1), NC.OUTLINE_VAR) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = title })
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    init {
        content.addView(body)
    }

    /** Page shown: draw what is cached, then reload. */
    fun refresh() {
        if (report == null) bg.execute { val c = UsageRepository.cached(ctx); ui.post { if (report == null && c != null) { report = c; render() } } }
        render()
        if (UsageRepository.isInstalled(ctx)) reload()
    }

    private fun reload() {
        if (busy) return
        busy = true; error = null; render()
        bg.execute {
            val r = UsageRepository.load(ctx)
            val threads = UsageModel.chatThreads(UsageRepository.chatRuns(ctx))
            ui.post {
                busy = false
                r.onSuccess { report = it; error = null }.onFailure { error = it.message ?: "Could not read usage." }
                chat = threads
                render()
            }
        }
    }

    private fun install() {
        if (busy) return
        busy = true; error = null; render()
        bg.execute {
            val err = UsageRepository.install(ctx)
            ui.post {
                busy = false
                if (err != null) { error = err; render() } else reload()
            }
        }
    }

    private fun render() {
        body.removeAllViews()
        if (!UsageRepository.isInstalled(ctx)) { renderInstall(); return }
        val full = report
        if (full == null) {
            val c = card(if (busy) "READING USAGE..." else "NO DATA YET")
            c.addView(text(12f, NC.ON_SURF_VAR).apply { text = error ?: if (busy) "Reading the logs of your AI tools." else "Nothing loaded yet."; setPadding(0, dp(6), 0, dp(10)) })
            if (!busy) c.addView(button("TRY AGAIN", true).apply { setOnClickListener { reload() } }, lp())
            body.addView(c)
            return
        }
        val rep = full.only(host::allowed)
        val hidden = full.sessions.flatMap { it.agents }.any { !host.allowed(it.agent) } || full.daily.flatMap { it.agents }.any { !host.allowed(it.agent) }

        body.addView(controls())
        if (error != null) body.addView(card("COULD NOT REFRESH").apply { addView(text(12f, NC.ERROR).apply { text = "$error Showing the last data."; setPadding(0, dp(6), 0, 0) }) })

        val rows = if (gran == Granularity.DAY) UsageModel.fillDays(rep.daily, UsageRepository.todayString(), gran.bars)
        else UsageModel.lastRows(rep.rows(gran), gran.bars)
        if (rows.all { it.agents.isEmpty() }) {
            body.addView(card("NO USAGE").apply {
                addView(text(12f, NC.ON_SURF_VAR).apply {
                    text = if (rep.isEmpty) "No AI usage found yet. Use an AI CLI (opencode, Claude Code, Codex...) in the terminal or a chat and it shows up here."
                    else "No usage in this range."
                    setPadding(0, dp(6), 0, 0)
                })
            })
        } else {
            val sum = UsageModel.summarize(rows)
            body.addView(totals(sum.tokens.total, sum.cost, sum.tokens, rows.first().period, rows.last().period))
            body.addView(chartCard(rows))
            body.addView(byCli(sum.byAgent))
            if (sum.byModel.isNotEmpty()) body.addView(models(sum.byModel))
        }
        val recent = UsageModel.recentSessions(rep, 8)
        if (recent.isNotEmpty()) body.addView(sessions(recent))
        val chatShown = chat.filter { host.allowed(it.agent) }.take(8)
        if (chatShown.isNotEmpty()) body.addView(chatCard(chatShown))
        if (hidden) body.addView(card("MORE TOOLS  ·  PRO").apply {
            addView(text(12f, NC.ON_SURF_VAR).apply { text = "Usage of Claude Code, Codex and the other CLIs was found but is hidden on the Free plan. Only opencode usage is shown."; setPadding(0, dp(6), 0, dp(10)) })
            addView(button("SEE PRO", false).apply { setOnClickListener { host.upgrade() } }, lp())
        })
        body.addView(text(11f, NC.OUTLINE, mono = true).apply { text = "Cost is an estimate from ccusage's price table (offline), not a bill. Read from this phone's logs only."; setPadding(0, dp(16), 0, 0) })
    }

    private fun renderInstall() {
        val c = card("USAGE TOOL NOT INSTALLED")
        c.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = "AI usage needs ccusage ${UsageModel.VERSION}, one small program (about 3 MB) from the npm registry. It is checked against a pinned hash and runs offline, reading only the logs of AI tools on this phone."
            setPadding(0, dp(6), 0, dp(10))
        })
        error?.let { c.addView(text(12f, NC.ERROR).apply { text = it; setPadding(0, 0, 0, dp(10)) }) }
        c.addView(button(if (busy) "INSTALLING..." else "INSTALL CCUSAGE", true).apply { alpha = if (busy) 0.5f else 1f; setOnClickListener { install() } }, lp())
        body.addView(c)
    }

    private fun controls(): View {
        val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val g = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        Granularity.entries.forEach { e ->
            g.addView(button(e.label, false, selected = e == gran).apply {
                contentDescription = "Group by ${e.label.lowercase()}"
                setOnClickListener { gran = e; render() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (e.ordinal > 0) leftMargin = dp(8) })
        }
        c.addView(g)
        val m = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("TOKENS" to false, "COST" to true).forEachIndexed { i, (l, v) ->
            m.addView(button(l, false, selected = v == byCost).apply { setOnClickListener { byCost = v; render() } },
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        m.addView(button(if (busy) "..." else "REFRESH", false, accent = NC.TERTIARY).apply {
            alpha = if (busy) 0.5f else 1f
            contentDescription = "Refresh usage"
            setOnClickListener { reload() }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        c.addView(m, lp(8))
        return c
    }

    private fun totals(total: Long, cost: Double, t: com.zenithblue.nativecode.usage.Tok, from: String, to: String): View = card("TOTAL").apply {
        addView(text(11f, NC.ON_SURF_VAR, mono = true).apply { text = if (from == to) from else "$from  to  $to" })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun big(v: String, l: String) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(24f, NC.ON_SURFACE, bold = true, mono = true).apply { text = v })
            addView(text(11f, NC.ON_SURF_VAR, mono = true).apply { text = l })
        }
        row.addView(big(UsageModel.fmtTokens(total), "TOKENS"), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(big(UsageModel.fmtCost(cost), "EST. COST"), LinearLayout.LayoutParams(0, -2, 1f))
        addView(row, lp(8))
        addView(text(11f, NC.ON_SURF_VAR, mono = true).apply {
            text = "in ${UsageModel.fmtTokens(t.input)} · out ${UsageModel.fmtTokens(t.output)}\ncache read ${UsageModel.fmtTokens(t.cacheRead)} · cache write ${UsageModel.fmtTokens(t.cacheWrite)}"
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun chartCard(rows: List<com.zenithblue.nativecode.usage.UsageRow>): View {
        val bars = UsageModel.bars(rows, gran, byCost)
        val c = card(if (byCost) "COST BY ${gran.label}" else "TOKENS BY ${gran.label}")
        val fmt: (Double) -> String = { if (byCost) UsageModel.fmtCost(it) else UsageModel.fmtTokens(it.toLong()) }
        val desc = "Chart of ${if (byCost) "cost" else "tokens"} by ${gran.label.lowercase()}: " + bars.joinToString(", ") { "${it.label} ${fmt(it.total)}" }
        c.addView(BarChartView(ctx).apply { set(bars, fmt, desc) }, lp(10))
        val legend = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        val agents = bars.flatMap { b -> b.parts.map { it.first } }.distinct()
        agents.forEach { a ->
            legend.addView(View(ctx).apply { background = GradientDrawable().apply { setColor(agentColor(a)) } }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { gravity = Gravity.CENTER_VERTICAL; rightMargin = dp(4) })
            legend.addView(text(11f, NC.ON_SURF_VAR, mono = true).apply { text = UsageModel.agentLabel(a) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        }
        c.addView(android.widget.HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(legend) })
        return c
    }

    private fun byCli(list: List<AgentUse>): View {
        val c = card("BY CLI")
        val max = list.maxOfOrNull { if (byCost) it.cost else it.tokens.total.toDouble() }?.takeIf { it > 0 } ?: 1.0
        list.forEach { a ->
            val v = if (byCost) a.cost else a.tokens.total.toDouble()
            c.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text(13f, agentColor(a.agent), bold = true, mono = true).apply { text = UsageModel.agentLabel(a.agent) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(12f, NC.ON_SURFACE, mono = true).apply { text = "${UsageModel.fmtTokens(a.tokens.total)}  ${UsageModel.fmtCost(a.cost)}" })
            }, lp(12))
            c.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(View(ctx).apply { setBackgroundColor(agentColor(a.agent)) }, LinearLayout.LayoutParams(0, dp(6), (v / max).toFloat().coerceIn(0.02f, 1f)))
                addView(View(ctx).apply { setBackgroundColor(NC.SURFACE_HIGH) }, LinearLayout.LayoutParams(0, dp(6), (1 - (v / max)).toFloat().coerceIn(0f, 0.98f)))
            }, lp(4))
        }
        return c
    }

    private fun models(list: List<com.zenithblue.nativecode.usage.ModelUse>): View {
        val c = card("MODELS")
        list.take(8).forEach { m ->
            c.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text(12f, NC.ON_SURFACE, mono = true).apply { text = m.model; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(12f, NC.ON_SURF_VAR, mono = true).apply { text = "${UsageModel.fmtTokens(m.tokens.total)}  ${UsageModel.fmtCost(m.cost)}"; setPadding(dp(8), 0, 0, 0) })
            }, lp(8))
        }
        return c
    }

    private fun sessions(list: List<com.zenithblue.nativecode.usage.UsageRow>): View {
        val c = card("RECENT SESSIONS")
        list.forEach { r ->
            val a = r.agents.firstOrNull() ?: return@forEach
            c.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text(12f, agentColor(a.agent), bold = true, mono = true).apply { text = UsageModel.agentLabel(a.agent) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(11f, NC.ON_SURF_VAR, mono = true).apply { text = (r.lastActivity ?: "").take(10) })
                addView(text(12f, NC.ON_SURFACE, mono = true).apply { text = "${UsageModel.fmtTokens(r.tokens.total)}  ${UsageModel.fmtCost(r.cost)}"; setPadding(dp(8), 0, 0, 0) })
            }, lp(8))
        }
        return c
    }

    private fun chatCard(list: List<ChatThreadUsage>): View {
        val c = card("CHAT THREADS")
        c.addView(text(11f, NC.ON_SURF_VAR).apply { text = "From the app's chats. Already counted in the CLI totals above."; setPadding(0, dp(4), 0, 0) })
        list.forEach { t ->
            c.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text(12f, NC.ON_SURFACE, mono = true).apply { text = t.title.ifBlank { "(untitled)" }; ellipsize = android.text.TextUtils.TruncateAt.END; maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(12f, NC.ON_SURF_VAR, mono = true).apply { text = "${UsageModel.fmtTokens(t.tokens)}  ${t.cost?.let(UsageModel::fmtCost) ?: "-"}"; setPadding(dp(8), 0, 0, 0) })
            }, lp(8))
        }
        return c
    }
}
