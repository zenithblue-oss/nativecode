package com.zenithblue.nativecode.terminal

import android.content.Context

/** Google Play stub. Chroot is unavailable in the rootless Play build. */
object ChrootCommandBuilder {
    const val CHROOT_PATH = "/disabled/chroot"
    const val CHROOT_HELPER = "/disabled/nativecode_chroot.sh"
    const val CHROOT_HELPER_ASSET = ""
    const val CHROOT_HELPER_VERSION = "disabled"
    const val RUNNER_ROOT = ""
    const val ENTER_FLUX = ""
    const val ENTER_ROOT = ""
    const val SESSION_EXEC = "/system/bin/sh"
    const val LAUNCH_TOOL_VERSION = "disabled"

    private fun disabled(): Nothing = throw UnsupportedOperationException("Chroot is unavailable in the Google Play build")
    fun build(ctx: Context, shellCmd: String, user: String = "flux"): Pair<Array<String>, HashMap<String, String>> = disabled()
    fun buildInteractive(ctx: Context, user: String = "flux", workDir: String? = null): Pair<Array<String>, HashMap<String, String>> = disabled()
    fun ensureHelperScript(ctx: Context): Boolean = false
    fun ensureTermWrapper(): Boolean = false
    fun ensureLauncherScript(ctx: Context): Boolean = false
    fun toolEnvInit(): String = ""
    fun toolBinaryName(type: String): String = disabled()
    fun buildToolShellCommand(ctx: Context, type: String, workDir: String?): String = disabled()
}
