package com.zenithblue.nativecode.usage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitsTest {
    // region claude

    private val claudeBody = """
    {"five_hour":{"utilization":37.0,"resets_at":"2026-11-15T14:59:59.943648+00:00"},
     "seven_day":{"utilization":26.5,"resets_at":"2026-11-20T08:00:00+00:00"},
     "seven_day_oauth_apps":null,"seven_day_opus":{"utilization":5.0,"resets_at":null},"seven_day_sonnet":null,
     "extra_usage":{"is_enabled":true,"monthly_limit":2000,"used_credits":500.0,"utilization":25.0}}
    """.trimIndent()

    @Test fun claudeOauthUsage() {
        val w = LimitsModel.parseClaude(claudeBody)
        assertEquals(listOf("5h", "Weekly", "Weekly Opus", "Extra usage"), w.map { it.label })
        assertEquals(37.0, w[0].usedPercent, 1e-9)
        assertEquals(LimitsModel.FIVE_HOUR, w[0].durationMins)
        assertEquals(1794754799943L, w[0].resetsAtMs)
        assertNull(w[2].resetsAtMs)
        assertEquals(25.0, w[3].usedPercent, 1e-9)
    }

    @Test fun claudeGetUsageShapeAndNoise() {
        val w = LimitsModel.parseClaude("""{"rate_limits_available":true,"rate_limits":{"five_hour":{"utilization":120,"resets_at":"2026-11-15T10:00:00Z"},
            "model_scoped":[{"display_name":"Fable","utilization":10,"resets_at":"2026-11-18T00:00:00Z"},{"utilization":1}],
            "extra_usage":{"is_enabled":false,"utilization":50}}}""")
        assertEquals(listOf("5h", "Weekly Fable"), w.map { it.label })
        assertEquals(100.0, w[0].usedPercent, 0.0)                      // clamped
        val sc = LimitsModel.parseClaude("""{"five_hour":{"utilization":1},"limits":[{"kind":"weekly_scoped","percent":55,"resets_at":"2026-11-18T00:00:00Z","scope":{"model":{"display_name":"Fable"}}},
            {"kind":"weekly_scoped","percent":9,"scope":{"model":{"display_name":"Fable"}}},{"kind":"other","percent":3,"scope":{"model":{"display_name":"X"}}},{"kind":"weekly_scoped","percent":3}]}""")
        assertEquals(listOf("5h", "Weekly Fable"), sc.map { it.label }); assertEquals(55.0, sc[1].usedPercent, 0.0)   // duplicates, other kinds and unscoped entries skipped
        assertTrue(LimitsModel.parseClaude("{}").isEmpty())
        assertTrue(LimitsModel.parseClaude("""{"five_hour":{"resets_at":"x"},"seven_day":{"utilization":null}}""").isEmpty())
        assertTrue(runCatching { LimitsModel.parseClaude("nope") }.isFailure)
    }

    @Test fun claudeAuthReadsPlanAndNeverPrintsToken() {
        val a = LimitsModel.claudeAuth("""{"claudeAiOauth":{"accessToken":"sk-ant-secret","refreshToken":"r","expiresAt":1794711599000,"subscriptionType":"max","rateLimitTier":"default_claude_max_5x"}}""")!!
        assertEquals("Max 5x", a.plan)
        assertEquals(1794711599000L, a.expiresAtMs)
        assertFalse(a.toString().contains("secret"))
        assertEquals("Pro", LimitsModel.claudeAuth("""{"claudeAiOauth":{"accessToken":"t","subscriptionType":"pro","rateLimitTier":null}}""")!!.plan)
        assertNull(LimitsModel.claudeAuth("""{"claudeAiOauth":{"refreshToken":"r"}}"""))
        assertNull(LimitsModel.claudeAuth("""{}""")); assertNull(LimitsModel.claudeAuth("garbage"))
    }

    // endregion

    // region codex

    @Test fun codexRateLimits() {
        val (plan, w) = LimitsModel.parseCodex(JSONObject("""{"rateLimits":{"limitId":"codex","primary":{"usedPercent":42,"windowDurationMins":300,"resetsAt":1800000000},
            "secondary":{"usedPercent":13,"windowDurationMins":10080,"resetsAt":1800500000},"credits":null,"planType":"plus"}}"""))
        assertEquals("Plus", plan)
        assertEquals(listOf("5h", "Weekly"), w.map { it.label })
        assertEquals(42.0, w[0].usedPercent, 0.0)
        assertEquals(1800000000_000L, w[0].resetsAtMs)
        assertEquals(10080, w[1].durationMins)
    }

    @Test fun codexByLimitIdOddWindowsAndAccount() {
        val (plan, w) = LimitsModel.parseCodex(JSONObject("""{"rateLimitsByLimitId":{"codex":{"primary":{"usedPercent":5.5,"windowDurationMins":1440,"resetsAt":1800000000000},"secondary":null,"planType":"pro_lite"}},
            "rateLimits":{"primary":{"usedPercent":99}}}"""))
        assertEquals("Pro Lite", plan)
        assertEquals(1, w.size)
        assertEquals("1d", w[0].label)
        assertEquals(1800000000000L, w[0].resetsAtMs)                    // already milliseconds
        val (p2, w2) = LimitsModel.parseCodex(JSONObject("""{"rateLimits":{"primary":{"usedPercent":1}}}"""))
        assertNull(p2); assertEquals("5h", w2.single().label)            // no duration: primary = 5h
        assertEquals("Plus", LimitsModel.codexAccountPlan(JSONObject("""{"account":{"type":"chatgpt","planType":"plus"}}""")))
        assertNull(LimitsModel.codexAccountPlan(JSONObject("""{"account":{"type":"apiKey","planType":null}}""")))
        assertTrue(LimitsModel.parseCodex(JSONObject("{}")).second.isEmpty())
    }

    // endregion

    // region grok

    @Test fun grokBilling() {
        val w = LimitsModel.parseGrok("""{"config":{"creditUsagePercent":12.5,"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","start":"2026-11-10T00:00:00Z","end":"2026-11-17T00:00:00Z"}}}""").single()
        assertEquals("Weekly", w.label); assertEquals(12.5, w.usedPercent, 0.0); assertEquals(10080, w.durationMins)
        assertEquals(LimitsModel.isoMs("2026-11-17T00:00:00Z"), w.resetsAtMs)
        val z = LimitsModel.parseGrok("""{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_MONTHLY","start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"}}}""").single()
        assertEquals(0.0, z.usedPercent, 0.0); assertEquals("Monthly", z.label)   // zero is omitted by Grok
        assertTrue(LimitsModel.parseGrok("{}").isEmpty())
        assertTrue(LimitsModel.parseGrok("""{"config":{"currentPeriod":{"type":"OTHER"}}}""").isEmpty())
    }

    // Recorded from a real signed-in Grok Pro account, redacted (ids, email, name removed).
    private val grokBilling = """{"config":{"currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY","start":"2026-11-14T03:14:55.358912+00:00","end":"2026-11-21T03:14:55.358912+00:00"},
        "onDemandCap":{"val":0},"onDemandUsed":{"val":0},"isUnifiedBillingUser":true,"prepaidBalance":{"val":0},"topUpMethod":"TOP_UP_METHOD_INLINE_PAYMENT_METHOD",
        "billingPeriodStart":"2026-11-14T03:14:55.358912+00:00","billingPeriodEnd":"2026-11-21T03:14:55.358912+00:00"}}"""
    private val grokUser = """{"userId":"u-redacted","email":"user@example.com","firstName":"X","teamId":null,"hasGrokCodeAccess":true,"subscriptionTier":"GrokPro"}"""

    // GetGrokCreditsConfig answer: data frame {1:{2:"",3:"",4:ts,5:ts(reset 2026-11-21T03:14:55.358912Z),8:..}} (ratio omitted = 0) + grpc-status:0 trailer.
    private val grokGrpcHex = "00000000480a4612001a00220c08afa4dfd7061080a092ab012a0c08af9984d8061080a092ab01421e0802120c08afa4dfd7061080a092ab011a0c08af9984d8061080a092ab01580162006802800000000f677270632d7374617475733a300d0a"
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun grokBillingRecordedAndWrapped() {
        val w = LimitsModel.parseGrok(grokBilling).single()                  // cap 0: no on-demand ring; the weekly period gives a 0% window
        assertEquals("Weekly", w.label); assertEquals(0.0, w.usedPercent, 0.0)
        assertEquals(LimitsModel.isoMs("2026-11-21T03:14:55.358912+00:00"), w.resetsAtMs); assertEquals(10080, w.durationMins)
        val m = LimitsModel.parseGrok("""{"config":{"creditUsagePercent":{"val":40},"monthlyLimit":{"val":200},"includedUsed":{"val":50},"onDemandCap":{"val":10},"onDemandUsed":{"val":2.5},
            "currentPeriod":{"type":"USAGE_PERIOD_TYPE_MONTHLY","start":"2026-11-01T00:00:00Z","end":"2026-12-01T00:00:00Z"}}}""")
        assertEquals(listOf("Monthly", "Monthly", "On-demand"), m.map { it.label })
        assertEquals(listOf(40.0, 25.0, 25.0), m.map { it.usedPercent })
    }

    @Test fun grokCreditPoolFromGrpcWeb() {
        val (pct, reset) = LimitsModel.parseGrokCredits(hex(grokGrpcHex))!!
        assertEquals(0.0, pct, 0.0); assertEquals(1795230895_358L, reset)
        // unframed payload with a fixed32 ratio of 0.35 and no reset
        val ratio = java.lang.Float.floatToIntBits(0.35f)
        val payload = byteArrayOf(0x0a, 5, 0x0d, ratio.toByte(), (ratio shr 8).toByte(), (ratio shr 16).toByte(), (ratio shr 24).toByte())
        val (p2, r2) = LimitsModel.parseGrokCredits(payload)!!
        assertEquals(35.0, p2, 0.01); assertNull(r2)
        assertNull(LimitsModel.parseGrokCredits(ByteArray(0))); assertNull(LimitsModel.parseGrokCredits("<!doctype html>".toByteArray()))
        assertNull(LimitsModel.parseGrokCredits(hex("00000000ff0a")))        // frame longer than the body
        assertNull(LimitsModel.parseGrokCredits(hex("0a03120000")))          // truncated field
    }

    @Test fun grokPlanAuthAndRefreshWriteback() {
        assertEquals("Grok Pro", LimitsModel.grokPlan(grokUser, null))
        assertEquals("Free", LimitsModel.grokPlan("""{"subscriptionTier":"free"}""", null))
        val jwt = "e30." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"tier":1}""".toByteArray()) + ".sig"
        assertEquals("SuperGrok", LimitsModel.grokPlan("{}", jwt)); assertNull(LimitsModel.grokPlan(null, "garbage"))
        val file = """{"https://auth.x.ai::cid":{"key":"tok-secret","auth_mode":"oidc","email":"user@example.com","refresh_token":"ref-secret","expires_at":"2026-11-14T20:01:35.692150971Z","oidc_issuer":"https://auth.x.ai","oidc_client_id":"cid"}}"""
        val a = LimitsModel.grokAuth(file)!!
        assertEquals("https://auth.x.ai::cid", a.entry); assertEquals("cid", a.clientId); assertFalse(a.apiKey)
        assertEquals(LimitsModel.isoMs("2026-11-14T20:01:35.692150971Z"), a.expiresAtMs); assertFalse(a.toString().contains("secret"))
        assertTrue(LimitsModel.grokAuth("""{"x":{"auth_mode":"api_key","key":"k"}}""")!!.apiKey)
        assertNull(LimitsModel.grokAuth("{}")); assertNull(LimitsModel.grokAuth("nope"))
        val out = JSONObject(LimitsModel.grokAuthUpdated(file, a.entry, "new-access", "new-refresh", 1_800_000_000_000L)!!).getJSONObject(a.entry)
        assertEquals("new-access", out.getString("key")); assertEquals("new-refresh", out.getString("refresh_token"))
        assertEquals("user@example.com", out.getString("email")); assertEquals("cid", out.getString("oidc_client_id"))   // the rest is kept
        assertTrue(out.getString("expires_at").startsWith("2027-01-15T08:00:00"))
        assertNull(LimitsModel.grokAuthUpdated(file, "missing", "a", null, 1))
        assertEquals("1.0.77", LimitsModel.grokVersion("""{"version":"1.0.77","stable_version":null}""")); assertEquals("1.0.77", LimitsModel.grokVersion("junk"))
        assertEquals("1.2.3", LimitsModel.grokVersion("""{"version":"1.2.3"}"""))
    }

    // endregion

    // region antigravity

    // Recorded from a real Google AI Pro sign-in, redacted.
    private val agySummary = """{"groups":[
      {"buckets":[{"bucketId":"gemini-weekly","displayName":"Weekly Limit Remaining","window":"weekly","resetTime":"2026-11-20T23:13:09Z","remainingFraction":0.98702484},
                  {"bucketId":"gemini-5h","displayName":"Five Hour Limit Remaining","window":"5h","resetTime":"2026-11-15T15:42:44Z","remainingFraction":1}],
       "displayName":"Gemini Models","description":"Models within this group: Gemini 3.8 Flash"},
      {"buckets":[{"bucketId":"3p-weekly","displayName":"Weekly Limit Remaining","window":"weekly","resetTime":"2026-11-21T17:08:18Z","remainingFraction":0.9990896},
                  {"bucketId":"3p-5h","displayName":"Five Hour Limit Remaining","window":"5h","resetTime":"2026-11-15T15:42:44Z","remainingFraction":1}],
       "displayName":"Claude and GPT models"}],"description":"x"}"""
    private val agyLoad = """{"currentTier":{"id":"free-tier","name":"Antigravity"},"cloudaicompanionProject":"aicode-consumers","paidTier":{"id":"g1-pro-tier","name":"Google AI Pro"}}"""

    @Test fun antigravityQuotaSummary() {
        val w = LimitsModel.parseAntigravity(agySummary)
        assertEquals(listOf("Gemini 5h", "Gemini Weekly", "Claude & GPT 5h", "Claude & GPT Weekly"), w.map { it.label })   // 5h first inside a family
        assertEquals(0.0, w[0].usedPercent, 0.0); assertEquals(1.297516, w[1].usedPercent, 1e-4)
        assertEquals(LimitsModel.isoMs("2026-11-15T15:42:44Z"), w[0].resetsAtMs); assertEquals(LimitsModel.FIVE_HOUR, w[0].durationMins); assertEquals(LimitsModel.WEEK, w[1].durationMins)
        assertEquals("gemini-weekly", w[1].id)
    }

    @Test fun antigravityDisabledMissingAndEmpty() {
        val w = LimitsModel.parseAntigravity("""{"groups":[{"displayName":"Gemini Models","buckets":[
            {"bucketId":"a-5h","window":"5h","disabled":true,"resetTime":"2026-11-15T15:00:00Z"},{"bucketId":"a-weekly","window":"weekly","disabled":true},
            {"bucketId":"b-weekly","window":"weekly"},{"bucketId":"c-weekly","window":"weekly","remainingFraction":0.25},{"bucketId":"d","window":"yearly","remainingFraction":1}]}]}""")
        assertEquals(listOf("Gemini 5h", "Gemini Weekly"), w.map { it.label })       // disabled 5h = used up; disabled weekly, no fraction and unknown windows skipped
        assertEquals(100.0, w[0].usedPercent, 0.0); assertEquals(75.0, w[1].usedPercent, 0.0)
        assertEquals(1, LimitsModel.parseAntigravity("""{"quotaSummary":{"groups":[{"displayName":"X","buckets":[{"window":"5h","remainingFraction":0.5}]}]}}""").size)
        assertTrue(LimitsModel.parseAntigravity("{}").isEmpty()); assertTrue(runCatching { LimitsModel.parseAntigravity("nope") }.isFailure)
    }

    @Test fun antigravityPlanProjectAuthAndSecrets() {
        assertEquals("Google AI Pro", LimitsModel.antigravityPlan(agyLoad)); assertEquals("aicode-consumers", LimitsModel.antigravityProject(agyLoad))
        assertEquals("Free", LimitsModel.antigravityPlan("""{"currentTier":{"id":"free-tier","name":"Antigravity"}}"""))
        assertEquals("Std", LimitsModel.antigravityPlan("""{"currentTier":{"id":"standard-tier","name":"Std"}}"""))
        assertEquals("p1", LimitsModel.antigravityProject("""{"cloudaicompanionProject":{"id":"p1"}}"""))
        assertNull(LimitsModel.antigravityPlan("nope")); assertNull(LimitsModel.antigravityProject("{}"))
        val a = LimitsModel.agyAuth("""{"token":{"access_token":"ya29.secret","token_type":"Bearer","refresh_token":"1//secret","expiry":"2026-11-14T19:06:45.40285624Z","expires_in":3599},"auth_method":"consumer"}""")!!
        assertEquals("ya29.secret", a.accessToken); assertEquals("1//secret", a.refreshToken); assertEquals(LimitsModel.isoMs("2026-11-14T19:06:45.40285624Z"), a.expiresAtMs)
        assertFalse(a.toString().contains("secret")); assertNull(LimitsModel.agyAuth("{}")); assertNull(LimitsModel.agyAuth("nope"))
        // a secret split across two read chunks is still found; look-alikes and duplicates are not
        val s1 = "GOCSPX-" + "a".repeat(28); val s2 = "GOCSPX-" + "B_-".repeat(9) + "c"
        val bin = ByteArray((1 shl 20) - 10) { 0 } + s1.toByteArray() + ByteArray(50) + s2.toByteArray() + " GOCSPX-short ".toByteArray() + s1.toByteArray()
        assertEquals(listOf(s1, s2), LimitsModel.scanClientSecrets(bin.inputStream()))
        assertEquals(1, LimitsModel.scanClientSecrets(bin.inputStream(), max = 1).size)
    }

    // endregion

    // region shared

    @Test fun formatting() {
        assertEquals("5h", LimitsModel.windowLabel(300, "x")); assertEquals("Weekly", LimitsModel.windowLabel(10080, "x"))
        assertEquals("Monthly", LimitsModel.windowLabel(43200, "x")); assertEquals("3d", LimitsModel.windowLabel(4320, "x"))
        assertEquals("2h", LimitsModel.windowLabel(120, "x")); assertEquals("45m", LimitsModel.windowLabel(45, "x")); assertEquals("x", LimitsModel.windowLabel(null, "x"))
        val now = 1_000_000_000L
        assertEquals("now", LimitsModel.resetIn(now - 5, now))
        assertEquals("1m", LimitsModel.resetIn(now + 10_000, now))
        assertEquals("45m", LimitsModel.resetIn(now + 45 * 60_000, now))
        assertEquals("2h 14m", LimitsModel.resetIn(now + (2 * 60 + 14) * 60_000L, now))
        assertEquals("3d 4h", LimitsModel.resetIn(now + (3 * 1440 + 4 * 60) * 60_000L, now))
    }

    private fun ok(agent: String, vararg p: Pair<String, Double>) = CliLimits(agent, LimitState.OK, "Plus", p.map { LimitWindow(it.first, it.first, it.second, 5_000L) }, fetchedAt = 7)

    @Test fun mostConstrainedAndCache() {
        val l = listOf(ok("claude", "5h" to 62.0, "Weekly" to 20.0), ok("codex", "5h" to 10.0, "Weekly" to 80.0),
            CliLimits("grok", LimitState.NOT_SIGNED_IN, message = "Not signed in."), CliLimits("opencode", LimitState.FREE))
        val w = LimitsModel.mostConstrained(l)!!
        assertEquals("Codex Weekly", w.first); assertEquals(80.0, w.second.usedPercent, 0.0)
        assertEquals("Claude 5h", LimitsModel.mostConstrained(l) { it == "claude" }!!.first)
        assertNull(LimitsModel.mostConstrained(l.drop(2)))
        assertEquals(l, LimitsModel.decode(LimitsModel.encode(l)))
        assertTrue(LimitsModel.decode("not json").isEmpty())
        assertNotNull(LimitsModel.decode(LimitsModel.encode(emptyList())))
    }

    // endregion

    // region hero tiles

    private fun day(date: String, agent: String, cost: Double, tok: Long) =
        UsageRow(date, listOf(AgentUse(agent, Tok(total = tok), cost, emptyList())))

    @Test fun heroDeltasAndSparklines() {
        val rep = UsageReport(listOf(
            day("2026-11-14", "claude", 2.0, 1000), day("2026-11-15", "claude", 3.0, 3000), day("2026-11-15", "opencode", 0.0, 500).let { r -> r },
            day("2026-11-05", "codex", 1.0, 100),
        ).groupBy { it.period }.map { (p, l) -> UsageRow(p, l.flatMap { it.agents }) }, emptyList(), emptyList(), emptyList())
        val h = UsageModel.hero(rep, "2026-11-15")
        assertEquals(3.0, h.todayCost.value, 1e-9); assertEquals(2.0, h.todayCost.prev, 1e-9)
        assertEquals(50.0, h.todayCost.deltaPct!!, 1e-9); assertEquals("+50%", UsageModel.fmtDelta(h.todayCost))
        assertEquals(3500.0, h.todayTokens.value, 0.0)
        assertEquals(5.0, h.weekCost.value, 1e-9); assertEquals(1.0, h.weekCost.prev, 1e-9)   // 11-09..11-15 vs 11-02..11-08
        assertEquals(Granularity.DAY.bars, h.weekCost.spark.size); assertEquals(3.0, h.weekCost.spark.last(), 1e-9); assertEquals(0.0, h.weekCost.spark.first(), 0.0)
        assertEquals(Granularity.MONTH.bars, UsageModel.hero(rep, "2026-11-15", Granularity.MONTH).todayCost.spark.size)
        assertEquals(List(Granularity.WEEK.bars) { 0.0 }, UsageModel.hero(UsageReport(emptyList(), emptyList(), emptyList(), emptyList()), "2026-11-15", Granularity.WEEK).todayCost.spark)
        assertEquals(2.0, h.activeClis.value, 0.0); assertEquals(1.0, h.activeClis.prev, 0.0)
        assertEquals("", UsageModel.fmtDelta(UsageModel.Stat(0.0, 0.0, emptyList())))
        assertEquals("new", UsageModel.fmtDelta(UsageModel.Stat(1.0, 0.0, emptyList())))
        assertEquals("-50%", UsageModel.fmtDelta(UsageModel.Stat(1.0, 2.0, emptyList())))
    }

    // endregion
}
