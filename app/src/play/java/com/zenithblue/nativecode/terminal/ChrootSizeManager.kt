package com.zenithblue.nativecode.terminal

import android.content.Context

/** Google Play stub. */
object ChrootSizeManager {
    data class Result(
        val path: String,
        val bytes: Long?,
        val dirExists: Boolean,
        val viaRoot: Boolean,
        val raw: String,
        val exitCode: Int,
        val rootOk: Boolean,
        val error: String? = null
    )
    fun measure(context: Context, path: String = ChrootCommandBuilder.CHROOT_PATH) =
        Result(path, null, false, false, "", -1, false, "root_disabled")
}
