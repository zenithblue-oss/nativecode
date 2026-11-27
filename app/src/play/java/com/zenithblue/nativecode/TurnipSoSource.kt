package com.zenithblue.nativecode

import android.content.Context
import java.io.InputStream

/** Where TurnipDriverUpdate gets the 17 MB Turnip .so: bundled in the play APK/asset delivery. */
object TurnipSoSource {
    /** Null = ready; else a user-facing error. */
    @Suppress("UNUSED_PARAMETER")
    fun ensure(ctx: Context): String? = null

    fun open(ctx: Context): InputStream = ctx.assets.open("turnip-27.0.0-r2/libvulkan_freedreno.so")
}
