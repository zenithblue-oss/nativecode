package com.zenithblue.nativecode.usage.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.usage.AgentUse
import com.zenithblue.nativecode.usage.ChatThreadUsage
import com.zenithblue.nativecode.usage.CliLimits
import com.zenithblue.nativecode.usage.Granularity
import com.zenithblue.nativecode.usage.LimitState
import com.zenithblue.nativecode.usage.LimitWindow
import com.zenithblue.nativecode.usage.LimitsModel
import com.zenithblue.nativecode.usage.LimitsRepository
import com.zenithblue.nativecode.usage.UsageModel
import com.zenithblue.nativecode.usage.UsageReport
import com.zenithblue.nativecode.usage.UsageRepository
import com.zenithblue.nativecode.usage.UsageRow
import java.util.concurrent.Executors
import com.zenithblue.nativecode.tr

internal interface UsageHostApi {
    /** True when usage of [agent] (a ccusage name such as `claude`) may be shown to this user. */
    fun allowed(agent: String): Boolean
    /** Shows the Upgrade sheet. */
    fun upgrade()
    /** Opens the sign-in popup of [agent] (a ccusage name such as `claude`). */
    fun openLogin(agent: String)
    /** Opens Settings > Components, where the Linux runtime update (ccusage, OpenSSH) is applied. */
    fun openComponents()
}

/** One colour per CLI, the same in every chart. Names always sit beside the colour. */
internal fun agentColor(agent: String): Int = when (agent) {
    "claude" -> NC.TERTIARY_CON
    "codex" -> NC.PRIMARY
    "opencode" -> Color.parseColor("#8ab4ff")
    "grok" -> Color.parseColor("#e5e2e1")
    "antigravity" -> Color.parseColor("#c58cff")
    else -> NC.OUTLINE
}

/** Root of the page: reports a pull-down gesture that starts with the scroll view at the top. */
private class PullLinear(ctx: Context, val onPull: () -> Unit) : LinearLayout(ctx) {
    private var downY = 0f
    private var armed = false
    private fun scrollTop(): Boolean { var p = parent; while (p != null) { if (p is ScrollView) return p.scrollY == 0; p = p.parent }; return false }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.rawY; armed = scrollTop() }
            MotionEvent.ACTION_UP -> {
                if (armed && e.rawY - downY > 110 * resources.displayMetrics.density && scrollTop()) onPull()
                armed = false
            }
            MotionEvent.ACTION_CANCEL -> if (armed && e.rawY - downY > 110 * resources.displayMetrics.density && scrollTop()) { onPull(); armed = false }
        }
        return super.dispatchTouchEvent(e)
    }
}

/**
 * AI usage page (plan 18), visual first: hero tiles with deltas and sparklines, plan-limit rings per signed-in CLI, a donut,
 * stacked bars and a trend line, model bars, and a collapsed Details section. Cost is an estimate from ccusage's price table.
 */
internal class UsagePage(private val ctx: Context, private val host: UsageHostApi) {
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var report: UsageReport? = null
    private var limits: List<CliLimits> = emptyList()
    private var chat: List<ChatThreadUsage> = emptyList()
    private var gran = Granularity.DAY
    private var byCost = true
    private var busy = false
    private var limitsBusy = false
    private var error: String? = null
    private var detailsOpen = false
    private var cols = 1

    val content: LinearLayout = PullLinear(ctx) { refreshAll() }.apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    private fun columns() = if (ctx.resources.displayMetrics.widthPixels / ctx.resources.displayMetrics.density >= 600f) 2 else 1

    init {
        content.addView(body)
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (columns() != cols && report != null) render() }
    }

    // ---- small view helpers ----

    private fun text(size: Float, color: Int, bold: Boolean = false, mono: Boolean = true) = TextView(ctx).apply {
        textSize = size; setTextColor(color)
        if (bold) paint.isFakeBoldText = true
        if (mono) typeface = Typeface.MONOSPACE
    }

    private fun lp(top: Int = 0, w: Int = -1) = LinearLayout.LayoutParams(w, -2).apply { topMargin = dp(top) }
    private fun weight(left: Int = 0) = LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(left) }

    /** Rounded card on the 8dp scale with a hairline border. */
    private fun card(title: String? = null, trailing: View? = null): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke(dp(1), NC.OUTLINE_VAR); cornerRadius = dp(14).toFloat() }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        if (title != null) addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(text(13f, NC.PRIMARY, bold = true).apply { text = title; letterSpacing = 0.08f }, weight())
            trailing?.let { addView(it) }
        })
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 12f; typeface = Typeface.MONOSPACE; paint.isFakeBoldText = true; gravity = Gravity.CENTER
        setTextColor(if (selected) Color.parseColor("#0A0A0A") else NC.ON_SURF_VAR)
        background = GradientDrawable().apply { setColor(if (selected) NC.PRIMARY_CON else NC.SURFACE_HIGH); cornerRadius = dp(20).toFloat() }
        minHeight = dp(40); minWidth = dp(64); setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true; isFocusable = true
        contentDescription = label.lowercase()
        setOnClickListener { onClick() }
    }

    private fun pill(label: String, color: Int) = TextView(ctx).apply {
        text = label; textSize = 11f; typeface = Typeface.MONOSPACE; paint.isFakeBoldText = true; setTextColor(color)
        background = GradientDrawable().apply { setColor(Color.argb(36, Color.red(color), Color.green(color), Color.blue(color))); cornerRadius = dp(10).toFloat() }
        setPadding(dp(8), dp(3), dp(8), dp(3))
    }

    private fun icon(res: Int, tint: Int, size: Int = 20) = ImageView(ctx).apply {
        setImageResource(res); setColorFilter(tint); layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The CLI's real logo on its contrast-safe tile (same assets and rule as the chat). */
    private fun tile(agent: String, size: Int = 36): View = CliLogos.tile(ctx, agent, size)

    private fun legendRow(agent: String, color: Int, name: String, value: String, share: String?) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(View(ctx).apply { background = GradientDrawable().apply { setColor(color); cornerRadius = dp(3).toFloat() } }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(8) })
        addView(tile(agent, 22), (LinearLayout.LayoutParams(dp(22), dp(22))).apply { rightMargin = dp(8) })
        addView(text(12f, NC.ON_SURFACE).apply { text = name; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, weight())
        addView(text(12f, NC.ON_SURF_VAR).apply { text = value })
        share?.let { addView(text(12f, NC.ON_SURF_VAR).apply { text = it; minWidth = dp(40); gravity = Gravity.END }) }
        minimumHeight = dp(28)
    }

    // ---- loading ----

    /** Page shown: draw what is cached, then reload. */
    fun refresh() {
        if (report == null) bg.execute {
            val c = UsageRepository.cached(ctx); val l = LimitsRepository.cached(ctx)
            ui.post { if (report == null && c != null) report = c; if (limits.isEmpty()) limits = l; render() }
        }
        render()
        if (UsageRepository.isInstalled(ctx)) refreshAll()
    }

    fun refreshAll() {
        reloadUsage()
        reloadLimits()
    }

    private fun reloadUsage() {
        if (busy || !UsageRepository.isInstalled(ctx)) return
        busy = true; error = null; render()
        bg.execute {
            val r = UsageRepository.load(ctx)
            val threads = UsageModel.chatThreads(UsageRepository.chatRuns(ctx))
            ui.post {
                busy = false
                r.onSuccess { report = it; error = null }.onFailure { error = it.message ?: tr(R.string.usage_could_not_read_usage) }
                chat = threads
                render()
            }
        }
    }

    private fun reloadLimits() {
        if (limitsBusy) return
        limitsBusy = true; render()
        bg.execute {
            val l = LimitsRepository.refresh(ctx, host::allowed)
            ui.post { limitsBusy = false; limits = l; render() }
        }
    }

    // ---- render ----

    private fun render() {
        cols = columns()
        body.removeAllViews()
        if (!UsageRepository.isInstalled(ctx)) { body.addView(installCard()); return }
        val full = report
        body.addView(topRow())
        if (full == null) {
            body.addView(emptyCard(if (busy) tr(R.string.usage_reading_your_ai_logs) else (error ?: tr(R.string.usage_nothing_loaded_yet)), retry = !busy))
            addLimits()
            return
        }
        val rep = full.only(host::allowed)
        val hidden = (full.sessions + full.daily).flatMap { it.agents }.any { !host.allowed(it.agent) }
        if (error != null) body.addView(card().apply { addView(text(12f, NC.ERROR, mono = false).apply { text = "$error Showing the last data." }) })

        val today = UsageRepository.todayString()
        if (rep.daily.isNotEmpty() || rep.sessions.isNotEmpty()) body.addView(hero(UsageModel.hero(rep, today, gran), UsageModel.activeAgents(rep, today)))
        addLimits()

        val rows = UsageModel.fillRows(rep.rows(gran), gran, today, gran.bars)
        if (rows.all { it.agents.isEmpty() }) {
            body.addView(emptyCard(tr(R.string.usage_no_ai_usage_yet_use), retry = false))
        } else {
            val sum = UsageModel.summarize(rows)
            val useCost = byCost && sum.cost > 0
            body.addView(metricRow(useCost))
            val donut = donutCard(sum.byAgent, sum.cost, sum.tokens.total, useCost, rows)
            val bars = barsCard(rows, useCost)
            val trend = trendCard(rows, useCost)
            if (cols >= 2) {
                body.addView(pair(donut, bars))
                body.addView(pair(trend, modelsCard(sum.byAgent, useCost)))
            } else {
                body.addView(donut); body.addView(bars); body.addView(trend); body.addView(modelsCard(sum.byAgent, useCost))
            }
        }
        if (hidden) body.addView(card(tr(R.string.usage_more_clis_pro)).apply {
            addView(text(12f, NC.ON_SURF_VAR, mono = false).apply { text = tr(R.string.usage_usage_of_claude_code_codex); setPadding(0, dp(6), 0, dp(10)) })
            addView(chip(tr(R.string.usage_see_pro), false) { host.upgrade() })
        })
        body.addView(details(rep))
        body.addView(text(11f, NC.OUTLINE).apply { text = tr(R.string.usage_estimated_cost_from_ccusage_prices); setPadding(0, dp(16), 0, dp(8)) })
    }

    private fun pair(a: View, b: View): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        (a.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; rightMargin = dp(8) }
        (b.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; leftMargin = dp(8) }
        addView(a); addView(b)
    }

    private fun topRow(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        Granularity.entries.forEachIndexed { i, g ->
            addView(chip(g.label.lowercase().replaceFirstChar { it.uppercase() }, g == gran) { gran = g; render() }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(8) })
        }
        addView(View(ctx), weight())
        addView(text(11f, NC.ON_SURF_VAR).apply { text = if (busy || limitsBusy) "updating..." else ""; setPadding(0, 0, dp(8), 0) })
        addView(FrameLayout(ctx).apply {
            minimumWidth = dp(48); minimumHeight = dp(48); isClickable = true; isFocusable = true
            contentDescription = "Refresh usage"
            background = GradientDrawable().apply { setColor(NC.SURFACE_HIGH); cornerRadius = dp(24).toFloat() }
            addView(icon(R.drawable.ic_lucide_rotate_cw, NC.TERTIARY, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { refreshAll() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    // ---- hero ----

    private fun hero(h: UsageModel.Hero, activeAgents: List<String>): View {
        fun t(label: String, big: String, s: UsageModel.Stat, color: Int, sub: String? = null, logos: List<String>? = null): View = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke(dp(1), NC.OUTLINE_VAR); cornerRadius = dp(14).toFloat() }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(text(10f, NC.ON_SURF_VAR).apply { text = label; letterSpacing = 0.08f })
            addView(text(24f, NC.ON_SURFACE, bold = true).apply { text = big; maxLines = 1; setPadding(0, dp(2), 0, 0) })
            val d = UsageModel.fmtDelta(s)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                if (d.isNotEmpty()) addView(pill(d, if (s.deltaPct != null && s.deltaPct!! < 0) NC.PRIMARY else NC.TERTIARY_CON))
                addView(text(10f, NC.OUTLINE).apply { text = sub ?: "vs before"; setPadding(dp(6), 0, 0, 0) })
            }, lp(4))
            if (logos != null) addView(CliLogos.row(ctx, logos, 24), lp(8)) else addView(SparkView(ctx, color).apply { set(s.spark) }, lp(6))
            contentDescription = "$label $big ${if (d.isNotEmpty()) d else ""}"
        }
        val cells = listOf(
            t(tr(R.string.usage_today_cost), UsageModel.fmtCost(h.todayCost.value), h.todayCost, NC.TERTIARY_CON, "vs yesterday"),
            t(tr(R.string.usage_today_tokens), UsageModel.fmtTokens(h.todayTokens.value.toLong()) + " tok", h.todayTokens, NC.PRIMARY, "vs yesterday"),
            t(tr(R.string.usage_7_days), UsageModel.fmtCost(h.weekCost.value), h.weekCost, Color.parseColor("#8ab4ff"), "vs prior 7"),
            t(tr(R.string.usage_active_clis), h.activeClis.value.toInt().toString(), h.activeClis, Color.parseColor("#c58cff"), "last 7 days", activeAgents),
        )
        val per = if (cols >= 2) 4 else 2
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
            cells.chunked(per).forEachIndexed { r, row ->
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(8) }) }
                }, lp(if (r == 0) 0 else 8))
            }
        }
    }

    // ---- limits ----

    private fun addLimits() {
        val shown = limits.filter { host.allowed(it.agent) }
        if (shown.isEmpty()) {
            if (limitsBusy) body.addView(card(tr(R.string.usage_plan_limits)).apply { addView(text(12f, NC.ON_SURF_VAR, mono = false).apply { text = tr(R.string.usage_reading_limits); setPadding(0, dp(8), 0, 0) }) })
            return
        }
        val sorted = shown.sortedWith(compareBy({ it.state != LimitState.OK }, { LimitsRepository.AGENTS.indexOf(it.agent) }))
        val cards = sorted.map { limitCard(it) }
        val at = shown.maxOf { it.fetchedAt }
        body.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) }
            addView(text(13f, NC.PRIMARY, bold = true).apply { text = tr(R.string.usage_plan_limits); letterSpacing = 0.08f }, weight())
            addView(text(10f, NC.OUTLINE).apply { text = if (limitsBusy) "updating..." else if (at > 0) "updated " + ago(at) else "" })
        })
        if (cols >= 2) cards.chunked(2).forEach { r -> body.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            r.forEachIndexed { i, c -> (c.layoutParams as LinearLayout.LayoutParams).apply { width = 0; weight = 1f; if (i > 0) leftMargin = dp(8) else rightMargin = dp(8) }; addView(c) }
            if (r.size == 1) addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        }) } else cards.forEach { body.addView(it) }
    }

    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t) / 1000
        return when { s < 60 -> "just now"; s < 3600 -> "${s / 60}m ago"; s < 86400 -> "${s / 3600}h ago"; else -> "${s / 86400}d ago" }
    }

    private fun limitCard(l: CliLimits): LinearLayout = card().apply {
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(tile(l.agent))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(15f, NC.ON_SURFACE, bold = true).apply { text = UsageModel.agentLabel(l.agent) })
                (l.plan ?: if (l.state == LimitState.FREE) "Free" else null)?.let { addView(text(11f, NC.ON_SURF_VAR).apply { text = "Plan: $it" }) }
            }, weight(12))
        })
        when (l.state) {
            LimitState.OK -> {
                val rings = if (l.windows.size <= 4) l.windows else l.windows.take(2)   // up to 2 rings per row, the rest as meters
                rings.chunked(2).forEach { row ->
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        row.forEach { w -> addView(ringCell(w), weight()) }
                        if (row.size == 1) addView(View(ctx), weight())
                    }, lp(12))
                }
                l.windows.drop(rings.size).forEach { w -> addView(barRow(w), lp(10)) }
            }
            LimitState.FREE -> addView(text(12f, NC.ON_SURF_VAR, mono = false).apply { text = tr(R.string.usage_free_models_no_plan_limits); setPadding(0, dp(10), 0, 0) })
            LimitState.NOT_SIGNED_IN -> {
                addView(text(12f, NC.ON_SURF_VAR, mono = false).apply { text = tr(R.string.usage_not_signed_in); setPadding(0, dp(10), 0, dp(8)) })
                addView(chip(tr(R.string.usage_sign_in), false) { host.openLogin(l.agent) })
            }
            LimitState.UNAVAILABLE -> addView(text(12f, NC.ON_SURF_VAR, mono = false).apply { text = l.message ?: tr(R.string.usage_not_available); setPadding(0, dp(10), 0, 0) })
            LimitState.ERROR -> addView(text(12f, NC.ERROR, mono = false).apply { text = l.message ?: tr(R.string.usage_could_not_read_limits); setPadding(0, dp(10), 0, 0) })
        }
    }

    private fun ringCell(w: LimitWindow): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        val reset = w.resetsAtMs?.let { if (it > System.currentTimeMillis()) "resets in " + LimitsModel.resetIn(it, System.currentTimeMillis()) else "reset passed" }
        val suffix = listOf(" 5h", " Weekly", " Daily").firstOrNull { w.label.endsWith(it) }   // "Gemini 5h": family under the ring, window in it
        val inRing = suffix?.trim() ?: w.label.takeIf { it.length <= 7 }
        val family = suffix?.let { w.label.removeSuffix(it) } ?: if (inRing == null) w.label else null   // a long name goes under the ring
        addView(RingView(ctx).apply { set(w.usedPercent, inRing.orEmpty(),"${w.label} limit ${w.usedPercent.toInt()} percent used" + (reset?.let { ", $it" } ?: "")) })
        if (family != null) addView(text(12f, NC.ON_SURFACE, bold = true).apply { text = family; gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) })
        addView(text(11f, NC.ON_SURF_VAR).apply { text = reset ?: "no reset time"; gravity = Gravity.CENTER; setPadding(0, dp(if (family != null) 0 else 4), 0, 0) })
    }

    private fun barRow(w: LimitWindow): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text(11f, NC.ON_SURF_VAR).apply { text = w.label; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, weight())
            addView(text(11f, NC.ON_SURFACE, bold = true).apply { text = "${w.usedPercent.toInt()}%" })
        })
        addView(meter(w.usedPercent / 100.0, limitColor(w.usedPercent)), lp(4))
    }

    private fun meter(fraction: Double, color: Int): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply { setColor(NC.SURFACE_HIGH); cornerRadius = dp(4).toFloat() }
        val f = fraction.coerceIn(0.0, 1.0).toFloat()
        addView(View(ctx).apply { background = GradientDrawable().apply { setColor(color); cornerRadius = dp(4).toFloat() } }, LinearLayout.LayoutParams(0, dp(8), f.coerceAtLeast(0.02f)))
        addView(View(ctx), LinearLayout.LayoutParams(0, dp(8), (1f - f).coerceAtLeast(0f)))
    }

    // ---- charts ----

    private fun metricRow(useCost: Boolean): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) }
        addView(text(13f, NC.PRIMARY, bold = true).apply { text = tr(R.string.usage_usage); letterSpacing = 0.08f }, weight())
        addView(chip(tr(R.string.usage_cost), useCost) { byCost = true; render() })
        addView(chip(tr(R.string.usage_tokens), !useCost) { byCost = false; render() }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
    }

    private fun value(a: Double, useCost: Boolean) = if (useCost) UsageModel.fmtCost(a) else UsageModel.fmtTokens(a.toLong())

    private fun donutCard(byAgent: List<AgentUse>, cost: Double, tokens: Long, useCost: Boolean, rows: List<UsageRow>): LinearLayout {
        val c = card(tr(R.string.usage_by_cli))
        val vals = byAgent.map { it to (if (useCost) it.cost else it.tokens.total.toDouble()) }
        val total = vals.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
        val slices = vals.map { (a, v) -> Slice(UsageModel.agentLabel(a.agent), v, agentColor(a.agent), value(v, useCost)) }
        val desc = "Donut of ${if (useCost) "cost" else "tokens"} by CLI: " + slices.joinToString(", ") { "${it.label} ${it.valueText}" }
        val donut = DonutView(ctx).apply { set(slices, if (useCost) UsageModel.fmtCost(cost) else UsageModel.fmtTokens(tokens), "total", desc) }
        c.addView(donut, LinearLayout.LayoutParams(dp(200), dp(200)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        val legend = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        vals.forEach { (a, v) -> legend.addView(legendRow(a.agent, agentColor(a.agent), UsageModel.agentLabel(a.agent), value(v, useCost), "${(v / total * 100).toInt()}%")) }
        c.addView(legend, lp(8))
        return c
    }

    private fun barsCard(rows: List<UsageRow>, useCost: Boolean): LinearLayout {
        val c = card(if (useCost) "COST BY ${gran.label}" else "TOKENS BY ${gran.label}")
        val bars = UsageModel.bars(rows, gran, useCost)
        val fmt: (Double) -> String = { value(it, useCost) }
        val detail = text(11f, NC.ON_SURF_VAR).apply { text = tr(R.string.usage_tap_a_bar_for_details) }
        val desc = "Stacked bars by ${gran.label.lowercase()}: " + bars.joinToString(", ") { "${it.label} ${fmt(it.total)}" }
        val v = BarsView(ctx, ::agentColor).apply {
            set(bars, fmt, desc)
            onSelect = { i ->
                detail.text = if (i < 0) tr(R.string.usage_tap_a_bar_for_details) else rows[i].let { r ->
                    r.period + "  ·  " + fmt(bars[i].total) + (if (r.agents.isEmpty()) "  ·  no usage" else "  ·  " + r.agents.joinToString(", ") { UsageModel.agentLabel(it.agent) + " " + value(if (useCost) it.cost else it.tokens.total.toDouble(), useCost) })
                }
            }
        }
        c.addView(v, lp(10))
        val agents = bars.flatMap { b -> b.parts.map { it.first } }.distinct()
        c.addView(android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; agents.forEach { a ->
                addView(View(ctx).apply { background = GradientDrawable().apply { setColor(agentColor(a)); cornerRadius = dp(3).toFloat() } }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { gravity = Gravity.CENTER_VERTICAL; rightMargin = dp(4) })
                addView(tile(a, 18), LinearLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER_VERTICAL; rightMargin = dp(4) })
                addView(text(11f, NC.ON_SURF_VAR).apply { text = UsageModel.agentLabel(a) }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
            } })
        }, lp(8))
        c.addView(detail, lp(8))
        return c
    }

    private fun trendCard(rows: List<UsageRow>, useCost: Boolean): LinearLayout {
        val c = card(tr(R.string.usage_trend))
        val vals = rows.map { if (useCost) it.cost else it.tokens.total.toDouble() }
        val labels = rows.map { UsageModel.barLabel(it.period, gran) }
        val detail = text(11f, NC.ON_SURF_VAR).apply { text = tr(R.string.usage_tap_the_line_for_a) }
        val desc = "Trend of ${if (useCost) "cost" else "tokens"}: " + labels.indices.joinToString(", ") { "${labels[it]} ${value(vals[it], useCost)}" }
        c.addView(AreaView(ctx).apply {
            set(vals, labels, desc)
            onSelect = { i -> detail.text = if (i < 0) tr(R.string.usage_tap_the_line_for_a) else rows[i].period + "  ·  " + value(vals[i], useCost) }
        }, lp(10))
        c.addView(detail, lp(8))
        return c
    }

    private fun modelsCard(byAgent: List<AgentUse>, useCost: Boolean): LinearLayout {
        val c = card(tr(R.string.usage_models))
        val list = byAgent.flatMap { a -> a.models.map { a.agent to it } }
            .sortedByDescending { if (useCost) it.second.cost else it.second.tokens.total.toDouble() }.take(6)
        val max = list.maxOfOrNull { if (useCost) it.second.cost else it.second.tokens.total.toDouble() }?.takeIf { it > 0 } ?: 1.0
        list.forEach { (agent, m) ->
            val v = if (useCost) m.cost else m.tokens.total.toDouble()
            c.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(tile(agent, 28))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(text(11f, NC.ON_SURFACE).apply { text = m.model; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }, weight())
                        addView(text(11f, NC.ON_SURF_VAR).apply { text = value(v, useCost); setPadding(dp(8), 0, 0, 0) })
                    })
                    addView(meter(v / max, agentColor(agent)), lp(4))
                }, weight(10))
            }, lp(12))
        }
        return c
    }

    // ---- details ----

    private fun details(rep: UsageReport): View {
        val c = card()
        c.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48)
            isClickable = true; isFocusable = true; contentDescription = "Details, ${if (detailsOpen) "expanded" else "collapsed"}"
            addView(text(13f, NC.PRIMARY, bold = true).apply { text = tr(R.string.usage_details); letterSpacing = 0.08f }, weight())
            addView(icon(R.drawable.ic_lucide_chevron_down, NC.ON_SURF_VAR).apply { rotation = if (detailsOpen) 180f else 0f })
            setOnClickListener { detailsOpen = !detailsOpen; render() }
        })
        if (!detailsOpen) return c
        val sessions = UsageModel.recentSessions(rep, 8)
        if (sessions.isNotEmpty()) {
            c.addView(text(11f, NC.OUTLINE).apply { text = tr(R.string.usage_recent_sessions); setPadding(0, dp(8), 0, dp(4)) })
            sessions.forEach { r ->
                val a = r.agents.firstOrNull() ?: return@forEach
                c.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(text(12f, agentColor(a.agent), bold = true).apply { text = UsageModel.agentLabel(a.agent) }, weight())
                    addView(text(11f, NC.ON_SURF_VAR).apply { text = (r.lastActivity ?: "").take(10) })
                    addView(text(12f, NC.ON_SURFACE).apply { text = "${UsageModel.fmtTokens(r.tokens.total)}  ${UsageModel.fmtCost(r.cost)}"; setPadding(dp(8), 0, 0, 0) })
                }, lp(6))
            }
        }
        val chatShown = chat.filter { host.allowed(it.agent) }.take(8)
        if (chatShown.isNotEmpty()) {
            c.addView(text(11f, NC.OUTLINE).apply { text = tr(R.string.usage_chat_threads_already_in_the); setPadding(0, dp(16), 0, dp(4)) })
            chatShown.forEach { t ->
                c.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(text(12f, NC.ON_SURFACE).apply { text = t.title.ifBlank { "(untitled)" }; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, weight())
                    addView(text(12f, NC.ON_SURF_VAR).apply { text = "${UsageModel.fmtTokens(t.tokens)}  ${t.cost?.let(UsageModel::fmtCost) ?: "-"}"; setPadding(dp(8), 0, 0, 0) })
                }, lp(6))
            }
        }
        val s = UsageModel.summarize(rep.daily)
        c.addView(text(11f, NC.OUTLINE).apply { text = tr(R.string.usage_token_split_range); setPadding(0, dp(16), 0, dp(4)) })
        c.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = "in ${UsageModel.fmtTokens(s.tokens.input)} · out ${UsageModel.fmtTokens(s.tokens.output)}\ncache read ${UsageModel.fmtTokens(s.tokens.cacheRead)} · write ${UsageModel.fmtTokens(s.tokens.cacheWrite)}"
        })
        return c
    }

    // ---- states ----

    /** ccusage ships in the Linux runtime update (Components); the app never downloads it. */
    private fun installCard(): View = card(tr(R.string.usage_set_up_usage)).apply {
        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_usage_empty); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(132), dp(104)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })
        addView(text(13f, NC.ON_SURFACE, mono = false).apply { text = tr(R.string.usage_ccusage_in_runtime_update, UsageModel.VERSION); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(12)) })
        addView(chip(tr(R.string.common_open_components), true) { host.openComponents() }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun emptyCard(message: String, retry: Boolean): View = card().apply {
        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_usage_empty); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(132), dp(104)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        addView(text(13f, NC.ON_SURF_VAR, mono = false).apply { text = message; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, if (retry) dp(12) else 0) })
        if (retry) addView(chip(tr(R.string.usage_try_again), true) { refreshAll() }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

}
