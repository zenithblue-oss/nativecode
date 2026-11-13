package com.zenithblue.nativecode.agent.chat.core

import android.content.Context
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * One agent CLI on stdio pipes (no PTY), for the chat runtimes (item 12). Stdout is split into lines on
 * `\n` bytes and each whole line is decoded as UTF-8 (a multi-byte char split across reads stays intact);
 * [onLine] runs on the reader thread, which never touches the UI. Stderr drains on its own thread into a
 * [STDERR_CAP] ring, so a stderr flood never blocks stdout and the gate's `NativeCode:` deny line is kept.
 * [onExit] runs once, after both streams end, with the exit code and the stderr tail.
 *
 * The env is exactly the given map (the terminal-tab contract: `environment().clear()`), never the app's own
 * env, which would leak host LD_LIBRARY_PATH / TERMUX_* into the guest (R4 B1).
 */
class PipeProcess(
    private val argv: List<String>,
    private val env: Map<String, String>,
    private val cwd: File,
    private val onLine: (String) -> Unit,
    private val onExit: (code: Int, stderrTail: String) -> Unit,
) {
    @Volatile private var proc: Process? = null
    private var stdin: OutputStream? = null // guarded by this
    private var err = ByteArray(0) // guarded by errLock
    private val errLock = Any()

    val isAlive: Boolean get() = proc?.isAlive == true

    fun start() {
        check(proc == null) { "already started" }
        val p = ProcessBuilder(argv).directory(cwd).also {
            it.environment().clear()
            it.environment().putAll(env)
        }.start()
        proc = p
        synchronized(this) { stdin = p.outputStream }
        val errThread = Thread({ drainStderr(p) }, "pipe-err").apply { isDaemon = true; start() }
        Thread({
            readStdout(p)
            val code = p.waitFor()
            errThread.join(2_000)
            onExit(code, stderrTail())
        }, "pipe-out").apply { isDaemon = true; start() }
    }

    /** One protocol message. Synchronized: a prompt and an approval reply from two threads never interleave. */
    @Synchronized
    fun writeLine(line: String): Boolean = try {
        val out = stdin ?: return false
        out.write((line + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
        true
    } catch (_: Exception) {
        false
    }

    /** Graceful stop off the caller's thread: stdin EOF, then SIGTERM to the tree after 2 s, then SIGKILL after 2 s more. */
    fun close() {
        synchronized(this) {
            runCatching { stdin?.close() }
            stdin = null
        }
        val p = proc ?: return
        Thread({
            if (!p.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)) {
                signalTree(p, TERM)
                if (!p.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)) kill()
            }
        }, "pipe-close").apply { isDaemon = true; start() }
    }

    /** Force stop now (hung turn). */
    fun kill() {
        val p = proc ?: return
        signalTree(p, KILL)
        p.destroyForcibly()
    }

    /**
     * Signal every guest task, then PRoot. PRoot ignores SIGTERM and a SIGKILLed PRoot never runs --kill-on-exit,
     * so its tracees would live on (an `opencode serve` holding the port and our stdout pipe, so [onExit] never
     * fires). Snapshot first: once PRoot dies they are reparented and no longer traced. Same walk as the
     * terminal tab's `terminateSessionProcesses`.
     */
    private fun signalTree(p: Process, sig: String) {
        // ponytail: pid from Process.toString() ("Process[pid=N, ...]" on Android and the JVM); Process.pid() is not in the SDK
        val root = PID.find(p.toString())?.groupValues?.get(1)?.toIntOrNull()
        if (root != null) runCatching { // toybox/coreutils `kill`: same uid, no hidden API, works in JVM tests
            ProcessBuilder(listOf(if (File("/system/bin/kill").exists()) "/system/bin/kill" else "kill", "-$sig") + (treeOf(root) + root).map { it.toString() }).redirectErrorStream(true).start()
                .apply { inputStream.readBytes(); waitFor(GRACE_MS, TimeUnit.MILLISECONDS) }
        }
        else if (sig == TERM) p.destroy()
    }

    /** Descendants (PPid) and tracees (TracerPid) of [root], from one /proc snapshot. */
    private fun treeOf(root: Int): List<Int> {
        val parent = HashMap<Int, Int>()
        val traced = ArrayList<Int>()
        File("/proc").listFiles()?.forEach { f ->
            val pid = f.name.toIntOrNull() ?: return@forEach
            if (pid <= 1 || pid == root) return@forEach
            runCatching {
                File(f, "status").forEachLine { l ->
                    if (l.startsWith("PPid:")) parent[pid] = l.substring(5).trim().toInt()
                    else if (l.startsWith("TracerPid:") && l.substring(10).trim().toInt() == root) traced += pid
                }
            }
        }
        val out = LinkedHashSet(traced)
        var frontier = setOf(root) + traced
        while (frontier.isNotEmpty()) {
            frontier = parent.filter { (pid, pp) -> pp in frontier && out.add(pid) }.keys
        }
        return out.toList()
    }

    fun stderrTail(): String = synchronized(errLock) { String(err, Charsets.UTF_8) }

    private fun readStdout(p: Process) {
        val buf = ByteArray(32 * 1024)
        val line = ByteArrayOutputStream(1024)
        try {
            val input = p.inputStream
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                var start = 0
                for (i in 0 until n) {
                    if (buf[i] != NL) continue
                    line.write(buf, start, i - start)
                    start = i + 1
                    deliver(line)
                }
                line.write(buf, start, n - start)
            }
            if (line.size() > 0) deliver(line) // last line without a trailing newline
        } catch (_: Exception) {
            // stream closed by kill(): exit path below still reports
        }
    }

    private fun deliver(line: ByteArrayOutputStream) {
        var s = line.toString(Charsets.UTF_8.name())
        line.reset()
        if (s.endsWith("\r")) s = s.dropLast(1)
        if (s.isNotEmpty()) runCatching { onLine(s) }
    }

    private fun drainStderr(p: Process) {
        val buf = ByteArray(8 * 1024)
        try {
            val input = p.errorStream
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                synchronized(errLock) {
                    val merged = err + buf.copyOf(n)
                    err = if (merged.size > STDERR_CAP) merged.copyOfRange(merged.size - STDERR_CAP, merged.size) else merged
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val STDERR_CAP = 64 * 1024
        private const val GRACE_MS = 2_000L
        private const val TERM = "TERM"
        private const val KILL = "KILL"
        private val PID = Regex("""pid=(\d+)""")
        private const val NL = '\n'.code.toByte()

        /**
         * A gated agent CLI the way a terminal tab starts it: PRoot → /tmp/launch_tool.sh → gate shim → CLI,
         * so the session is counted and the agent's own shell tool inherits the gate PATH (plan 9.2.9).
         * [extraEnv] reaches the CLI: PRoot passes its env to the guest, and neither the bash -c wrapper,
         * launch_tool.sh nor the gate shim clear it (they only export additions).
         */
        fun forTool(
            ctx: Context,
            toolArgv: List<String>,
            workDir: String?,
            onLine: (String) -> Unit,
            onExit: (code: Int, stderrTail: String) -> Unit,
            extraEnv: Map<String, String> = emptyMap(),
        ): PipeProcess {
            val (args, env) = LinuxCommandBuilder.buildToolDirect(ctx, toolArgv, workDir = workDir)
            val argv = LinuxCommandBuilder.sessionArgs(ctx, args, method = "proot").toList()
            val pipeEnv = HashMap(env).apply { put("NO_COLOR", "1"); put("TERM", "dumb"); putAll(extraEnv) }
            return PipeProcess(argv, pipeEnv, File(ctx.filesDir, "home"), onLine, onExit)
        }
    }
}
