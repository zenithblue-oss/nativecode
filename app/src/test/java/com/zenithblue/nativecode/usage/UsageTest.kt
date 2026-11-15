package com.zenithblue.nativecode.usage

import com.zenithblue.nativecode.entitlements.FeatureGate
import com.zenithblue.nativecode.entitlements.GateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

class UsageTest {
    /** Shape of real `ccusage daily --sections daily,weekly,monthly,session --by-agent --json` output (20.0.49), trimmed. */
    private val sample = """
    {
      "daily": [
        {"agent":"all","period":"2026-11-14","inputTokens":110,"outputTokens":40,"cacheReadTokens":1000,"cacheCreationTokens":50,"totalTokens":1200,"totalCost":2.5,
         "metadata":{"agents":["claude","opencode"]},"modelsUsed":["claude-opus-5-5","big-pickle"],
         "agents":[
           {"agent":"claude","inputTokens":100,"outputTokens":30,"cacheReadTokens":900,"cacheCreationTokens":40,"totalTokens":1070,"totalCost":2.5,
            "modelBreakdowns":[{"modelName":"claude-opus-5-5","inputTokens":100,"outputTokens":30,"cacheReadTokens":900,"cacheCreationTokens":40,"cost":2.5}]},
           {"agent":"opencode","inputTokens":10,"outputTokens":10,"cacheReadTokens":100,"cacheCreationTokens":10,"totalTokens":130,"totalCost":0.0,
            "modelBreakdowns":[{"modelName":"big-pickle","inputTokens":10,"outputTokens":10,"cacheReadTokens":100,"cacheCreationTokens":10,"cost":0.0}]}
         ]},
        {"agent":"all","period":"2026-11-15","inputTokens":5,"outputTokens":5,"cacheReadTokens":0,"cacheCreationTokens":0,"totalTokens":10,"totalCost":0.0,
         "agents":[{"agent":"opencode","inputTokens":5,"outputTokens":5,"totalTokens":10,"totalCost":0.0,"modelBreakdowns":[{"modelName":"big-pickle","inputTokens":5,"outputTokens":5,"cost":0.0}]}]}
      ],
      "weekly": [{"agent":"all","period":"2026-11-09","totalTokens":1210,"totalCost":2.5,"agents":[{"agent":"claude","totalTokens":1070,"totalCost":2.5},{"agent":"opencode","totalTokens":140,"totalCost":0.0}]}],
      "monthly": [{"agent":"all","period":"2026-11","totalTokens":1210,"totalCost":2.5,"agents":[{"agent":"claude","totalTokens":1070,"totalCost":2.5},{"agent":"opencode","totalTokens":140,"totalCost":0.0}]}],
      "session": [
        {"agent":"claude","period":"sess-a","inputTokens":100,"outputTokens":30,"totalTokens":1070,"totalCost":2.5,"metadata":{"lastActivity":"2026-11-14T10:00:00.000Z"}},
        {"agent":"opencode","period":"sess-b","totalTokens":140,"totalCost":0.0,"metadata":{"lastActivity":"2026-11-15T09:00:00.000Z"}}
      ],
      "totals": {"totalTokens":1210,"totalCost":2.5}
    }
    """.trimIndent()

    private val report get() = UsageModel.parse(sample)

    @Test fun parsesAllSections() {
        val r = report
        assertEquals(2, r.daily.size); assertEquals(1, r.weekly.size); assertEquals(1, r.monthly.size); assertEquals(2, r.sessions.size)
        val d = r.daily[0]
        assertEquals("2026-11-14", d.period)
        assertEquals(listOf("claude", "opencode"), d.agents.map { it.agent })
        assertEquals(2.5, d.cost, 1e-9)
        assertEquals(Tok(100, 30, 900, 40, 1070), d.agents[0].tokens)
        assertEquals("claude-opus-5-5", d.agents[0].models.single().model)
        assertEquals("2026-11-14T10:00:00.000Z", r.sessions[0].lastActivity)
        assertEquals("claude", r.sessions[0].agents.single().agent)
    }

    @Test fun toleratesMissingFieldsAndNoise() {
        val r = UsageModel.parse("warning: something {odd}\n{\"daily\":[{\"period\":\"2026-01-01\"},{\"agent\":\"codex\"},{\"period\":\"2026-01-02\",\"agent\":\"codex\",\"totalCost\":-3,\"inputTokens\":7,\"modelBreakdowns\":[{\"cost\":1.5},{\"modelName\":\"m\",\"cost\":1.5}]}]}")
        assertEquals(2, r.daily.size)                          // the row with no period is skipped
        assertEquals("all", r.daily[0].agents.single().agent)  // no agent name: "all"
        assertEquals(0.0, r.daily[1].cost, 0.0)                // negative cost clamped
        assertEquals(7, r.daily[1].tokens.total)               // no totalTokens: parts summed
        assertTrue(r.weekly.isEmpty() && r.sessions.isEmpty())
        assertEquals(1, r.daily[1].agents.single().models.size) // nameless model skipped
    }

    @Test fun emptyReportAndGarbage() {
        val e = UsageModel.parse("{\"daily\":[],\"totals\":{}}")
        assertTrue(e.isEmpty)
        assertEquals(-1, UsageModel.jsonStart("no json here"))
        assertTrue(runCatching { UsageModel.parse("not json") }.isFailure)
        assertTrue(runCatching { UsageModel.parse("{broken") }.isFailure)
    }

    @Test fun filterKeepsOnlyAllowedCliAndRecomputesTotals() {
        val r = report.only { it == "opencode" }
        assertEquals(2, r.daily.size)
        assertEquals(130L, r.daily[0].tokens.total)
        assertEquals(0.0, r.daily[0].cost, 0.0)
        assertEquals(listOf("sess-b"), r.sessions.map { it.period })
        assertTrue(report.only { false }.isEmpty)
    }

    @Test fun summaryByCliAndModel() {
        val s = UsageModel.summarize(report.daily)
        assertEquals(1210L, s.tokens.total)                                          // 1070 + 130 + 10 from the three agent slices
        assertEquals(2.5, s.cost, 1e-9)
        assertEquals(listOf("claude", "opencode"), s.byAgent.map { it.agent })       // most cost first
        assertEquals(130L + 10L, s.byAgent[1].tokens.total)
        assertEquals(listOf("claude-opus-5-5", "big-pickle"), s.byModel.map { it.model })
        assertEquals(15L, s.byModel[1].tokens.input)
    }

    @Test fun barsRangesAndLabels() {
        val rows = UsageModel.lastRows(report.daily, 1)
        assertEquals(listOf("2026-11-15"), rows.map { it.period })
        val tokenBars = UsageModel.bars(report.daily, Granularity.DAY, byCost = false)
        assertEquals(listOf("14", "15"), tokenBars.map { it.label })
        assertEquals(1200.0, tokenBars[0].total, 1e-9)
        val costBars = UsageModel.bars(report.daily, Granularity.DAY, byCost = true)
        assertEquals(listOf("claude" to 2.5), costBars[0].parts)   // zero cost dropped
        assertTrue(costBars[1].parts.isEmpty())
        assertEquals("11/9", UsageModel.barLabel("2026-11-09", Granularity.WEEK))
        assertEquals("Nov", UsageModel.barLabel("2026-11", Granularity.MONTH))
        assertEquals("Oct", UsageModel.barLabel("2026-10", Granularity.MONTH))
    }

    @Test fun fillDaysKeepsAFixedWindow() {
        val f = UsageModel.fillDays(report.daily, "2026-11-16", 4)
        assertEquals(listOf("2026-11-13", "2026-11-14", "2026-11-15", "2026-11-16"), f.map { it.period })
        assertTrue(f[0].agents.isEmpty() && f[3].agents.isEmpty())
        assertEquals(1200L, f[1].tokens.total)
        assertEquals(listOf("2026-11-30", "2026-12-01"), UsageModel.fillDays(emptyList(), "2026-12-01", 2).map { it.period })
        assertEquals(listOf("2026-11-14", "2026-11-15"), UsageModel.fillDays(report.daily, "bad", 2).map { it.period })
    }

    private fun row(period: String, cost: Double) = UsageRow(period, listOf(AgentUse("claude", Tok(total = 10), cost, emptyList())))

    @Test fun fillRowsDaysGapsSinglePointAndZeros() {
        val g = Granularity.DAY
        val gaps = UsageModel.fillRows(listOf(row("2026-11-15", 3.0), row("2026-11-12", 1.0)), g, "2026-11-15", 5)   // unsorted input
        assertEquals(listOf("2026-11-11", "2026-11-12", "2026-11-13", "2026-11-14", "2026-11-15"), gaps.map { it.period })
        assertEquals(listOf(0.0, 1.0, 0.0, 0.0, 3.0), gaps.map { it.cost })
        val one = UsageModel.fillRows(listOf(row("2026-11-15", 2.0)), g, "2026-11-15", 14)
        assertEquals(14, one.size); assertEquals(2.0, one.last().cost, 0.0); assertEquals(13.0, one.count { it.agents.isEmpty() }.toDouble(), 0.0)
        val zeros = UsageModel.fillRows(emptyList(), g, "2026-11-15", 14)
        assertEquals(14, zeros.size); assertTrue(zeros.all { it.cost == 0.0 })
        assertEquals(listOf("2026-11-15"), UsageModel.fillRows(listOf(row("2025-01-01", 9.0)), g, "2026-11-15", 1).map { it.period }) // out of window ignored
        assertEquals(1, UsageModel.fillRows(listOf(row("2026-11-15", 1.0)), g, "bad", 1).size)
    }

    @Test fun fillRowsWeeksAndMonthsAlignToTheirKeys() {
        // 2026-11-15 is a Sunday: its week starts Monday 2026-11-09.
        val w = UsageModel.fillRows(listOf(row("2026-10-26", 4.0)), Granularity.WEEK, "2026-11-15", 4)
        assertEquals(listOf("2026-10-26", "2026-11-02", "2026-11-09").let { listOf("2026-10-19") + it }, w.map { it.period })
        assertEquals(listOf(0.0, 4.0, 0.0, 0.0), w.map { it.cost })
        val m = UsageModel.fillRows(listOf(row("2025-12", 5.0)), Granularity.MONTH, "2026-02-10", 4)
        assertEquals(listOf("2025-11", "2025-12", "2026-01", "2026-02"), m.map { it.period })     // crosses the year
        assertEquals(listOf(0.0, 5.0, 0.0, 0.0), m.map { it.cost })
    }

    @Test fun todayAndRecentSessions() {
        val t = UsageModel.today(report, "2026-11-15")!!
        assertEquals(10L, t.first); assertEquals(0.0, t.second, 0.0)
        assertNull(UsageModel.today(report, "2026-11-16"))
        assertEquals(listOf("sess-b", "sess-a"), UsageModel.recentSessions(report, 5).map { it.period })
        assertEquals(1, UsageModel.recentSessions(report, 1).size)
    }

    @Test fun chatThreadsMergeRuns() {
        val l = UsageModel.chatThreads(listOf(
            ChatRunUsage("t1", "Fix bug", "claude-code", 100, 0.5),
            ChatRunUsage("t1", "Fix bug", "claude-code", 50, 0.25),
            ChatRunUsage("t2", "Local", "opencode", 900, null),
            ChatRunUsage("t3", "Empty", "codex", 0, null),
        ))
        assertEquals(listOf("t1", "t2"), l.map { it.threadId })      // t3 has nothing; cost first
        assertEquals(150L, l[0].tokens); assertEquals(0.75, l[0].cost!!, 1e-9)
        assertEquals("claude", l[0].agent)
        assertNull(l[1].cost)
    }

    @Test fun toolIdsAndGating() {
        assertEquals("claude-code", UsageModel.toolId("claude")); assertEquals("claude", UsageModel.agentOf("claude-code"))
        assertEquals("agy", UsageModel.toolId("antigravity"))
        fun allowed(agent: String, pro: Boolean) = FeatureGate.canLaunchAiTool(pro, UsageModel.toolId(agent), enforce = true) is GateResult.Allow
        assertTrue(allowed("opencode", false))
        assertFalse(allowed("claude", false)); assertFalse(allowed("codex", false)); assertFalse(allowed("grok", false))
        assertTrue(allowed("claude", true))
        // a Free user's view holds opencode only
        val free = report.only { allowed(it, false) }
        assertEquals(setOf("opencode"), free.daily.flatMap { it.agents }.map { it.agent }.toSet())
    }

    @Test fun formatting() {
        assertEquals("999", UsageModel.fmtTokens(999)); assertEquals("12.3K", UsageModel.fmtTokens(12_345))
        assertEquals("1.50M", UsageModel.fmtTokens(1_500_000)); assertEquals("4.35B", UsageModel.fmtTokens(4_351_315_846))
        assertEquals("$0.00", UsageModel.fmtCost(0.0)); assertEquals("<$0.01", UsageModel.fmtCost(0.004))
        assertEquals("$2.50", UsageModel.fmtCost(2.5)); assertEquals("$1,875", UsageModel.fmtCost(1875.3))
    }

    @Test fun sinceIsFirstOfMonthFiveMonthsBack() {
        val utc = TimeZone.getTimeZone("UTC")
        val nov15 = 1_794_700_800_000L // 2026-11-15T00:00Z
        assertEquals("20260601", UsageRepository.sinceDate(utc, nov15))
        assertEquals("2026-11-15", UsageRepository.todayString(utc, nov15))
        assertEquals("20250901", UsageRepository.sinceDate(utc, 1_770_000_000_000L)) // 2026-02-02
    }

    // region pinned download

    private fun tar(vararg files: Pair<String, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        for ((name, data) in files) {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h)
            String.format("%011o", data.size).toByteArray().copyInto(h, 124)
            h[156] = '0'.code.toByte()
            o.write(h); o.write(data); o.write(ByteArray(((data.size + 511) / 512 * 512) - data.size))
        }
        o.write(ByteArray(1024))
        return o.toByteArray()
    }

    private fun gz(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test fun tgzExtractsOneFileAndChecksIntegrity() {
        val bin = ByteArray(1300) { (it % 251).toByte() }
        val t = gz(tar("package/package.json" to "{}".toByteArray(), "package/bin/ccusage" to bin))
        assertTrue(bin.contentEquals(Tgz.extract(t, "package/bin/ccusage")))
        assertNull(Tgz.extract(t, "package/bin/other"))
        assertNull(Tgz.extract(t, "../../evil"))
        val good = "sha512-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-512").digest(t))
        assertTrue(Tgz.integrityOk(t, good))
        assertFalse(Tgz.integrityOk(t + 1, good))
        assertFalse(Tgz.integrityOk(t, "sha1-abc")); assertFalse(Tgz.integrityOk(t, "sha512-!!!"))
        assertNotNull(UsageModel.VERSION)
    }

    // endregion
}
