package com.zenithblue.nativecode.terminal

import android.content.Context

/** Google Play stub. */
object ChrootProcessManager {
    data class Proc(val pid: Int, val comm: String, val cmdline: String)
    data class ListResult(
        val path: String,
        val processes: List<Proc>,
        val raw: String,
        val rootOk: Boolean,
        val error: String? = null
    )
    data class KillResult(
        val killed: Int,
        val failed: Int,
        val remaining: List<Proc>,
        val verifiedClean: Boolean,
        val raw: String,
        val rootOk: Boolean,
        val error: String? = null
    )
    fun list(context: Context, path: String = ChrootCommandBuilder.CHROOT_PATH) =
        ListResult(path, emptyList(), "", false, "root_disabled")
    fun killAll(context: Context, path: String = ChrootCommandBuilder.CHROOT_PATH) =
        KillResult(0, 0, emptyList(), false, "", false, "root_disabled")
}
