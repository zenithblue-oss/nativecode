package com.zenithblue.nativecode.terminal

import android.content.Context
import com.zenithblue.nativecode.entitlements.GuestGate
import java.io.File

/** Builds shell arguments and environment map for proot Debian sessions. */
object ProotCommandBuilder {

    private const val ANDROID_DEV_ENV =
        "export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk; " +
            "export PATH=/opt/android-sdk/build-tools/36.1.0:/opt/android-sdk/platform-tools:\$PATH; "

    /** Venus/Turnip env for login shells. /dev/dri EACCES is expected; vtest is the path. */
    private const val GPU_ENV =
        "[ -r /etc/profile.d/flux-gpu.sh ] && . /etc/profile.d/flux-gpu.sh; "

    private fun resolveRootfsDir(ctx: Context): File {
        // Not ctx.filesDir: that is /data/user/0/…, which the guest cannot see. link2symlink
        // stores this path in every hard-link symlink, so it must be the bound /data/data one.
        val alpaquita = File(
            TermuxHostPaths.PREFIX,
            "var/lib/proot-distro/containers/alpaquita/rootfs"
        )
        if (alpaquita.isDirectory) return alpaquita

        val alpine = File(
            TermuxHostPaths.PREFIX,
            "var/lib/proot-distro/containers/alpine/rootfs"
        )
        if (alpine.isDirectory) return alpine

        return ProjectPathResolver.prootRootfsDir(ctx)
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

    fun build(
        ctx: Context,
        shellCmd: String,
        user: String = "flux",
        useSharedTmp: Boolean = true
    ): Pair<Array<String>, HashMap<String, String>> {
        // Payload is one argv element. Guest bash interprets it; do not re-escape.
        // Linker prefix is added once by ShellCommandRunner.adjustHostCmd / sessionExec.
        // Direct libproot.so launch: the python proot-distro wrapper cost ~290 ms per
        // command (381 vs 86 ms for `true`, MT6897). Same proot flags and binds.
        val (args, env) = buildDirect(
            ctx, user, null, useSharedTmp,
            listOf("/bin/bash", "--noprofile", "--norc", "-c", ANDROID_DEV_ENV + GPU_ENV + shellCmd)
        )
        // ShellCommandRunner merges the host env (LD_LIBRARY_PATH, PREFIX…) under this map and
        // cannot unset keys, so pin the exact env in argv like proot-distro's clean execvpe did.
        val guestEnv = legacyGuestEnv(env)
        val argv = listOf("/system/bin/env", "-i") + guestEnv.map { (k, v) -> "$k=$v" } +
            "/system/bin/linker64" + args
        return argv.toTypedArray() to guestEnv
    }

    private val LEGACY_ENV_KEYS = setOf(
        "ANDROID_ART_ROOT", "ANDROID_DATA", "ANDROID_I18N_ROOT", "ANDROID_ROOT",
        "ANDROID_RUNTIME_ROOT", "ANDROID_TZDATA_ROOT", "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH",
        "EXTERNAL_STORAGE", "MOZ_FAKE_NO_SANDBOX", "PULSE_SERVER", "HOME", "USER", "TERM",
        "PROOT_LOADER", "PROOT_TMP_DIR", "PROOT_L2S_DIR", "PROOT_NO_SECCOMP", "PROOT_VERBOSE"
    )

    /** Guest env that `proot-distro login` gave automation commands: no host vars, its PATH. */
    internal fun legacyGuestEnv(env: Map<String, String>): HashMap<String, String> =
        HashMap(env.filterKeys { it in LEGACY_ENV_KEYS }).apply {
            put(
                "PATH",
                "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:" +
                    "/usr/local/games:/usr/games:/system/bin:/system/xbin:${TermuxHostPaths.BIN}"
            )
        }

    private fun ensureSysdata(sysdataDir: File) {
        if (sysdataDir.exists() && File(sysdataDir, "stat").exists()) return
        try {
            sysdataDir.mkdirs()
            File(sysdataDir, "sys_empty").mkdirs()
            val stubs = mapOf(
                "loadavg" to "0.12 0.07 0.02 2/165 765\n",
                "uptime" to "124.08 932.80\n",
                "stat" to "cpu  1957 0 2877 93280 262 342 254 87 0 0\nctxt 140223\nbtime 1680020856\nprocesses 772\nprocs_running 2\nprocs_blocked 0\n",
                "version" to "Linux version 6.17.0-PRoot-Distro (proot@termux) #1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\n",
                "vmstat" to "nr_free_pages 1743136\nnr_anon_pages 7723\nnr_mapped 8905\nnr_file_pages 253569\n",
                "sysctl_entry_cap_last_cap" to "40\n",
                "sysctl_inotify_max_user_watches" to "4096\n",
                "sysctl_kernel_overflowuid" to "65534\n",
                "sysctl_kernel_overflowgid" to "65534\n"
            )
            for ((name, content) in stubs) {
                val f = File(sysdataDir, name)
                if (!f.exists()) f.writeText(content)
            }
        } catch (_: Exception) {}
    }

    private fun ensureTermuxProfile(rootfsDir: File) {
        val target = File(rootfsDir, "etc/profile.d/termux-profile.sh")
        if (target.exists()) return
        try {
            target.parentFile?.mkdirs()
            val termuxBin = "${TermuxHostPaths.PREFIX}/bin"
            val content = buildString {
                appendLine("case \":\${PATH}:\" in")
                appendLine("  *\":/home/flux/.local/bin:\"*) ;;")
                appendLine("  *) export PATH=\"/home/flux/.local/bin:/home/flux/bin:/home/flux/.cargo/bin:/opt/nodejs/bin:\${PATH}\" ;;")
                appendLine("esac")
                appendLine("case \":\${PATH}:\" in")
                appendLine("  *\":$termuxBin:\"*) ;;")
                appendLine("  *) export PATH=\"\${PATH}:$termuxBin\" ;;")
                appendLine("esac")
                appendLine("export ANDROID_ART_ROOT='/apex/com.android.art'")
                appendLine("export ANDROID_DATA='/data'")
                appendLine("export ANDROID_I18N_ROOT='/apex/com.android.i18n'")
                appendLine("export ANDROID_ROOT='/system'")
                appendLine("export ANDROID_TZDATA_ROOT='/apex/com.android.tzdata'")
                appendLine("export EXTERNAL_STORAGE='/sdcard'")
                appendLine("export MOZ_FAKE_NO_SANDBOX='1'")
                appendLine("export PULSE_SERVER='127.0.0.1'")
            }
            target.writeText(content)
        } catch (_: Exception) {}
    }

    /** Direct interactive shell: launches libproot.so -> /bin/bash -l without Python or host bash wrappers. */
    fun buildInteractive(
        ctx: Context,
        user: String = "flux",
        workDir: String? = null,
        useSharedTmp: Boolean = true
    ): Pair<Array<String>, HashMap<String, String>> =
        buildDirect(ctx, user, workDir, useSharedTmp, listOf("/bin/bash", "-l"))

    /**
     * Terminal tools only. Guest runs the launcher directly under PRoot — no Python.
     * [toolArgv] is already split; this does not parse command strings.
     */
    fun buildTool(
        ctx: Context,
        toolArgv: List<String>,
        user: String = "flux",
        workDir: String? = null,
        useSharedTmp: Boolean = true
    ): Pair<Array<String>, HashMap<String, String>> =
        buildDirect(
            ctx, user, workDir, useSharedTmp,
            listOf("/bin/bash", "--noprofile", "--norc", "-c", toolLaunch(toolArgv))
        )

    private fun toolLaunch(toolArgv: List<String>): String {
        val q = toolArgv.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        return ". /etc/profile.d/flux-gpu.sh 2>/dev/null || true; exec /tmp/launch_tool.sh $q"
    }

    private fun buildDirect(
        ctx: Context,
        user: String,
        workDir: String?,
        useSharedTmp: Boolean,
        guestArgv: List<String>
    ): Pair<Array<String>, HashMap<String, String>> {
        HostServerController.ensureGuestGpu(ctx)

        val rootfsDir = resolveRootfsDir(ctx)
        val rootfs = rootfsDir.absolutePath
        val prootBinFile = TermuxHostPaths.libProot(ctx)
        val prootBin = if (prootBinFile.exists()) prootBinFile.absolutePath else "${TermuxHostPaths.BIN}/proot"

        val guestUser = if (user == "root") "root" else "flux"
        val guestHome = if (guestUser == "root") "/root" else "/home/flux"
        val targetCwd = if (!workDir.isNullOrBlank()) {
            try {
                val dir = if (workDir.startsWith("/storage") || workDir.startsWith("/sdcard")) {
                    File(workDir)
                } else {
                    File(rootfsDir, workDir.removePrefix("/"))
                }
                dir.mkdirs()
            } catch (_: Exception) {}
            workDir
        } else {
            guestHome
        }

        val sysdataDir = File(rootfsDir.parentFile, "sysdata")
        ensureSysdata(sysdataDir)
        ensureTermuxProfile(rootfsDir)

        val argsList = mutableListOf(
            prootBin,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "--kernel-release=\\Linux\\localhost\\6.17.0-PRoot-Distro\\#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\\aarch64\\localdomain\\-1\\",
            "-L",
            "--change-id=${if (guestUser == "root") "0:0" else "1000:1000"}",
            "--rootfs=$rootfs",
            "--cwd=$targetCwd",
            "--bind=/dev",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev/urandom:/dev/random"
        )

        // File descriptors
        val fdDir = File("/proc/self/fd")
        if (fdDir.isDirectory) {
            argsList.add("--bind=/proc/self/fd:/dev/fd")
            for (i in 0..2) {
                val names = listOf("stdin", "stdout", "stderr")
                if (File("/proc/self/fd/$i").exists()) {
                    argsList.add("--bind=/proc/self/fd/$i:/dev/${names[i]}")
                }
            }
        }

        // Sysdata / fake proc
        if (sysdataDir.isDirectory) {
            val sysEmpty = File(sysdataDir, "sys_empty")
            if (sysEmpty.exists()) {
                argsList.add("--bind=${sysEmpty.absolutePath}:/sys/fs/selinux")
            }
            val sysdataChecks = listOf(
                "loadavg" to "/proc/loadavg",
                "stat" to "/proc/stat",
                "uptime" to "/proc/uptime",
                "version" to "/proc/version",
                "vmstat" to "/proc/vmstat",
                "sysctl_entry_cap_last_cap" to "/proc/sys/kernel/cap_last_cap",
                "sysctl_inotify_max_user_watches" to "/proc/sys/fs/inotify/max_user_watches",
                "sysctl_kernel_overflowuid" to "/proc/sys/kernel/overflowuid",
                "sysctl_kernel_overflowgid" to "/proc/sys/kernel/overflowgid"
            )
            for ((stub, target) in sysdataChecks) {
                val f = File(sysdataDir, stub)
                if (f.exists()) {
                    argsList.add("--bind=${f.absolutePath}:$target")
                }
            }
        }

        // /dev/shm
        val shmDir = File(rootfsDir, "tmp")
        try { shmDir.mkdirs() } catch (_: Exception) {}
        argsList.add("--bind=${shmDir.absolutePath}:/dev/shm")

        // Dalvik/ART caches
        val dalvikDirs = listOf(
            "/data/app",
            "/data/dalvik-cache",
            "/data/misc/apexdata/com.android.art/dalvik-cache"
        )
        for (d in dalvikDirs) {
            val f = File(d)
            if (f.isDirectory && f.canExecute()) {
                argsList.add("--bind=$d")
            }
        }

        // Shared storage
        val sdcard = File("/storage/emulated/0")
        if (sdcard.isDirectory && sdcard.canRead()) {
            argsList.add("--bind=/storage/emulated/0")
            argsList.add("--bind=/storage/emulated/0:/sdcard")
            argsList.add("--bind=/storage/emulated/0:/mnt/sdcard")
        }

        // App cache and home
        argsList.add("--bind=${ctx.cacheDir.absolutePath}")
        argsList.add("--bind=${TermuxHostPaths.homeDir(ctx).absolutePath}")

        // Android system paths
        val systemPaths = listOf(
            "/apex",
            "/odm",
            "/product",
            "/system",
            "/system_ext",
            "/vendor",
            "/linkerconfig/ld.config.txt",
            "/linkerconfig/com.android.art/ld.config.txt"
        )
        for (p in systemPaths) {
            val f = File(p)
            if (f.exists()) {
                if (f.isDirectory && f.canExecute()) {
                    argsList.add("--bind=$p")
                } else if (f.isFile && f.canRead()) {
                    argsList.add("--bind=$p")
                }
            }
        }

        // Host prefix & tmp
        argsList.add("--bind=${TermuxHostPaths.PREFIX}")
        // Hard links made by 1.7.x shells point at /data/user/0/…/.l2s; keep them resolvable.
        argsList.add("--bind=${TermuxHostPaths.PREFIX}:/data/user/0/${TermuxHostPaths.PACKAGE}/files/usr")
        if (useSharedTmp) {
            val hostTmp = File(TermuxHostPaths.TMPDIR)
            try { hostTmp.mkdirs() } catch (_: Exception) {}
            argsList.add("--bind=${TermuxHostPaths.TMPDIR}:/tmp")
        }

        argsList.addAll(guestArgv)

        // Environment map
        val envMap = prootHostEnv(ctx).apply {
            // Never inherit host bionic library paths or certificates into guest glibc runtime
            remove("LD_PRELOAD")
            remove("LD_LIBRARY_PATH")
            remove("SSL_CERT_FILE")
            remove("CURL_CA_BUNDLE")

            val l2sDir = File(rootfsDir, ".l2s")
            try { l2sDir.mkdirs() } catch (_: Exception) {}
            put("PROOT_L2S_DIR", l2sDir.absolutePath)
            put("PROOT_LOADER", TermuxHostPaths.libLoader(ctx).absolutePath)
            put("PROOT_TMP_DIR", TermuxHostPaths.TMPDIR)

            put("HOME", guestHome)
            put("USER", guestUser)
            put("LOGNAME", guestUser)
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "C.UTF-8")

            put("GIT_CONFIG_COUNT", "1")
            put("GIT_CONFIG_KEY_0", "safe.directory")
            put("GIT_CONFIG_VALUE_0", "*")

            put("ANDROID_HOME", "/opt/android-sdk")
            put("ANDROID_SDK_ROOT", "/opt/android-sdk")

            put("MOZ_FAKE_NO_SANDBOX", "1")
            put("PULSE_SERVER", "127.0.0.1")
            put("EXTERNAL_STORAGE", "/sdcard")
            put("TMPDIR", "/tmp")
            put("XDG_RUNTIME_DIR", "/tmp")

            val basePath = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            val sdkPath = "/opt/android-sdk/build-tools/36.1.0:/opt/android-sdk/platform-tools"
            // Gate shims first, then CLI self-update targets (opencode 2.x -> ~/.opencode/bin, claude, grok, agy,
            // kiro -> ~/.local/bin). npm -g prefix comes from ~/.npmrc, not env: root's installs must reach /usr/local.
            put("NPM_CONFIG_ALLOW_SCRIPTS", "opencode-ai")
            val userBins = "${GuestGate.GATE_BIN}:$guestHome/.opencode/bin:$guestHome/.local/bin:$guestHome/bin:$guestHome/.cargo/bin:/opt/nodejs/bin"
            put("PATH", "$userBins:$sdkPath:$basePath:${TermuxHostPaths.BIN}")
        }

        return argsList.toTypedArray() to envMap
    }
}
