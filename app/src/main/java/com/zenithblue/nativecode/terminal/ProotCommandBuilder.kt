package com.zenithblue.nativecode.terminal

import android.content.Context

/** Builds shell arguments and environment map for proot Debian sessions. */
object ProotCommandBuilder {

    private const val GIT_SAFE_DIRECTORY =
        "export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0=\"\$PWD\"; "

    private const val FLUX_INTERACTIVE_HOME =
        GIT_SAFE_DIRECTORY + "exec /bin/bash -l"

    private const val FLUX_INTERACTIVE_WORKDIR =
        "mkdir -p -- \"\$1\" && cd -- \"\$1\" && " + GIT_SAFE_DIRECTORY + "exec /bin/bash -l"

    private const val ROOT_INTERACTIVE_HOME = GIT_SAFE_DIRECTORY + "exec /bin/bash -l"
    private const val ROOT_INTERACTIVE_WORKDIR =
        "mkdir -p -- \"\$1\" && cd -- \"\$1\" && " + GIT_SAFE_DIRECTORY + "exec /bin/bash -l"

    fun build(
        ctx: Context,
        shellCmd: String,
        user: String = "flux",
        useSharedTmp: Boolean = true
    ): Pair<Array<String>, HashMap<String, String>> {
        val shell = TermuxHostPaths.libBash(ctx).absolutePath
        val sharedTmpFlag = if (useSharedTmp) "--shared-tmp" else ""
        val prootDistro = TermuxHostPaths.PROOT_DISTRO

        // Single-quote guest payload so host bash never expands $HOME/$PATH/etc.
        // Escape embedded single quotes: ' → '\''.
        val escaped = shellCmd.replace("'", "'\\''")
        val command =
            "exec python $prootDistro login alpine $sharedTmpFlag --user $user -- " +
                "/bin/bash --noprofile --norc -c '$escaped'"
        val args = arrayOf(shell, "-c", command)

        // Host package env + interactive TERM (guest login inherits package identity)
        val envMap = HostCommandBuilder.envMap(ctx, forceHostSetup = false, includeTerm = true)
        return args to envMap
    }

    /** Interactive shell: proot-distro enters through passwd Bash, then NativeCode selects UX shell. */
    fun buildInteractive(
        ctx: Context,
        user: String = "flux",
        workDir: String? = null,
        useSharedTmp: Boolean = true
    ): Pair<Array<String>, HashMap<String, String>> {
        val shell = TermuxHostPaths.libBash(ctx).absolutePath
        val sharedTmpFlag = if (useSharedTmp) "--shared-tmp" else ""
        val prootDistro = TermuxHostPaths.PROOT_DISTRO
        val guestUser = if (user == "root") "root" else "flux"
        val payload = when {
            guestUser == "root" && workDir.isNullOrBlank() -> ROOT_INTERACTIVE_HOME
            guestUser == "root" -> ROOT_INTERACTIVE_WORKDIR
            workDir.isNullOrBlank() -> FLUX_INTERACTIVE_HOME
            else -> FLUX_INTERACTIVE_WORKDIR
        }
        val escapedPayload = payload.replace("'", "'\\''")
        val workDirArg = workDir?.takeIf { it.isNotBlank() }?.let(ShellQuoting::singleQuote).orEmpty()
        val positional = if (workDirArg.isEmpty()) "" else " nativecode-interactive $workDirArg"
        val command =
            "exec python $prootDistro login alpine $sharedTmpFlag --user $guestUser -- " +
                "/bin/bash --noprofile --norc -c '$escapedPayload'$positional"
        val args = arrayOf(shell, "-c", command)
        val envMap = HostCommandBuilder.envMap(ctx, forceHostSetup = false, includeTerm = true)
        return args to envMap
    }
}
