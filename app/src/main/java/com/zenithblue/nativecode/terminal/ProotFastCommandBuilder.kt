package com.zenithblue.nativecode.terminal

import android.content.Context
import java.io.File

/**
 * Builds direct native PRoot arguments and environment for Debian guest sessions.
 * Completely eliminates Python and proot-distro runtime overhead for instant shell launch.
 */
object ProotFastCommandBuilder {

    private fun parseInteractiveWorkdir(shellCmd: String): String? {
        val t = shellCmd.trim()
        val m = Regex("""^mkdir -p (.+) && cd \1 && exec zsh$""").matchEntire(t)
            ?: return null
        val dir = m.groupValues[1].trim()
        return dir.takeIf { it.isNotEmpty() && !it.contains('\'') }
    }

    private fun parseToolExec(shellCmd: String): Pair<String?, String>? {
        val t = shellCmd.trim()
        val withDir = Regex(
            """^mkdir -p (.+) && cd \1 && (/tmp/launch_tool\.sh\s+\S+)$"""
        ).matchEntire(t)
        if (withDir != null) {
            val dir = withDir.groupValues[1].trim()
            if (dir.contains('\'')) return null
            return dir to withDir.groupValues[2].trim()
        }
        val bare = Regex("""^(/tmp/launch_tool\.sh\s+\S+)$""").matchEntire(t)
        if (bare != null) {
            return null to bare.groupValues[1].trim()
        }
        return null
    }

    private fun buildGuestPath(rootfs: String, homeDir: String): String {
        val base = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val userBins = "$homeDir/.local/bin:$homeDir/bin:$homeDir/.cargo/bin:/opt/nodejs/bin"

        val nvmNodeDir = File(rootfs, "$homeDir/.nvm/versions/node")
        val latestNode = if (nvmNodeDir.exists() && nvmNodeDir.isDirectory) {
            try {
                nvmNodeDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("v") }
                    ?.maxByOrNull { it.name }
                    ?.name
            } catch (_: Exception) {
                null
            }
        } else null

        val nvmBin = if (latestNode != null) "$homeDir/.nvm/versions/node/$latestNode/bin:" else ""
        return "$nvmBin$userBins:$base"
    }

    fun build(
        ctx: Context,
        shellCmd: String,
        user: String = "flux",
        useSharedTmp: Boolean = true
    ): Pair<Array<String>, HashMap<String, String>> {
        val prootBinFile = TermuxHostPaths.libProot(ctx)
        val prootBin = if (prootBinFile.exists()) prootBinFile.absolutePath else "${TermuxHostPaths.BIN}/proot"
        val rootfs = "${TermuxHostPaths.PREFIX}/var/lib/proot-distro/containers/debian/rootfs"

        val guestUser = if (user == "root") "root" else "flux"
        val guestHome = if (guestUser == "root") "/root" else "/home/flux"

        val workdir = parseInteractiveWorkdir(shellCmd)
        val toolExec = parseToolExec(shellCmd)
        val isInteractive = workdir != null ||
            shellCmd == "exec zsh" ||
            shellCmd == "/bin/bash --login" ||
            shellCmd.isBlank()

        val targetCwd = when {
            workdir != null -> workdir
            toolExec?.first != null -> toolExec.first!!
            else -> guestHome
        }

        val guestPath = buildGuestPath(rootfs, guestHome)

        // Direct PRoot Flags
        val flags = mutableListOf(
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "--sysvipc",
            "--change-id=${if (guestUser == "root") "0:0" else "1000:1000"}",
            "--rootfs=$rootfs",
            "--cwd=$targetCwd"
        )

        // Essential Bind Mounts
        val binds = mutableListOf(
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev/urandom:/dev/random"
        )
        if (useSharedTmp) {
            binds.add("--bind=${TermuxHostPaths.PREFIX}/tmp:/tmp")
        }
        binds.add("--bind=$rootfs/tmp:/dev/shm")

        val sysEmpty = File("${TermuxHostPaths.PREFIX}/var/lib/proot-distro/sysdata/sys_empty")
        if (sysEmpty.exists()) {
            binds.add("--bind=${sysEmpty.absolutePath}:/sys/fs/selinux")
        }

        val sdcard = File("/storage/emulated/0")
        if (sdcard.exists() && sdcard.canRead()) {
            binds.add("--bind=/storage/emulated/0:/sdcard")
        }

        // Clean Guest Environment
        val guestEnv = arrayOf(
            "HOME=$guestHome",
            "USER=$guestUser",
            "LOGNAME=$guestUser",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=$guestPath",
            "TMPDIR=/tmp",
            "XDG_RUNTIME_DIR=/tmp",
            "NVM_DIR=$guestHome/.nvm",
            "DEBIAN_FRONTEND=noninteractive",
            "FLUX_QUIET_SHELL=1"
        )

        val guestCmd: Array<String> = when {
            isInteractive -> {
                val shellBin = if (shellCmd == "/bin/bash --login") "/bin/bash" else "/bin/zsh"
                arrayOf("/usr/bin/env", "-i", *guestEnv, shellBin, "-l")
            }
            toolExec != null -> {
                val toolArgs = toolExec.second.split(Regex("\\s+")).filter { it.isNotBlank() }
                arrayOf("/usr/bin/env", "-i", *guestEnv, *toolArgs.toTypedArray())
            }
            else -> {
                arrayOf("/usr/bin/env", "-i", *guestEnv, "/bin/sh", "-c", shellCmd)
            }
        }

        val args = arrayOf(
            prootBin,
            *flags.toTypedArray(),
            *binds.toTypedArray(),
            *guestCmd
        )

        val envMap = HostCommandBuilder.envMap(ctx, forceHostSetup = false, includeTerm = true)
        return args to envMap
    }
}
