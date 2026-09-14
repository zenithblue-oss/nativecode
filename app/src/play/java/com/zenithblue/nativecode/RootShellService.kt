package com.zenithblue.nativecode

import android.content.Context
import java.io.File

/** Google Play stub. The Play build has no superuser execution path. */
object RootShell {
    data class CaptureResult(val exitCode: Int, val stdout: String)

    fun isRootAvailable(): Boolean = false
    fun probeRootAvailable(forceClearCache: Boolean = false, onResult: (Boolean) -> Unit) = onResult(false)
    fun capture(cmd: String, timeoutMs: Long = 0L): String = ""
    fun captureResult(cmd: String, timeoutMs: Long = 0L) = CaptureResult(-1, "root_disabled")
    fun copyIntoChroot(hostSrc: File, guestAbsPath: String, chrootPath: String = "/disabled/chroot", timeoutMs: Long = 30_000L) =
        CaptureResult(-1, "root_disabled")
    fun copyOutOfChroot(hostSrc: File, dest: File, timeoutMs: Long = 60_000L) =
        CaptureResult(-1, "root_disabled")
    fun execute(cmd: String, onLine: (String) -> Unit = {}, onDone: (Int) -> Unit = {}) = onDone(-1)
    fun executeSync(cmd: String): Int = -1
    fun executeScript(scriptPath: String, onLine: (String) -> Unit = {}, onDone: (Int) -> Unit = {}) = onDone(-1)
    fun executeScriptAsset(
        context: Context,
        assetName: String,
        args: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {},
        onDone: (Int) -> Unit = {}
    ) = onDone(-1)
    fun resolveSuInvocation(): List<String>? = null
    fun clearSuCache() = Unit
    fun shellRootCommand(cmd: String): String = "false"
    fun executeInChroot(
        cmd: String,
        user: String = "flux",
        chrootPath: String = "/disabled/chroot",
        onLine: (String) -> Unit = {},
        onDone: (Int) -> Unit = {},
        context: Context? = null
    ) = onDone(-1)
    fun captureInChroot(
        cmd: String,
        user: String = "flux",
        chrootPath: String = "/disabled/chroot",
        timeoutMs: Long = 60_000L,
        context: Context? = null
    ) = CaptureResult(-1, "root_disabled")
    fun stageAsset(context: Context, assetName: String): String? = null
}
