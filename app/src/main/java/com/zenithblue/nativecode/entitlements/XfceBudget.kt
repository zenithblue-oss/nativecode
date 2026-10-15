package com.zenithblue.nativecode.entitlements

import android.content.Context
import java.time.LocalDate

/** Free desktop wall-clock budget per local day (plan §3.6). */
object XfceBudget {
    private const val PREFS = "nativecode_prefs"
    private const val KEY_DAY = "xfce_day_key"
    private const val KEY_USED = "xfce_used_ms"
    private const val KEY_START = "xfce_session_start_ms"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun today() = LocalDate.now().toString()

    /** Closed sessions today + the running one. */
    fun usedTodayMs(ctx: Context): Long {
        val p = prefs(ctx)
        val closed = if (p.getString(KEY_DAY, null) == today()) p.getLong(KEY_USED, 0L) else 0L
        val start = p.getLong(KEY_START, 0L)
        return closed + if (start > 0) (System.currentTimeMillis() - start).coerceAtLeast(0) else 0L
    }

    fun remainingMs(ctx: Context): Long = (FeatureLimits.FREE_XFCE_DAILY_MS - usedTodayMs(ctx)).coerceAtLeast(0)

    fun start(ctx: Context) {
        stop(ctx)
        prefs(ctx).edit().putLong(KEY_START, System.currentTimeMillis()).apply()
    }

    /** Idempotent; folds the running session into today's total. */
    fun stop(ctx: Context) {
        val p = prefs(ctx)
        val start = p.getLong(KEY_START, 0L)
        if (start <= 0) return
        val used = usedTodayMs(ctx)
        p.edit().putString(KEY_DAY, today()).putLong(KEY_USED, used).remove(KEY_START).apply()
    }
}
