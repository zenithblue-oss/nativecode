package com.zenithblue.nativecode.terminal

import android.content.Context
import java.io.File

/** Builds direct native PRoot arguments and environment for Debian guest sessions. */
object ProotFastCommandBuilder {

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
        return bare?.let { null to it.groupValues[1].trim() }
    }

    private fun guestFile(rootfs: String, guestPath: String): File =
        File(rootfs, guestPath.removePrefix("/"))

    private fun buildGuestPath(rootfs: String, homeDir: String): String {
        val base = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val userBins = "$homeDir/.local/bin:$homeDir/bin:$homeDir/.cargo/bin:/opt/nodejs/bin"
        val nvmNodeDir = guestFile(rootfs, "$homeDir/.nvm/versions/node")
        val latestNode = if (nvmNodeDir.isDirectory) {
            try {
                nvmNodeDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("v") }
                    ?.maxByOrNull { it.name }
                    ?.name
            } catch (_: Exception) {
                null
            }
        } else null
        val nvmBin = latestNode?.let { "$homeDir/.nvm/versions/node/$it/bin:" } ?: ""
        return "$nvmBin$userBins:$base"
    }

    private fun resolveInteractiveShell(rootfs: String, user: String): String {
        val marker = try {
            guestFile(rootfs, InteractiveShellPolicy.MARKER_PATH).readText()
        } catch (_: Exception) {
            null
        }
        return InteractiveShellPolicy.resolveForUser(user, marker) { candidate ->
            guestFile(rootfs, candidate).canExecute()
        }
    }

    private fun buildInternal(
        ctx: Context,
        user: String,
        useSharedTmp: Boolean,
        targetCwd: String,
        guestCommand: Array<String>
    ): Pair<Array<String>, HashMap<String, String>> {
        val prootBinFile = TermuxHostPaths.libProot(ctx)
        val prootBin = if (prootBinFile.exists()) prootBinFile.absolutePath else "${TermuxHostPaths.BIN}/proot"
        val rootfs = "${TermuxHostPaths.PREFIX}/var/lib/proot-distro/containers/debian/rootfs"
        val guestUser = if (user == "root") "root" else "flux"
        val guestHome = if (guestUser == "root") "/root" else "/home/flux"
        val guestPath = buildGuestPath(rootfs, guestHome)

        val flags = mutableListOf(
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "--sysvipc",
            "--change-id=${if (guestUser == "root") "0:0" else "1000:1000"}",
            "--rootfs=$rootfs",
            "--cwd=$targetCwd"
        )
        val binds = mutableListOf(
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev/urandom:/dev/random"
        )
        if (useSharedTmp) binds.add("--bind=${TermuxHostPaths.PREFIX}/tmp:/tmp")
        binds.add("--bind=$rootfs/tmp:/dev/shm")

        val sysEmpty = File("${TermuxHostPaths.PREFIX}/var/lib/proot-distro/sysdata/sys_empty")
        if (sysEmpty.exists()) binds.add("--bind=${sysEmpty.absolutePath}:/sys/fs/selinux")
        val sdcard = File("/storage/emulated/0")
        if (sdcard.exists() && sdcard.canRead()) binds.add("--bind=/storage/emulated/0:/sdcard")

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
            "GIT_CONFIG_COUNT=1",
            "GIT_CONFIG_KEY_0=safe.directory",
            "GIT_CONFIG_VALUE_0=$targetCwd",
            "DEBIAN_FRONTEND=noninteractive",
            "FLUX_QUIET_SHELL=1"
        )
        val command = arrayOf("/usr/bin/env", "-i", *guestEnv, *guestCommand)
        val args = arrayOf(prootBin, *flags.toTypedArray(), *binds.toTypedArray(), *command)
        val envMap = HostCommandBuilder.envMap(ctx, forceHostSetup = false, includeTerm = true)
        return args to envMap
    }

    fun build(
        ctx: Context,
        shellCmd: String,
        user: String = "flux",
        useSharedTmp: Boolean = true
    ): Pair<Array<String>, HashMap<String, String>> {
        val toolExec = parseToolExec(shellCmd)
        val guestUser = if (user == "root") "root" else "flux"
        val guestHome = if (guestUser == "root") "/root" else "/home/flux"
        val targetCwd = toolExec?.first?.takeIf { !it.isNullOrBlank() } ?: guestHome
        val guestCommand = if (toolExec != null) {
            toolExec.second.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .toTypedArray()
        } else {
            arrayOf("/bin/bash", "--noprofile", "--norc", "-c", shellCmd)
        }
        return buildInternal(ctx, user, useSharedTmp, targetCwd, guestCommand)
    }

    fun buildInteractive(
        ctx: Context,
        user: String = "flux",
        workDir: String? = null,
        useSharedTmp: Boolean = true
    ): Pair<Array<String>, HashMap<String, String>> {
        val guestUser = if (user == "root") "root" else "flux"
        val guestHome = if (guestUser == "root") "/root" else "/home/flux"
        val rootfs = "${TermuxHostPaths.PREFIX}/var/lib/proot-distro/containers/debian/rootfs"
        val targetCwd = workDir?.takeIf { it.isNotBlank() } ?: guestHome
        val shellBin = resolveInteractiveShell(rootfs, guestUser)
        return buildInternal(ctx, guestUser, useSharedTmp, targetCwd, arrayOf(shellBin, "-l"))
    }
}
