package com.zenithblue.nativecode.git

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.terminal.ShellCommandRunner

/**
 * One long-lived `git log` process. A worker thread parses lines, runs the lane layout and
 * hands rows to the main thread in batches (first after [FIRST_BATCH], then every [BATCH]).
 * It stops reading once [allow] rows are in; the pipe fills and git blocks, so a huge repo
 * costs nothing until the user scrolls. [cancel] kills the process.
 *
 * [onBatch] / [onEnd] run on the main thread; [onBatch] also gets a layout snapshot taken at the
 * batch end so a killed stream can resume with `--skip` and the same lane state.
 */
class GitLogStream(
    private val ctx: Context,
    private val project: String,
    private val method: String,
    private val ref: String?,
    private val skip: Int,
    private val layout: GitGraphLayout,
    private val onBatch: (rows: List<GraphRow>, resume: GitGraphLayout) -> Unit,
    private val onEnd: (finished: Boolean, exit: Int) -> Unit
) {
    @Volatile private var cancelled = false
    @Volatile private var proc: Process? = null
    @Volatile private var allow = 0

    /** Raise the row budget (absolute row count, including [skip]). */
    fun allowUpTo(rows: Int) {
        if (rows > allow) allow = rows
    }

    fun cancel() {
        cancelled = true
        try { proc?.destroyForcibly() } catch (_: Exception) {}
    }

    fun start() {
        Thread({ run() }, "git-log").start()
    }

    private fun run() {
        var finished = false
        var exit = -1
        try {
            val (args, env) = LinuxCommandBuilder.build(ctx, GitGuestCommands.logStream(project, ref, skip), method = method)
            val full = HashMap(env).apply { putAll(GitGuestCommands.gitEnv(project)) }
            val p = ShellCommandRunner.startStdout(ctx, args, full)
            proc = p
            if (cancelled) p.destroyForcibly()
            var batch = ArrayList<GraphRow>(BATCH)
            var delivered = 0
            var total = skip
            val buf = ByteArray(32 * 1024)
            val line = java.io.ByteArrayOutputStream(512)
            val input = p.inputStream
            loop@ while (!cancelled) {
                val n = input.read(buf)
                if (n < 0) break
                var start = 0
                for (i in 0 until n) {
                    if (buf[i] != '\n'.code.toByte()) continue
                    line.write(buf, start, i - start)
                    start = i + 1
                    val c = GitLogParse.parseLine(line.toString(Charsets.UTF_8.name()))
                    line.reset()
                    if (c == null) continue
                    batch.add(layout.add(c))
                    total++
                    if (batch.size >= (if (delivered == 0) FIRST_BATCH else BATCH)) {
                        delivered += batch.size
                        post(batch, layout.copy())
                        batch = ArrayList(BATCH)
                        // Backpressure: stop reading (git blocks on the full pipe) until the UI wants more.
                        // ponytail: 40 ms poll while parked; condition variable if this ever shows in a profile.
                        while (!cancelled && total >= allow) Thread.sleep(40)
                        if (cancelled) break@loop
                    }
                }
                line.write(buf, start, n - start)
            }
            if (!cancelled && batch.isNotEmpty()) post(batch, layout.copy())
            if (!cancelled) { exit = p.waitFor(); finished = true } else p.destroyForcibly()
        } catch (_: Exception) {
            proc?.destroyForcibly()
        }
        if (!cancelled) MAIN.post { if (!cancelled) onEnd(finished, exit) }
    }

    private fun post(rows: List<GraphRow>, resume: GitGraphLayout) {
        MAIN.post { if (!cancelled) onBatch(rows, resume) }
    }

    companion object {
        const val FIRST_BATCH = 40
        const val BATCH = 500
        private val MAIN = Handler(Looper.getMainLooper())
    }
}
