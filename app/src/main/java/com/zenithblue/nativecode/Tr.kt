package com.zenithblue.nativecode

import android.content.Context
import androidx.annotation.StringRes

/**
 * String lookup for code that has no Context at hand (plan 20). Uses the application context, whose resources
 * follow the per-app language. English source text lives in res/values/strings.xml; never hard-code UI text.
 */
object Strs {
    @Volatile var app: Context? = null

    private var cachedTag = ""
    private var cachedCtx: Context? = null

    /** API 33+: the application context already follows the system per-app language. Below: apply AppCompat's stored choice. */
    private fun localized(c: Context): Context {
        if (android.os.Build.VERSION.SDK_INT >= 33) return c
        val l = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
        if (l.isEmpty) return c
        val tag = l.toLanguageTags()
        if (tag != cachedTag || cachedCtx == null) {
            val cfg = android.content.res.Configuration(c.resources.configuration)
            cfg.setLocales(android.os.LocaleList.forLanguageTags(tag))
            cachedCtx = c.createConfigurationContext(cfg)
            cachedTag = tag
        }
        return cachedCtx!!
    }

    fun get(@StringRes id: Int, vararg args: Any?): String {
        val c = app ?: return "" // only before Application.onCreate (never for UI)
        val lc = localized(c)
        return if (args.isEmpty()) lc.getString(id) else lc.getString(id, *args)
    }
}

fun tr(@StringRes id: Int, vararg args: Any?): String = Strs.get(id, *args)
