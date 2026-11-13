package com.zenithblue.nativecode.telemetry

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.FirebaseAnalytics.ConsentStatus
import com.google.firebase.analytics.FirebaseAnalytics.ConsentType
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * The only file that imports Firebase. Collection is OFF (manifest) until the user opts in.
 * Events and parameter keys are fixed enums; values are reduced to short id-like tokens, so no
 * project name, path, prompt, account or token can reach an event or crash key.
 * Without app/google-services.json there is no FirebaseApp and every call is a no-op.
 */
object Telemetry {
    private const val TAG = "NCTelemetry"
    private const val PREFS = "nativecode_prefs"
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
    }

    enum class Param(val key: String) {
        BACKEND("backend"), TOOL("tool"), LIMIT("limit"), SOURCE("source"), SKU("sku")
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun available(ctx: Context) = FirebaseApp.getApps(ctx).isNotEmpty()

    fun crashConsent(ctx: Context) = prefs(ctx).getBoolean(KEY_CRASH, false)
    fun usageConsent(ctx: Context) = prefs(ctx).getBoolean(KEY_USAGE, false)
    fun asked(ctx: Context) = prefs(ctx).getBoolean(KEY_ASKED, false)

    fun setConsent(ctx: Context, crash: Boolean, usage: Boolean) {
        val hadCrash = crashConsent(ctx)
        val hadUsage = usageConsent(ctx)
        prefs(ctx).edit().putBoolean(KEY_CRASH, crash).putBoolean(KEY_USAGE, usage).putBoolean(KEY_ASKED, true).apply()
        apply(ctx)
        // Revoke also drops what is queued on the device, not only future collection.
        if (!available(ctx)) return
        try {
            if (hadCrash && !crash) FirebaseCrashlytics.getInstance().deleteUnsentReports()
            if (hadUsage && !usage) FirebaseAnalytics.getInstance(ctx).resetAnalyticsData()
        } catch (e: Exception) {
            Log.w(TAG, "revoke cleanup failed", e)
        }
    }

    /** Push the stored choice into the SDKs. Call at app start and after every change. */
    fun apply(ctx: Context) {
        if (!available(ctx)) return
        try {
            val usage = usageConsent(ctx)
            FirebaseAnalytics.getInstance(ctx).apply {
                // No ads in this app: ad consent is always denied.
                setConsent(
                    mapOf(
                        ConsentType.ANALYTICS_STORAGE to if (usage) ConsentStatus.GRANTED else ConsentStatus.DENIED,
                        ConsentType.AD_STORAGE to ConsentStatus.DENIED,
                        ConsentType.AD_USER_DATA to ConsentStatus.DENIED,
                        ConsentType.AD_PERSONALIZATION to ConsentStatus.DENIED,
                    )
                )
                setAnalyticsCollectionEnabled(usage)
            }
            FirebaseCrashlytics.getInstance().apply {
                setCrashlyticsCollectionEnabled(crashConsent(ctx))
                val p = prefs(ctx)
                setCustomKey("backend", token(p.getString("linux_method", "proot")))
                setCustomKey("gpu", token(p.getString("flux_gpu", "virgl")))
            }
        } catch (e: Exception) {
            Log.w(TAG, "apply failed", e)
        }
    }

    fun log(ctx: Context, event: Event, vararg params: Pair<Param, String?>) {
        if (!available(ctx) || !usageConsent(ctx)) return
        try {
            val b = Bundle()
            params.forEach { (k, v) -> b.putString(k.key, token(v)) }
            FirebaseAnalytics.getInstance(ctx).logEvent(event.id, b)
        } catch (e: Exception) {
            Log.w(TAG, "log ${event.id} failed", e)
        }
    }

    /** Once per install, whether or not it was sent (a later opt-in must not fake a "first"). */
    fun logOnce(ctx: Context, event: Event, vararg params: Pair<Param, String?>) {
        val key = "telemetry_once_${event.id}"
        val p = prefs(ctx)
        if (p.getBoolean(key, false)) return
        p.edit().putBoolean(key, true).apply()
        log(ctx, event, *params)
    }

    /** Short id-like token or "other": free-form text (paths, names, prompts) never passes. */
    fun token(v: String?): String =
        v?.takeIf { it.length <= 40 && TOKEN.matches(it) } ?: "other"

    private val TOKEN = Regex("[A-Za-z0-9_.-]+")
}
