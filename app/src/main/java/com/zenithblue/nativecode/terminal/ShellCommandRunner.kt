package com.zenithblue.nativecode.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper

/** Cancel handle for a streamed shell process. Safe before the process exists. */
class ShellJob {
    @Volatile
    var cancelled: Boolean = false
        private set

    @Volatile
    private var process: Process? = null

    fun attach(proc: Process) {
        process = proc
        if (cancelled) {
            try { proc.destroyForcibly() } catch (_: Exception) {}
        }
    }

    fun cancel() {
        cancelled = true
        try { process?.destroyForcibly() } catch (_: Exception) {}
    }

    /** Writes [text] + newline to the process stdin (e.g. a pasted login code). */
    fun writeLine(text: String): Boolean = try {
        process?.outputStream?.let { it.write((text + "\n").toByteArray()); it.flush(); true } ?: false
    } catch (_: Exception) {
        false
    }
}

/** Runs shell commands with proper environment, supporting both proot and chroot. */
object ShellCommandRunner {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Host ET_DYN tools (libbash.so / libproot.so) live under /data/app or /data/data.
     * Prefix with system linker64 so Bionic resolves NEEDED libs via LD_LIBRARY_PATH / RUNPATH.
     */
    private fun adjustHostCmd(cmd: Array<String>): Array<String> {
        if (cmd.isEmpty()) return cmd
        val exe = cmd[0]
        if (exe == "/system/bin/linker64" || exe.startsWith("/system/")) return cmd
        val underAppData =
            exe.startsWith("/data/data/") ||
                exe.startsWith("/data/app/") ||
                exe.startsWith("/data/user/")
        return if (underAppData) arrayOf("/system/bin/linker64") + cmd else cmd
    }

    /** Runs a command and returns its exit code. Output is consumed but not returned. */
    fun run(ctx: Context, cmd: Array<String>, envMap: Map<String, String>? = null): Int {
        val pb = ProcessBuilder(*adjustHostCmd(cmd))
        applyEnvironment(ctx, pb, envMap)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val stream = proc.inputStream
        val buf = ByteArray(1024)
        while (stream.read(buf) != -1) { /* consume */ }
        return proc.waitFor()
    }

    /** Runs a command and returns combined stdout/stderr as a string (blocking). */
    fun runCapture(ctx: Context, cmd: Array<String>, envMap: Map<String, String>? = null): String {
        val pb = ProcessBuilder(*adjustHostCmd(cmd))
        applyEnvironment(ctx, pb, envMap)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val text = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        proc.waitFor()
        return text
    }

    /** Blocking capture with exit code (call from bg thread). */
    fun runCaptureExit(
        ctx: Context,
        cmd: Array<String>,
        envMap: Map<String, String>? = null
    ): Pair<Int, String> {
        val pb = ProcessBuilder(*adjustHostCmd(cmd))
        applyEnvironment(ctx, pb, envMap)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val text = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exit = proc.waitFor()
        return exit to text
    }

    /** Runs a command and streams each output line to [onLine] on the main thread. */
    fun runStreamed(
        ctx: Context,
        cmd: Array<String>,
        envMap: Map<String, String>? = null,
        onLine: (line: String) -> Unit,
        onDone: (exitCode: Int) -> Unit
    ) {
        runStreamedCancelable(ctx, cmd, envMap, onLine, onDone)
    }

    /**
     * Streamed run with cancel handle. [onLine]/[onDone] post to main thread.
     * Process starts on a worker thread; cancel is safe before/after start.
     */
    fun runStreamedCancelable(
        ctx: Context,
        cmd: Array<String>,
        envMap: Map<String, String>? = null,
        onLine: (line: String) -> Unit,
        onDone: (exitCode: Int) -> Unit
    ): ShellJob {
        val pb = ProcessBuilder(*adjustHostCmd(cmd))
        applyEnvironment(ctx, pb, envMap)
        pb.redirectErrorStream(true)

        val job = ShellJob()
        val queue = java.util.concurrent.LinkedBlockingQueue<String>(256)
        val drainPosted = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val exitCode = java.util.concurrent.atomic.AtomicInteger(-1)

        fun finish(code: Int) {
            if (completed.compareAndSet(false, true)) onDone(code)
        }

        fun scheduleDrain() {
            if (!drainPosted.compareAndSet(false, true)) return
            mainHandler.post {
                var n = 0
                val start = System.nanoTime()
                while (n < 64 && System.nanoTime() - start < 4_000_000L) {
                    val line = queue.poll() ?: break
                    onLine(line)
                    n++
                }
                drainPosted.set(false)
                if (queue.isNotEmpty()) {
                    scheduleDrain()
                } else if (done.get()) {
                    if (queue.isNotEmpty()) scheduleDrain()
                    else finish(exitCode.get())
                }
            }
        }

        Thread {
            val proc = try {
                if (job.cancelled) {
                    done.set(true)
                    scheduleDrain()
                    return@Thread
                }
                pb.start().also { job.attach(it) }
            } catch (_: Exception) {
                done.set(true)
                scheduleDrain()
                return@Thread
            }
            if (job.cancelled) {
                try { proc.destroyForcibly() } catch (_: Exception) {}
            }
            try {
                val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (job.cancelled) break
                    queue.put(line ?: continue)
                    scheduleDrain()
                }
            } catch (_: Exception) {
            }
            exitCode.set(try {
                if (job.cancelled) {
                    try { proc.destroyForcibly() } catch (_: Exception) {}
                    -1
                } else {
                    proc.waitFor()
                }
            } catch (_: Exception) {
                -1
            })
            done.set(true)
            scheduleDrain()
        }.start()

        return job
    }

    private fun applyEnvironment(ctx: Context, pb: ProcessBuilder, envMap: Map<String, String>?) {
        val env = pb.environment()
        HostCommandBuilder.applyTo(ctx, pb, forceHostSetup = false)
        env["GIT_TERMINAL_PROMPT"] = "0"

        // Override with envMap if provided (e.g. chroot-specific values)
        if (envMap != null) {
            envMap.forEach { (k, v) ->
                env[k] = v
            }
            if (!envMap.containsKey("LD_PRELOAD")) {
                env.remove("LD_PRELOAD")
            }
        }
    }
}
