package com.zenithblue.nativecode.usage

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/** One plan window of a CLI (5-hour session, weekly, monthly...). [usedPercent] is 0..100. */
data class LimitWindow(val id: String, val label: String, val usedPercent: Double, val resetsAtMs: Long?, val durationMins: Int? = null)

enum class LimitState { OK, NOT_SIGNED_IN, UNAVAILABLE, FREE, ERROR }

/** Plan and limit windows of one CLI, with when they were read. [message] explains every state except OK. */
data class CliLimits(
    val agent: String,
    val state: LimitState,
    val plan: String? = null,
    val windows: List<LimitWindow> = emptyList(),
    val message: String? = null,
    val fetchedAt: Long = 0,
)

/** What the claude credentials file gives: the bearer token (memory only) and the plan. Never printed. */
class ClaudeAuth(val accessToken: String, val expiresAtMs: Long, val plan: String?) {
    override fun toString() = "ClaudeAuth(redacted)"
}

/** Pure parsers and formatters for the plan-limit readers (plan 18). */
object LimitsModel {
    const val FIVE_HOUR = 300
    const val WEEK = 10080

    // ---- claude ----

    /** `~/.claude/.credentials.json` -> [ClaudeAuth], or null when there is no OAuth sign-in in it. */
    fun claudeAuth(text: String): ClaudeAuth? = runCatching {
        val o = JSONObject(text).optJSONObject("claudeAiOauth") ?: return null
        val tok = o.s("accessToken") ?: return null
        ClaudeAuth(tok, o.optLong("expiresAt", 0), claudePlan(o.s("subscriptionType").orEmpty(), o.s("rateLimitTier").orEmpty()))
    }.getOrNull()

    /** `max` + `default_claude_max_5x` -> `Max 5x`; `pro` -> `Pro`. */
    fun claudePlan(type: String, tier: String): String? {
        if (type.isBlank()) return null
        val mult = Regex("""(\d+x)""").find(tier)?.value
        return type.replaceFirstChar { it.uppercase() } + (mult?.let { " $it" } ?: "")
    }

    /**
     * Windows of the Claude usage answer (`/api/oauth/usage`, or `rate_limits` of the CLI's `get_usage`): `five_hour` and
     * `seven_day` objects with `utilization` (0-100) and `resets_at` (ISO), `seven_day_<model>` extras, a `model_scoped`
     * array, and `extra_usage` (monthly spend). Null or malformed entries are skipped.
     */
    fun parseClaude(body: String): List<LimitWindow> {
        val root = JSONObject(body)
        val o = root.optJSONObject("rate_limits") ?: root
        val out = ArrayList<LimitWindow>()
        fun add(id: String, label: String, w: JSONObject?, mins: Int?) {
            if (w == null || !w.has("utilization") || w.isNull("utilization")) return
            out += LimitWindow(id, label, pct(w.optDouble("utilization", 0.0)), isoMs(w.s("resets_at").orEmpty()), mins)
        }
        add("five_hour", "5h", o.optJSONObject("five_hour"), FIVE_HOUR)
        add("seven_day", "Weekly", o.optJSONObject("seven_day"), WEEK)
        o.keys().asSequence().filter { it.startsWith("seven_day_") && it != "seven_day_oauth_apps" }.sorted().forEach { k ->
            add(k, "Weekly " + k.removePrefix("seven_day_").replace('_', ' ').replaceFirstChar { it.uppercase() }, o.optJSONObject(k), WEEK)
        }
        o.optJSONArray("model_scoped")?.let { a ->
            for (i in 0 until a.length()) {
                val m = a.optJSONObject(i) ?: continue
                val n = m.s("display_name") ?: continue
                add("scoped_$n", "Weekly $n", m, WEEK)
            }
        }
        // newer answers carry model-scoped weekly windows in limits[]: {kind: weekly_scoped, percent, resets_at, scope.model.display_name}
        o.optJSONArray("limits")?.let { a ->
            for (i in 0 until a.length()) {
                val m = a.optJSONObject(i) ?: continue
                if (m.s("kind") != "weekly_scoped") continue
                val n = m.optJSONObject("scope")?.optJSONObject("model")?.s("display_name") ?: continue
                if (out.any { it.id == "scoped_$n" } || !m.has("percent") || m.isNull("percent")) continue
                out += LimitWindow("scoped_$n", "Weekly $n", pct(m.optDouble("percent", 0.0)), isoMs(m.s("resets_at").orEmpty()), WEEK)
            }
        }
        o.optJSONObject("extra_usage")?.takeIf { it.optBoolean("is_enabled", false) && it.has("utilization") && !it.isNull("utilization") }
            ?.let { out += LimitWindow("extra", "Extra usage", pct(it.optDouble("utilization", 0.0)), null) }
        return out
    }

    // ---- codex ----

    /**
     * `account/rateLimits/read` result: `rateLimits` (or `rateLimitsByLimitId.codex`) with `primary` and `secondary`
     * windows `{usedPercent, windowDurationMins, resetsAt}` (resetsAt in epoch seconds) and a `planType`.
     */
    fun parseCodex(res: JSONObject): Pair<String?, List<LimitWindow>> {
        val snap = res.optJSONObject("rateLimitsByLimitId")?.optJSONObject("codex") ?: res.optJSONObject("rateLimits") ?: res
        val out = ArrayList<LimitWindow>()
        for (k in listOf("primary", "secondary")) {
            val w = snap.optJSONObject(k) ?: continue
            if (!w.has("usedPercent") || w.isNull("usedPercent")) continue
            val mins = if (w.has("windowDurationMins") && !w.isNull("windowDurationMins")) w.optInt("windowDurationMins") else null
            out += LimitWindow(k, windowLabel(mins, if (k == "primary") "5h" else "Weekly"), pct(w.optDouble("usedPercent", 0.0)), epochMs(w.optLong("resetsAt", 0)), mins)
        }
        return snap.s("planType")?.let { codexPlan(it) } to out
    }

    /** `account/read` result -> plan label, or null (api key, no plan). */
    fun codexAccountPlan(res: JSONObject): String? = res.optJSONObject("account")?.s("planType")?.let { codexPlan(it) }

    private fun codexPlan(p: String) = p.replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    // ---- grok ----

    /** The `~/.grok/auth.json` entry that holds the sign-in: its property name and fields. Tokens stay in memory, never printed. */
    class GrokAuth(
        val entry: String, val accessToken: String, val refreshToken: String?, val clientId: String?, val issuer: String?,
        val expiresAtMs: Long?, val apiKey: Boolean,
    ) { override fun toString() = "GrokAuth(redacted)" }

    /** First object of `auth.json` that carries a `key`; null when signed out. */
    fun grokAuth(text: String): GrokAuth? = try {
        val root = JSONObject(text)
        var found: GrokAuth? = null
        for (k in root.keys()) {
            val o = root.optJSONObject(k) ?: continue
            val key = o.s("key") ?: continue
            found = GrokAuth(k, key, o.s("refresh_token"), o.s("oidc_client_id"), o.s("oidc_issuer"), o.s("expires_at")?.let { isoMs(it) }, o.s("auth_mode") == "api_key")
            break
        }
        found
    } catch (e: Exception) { null }

    /**
     * `~/.grok/auth.json` text with a refreshed sign-in written into [entry] (rotated refresh token, new expiry); every other
     * field and entry is kept. Null when the text or entry is not JSON.
     */
    fun grokAuthUpdated(text: String, entry: String, accessToken: String, refreshToken: String?, expiresAtMs: Long): String? = try {
        val root = JSONObject(text)
        val o = root.getJSONObject(entry)
        o.put("key", accessToken)
        if (refreshToken != null) o.put("refresh_token", refreshToken)
        o.put("expires_at", java.time.Instant.ofEpochMilli(expiresAtMs).toString())
        root.toString(2)
    } catch (e: Exception) { null }

    /** Version of the installed Grok CLI from `~/.grok/version.json` (the billing proxy refuses unknown or too old identities). */
    fun grokVersion(text: String?): String = runCatching { JSONObject(text!!).s("version") }.getOrNull()?.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) } ?: "1.0.77"

    /** Numeric field, or the `{val: n}` wrapper Grok's protobuf-JSON uses; null when missing. */
    private fun JSONObject.num(k: String): Double? {
        if (!has(k) || isNull(k)) return null
        val v = opt(k)
        val x = if (v is JSONObject) (if (v.has("val") && !v.isNull("val")) v.opt("val") else null) else v
        return (x as? Number)?.toDouble() ?: (x as? String)?.toDoubleOrNull()
    }

    /**
     * `https://cli-chat-proxy.grok.com/v1/billing?format=credits`: `config.creditUsagePercent` and `config.currentPeriod{type,start,end}`
     * (the subscription window, id `subscription`), `monthlyLimit`/`includedUsed` (Monthly) and `onDemandCap`/`onDemandUsed` (On-demand,
     * only when the cap is above zero). Numbers may be `{val}` wrapped.
     */
    fun parseGrok(body: String): List<LimitWindow> {
        val cfg = JSONObject(body).optJSONObject("config") ?: return emptyList()
        val period = cfg.optJSONObject("currentPeriod")
        val type = period?.s("type").orEmpty().removePrefix("USAGE_PERIOD_TYPE_")
        val start = isoMs(period?.s("start").orEmpty())
        val end = isoMs(period?.s("end").orEmpty()) ?: isoMs(cfg.s("billingPeriodEnd").orEmpty())
        val mins = if (start != null && end != null && end > start) ((end - start) / 60_000).toInt() else null
        val out = ArrayList<LimitWindow>()
        val sub = cfg.num("creditUsagePercent") ?: if ((type == "WEEKLY" || type == "MONTHLY") && start != null && end != null && start < end) 0.0 else null // a zero percentage is omitted
        if (sub != null) out += LimitWindow("subscription", when (type) { "WEEKLY" -> "Weekly"; "MONTHLY" -> "Monthly"; else -> "Credits" }, pct(sub), end, mins)
        cfg.num("monthlyLimit")?.takeIf { it > 0 }?.let { lim ->
            out += LimitWindow("monthly", "Monthly", pct((cfg.num("includedUsed") ?: cfg.num("totalUsed") ?: 0.0) / lim * 100), end, 43200)
        }
        cfg.num("onDemandCap")?.takeIf { it > 0 }?.let { cap ->
            out += LimitWindow("on_demand", "On-demand", pct((cfg.num("onDemandUsed") ?: 0.0) / cap * 100), end, mins)
        }
        return out
    }

    /**
     * gRPC-web answer of `grok_api_v2.GrokBuildBilling/GetGrokCreditsConfig` (the SuperGrok weekly pool the CLI's `/usage` shows):
     * field 1 holds `{1: fixed32 usage ratio 0..1 (omitted = 0), 5: Timestamp reset}`. Returns percent used and the reset in epoch ms,
     * or null when [b] is not that message.
     */
    fun parseGrokCredits(b: ByteArray): Pair<Double, Long?>? {
        try {
            var payload = b
            val flag = if (b.isNotEmpty()) b[0].toInt() and 0xff else -1
            if (b.size >= 5 && (flag == 0 || flag == 1 || flag == 0x80 || flag == 0x81)) { // gRPC-web frames: flag + u32 length
                var o = 0
                var data: ByteArray? = null
                while (o + 5 <= b.size && data == null) {
                    val f = b[o].toInt() and 0xff
                    val len = ((b[o + 1].toInt() and 0xff) shl 24) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 8) or (b[o + 4].toInt() and 0xff)
                    if (len < 0 || o + 5 + len > b.size) return null
                    if (f and 0x80 == 0) data = b.copyOfRange(o + 5, o + 5 + len)
                    o += 5 + len
                }
                payload = data ?: return null
            }
            val info = pbFields(pbFields(payload)?.get(1)?.bytes ?: return null) ?: return null
            val r = info[1]
            val ratio = when (r?.wire) { null -> 0.0; 5 -> Float.fromBits(r!!.value.toInt()).toDouble(); 1 -> Double.fromBits(r!!.value); else -> return null }
            if (ratio.isNaN() || ratio < 0) return null
            val ts = info[5]?.bytes?.let { pbFields(it) }
            val secs = ts?.get(1)?.value ?: 0L
            val nanos = ts?.get(2)?.value ?: 0L
            return pct(ratio * 100) to (if (secs > 0) secs * 1000 + nanos / 1_000_000 else null)
        } catch (e: Exception) { return null }
    }

    private class Pb(val wire: Int, val value: Long, val bytes: ByteArray?)

    /** One level of protobuf wire fields by number (last wins); null on malformed input. */
    private fun pbFields(b: ByteArray): Map<Int, Pb>? {
        val m = HashMap<Int, Pb>()
        var i = 0
        fun varint(): Long? {
            var r = 0L
            var sh = 0
            while (i < b.size && sh <= 63) {
                val x = b[i++].toInt() and 0xff
                r = r or ((x and 0x7f).toLong() shl sh)
                if (x and 0x80 == 0) return r
                sh += 7
            }
            return null
        }
        fun fixed(n: Int): Long? {
            if (i + n > b.size) return null
            var v = 0L
            for (k in n - 1 downTo 0) v = (v shl 8) or (b[i + k].toLong() and 0xff)
            i += n
            return v
        }
        while (i < b.size) {
            val tag = varint() ?: return null
            val f = (tag ushr 3).toInt()
            if (f == 0) return null
            m[f] = when (val w = (tag and 7).toInt()) {
                0 -> Pb(0, varint() ?: return null, null)
                1 -> Pb(1, fixed(8) ?: return null, null)
                5 -> Pb(5, fixed(4) ?: return null, null)
                2 -> {
                    val n = varint() ?: return null
                    if (n < 0 || i + n > b.size) return null
                    Pb(w, 0, b.copyOfRange(i, i + n.toInt())).also { i += n.toInt() }
                }
                else -> return null
            }
        }
        return m
    }

    /** Plan name from `/v1/user` (`subscriptionTier` like `GrokPro` -> `Grok Pro`), else the `tier` claim of the access token JWT. */
    fun grokPlan(userBody: String?, accessToken: String?): String? {
        val t = runCatching { JSONObject(userBody!!).s("subscriptionTier") }.getOrNull()
        if (t != null) return if (t.matches(Regex("(?i)(none|null|free)"))) "Free" else t.replace(Regex("[_-]+"), " ").replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").trim()
        return runCatching {
            val p = String(java.util.Base64.getUrlDecoder().decode(accessToken!!.split('.')[1]))
            when (JSONObject(p).optInt("tier", -1)) {
                0 -> "Free"; 1 -> "SuperGrok"; 2 -> "X Basic"; 3 -> "X Premium"; 4 -> "X Premium Plus"; 5 -> "SuperGrok Heavy"; 6 -> "SuperGrok Lite"; else -> null
            }
        }.getOrNull()
    }

    // ---- antigravity ----

    /** `~/.gemini/antigravity-cli/antigravity-oauth-token`: `{token:{access_token, refresh_token, expiry}}`. Tokens stay in memory, never printed. */
    class AgyAuth(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long?) { override fun toString() = "AgyAuth(redacted)" }

    fun agyAuth(text: String): AgyAuth? = try {
        val t = JSONObject(text).optJSONObject("token")
        val a = t?.s("access_token")
        if (t == null || a == null) null else AgyAuth(a, t.s("refresh_token"), t.s("expiry")?.let { isoMs(it) })
    } catch (e: Exception) { null }

    /** Plan of `loadCodeAssist`: the paid tier name (`Google AI Pro`), or `Free` for the free tier. */
    fun antigravityPlan(body: String): String? = try {
        val o = JSONObject(body)
        o.optJSONObject("paidTier")?.s("name") ?: o.optJSONObject("currentTier")?.let { if (it.s("id") == "free-tier") "Free" else it.s("name") }
    } catch (e: Exception) { null }

    /** Project id of `loadCodeAssist` (`cloudaicompanionProject`, a string or `{id}`); null when none. */
    fun antigravityProject(body: String): String? = try {
        val o = JSONObject(body)
        o.optJSONObject("cloudaicompanionProject")?.s("id") ?: if (o.opt("cloudaicompanionProject") is String) o.s("cloudaicompanionProject") else null
    } catch (e: Exception) { null }

    /**
     * `retrieveUserQuotaSummary`: `groups[]` of model families, each with `buckets[]` `{window: 5h|weekly, remainingFraction 0..1, resetTime, disabled}`.
     * One window per bucket labelled `<family> 5h` / `<family> Weekly`; a disabled 5h bucket counts as used up, a disabled weekly bucket is skipped.
     */
    fun parseAntigravity(body: String): List<LimitWindow> {
        val groups = JSONObject(body).let { it.optJSONArray("groups") ?: it.optJSONObject("quotaSummary")?.optJSONArray("groups") } ?: return emptyList()
        val out = ArrayList<LimitWindow>()
        for (i in 0 until groups.length()) {
            val g = groups.optJSONObject(i) ?: continue
            val fam = (g.s("displayName") ?: "Models").replace(Regex("(?i)\\s+models?$"), "").replace(Regex("(?i)\\band\\b"), "&")
            val buckets = g.optJSONArray("buckets") ?: continue
            val ws = ArrayList<LimitWindow>()
            for (j in 0 until buckets.length()) {
                val b = buckets.optJSONObject(j) ?: continue
                val text = (b.s("window").orEmpty() + " " + b.s("bucketId").orEmpty() + " " + b.s("displayName").orEmpty()).lowercase()
                val (mins, label) = when {
                    "weekly" in text -> WEEK to "Weekly"
                    "5h" in text || "five hour" in text -> FIVE_HOUR to "5h"
                    "daily" in text -> 1440 to "Daily"
                    else -> continue
                }
                val disabled = b.optBoolean("disabled", false)
                if (disabled && mins == WEEK) continue
                val rem = if (disabled) 0.0 else b.num("remainingFraction") ?: continue
                ws += LimitWindow(b.s("bucketId") ?: "$fam $label", "$fam $label", pct((1 - rem) * 100), isoMs(b.s("resetTime").orEmpty()), mins)
            }
            out += ws.sortedBy { it.durationMins ?: Int.MAX_VALUE }
        }
        return out
    }

    /** Distinct Google OAuth client secrets (`GOCSPX-` + 28) found in a stream, scanned in chunks; at most [max]. */
    fun scanClientSecrets(input: java.io.InputStream, max: Int = 8): List<String> {
        val re = Regex("GOCSPX-[A-Za-z0-9_-]{28}")
        val found = LinkedHashSet<String>()
        val buf = ByteArray(1 shl 20)
        var carry = ""
        while (found.size < max) {
            val n = input.read(buf)
            if (n < 0) break
            val s = carry + String(buf, 0, n, Charsets.ISO_8859_1)
            re.findAll(s).forEach { found += it.value }
            carry = s.takeLast(40)
        }
        return found.toList().take(max)
    }

    // ---- shared ----

    /** String field or null when missing, JSON null or blank (Android's optString turns JSON null into "null"). */
    private fun JSONObject.s(k: String): String? = if (has(k) && !isNull(k)) optString(k).ifBlank { null } else null

    fun pct(v: Double): Double = if (v.isNaN()) 0.0 else v.coerceIn(0.0, 100.0)

    /** `300` -> `5h`, `10080` -> `Weekly`, `1440` -> `1d`; [fallback] when the length is unknown. */
    fun windowLabel(mins: Int?, fallback: String): String = when {
        mins == null || mins <= 0 -> fallback
        mins == FIVE_HOUR -> "5h"
        mins == WEEK -> "Weekly"
        mins in 43200..44640 -> "Monthly"
        mins % 1440 == 0 -> "${mins / 1440}d"
        mins % 60 == 0 -> "${mins / 60}h"
        else -> "${mins}m"
    }

    fun isoMs(s: String): Long? = if (s.isBlank()) null else runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

    /** Epoch seconds, or milliseconds when the value is already that large. 0 = unknown. */
    fun epochMs(v: Long): Long? = when { v <= 0 -> null; v > 100_000_000_000L -> v; else -> v * 1000 }

    /** `2h 14m`, `3d 4h`, `45m`, `now` for a reset [resetsAtMs] seen at [nowMs]. */
    fun resetIn(resetsAtMs: Long, nowMs: Long): String {
        val m = ((resetsAtMs - nowMs) / 60_000).coerceAtLeast(0)
        return when {
            resetsAtMs <= nowMs -> "now"
            m >= 1440 -> "${m / 1440}d ${(m % 1440) / 60}h"
            m >= 60 -> "${m / 60}h ${m % 60}m"
            else -> "${m.coerceAtLeast(1)}m"
        }
    }

    /** The window closest to its limit across signed-in CLIs, labelled `Claude 5h`; null when none reports one. */
    fun mostConstrained(limits: List<CliLimits>, allowed: (String) -> Boolean = { true }): Pair<String, LimitWindow>? =
        limits.filter { it.state == LimitState.OK && allowed(it.agent) }
            .flatMap { l -> l.windows.map { UsageModel.agentLabel(l.agent).substringBefore(' ') + " " + it.label to it } }
            .maxByOrNull { it.second.usedPercent }

    // ---- cache ----

    fun encode(list: List<CliLimits>): String = JSONArray().also { a ->
        list.forEach { l ->
            a.put(JSONObject().put("agent", l.agent).put("state", l.state.name).putOpt("plan", l.plan).putOpt("message", l.message).put("at", l.fetchedAt)
                .put("windows", JSONArray().also { w -> l.windows.forEach { x ->
                    w.put(JSONObject().put("id", x.id).put("label", x.label).put("used", x.usedPercent).putOpt("reset", x.resetsAtMs).putOpt("mins", x.durationMins))
                } }))
        }
    }.toString()

    fun decode(text: String): List<CliLimits> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val st = runCatching { LimitState.valueOf(o.optString("state")) }.getOrNull() ?: return@mapNotNull null
            val ws = o.optJSONArray("windows")?.let { w ->
                (0 until w.length()).mapNotNull { j ->
                    val x = w.optJSONObject(j) ?: return@mapNotNull null
                    LimitWindow(x.optString("id"), x.optString("label"), pct(x.optDouble("used", 0.0)),
                        if (x.has("reset")) x.optLong("reset") else null, if (x.has("mins")) x.optInt("mins") else null)
                }
            } ?: emptyList()
            CliLimits(o.optString("agent"), st, o.s("plan"), ws, o.s("message"), o.optLong("at", 0))
        }
    }.getOrDefault(emptyList())
}
