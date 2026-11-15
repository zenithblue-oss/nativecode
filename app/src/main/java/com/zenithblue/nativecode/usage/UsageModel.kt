package com.zenithblue.nativecode.usage

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Token counts of one slice. [total] is ccusage's own total when it gave one (it can count more than the four parts). */
data class Tok(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0, val total: Long = input + output + cacheRead + cacheWrite) {
    operator fun plus(o: Tok) = Tok(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite, total + o.total)
}

data class ModelUse(val model: String, val tokens: Tok, val cost: Double)

/** One CLI's usage inside one period (or one session). [agent] is ccusage's name: claude, codex, opencode, grok, antigravity. */
data class AgentUse(val agent: String, val tokens: Tok, val cost: Double, val models: List<ModelUse>)

/** One day, week (Monday), month or session. [period] is `2026-11-15`, `2026-11`, or the session id. */
data class UsageRow(val period: String, val agents: List<AgentUse>, val lastActivity: String? = null) {
    val tokens: Tok get() = agents.fold(Tok()) { a, u -> a + u.tokens }
    val cost: Double get() = agents.sumOf { it.cost }
}

enum class Granularity(val label: String, val bars: Int) { DAY("DAY", 14), WEEK("WEEK", 8), MONTH("MONTH", 6) }

/** Everything `ccusage daily --sections daily,weekly,monthly,session --by-agent --json` returns. */
data class UsageReport(val daily: List<UsageRow>, val weekly: List<UsageRow>, val monthly: List<UsageRow>, val sessions: List<UsageRow>) {
    fun rows(g: Granularity) = when (g) { Granularity.DAY -> daily; Granularity.WEEK -> weekly; Granularity.MONTH -> monthly }

    /** Same report keeping only the CLIs [allowed] says yes to; rows left with no CLI are dropped. */
    fun only(allowed: (String) -> Boolean): UsageReport {
        fun f(l: List<UsageRow>) = l.mapNotNull { r -> r.agents.filter { allowed(it.agent) }.takeIf { it.isNotEmpty() }?.let { r.copy(agents = it) } }
        return UsageReport(f(daily), f(weekly), f(monthly), f(sessions))
    }

    val isEmpty get() = daily.isEmpty() && weekly.isEmpty() && monthly.isEmpty() && sessions.isEmpty()
}

data class Summary(val tokens: Tok, val cost: Double, val byAgent: List<AgentUse>, val byModel: List<ModelUse>)

/** A bar of the chart: a label and one value per CLI. */
data class Bar(val label: String, val parts: List<Pair<String, Double>>) { val total get() = parts.sumOf { it.second } }

/** Tokens and cost of one chat thread (item 12 chat store), summed over its runs. */
data class ChatThreadUsage(val threadId: String, val title: String, val agent: String, val tokens: Long, val cost: Double?)

/** One chat run's usage as stored (item 12). [cost] is null when the CLI gave none. */
data class ChatRunUsage(val threadId: String, val title: String, val tool: String, val tokens: Long, val cost: Double?)

object UsageModel {
    /** ccusage version the app installs. The tarball hash is the npm `integrity` of that exact file. */
    const val VERSION = "20.0.49"

    // ---- parsing ----

    /** Parses ccusage `--json` output. Tolerant: missing fields count as 0, unknown fields are ignored, rows with no period are skipped. Throws on non-JSON. */
    fun parse(text: String): UsageReport {
        val start = jsonStart(text)
        require(start >= 0) { "no JSON in ccusage output" }
        val o = JSONObject(text.substring(start))
        fun rows(vararg keys: String): List<UsageRow> =
            keys.firstNotNullOfOrNull { o.optJSONArray(it) }?.let { a -> (0 until a.length()).mapNotNull { parseRow(a.optJSONObject(it)) } } ?: emptyList()
        return UsageReport(rows("daily"), rows("weekly"), rows("monthly"), rows("session", "sessions"))
    }

    /** Index of the `{` that opens the report: the first one at a line start, so a warning printed before it is skipped. */
    fun jsonStart(text: String): Int = Regex("(?m)^\\{").find(text)?.range?.first ?: -1

    private fun parseRow(o: JSONObject?): UsageRow? {
        if (o == null) return null
        val period = o.optString("period", "").ifBlank { return null }
        val arr = o.optJSONArray("agents")
        val agents = if (arr != null && arr.length() > 0) (0 until arr.length()).mapNotNull { parseAgent(arr.optJSONObject(it), null) }
        else listOfNotNull(parseAgent(o, "all"))
        return UsageRow(period, agents, o.optJSONObject("metadata")?.optString("lastActivity", "")?.ifBlank { null })
    }

    private fun parseAgent(o: JSONObject?, fallbackName: String?): AgentUse? {
        if (o == null) return null
        val name = o.optString("agent", "").ifBlank { fallbackName ?: return null }
        val models = o.optJSONArray("modelBreakdowns")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val m = a.optJSONObject(i) ?: return@mapNotNull null
                val n = m.optString("modelName", "").ifBlank { return@mapNotNull null }
                ModelUse(n, tok(m), num(m, "cost"))
            }
        } ?: emptyList()
        return AgentUse(name, tok(o), num(o, "totalCost").let { if (it == 0.0 && !o.has("totalCost")) models.sumOf { m -> m.cost } else it }, models)
    }

    private fun tok(o: JSONObject): Tok {
        val i = lng(o, "inputTokens"); val out = lng(o, "outputTokens"); val cr = lng(o, "cacheReadTokens"); val cw = lng(o, "cacheCreationTokens")
        return Tok(i, out, cr, cw, if (o.has("totalTokens")) lng(o, "totalTokens") else i + out + cr + cw)
    }

    private fun lng(o: JSONObject, k: String): Long = o.optLong(k, 0).coerceAtLeast(0)
    private fun num(o: JSONObject, k: String): Double = o.optDouble(k, 0.0).let { if (it.isNaN() || it < 0) 0.0 else it }

    // ---- gating ----

    /** App tool id (FeatureGate / terminalType) for a ccusage agent name. */
    fun toolId(agent: String): String = when (agent) { "claude" -> "claude-code"; "antigravity" -> "agy"; else -> agent }

    /** ccusage agent name for an app tool id (chat thread `tool`). */
    fun agentOf(toolId: String): String = when (toolId) { "claude-code" -> "claude"; "agy" -> "antigravity"; else -> toolId }

    fun agentLabel(agent: String): String = when (agent) {
        "claude" -> "Claude Code"; "codex" -> "Codex"; "opencode" -> "opencode"; "grok" -> "Grok"; "antigravity" -> "Antigravity"
        else -> agent.replaceFirstChar { it.uppercase() }
    }

    // ---- aggregates ----

    fun summarize(rows: List<UsageRow>): Summary {
        val agents = LinkedHashMap<String, AgentUse>()
        for (r in rows) for (a in r.agents) {
            val p = agents[a.agent]
            agents[a.agent] = if (p == null) a else AgentUse(a.agent, p.tokens + a.tokens, p.cost + a.cost, p.models + a.models)
        }
        val merged = agents.values.map { a -> a.copy(models = mergeModels(a.models)) }.sortedByDescending { it.cost }
        return Summary(rows.fold(Tok()) { t, r -> t + r.tokens }, rows.sumOf { it.cost }, merged, merged.flatMap { it.models })
    }

    private fun mergeModels(l: List<ModelUse>): List<ModelUse> =
        l.groupBy { it.model }.map { (n, g) -> ModelUse(n, g.fold(Tok()) { t, m -> t + m.tokens }, g.sumOf { it.cost }) }.sortedByDescending { it.cost }

    /** The last [n] rows by period, oldest first. */
    fun lastRows(rows: List<UsageRow>, n: Int): List<UsageRow> = rows.sortedBy { it.period }.takeLast(n)

    /** Exactly [n] consecutive days ending [endDate] (`yyyy-MM-dd`), an empty row for a day with no usage, so the day chart keeps a steady scale. */
    fun fillDays(rows: List<UsageRow>, endDate: String, n: Int): List<UsageRow> {
        val end = runCatching { java.time.LocalDate.parse(endDate) }.getOrNull() ?: return lastRows(rows, n)
        val by = rows.associateBy { it.period }
        return (n - 1 downTo 0).map { end.minusDays(it.toLong()).toString().let { d -> by[d] ?: UsageRow(d, emptyList()) } }
    }

    /** Chart bars for [rows] (already limited): value is cost or total tokens per CLI. Zero values are dropped. */
    fun bars(rows: List<UsageRow>, g: Granularity, byCost: Boolean): List<Bar> = rows.map { r ->
        Bar(barLabel(r.period, g), r.agents.map { it.agent to (if (byCost) it.cost else it.tokens.total.toDouble()) }.filter { it.second > 0 })
    }

    /** Short axis label: `15` for a day, `10/12` for a week (its Monday), `Oct` for a month. */
    fun barLabel(period: String, g: Granularity): String = when (g) {
        Granularity.DAY -> period.takeLast(2)
        Granularity.WEEK -> if (period.length >= 10) period.substring(5, 7).trimStart('0') + "/" + period.substring(8, 10).trimStart('0') else period
        Granularity.MONTH -> period.takeLast(2).toIntOrNull()?.let { MONTHS.getOrNull(it - 1) } ?: period
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Today's tokens and cost ([date] is `yyyy-MM-dd` in the same timezone ccusage used). Null when there is no row for it. */
    fun today(report: UsageReport, date: String): Pair<Long, Double>? =
        report.daily.firstOrNull { it.period == date }?.let { it.tokens.total to it.cost }

    /** Newest sessions first. */
    fun recentSessions(report: UsageReport, n: Int): List<UsageRow> =
        report.sessions.sortedByDescending { it.lastActivity ?: "" }.take(n)

    // ---- chat store merge (item 12) ----

    /**
     * Per-thread totals from the chat store, newest-cost first. These runs are also in the CLI logs ccusage reads,
     * so the page lists them beside the CLI totals and never adds them to those totals.
     */
    fun chatThreads(runs: List<ChatRunUsage>): List<ChatThreadUsage> =
        runs.groupBy { it.threadId }.map { (id, g) ->
            val costs = g.mapNotNull { it.cost }
            ChatThreadUsage(id, g.first().title, agentOf(g.first().tool), g.sumOf { it.tokens }, if (costs.isEmpty()) null else costs.sum())
        }.filter { it.tokens > 0 || (it.cost ?: 0.0) > 0 }
            .sortedWith(compareByDescending<ChatThreadUsage> { it.cost ?: 0.0 }.thenByDescending { it.tokens })

    // ---- formatting ----

    fun fmtTokens(n: Long): String = when {
        n >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", n / 1e9)
        n >= 1_000_000L -> String.format(Locale.US, "%.2fM", n / 1e6)
        n >= 10_000L -> String.format(Locale.US, "%.1fK", n / 1e3)
        else -> n.toString()
    }

    fun fmtCost(c: Double): String = when {
        c <= 0.0 -> "$0.00"
        c < 0.01 -> "<$0.01"
        c >= 1000 -> String.format(Locale.US, "$%,.0f", c)
        else -> String.format(Locale.US, "$%.2f", c)
    }
}
