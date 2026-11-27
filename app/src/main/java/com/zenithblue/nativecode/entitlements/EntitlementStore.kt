package com.zenithblue.nativecode.entitlements

import android.content.Context
import com.zenithblue.nativecode.BuildConfig

/**
 * Pro entitlement. Play Billing ([com.zenithblue.nativecode.billing.BillingRepository]) writes a
 * cached flag after each successful purchase query; offline the cache holds for [OFFLINE_TTL_MS].
 * Debug builds also honour the `force_pro` pref.
 */
object EntitlementStore {
    private const val PREFS = "nativecode_prefs"
    const val KEY_FORCE_PRO = "force_pro"
    private const val KEY_PRO = "pro_cached"
    private const val KEY_PRO_CHECKED_MS = "pro_checked_ms"
    const val OFFLINE_TTL_MS = 7L * 24 * 60 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPro(ctx: Context): Boolean {
        if (!BuildConfig.MONETISATION_ENFORCE) return true // fdroid: no plans, Pro is always on (UI labels follow this)
        val p = prefs(ctx)
        if (BuildConfig.DEBUG && p.getBoolean(KEY_FORCE_PRO, false)) return true
        return isCacheValid(p.getBoolean(KEY_PRO, false), p.getLong(KEY_PRO_CHECKED_MS, 0L), System.currentTimeMillis())
    }

    /** Epoch ms until which [isPro] stays true without a Play refresh; 0 when not Pro. Feeds the guest gate. */
    fun proValidUntilMs(ctx: Context): Long {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (BuildConfig.DEBUG && p.getBoolean(KEY_FORCE_PRO, false)) return now + OFFLINE_TTL_MS
        return if (isCacheValid(p.getBoolean(KEY_PRO, false), p.getLong(KEY_PRO_CHECKED_MS, 0L), now))
            p.getLong(KEY_PRO_CHECKED_MS, 0L) + OFFLINE_TTL_MS else 0L
    }

    /** Called only after Play answered OK, so a successful empty query clears Pro. */
    fun setPro(ctx: Context, pro: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_PRO, pro).putLong(KEY_PRO_CHECKED_MS, System.currentTimeMillis()).apply()
    }

    internal fun isCacheValid(pro: Boolean, checkedMs: Long, nowMs: Long): Boolean =
        pro && nowMs - checkedMs in 0 until OFFLINE_TTL_MS
}
