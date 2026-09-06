package com.zenithblue.nativecode.terminal

import android.content.Context

/** Entry point for building terminal commands.
 *  Delegates to ProotCommandBuilder or ChrootCommandBuilder based on method. */
object LinuxCommandBuilder {

    /** Current isolation method: "proot" (default) or "chroot". */
    var currentMethod = "proot"

    /** Guest user for session type. Rooted shell → root; everything else → flux. */
    fun sessionUserForType(type: String): String =
        if (type == "shell-root") "root" else "flux"

    fun build(
        ctx: Context,
        shellCmd: String,
        user: String = "flux",
        useSharedTmp: Boolean = true,
        method: String = currentMethod
    ): Pair<Array<String>, HashMap<String, String>> {
        return when (method) {
            "chroot" -> ChrootCommandBuilder.build(ctx, shellCmd, user)
            else -> ProotFastCommandBuilder.build(ctx, shellCmd, user, useSharedTmp)
        }
    }

    fun buildInteractive(
        ctx: Context,
        user: String = "flux",
        workDir: String? = null,
        useSharedTmp: Boolean = true,
        method: String = currentMethod
    ): Pair<Array<String>, HashMap<String, String>> {
        return when (method) {
            "chroot" -> ChrootCommandBuilder.buildInteractive(ctx, user, workDir)
            else -> ProotFastCommandBuilder.buildInteractive(ctx, user, workDir, useSharedTmp)
        }
    }

    /** System automation entry point. Every backend executes this through guest Bash. */
    fun buildGuestBash(
        ctx: Context,
        shellCmd: String,
        user: String = "flux",
        useSharedTmp: Boolean = true,
        method: String = currentMethod
    ): Pair<Array<String>, HashMap<String, String>> =
        build(ctx, shellCmd, user, useSharedTmp, method)

    /** Returns the executable path for TerminalSession. */
    fun sessionExec(
        ctx: Context,
        args: Array<String>,
        method: String = currentMethod
    ): String {
        return if (method == "chroot") {
            ChrootCommandBuilder.SESSION_EXEC
        } else {
            val exe = args.firstOrNull() ?: TermuxHostPaths.libProot(ctx).absolutePath
            if (exe.startsWith("/data/app/") || exe.startsWith("/data/data/") || exe.startsWith("/data/user/")) {
                "/system/bin/linker64"
            } else {
                exe
            }
        }
    }

    /** Returns the adjusted argv for TerminalSession (linker-wrapped on host under SDK 36/37). */
    fun sessionArgs(
        ctx: Context,
        args: Array<String>,
        method: String = currentMethod
    ): Array<String> {
        if (method == "chroot") return args
        val exe = args.firstOrNull() ?: ""
        if (exe.startsWith("/data/app/") || exe.startsWith("/data/data/") || exe.startsWith("/data/user/")) {
            return arrayOf("/system/bin/linker64") + args
        }
        return args
    }
}
