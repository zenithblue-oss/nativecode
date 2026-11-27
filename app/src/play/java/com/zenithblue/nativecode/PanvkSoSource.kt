package com.zenithblue.nativecode

import android.content.Context
import java.io.InputStream

/** Where PanvkDriverUpdate gets the 21 MB PanVK .so: bundled in the play APK/asset delivery. */
object PanvkSoSource {
    /** Null = ready; else a user-facing error. */
    @Suppress("UNUSED_PARAMETER")
    fun ensure(ctx: Context): String? = null

    fun open(ctx: Context): InputStream = ctx.assets.open("panvk-26.3.1/libvulkan_panfrost_kbase.so")
}
