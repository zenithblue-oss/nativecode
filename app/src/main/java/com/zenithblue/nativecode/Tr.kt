package com.zenithblue.nativecode

import android.content.Context
import androidx.annotation.StringRes

/**
 * String lookup for code that has no Context at hand (plan 20). Uses the application context, whose resources
 * follow the per-app language. English source text lives in res/values/strings.xml; never hard-code UI text.
 */
object Strs {
    @Volatile var app: Context? = null

    fun get(@StringRes id: Int, vararg args: Any?): String {
        val c = app ?: return "" // only before Application.onCreate (never for UI)
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }
}

fun tr(@StringRes id: Int, vararg args: Any?): String = Strs.get(id, *args)
