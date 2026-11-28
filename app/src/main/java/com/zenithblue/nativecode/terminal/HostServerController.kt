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
    private val TCP_MODULE get() = "module-native-protocol-tcp ${PulseHost.tcpArgs()}"

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

    @Volatile
    private var bgThread: Thread? = null

    private val bgExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "host-server-controller-bg").apply {
            isDaemon = true
            bgThread = this
        }
    }

    private val ensureLock = Any()

    /** In-flight prepare, keyed by rootfs + GPU config generation. Same key joins; a new key is queued, not dropped. */
    private val inFlightByGen = HashMap<String, java.util.concurrent.Future<*>>()

    @Volatile
    private var cachedVenusStatus: Pair<Long, Status>? = null
    @Volatile
    private var cachedPulseStatus: Pair<Long, Status>? = null
    private const val STATUS_CACHE_TTL_MS = 5000L

    fun invalidateStatusCache() {
        cachedVenusStatus = null
        cachedPulseStatus = null
    }

    fun binary(kind: Kind): File = when (kind) {
        Kind.VENUS -> File(TermuxHostPaths.BIN, "virgl_test_server_android")
        Kind.PULSE -> File(TermuxHostPaths.BIN, "pulseaudio")
    }

    fun socketFile(): File = File(TermuxHostPaths.TMPDIR, ".virgl_test")

    fun processName(kind: Kind): String = when (kind) {
        Kind.VENUS -> "virgl_test_server_android"
        Kind.PULSE -> "pulseaudio"
    }

    fun status(kind: Kind, forceRefresh: Boolean = false): Status {
        val now = System.nanoTime() / 1_000_000L
        if (!forceRefresh) {
            val live = if (kind == Kind.VENUS) venusProcess else pulseProcess
            val handleDead = live != null && !live.isAlive
            val cached = when (kind) {
                Kind.VENUS -> cachedVenusStatus
                Kind.PULSE -> cachedPulseStatus
            }
            // Venus "running" with a missing socket is not healthy — fall through so recovery can see it.
            val socketDown = kind == Kind.VENUS &&
                cached?.second?.running == true &&
                !socketFile().exists()
            if (cached != null &&
                (now - cached.first) < STATUS_CACHE_TTL_MS &&
                !handleDead &&
                !socketDown
            ) {
                return cached.second
            }
            // Live handle is enough inside the health window. /proc only when dead, socket-down, stale, or forced.
            if (live?.isAlive == true && (kind != Kind.VENUS || socketFile().exists())) {
                val st = Status(kind, binary(kind).isFile, true, "running (live handle)")
                when (kind) {
                    Kind.VENUS -> cachedVenusStatus = now to st
                    Kind.PULSE -> cachedPulseStatus = now to st
                }
                return st
            }
        }
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
        val st = Status(kind, bin.isFile, running, detail)
        when (kind) {
            Kind.VENUS -> cachedVenusStatus = now to st
            Kind.PULSE -> cachedPulseStatus = now to st
        }
        return st
    }

    fun start(ctx: Context, kind: Kind, forceRefresh: Boolean = false): String {
        val st = status(kind, forceRefresh)
        if (!st.binaryExists) return "${label(kind)}: binary missing (rebuild bootstrap)"
        if (st.running) return "${label(kind)}: already ${st.detail}"
        File(TermuxHostPaths.TMPDIR).mkdirs()
        File(TermuxHostPaths.HOME, ".pulse").mkdirs()
        val result = when (kind) {
            Kind.VENUS -> startVenus(ctx)
            Kind.PULSE -> startPulse(ctx)
        }
        invalidateStatusCache()
        return result
    }

    /**
     * Ensure both Venus and PulseAudio servers are running by default.
     * Runs asynchronously on a background executor by default to keep the UI thread responsive.
     */
    fun ensureServers(ctx: Context, async: Boolean = true) {
        val app = ctx.applicationContext
        val key = configGeneration(app)
        val future = synchronized(ensureLock) {
            inFlightByGen.entries.removeAll { it.value.isDone }
            val flying = inFlightByGen[key]
            if (flying != null && !flying.isDone) {
                flying
            } else {
                val submitted = bgExecutor.submit { runEnsureServers(app) }
                inFlightByGen[key] = submitted
                submitted
            }
        }
        if (!async && Thread.currentThread() != bgThread) {
            try {
                future.get()
            } catch (_: Throwable) {
            }
        }
    }

    /** Rootfs path + the GPU files GuestGpuConfig would write. Different config is a different key. */
    private fun configGeneration(ctx: Context): String {
        val detect = GpuAccelDetector.detect()
        val alpaquita = File(ctx.filesDir, "usr/var/lib/proot-distro/containers/alpaquita/rootfs")
        val alpine = File(ctx.filesDir, "usr/var/lib/proot-distro/containers/alpine/rootfs")
        val root = when {
            alpaquita.isDirectory -> alpaquita.absolutePath
            alpine.isDirectory -> alpine.absolutePath
            else -> ""
        }
        val backend = when (detect.mode) {
            GpuAccelDetector.MODE_TURNIP -> GpuAccelDetector.MODE_TURNIP
            GpuAccelDetector.MODE_PANVK -> "panvk-zink"
            else -> "zink-venus"
        }
        return listOf(root, detect.mode, backend, detect.vendorHint, GpuAccelDetector.methodLabel(backend))
            .joinToString("\u0000")
    }

    private fun runEnsureServers(ctx: Context) {
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
     * Backward-compatible alias for terminal spawn. Non-blocking async.
     */
    fun ensureGuestGpu(ctx: Context) {
        ensureServers(ctx, async = true)
    }

    fun stop(kind: Kind): String {
        invalidateStatusCache()
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
                // a sink module that fails to load must not take the whole daemon (and its TCP port) down
                "--fail=no",
                "--dl-search-path", "${TermuxHostPaths.LIB}/pulseaudio/modules",
                "--load", TCP_MODULE,
                "--load", PulseHost.SINK_MODULE,
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
                withLinker(listOf(pacmd.absolutePath, "load-module", "module-native-protocol-tcp") + PulseHost.tcpArgs().split(" "))
            )
            p2.waitFor(4, TimeUnit.SECONDS)
        }
        if (proc.isAlive && PulseHost.needsFallback(pactl(ctx, "list", "short", "sinks"))) {
            Log.w(TAG, "no sink after ${PulseHost.SINK_MODULE}; trying ${PulseHost.FALLBACK_SINK_MODULE}")
            pactl(ctx, "load-module", PulseHost.FALLBACK_SINK_MODULE)
        }
        val st = status(Kind.PULSE)
        return if (st.running) {
            "PulseAudio: ${st.detail} (exit=$code)"
        } else {
            "PulseAudio: start failed (${st.detail}, exit=$code)"
        }
    }

    /** `pactl -s <this build's server> args...` with the pulse env; returns its output ("" on any failure). */
    private fun pactl(ctx: Context, vararg args: String): String {
        val bin = File(TermuxHostPaths.BIN, "pactl")
        if (!bin.isFile) return ""
        return try {
            val pb = hostBuilder(ctx, withLinker(listOf(bin.absolutePath, "-s", PulseHost.server()) + args))
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(4, TimeUnit.SECONDS)
            out
        } catch (e: Exception) {
            Log.w(TAG, "pactl failed", e)
            ""
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

    private fun hostBuilder(ctx: Context, argv: List<String>): ProcessBuilder {
        val pb = ProcessBuilder(argv)
        HostCommandBuilder.applyTo(ctx, pb)
        val env = pb.environment()
        env.remove("LD_PRELOAD")
        val isPulse = argv.any {
            it.endsWith("pulseaudio") || it.endsWith("/pacmd") || it.endsWith("/pactl")
        }
        if (isPulse) {
            env["LD_LIBRARY_PATH"] = PulseHost.ldPath(TermuxHostPaths.LIB)
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
        return pb
    }

    private fun spawn(ctx: Context, argv: List<String>): Process {
        val proc = hostBuilder(ctx, argv).start()
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
