package com.zenithblue.nativecode.terminal

import android.content.Context
import com.zenithblue.nativecode.BuildConfig

/** Entry point for building terminal commands.
 *  Delegates to ProotCommandBuilder or ChrootCommandBuilder based on method. */
object LinuxCommandBuilder {

    /** Current isolation method: "proot" (default) or "chroot". */
    @Volatile
    var environmentGeneration: Int = 0
        private set

    private var methodName: String = "proot"

    var currentMethod: String
        get() = methodName
        set(value) {
            if (methodName != value) environmentGeneration++
            methodName = value
        }

    /** Guest user for session type. Rooted shell → root; everything else → flux. */
    fun sessionUserForType(type: String): String =
        if (!com.zenithblue.nativecode.ProotOnly.ON && type == "shell-root") "root" else "flux"

    private fun effectiveMethod(method: String): String =
        if (com.zenithblue.nativecode.ProotOnly.ON) "proot" else method

    fun build(
        ctx: Context,
        shellCmd: String,
        user: String = "flux",
        useSharedTmp: Boolean = true,
        method: String = currentMethod
    ): Pair<Array<String>, HashMap<String, String>> {
        return when (effectiveMethod(method)) {
            "chroot" -> ChrootCommandBuilder.build(ctx, shellCmd, user)
            else -> ProotCommandBuilder.build(ctx, shellCmd, user, useSharedTmp)
        }
    }

    fun buildInteractive(
        ctx: Context,
        user: String = "flux",
        workDir: String? = null,
        useSharedTmp: Boolean = true,
        method: String = currentMethod
    ): Pair<Array<String>, HashMap<String, String>> {
        return when (effectiveMethod(method)) {
            "chroot" -> ChrootCommandBuilder.buildInteractive(ctx, user, workDir)
            else -> ProotCommandBuilder.buildInteractive(ctx, user, workDir, useSharedTmp)
        }
    }

    /**
     * Proot terminal tools only: launcher runs directly under PRoot, no Python.
     * Chroot and automated commands stay on [build].
     */
    fun buildToolDirect(
        ctx: Context,
        toolArgv: List<String>,
        user: String = "flux",
        workDir: String? = null,
        useSharedTmp: Boolean = true
    ): Pair<Array<String>, HashMap<String, String>> {
        if (!ChrootCommandBuilder.ensureLauncherScript(ctx)) {
            throw IllegalStateException("launch_tool: failed to install launcher")
        }
        return ProotCommandBuilder.buildTool(ctx, toolArgv, user, workDir, useSharedTmp)
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
        return if (effectiveMethod(method) == "chroot") {
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
        if (effectiveMethod(method) == "chroot") return args
        val exe = args.firstOrNull() ?: ""
        if (exe.startsWith("/data/app/") || exe.startsWith("/data/data/") || exe.startsWith("/data/user/")) {
            return arrayOf("/system/bin/linker64") + args
        }
        return args
    }
}
