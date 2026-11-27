package com.zenithblue.nativecode.telemetry

import android.content.Context

/**
 * F-Droid flavor: no analytics, no crash reporting, nothing leaves the device. Same surface as the play
 * Telemetry so callers compile unchanged; every call is a no-op and the consent sheet never shows.
 */
object Telemetry {
    const val KEY_CRASH = "consent_crash_reports"
    const val KEY_USAGE = "consent_usage_stats"
    const val KEY_ASKED = "consent_asked"

    enum class Event(val id: String) {
        SETUP_COMPLETE("setup_complete"),
        FIRST_AI_SESSION("first_ai_session"),
        AI_SESSION_START("ai_session_start"),
        FREE_LIMIT_HIT("free_limit_hit"),
        PAYWALL_VIEW("paywall_view"),
        TRIAL_START("trial_start"),
        PRO_PURCHASE("pro_purchase"),
        DESKTOP_START("desktop_start"),
        COMPONENT_UPDATE("component_update"),
    }

    enum class Param(val key: String) {
        BACKEND("backend"), TOOL("tool"), LIMIT("limit"), SOURCE("source"), SKU("sku"),
        SURFACE("surface"), DURATION("duration"), ID("id"), RESULT("result")
    }

    fun durationBucket(ms: Long): String = when {
        ms <= 0 -> "unknown"
        ms < 5 * 60_000 -> "lt5m"
        ms < 15 * 60_000 -> "5to15m"
        ms < 30 * 60_000 -> "15to30m"
        else -> "gt30m"
    }

    @Suppress("UNUSED_PARAMETER") fun componentUpdate(ctx: Context, id: String, ok: Boolean) = Unit
    @Suppress("UNUSED_PARAMETER") fun crashConsent(ctx: Context) = false
    @Suppress("UNUSED_PARAMETER") fun usageConsent(ctx: Context) = false

    /** Always "asked": the consent sheet / onboarding consent page never shows without a telemetry SDK. */
    @Suppress("UNUSED_PARAMETER") fun asked(ctx: Context) = true

    @Suppress("UNUSED_PARAMETER") fun setConsent(ctx: Context, crash: Boolean, usage: Boolean) = Unit
    @Suppress("UNUSED_PARAMETER") fun apply(ctx: Context) = Unit
    @Suppress("UNUSED_PARAMETER") fun log(ctx: Context, event: Event, vararg params: Pair<Param, String?>) = Unit
    @Suppress("UNUSED_PARAMETER") fun logOnce(ctx: Context, event: Event, vararg params: Pair<Param, String?>) = Unit

    /** Short id-like token or "other": same rule as the play build (kept so shared tests hold). */
    fun token(v: String?): String =
        v?.takeIf { it.length <= 40 && TOKEN.matches(it) } ?: "other"

    private val TOKEN = Regex("[A-Za-z0-9_.-]+")
}
