package com.zenithblue.nativecode.terminal

import android.content.Context
import java.io.File

/** Builds shell arguments and environment map for proot Debian sessions. */
object ProotCommandBuilder {

    private const val GIT_SAFE_DIRECTORY =
        "export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0=\"\$PWD\"; "

    private const val ANDROID_DEV_ENV =
        "export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk; " +
            "export PATH=/opt/android-sdk/build-tools/36.1.0:/opt/android-sdk/platform-tools:\$PATH; "

    /** Venus/Turnip env for login shells. /dev/dri EACCES is expected; vtest is the path. */
    private const val GPU_ENV =
        "[ -r /etc/profile.d/flux-gpu.sh ] && . /etc/profile.d/flux-gpu.sh; "

    private const val FLUX_INTERACTIVE_HOME =
        GIT_SAFE_DIRECTORY + GPU_ENV + "exec /bin/bash -l"

    private const val FLUX_INTERACTIVE_WORKDIR =
        "mkdir -p -- \"\$1\" && cd -- \"\$1\" && " + GIT_SAFE_DIRECTORY + GPU_ENV + "exec /bin/bash -l"

    private const val ROOT_INTERACTIVE_HOME = GIT_SAFE_DIRECTORY + GPU_ENV + "exec /bin/bash -l"
    private const val ROOT_INTERACTIVE_WORKDIR =
        "mkdir -p -- \"\$1\" && cd -- \"\$1\" && " + GIT_SAFE_DIRECTORY + GPU_ENV + "exec /bin/bash -l"

    private fun resolveDistro(ctx: Context): String {
        val alpaquita = File(
            ctx.filesDir,
            "usr/var/lib/proot-distro/containers/alpaquita/rootfs"
        )
        if (alpaquita.isDirectory) return "alpaquita"

        val alpine = File(
            ctx.filesDir,
            "usr/var/lib/proot-distro/containers/alpine/rootfs"
        )
        if (alpine.isDirectory) return "alpine"

        return ProjectPathResolver.DEFAULT_DISTRO
    }

    private fun prootHostEnv(ctx: Context): HashMap<String, String> =
        HostCommandBuilder.envMap(
            ctx,
            forceHostSetup = false,
            includeTerm = true
        ).apply {
            // Never inject libtermux-exec into proot-distro/guest execution.
            remove("LD_PRELOAD")
        }

    private fun pythonCmd(ctx: Context): String {
        val pythonBin = "${TermuxHostPaths.BIN}/python"
        return if (File("/system/bin/linker64").exists()) {
            "/system/bin/linker64 $pythonBin"
        } else {
            pythonBin
        }
    }

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
        val distro = resolveDistro(ctx)
        val escaped = (ANDROID_DEV_ENV + GPU_ENV + shellCmd).replace("'", "'\\''")
        val py = pythonCmd(ctx)
        val command =
            "exec $py $prootDistro login $distro $sharedTmpFlag --user $user -- " +
                "/bin/bash --noprofile --norc -c '$escaped'"
        val args = arrayOf(shell, "-c", command)

        val envMap = prootHostEnv(ctx)
        return args to envMap
    }

    /** Interactive shell: proot-distro enters through passwd Bash, then NativeCode selects UX shell. */
    fun buildInteractive(
        ctx: Context,
        user: String = "flux",
        workDir: String? = null,
        useSharedTmp: Boolean = true
    ): Pair<Array<String>, HashMap<String, String>> {
        HostServerController.ensureGuestGpu(ctx)
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
        val distro = resolveDistro(ctx)
        val py = pythonCmd(ctx)
        val command =
            "exec $py $prootDistro login $distro $sharedTmpFlag --user $guestUser -- " +
                "/bin/bash --noprofile --norc -c '$escapedPayload'$positional"
        val args = arrayOf(shell, "-c", command)
        val envMap = prootHostEnv(ctx)
        return args to envMap
    }
}
