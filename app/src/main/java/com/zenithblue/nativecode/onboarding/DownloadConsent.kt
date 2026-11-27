package com.zenithblue.nativecode.onboarding

import android.content.Context

/** F-Droid flavor: the user's opt-in to downloading the runtime packs. Nothing is fetched before it is granted. */
object DownloadConsent {
    private const val PREFS = "nativecode_prefs"
    private const val KEY = "fdroid_download_consent"

    fun granted(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun grant(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }
}
