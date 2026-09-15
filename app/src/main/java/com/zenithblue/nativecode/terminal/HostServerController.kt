package com.zenithblue.nativecode.terminal

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Host Venus (virgl_test_server_android --venus) and PulseAudio servers.
 *
 * Same contract as FluxLinux: start as the app user (not root), TCP Pulse,
 * vtest socket at `$PREFIX/tmp/.virgl_test`. Hardware page drives this
 * via ProcessBuilder — no GPU setup scripts, no driver downloads.
 */
object HostServerController {
    private const val TAG = "HostServerController"
    private const val LINKER = "/system/bin/linker64"
    private const val TCP_MODULE = "module-native-protocol-tcp auth-ip-acl=127.0.0.1 auth-anonymous=1"

    enum class Kind { VENUS, PULSE }

    data class Status(
        val kind: Kind,
        val binaryExists: Boolean,
        val running: Boolean,
        val detail: String
    )

    @Volatile
    private var venusProcess: Process? = null

    @Volatile
    private var pulseProcess: Process? = null

    fun binary(kind: Kind): File = when (kind) {
        Kind.VENUS -> File(TermuxHostPaths.BIN, "virgl_test_server_android")
        Kind.PULSE -> File(TermuxHostPaths.BIN, "pulseaudio")
    }

    fun socketFile(): File = File(TermuxHostPaths.TMPDIR, ".virgl_test")

    fun processName(kind: Kind): String = when (kind) {
        Kind.VENUS -> "virgl_test_server_android"
        Kind.PULSE -> "pulseaudio"
    }

    fun status(kind: Kind): Status {
        val bin = binary(kind)
        val pids = findPids(processName(kind))
        val running = pids.isNotEmpty() || (kind == Kind.VENUS && venusProcess?.isAlive == true)
        val detail = when {
            !bin.isFile -> "binary missing"
            kind == Kind.VENUS && running && socketFile().exists() ->
                "running pid=${pids.joinToString(",")} socket live"
            kind == Kind.VENUS && running ->
                "running pid=${pids.joinToString(",")} socket offline"
            running -> "running pid=${pids.joinToString(",")}"
            else -> "stopped"
        }
        return Status(kind, bin.isFile, running, detail)
    }

    fun start(ctx: Context, kind: Kind): String {
        val st = status(kind)
        if (!st.binaryExists) return "${label(kind)}: binary missing (rebuild bootstrap)"
        if (st.running) return "${label(kind)}: already ${st.detail}"
        File(TermuxHostPaths.TMPDIR).mkdirs()
        File(TermuxHostPaths.HOME, ".pulse").mkdirs()
        return when (kind) {
            Kind.VENUS -> startVenus(ctx)
            Kind.PULSE -> startPulse(ctx)
        }
    }

    /**
     * Ensure both Venus and PulseAudio servers are running by default.
     */
    fun ensureServers(ctx: Context) {
        GuestGpuConfig.applyToRootfs(ctx)
        if (binary(Kind.VENUS).isFile) {
            try {
                start(ctx, Kind.VENUS)
            } catch (e: Exception) {
                Log.w(TAG, "ensure Venus", e)
            }
        }
        if (binary(Kind.PULSE).isFile) {
            try {
                start(ctx, Kind.PULSE)
            } catch (e: Exception) {
                Log.w(TAG, "ensure Pulse", e)
            }
        }
    }

    /**
     * Backward-compatible alias for terminal spawn.
     */
    fun ensureGuestGpu(ctx: Context) {
        ensureServers(ctx)
    }

    fun stop(kind: Kind): String {
        if (kind == Kind.VENUS) {
            try {
                venusProcess?.destroy()
            } catch (_: Throwable) {
            }
            venusProcess = null
        }
        if (kind == Kind.PULSE) {
            try {
                pulseProcess?.destroy()
            } catch (_: Throwable) {
            }
            pulseProcess = null
        }
        val pids = findPids(processName(kind))
        for (pid in pids) {
            try {
                android.os.Process.killProcess(pid)
            } catch (_: Throwable) {
            }
            try {
                ProcessBuilder("/system/bin/kill", "-9", pid.toString()).start().waitFor()
            } catch (_: Throwable) {
            }
        }
        if (kind == Kind.VENUS) {
            socketFile().delete()
        }
        Thread.sleep(200)
        val after = status(kind)
        return if (after.running) {
            "${label(kind)}: still ${after.detail}"
        } else {
            "${label(kind)}: stopped"
        }
    }

    fun restart(ctx: Context, kind: Kind): String {
        val stopped = stop(kind)
        val started = start(ctx, kind)
        return "$stopped; $started"
    }

    internal fun venusArgv(binary: String, socketPath: String, helpText: String): List<String> {
        val args = mutableListOf(binary, "--socket-path", socketPath)
        if (helpText.contains("--venus")) {
            if (helpText.contains("--no-virgl")) {
                args += listOf("--no-virgl", "--venus")
            } else {
                args += "--venus"
            }
        }
        return withLinker(args)
    }

    internal fun pulseArgv(binary: String): List<String> {
        // linker64 so ProcessBuilder can start a $PREFIX ELF (targetSdk 29+
        // blocks exec of /data/data). --daemonize=no avoids pulse's --start
        // re-exec, which fails when /proc/self/exe is linker64.
        return withLinker(
            listOf(
                binary,
                "--daemonize=no",
                "--disallow-exit",
                "--dl-search-path", "${TermuxHostPaths.LIB}/pulseaudio/modules",
                "--load", TCP_MODULE,
                "--exit-idle-time=-1"
            )
        )
    }

    internal fun classify(binaryExists: Boolean, running: Boolean, socketLive: Boolean): String {
        if (!binaryExists) return "MISSING"
        if (!running) return "STOPPED"
        return if (socketLive) "RUNNING" else "RUNNING"
    }

    private fun startVenus(ctx: Context): String {
        val bin = binary(Kind.VENUS).absolutePath
        val sock = socketFile().absolutePath
        val help = runHelp(ctx, bin)
        val argv = venusArgv(bin, sock, help)
        val proc = spawn(ctx, argv)
        venusProcess = proc
        Thread.sleep(800)
        // PRoot guest must connect to this AF_UNIX socket (not /dev/dri).
        try {
            ProcessBuilder("/system/bin/chmod", "777", sock).start().waitFor(1, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            socketFile().setReadable(true, false)
            socketFile().setWritable(true, false)
            socketFile().setExecutable(true, false)
        }
        val st = status(Kind.VENUS)
        return if (st.running) {
            "Venus: ${st.detail}"
        } else {
            "Venus: start failed (${st.detail})"
        }
    }

    private fun startPulse(ctx: Context): String {
        val bin = binary(Kind.PULSE).absolutePath
        val argv = pulseArgv(bin)
        val proc = spawn(ctx, argv)
        pulseProcess = proc
        Thread.sleep(800)
        val code = if (proc.isAlive) 0 else proc.exitValue()
        val pacmd = File(TermuxHostPaths.BIN, "pacmd")
        if (pacmd.isFile) {
            val p2 = spawn(
                ctx,
                withLinker(listOf(pacmd.absolutePath, "load-module", "module-native-protocol-tcp", "auth-ip-acl=127.0.0.1", "auth-anonymous=1"))
            )
            p2.waitFor(4, TimeUnit.SECONDS)
        }
        val st = status(Kind.PULSE)
        return if (st.running) {
            "PulseAudio: ${st.detail} (exit=$code)"
        } else {
            "PulseAudio: start failed (${st.detail}, exit=$code)"
        }
    }

    private fun runHelp(ctx: Context, binary: String): String {
        return try {
            val pb = ProcessBuilder(withLinker(listOf(binary, "--help")))
            HostCommandBuilder.applyTo(ctx, pb)
            val env = pb.environment()
            env.remove("LD_PRELOAD")
            // PREFIX/lib shadows Android libEGL and breaks linker (Xzs_Construct).
            env["LD_LIBRARY_PATH"] = "/system/lib64:${TermuxHostPaths.PREFIX}/opt/virglrenderer-android/lib"
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(2, TimeUnit.SECONDS)
            out
        } catch (e: Exception) {
            Log.w(TAG, "help failed", e)
            ""
        }
    }

    private fun spawn(ctx: Context, argv: List<String>): Process {
        val pb = ProcessBuilder(argv)
        HostCommandBuilder.applyTo(ctx, pb)
        val env = pb.environment()
        env.remove("LD_PRELOAD")
        val isPulse = argv.any {
            it.endsWith("pulseaudio") || it.endsWith("/pacmd") || it.endsWith("/pactl")
        }
        if (isPulse) {
            env["LD_LIBRARY_PATH"] = TermuxHostPaths.LIB
        } else {
            // PREFIX/lib shadows Android libEGL/libvulkan and breaks vtest (Xzs_Construct).
            env["LD_LIBRARY_PATH"] = "/system/lib64:${TermuxHostPaths.PREFIX}/opt/virglrenderer-android/lib"
            val rs = File(TermuxHostPaths.PREFIX, "opt/virglrenderer-android/libexec/virgl_render_server")
            val rsAlt = File(TermuxHostPaths.PREFIX, "libexec/virgl_render_server")
            when {
                rs.isFile -> env["RENDER_SERVER_EXEC_PATH"] = rs.absolutePath
                rsAlt.isFile -> env["RENDER_SERVER_EXEC_PATH"] = rsAlt.absolutePath
            }
        }
        env["XDG_RUNTIME_DIR"] = TermuxHostPaths.TMPDIR
        env["PULSE_RUNTIME_PATH"] = File(TermuxHostPaths.HOME, ".pulse").absolutePath
        env["PULSE_STATE_PATH"] = File(TermuxHostPaths.HOME, ".pulse").absolutePath
        pb.redirectErrorStream(true)
        File(TermuxHostPaths.TMPDIR).mkdirs()
        pb.directory(File(TermuxHostPaths.HOME))
        val proc = pb.start()
        Thread({
            try {
                proc.inputStream.bufferedReader().use { r ->
                    while (r.readLine() != null) {
                        // drain
                    }
                }
            } catch (_: Throwable) {
            }
        }, "host-server-drain").apply { isDaemon = true }.start()
        return proc
    }

    internal fun withLinker(args: List<String>): List<String> {
        val first = args.firstOrNull().orEmpty()
        if (first == LINKER || first.startsWith("/system/")) {
            return args
        }
        return listOf(LINKER) + args
    }

    internal fun findPids(needle: String): List<Int> {
        val proc = File("/proc")
        val dirs = proc.listFiles() ?: return emptyList()
        val hits = ArrayList<Int>()
        for (dir in dirs) {
            val pid = dir.name.toIntOrNull() ?: continue
            val cmd = try {
                File(dir, "cmdline").readBytes().toString(Charsets.ISO_8859_1).replace('\u0000', ' ')
            } catch (_: Throwable) {
                continue
            }
            if (cmd.contains(needle)) hits += pid
        }
        return hits
    }

    private fun label(kind: Kind): String = when (kind) {
        Kind.VENUS -> "Venus"
        Kind.PULSE -> "PulseAudio"
    }
}
