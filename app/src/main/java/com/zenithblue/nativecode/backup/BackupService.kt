package com.zenithblue.nativecode.backup

import android.content.Context
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.cliauth.CliToolCatalog
import com.zenithblue.nativecode.cliauth.CliVersionCheck
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import java.io.File
import java.util.concurrent.Executors
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Cancel handle shared by backup, verify and restore. */
class BackupJob {
    @Volatile var cancelled = false
        private set
    @Volatile private var proc: Process? = null

    fun attach(p: Process) { proc = p; if (cancelled) p.destroyForcibly() }
    fun cancel() { cancelled = true; proc?.destroyForcibly() }
}

data class BackupResult(val ok: Boolean, val sha256: String, val bytes: Long, val files: Long, val message: String?, val cancelled: Boolean, val app: Boolean = false)
/** [sha256] = hash of the archive without its embedded trailer; [manifest] = the embedded manifest, null for an old two-file archive. */
data class VerifyResult(val ok: Boolean, val sha256: String, val bytes: Long, val members: Long, val topLevel: List<String>, val message: String?, val cancelled: Boolean, val manifest: Manifest? = null)
data class ExtractResult(val ok: Boolean, val message: String?, val cancelled: Boolean)

/**
 * Backup and restore of the guest home through guest `tar --zstd` (plan 15). The archive travels as raw bytes
 * between a SAF stream and the guest process; sha256 is computed on the host while copying.
 * Always the proot guest: the chroot rootfs is root-owned and outside the app's storage.
 * Every function blocks: call from a background thread.
 */
object BackupService {
    private const val METHOD = "proot"
    private const val BUF = 256 * 1024

    private fun start(ctx: Context, cmd: String, mergeStderr: Boolean): Process {
        val (args, env) = LinuxCommandBuilder.build(ctx, cmd, method = METHOD)
        return ShellCommandRunner.startPiped(ctx, args, env, mergeStderr)
    }

    /**
     * Drains a process stream until it ends. Counts the member lines (tar `-v` prints one per file on stderr) and
     * keeps the last [keep] diagnostic lines. With [allDiag] every line is a diagnostic (no `-v`, merged output).
     */
    private class ErrDrain(stream: InputStream, allDiag: Boolean = false, private val keep: Int = 12) {
        @Volatile var lines = 0L
        private val tail = ArrayDeque<String>()
        private val t = Thread {
            try {
                stream.bufferedReader(Charsets.UTF_8).forEachLine {
                    if (allDiag || it.startsWith("tar: ") || it.startsWith("zstd")) {
                        synchronized(tail) { tail.addLast(it); if (tail.size > keep) tail.removeFirst() }
                    } else lines++
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        fun join() { t.join(2000) }
        fun text(): String = synchronized(tail) { tail.joinToString("\n") }
    }

    private fun hex(md: MessageDigest) = md.digest().joinToString("") { "%02x".format(it) }

    /** Registered projects living outside ~/repos, so the Projects scope covers a custom location too. */
    private fun extraProjects(ctx: Context): List<String> = try {
        val arr = org.json.JSONArray(ctx.getSharedPreferences("nativecode_prefs", Context.MODE_PRIVATE).getString("projects_json", "[]"))
        BackupPlan.extraProjectDirs((0 until arr.length()).map { arr.getJSONObject(it).getString("path") })
    } catch (_: Exception) {
        emptyList()
    }

    /** Size of the selection in bytes before compression, or null when it can not be estimated. */
    fun estimate(ctx: Context, scope: BackupScope, patterns: List<String>, job: BackupJob): Long? = try {
        val p = start(ctx, BackupPlan.estimateCommand(scope, patterns, extraProjects(ctx)), mergeStderr = false)
        job.attach(p)
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        if (job.cancelled) null else BackupPlan.parseEstimateKb(out)
    } catch (_: Exception) {
        null
    }

    /**
     * Streams `tar --zstd -c` into [out]. [onProgress] gets the compressed bytes written so far, a few times a second.
     * The caller deletes the destination document when the result is not ok.
     */
    fun backup(ctx: Context, out: OutputStream, scope: BackupScope, patterns: List<String>, job: BackupJob, onProgress: (Long) -> Unit): BackupResult {
        // the app's chat data is staged into the guest home so the same tar archives it; always removed again
        val appDir = File(ProjectPathResolver.guestHomeDir(ctx, METHOD), BackupPlan.APP_DIR)
        val app = AppDataBackup.stage(ctx, appDir)
        try {
            return tarBackup(ctx, out, scope, patterns, app, job, onProgress)
        } finally {
            appDir.deleteRecursively()
        }
    }

    /** Appends the manifest as the archive's trailer (one-file backup). Call once, after a successful [backup], on the same stream. */
    fun appendManifest(out: OutputStream, m: Manifest) {
        out.write(BackupTrailer.encode(BackupPlan.manifestJson(m)))
        out.flush()
    }

    /** `cli.<id>` -> version for every CLI with a readable `--version`, recorded in the manifest so a restore can offer an update. Blocking. */
    fun cliVersions(ctx: Context): Map<String, String> {
        val pool = Executors.newFixedThreadPool(3)
        try {
            val jobs = CliToolCatalog.ALL.map { d -> pool.submit<Pair<String, String?>> { d.id to runCatching { CliVersionCheck.check(ctx, d.id)?.installed }.getOrNull() } }
            return jobs.mapNotNull { f -> runCatching { f.get() }.getOrNull() }.filter { it.second != null }.associate { BackupPlan.cliKey(it.first) to it.second!! }
        } finally { pool.shutdown() }
    }

    private fun tarBackup(ctx: Context, out: OutputStream, scope: BackupScope, patterns: List<String>, app: Boolean, job: BackupJob, onProgress: (Long) -> Unit): BackupResult {
        val md = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var drain: ErrDrain? = null
        try {
            val p = start(ctx, BackupPlan.createCommand(scope, patterns, extraProjects(ctx), app), mergeStderr = false)
            job.attach(p)
            p.outputStream.close()
            drain = ErrDrain(p.errorStream)
            val buf = ByteArray(BUF)
            var last = 0L
            p.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (job.cancelled) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    total += n
                    val now = System.nanoTime()
                    if (now - last > 250_000_000L) { last = now; onProgress(total) }
                }
            }
            if (job.cancelled) { p.destroyForcibly(); return BackupResult(false, "", total, 0, "Cancelled", true) }
            out.flush()
            val exit = p.waitFor()
            drain.join()
            onProgress(total)
            // tar exit 1 = some file changed while it was read: the archive is still complete and valid.
            val ok = exit == 0 || exit == 1
            val msg = when {
                ok -> if (exit == 1) "Some files changed while being archived." else null
                exit == 3 -> "There is no repos folder to back up."
                else -> "tar failed (exit $exit)" + drain.text().let { if (it.isBlank()) "" else ": $it" }
            }
            return BackupResult(ok, hex(md), total, drain.lines, msg, false, app)
        } catch (e: Exception) {
            return BackupResult(false, "", total, 0, if (job.cancelled) "Cancelled" else (e.message ?: e.javaClass.simpleName), job.cancelled)
        }
    }

    /**
     * Pass 1 of a restore: one read of the archive feeds sha256 and a guest `tar -t`, which lists every member.
     * Rejects absolute paths and `..` members; returns the top-level entry names.
     */
    fun verify(ctx: Context, input: InputStream, job: BackupJob, onProgress: (Long) -> Unit): VerifyResult {
        val scan = BackupTrailer.Scanner()
        var total = 0L
        val members = AtomicLong()
        val top = LinkedHashSet<String>()
        var bad: String? = null
        try {
            val p = start(ctx, BackupPlan.listCommand(), mergeStderr = false)
            job.attach(p)
            val drain = ErrDrain(p.errorStream, allDiag = true)
            val lister = Thread {
                try {
                    p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { name ->
                        members.incrementAndGet()
                        if (!BackupPlan.isSafeMember(name)) { if (bad == null) bad = name }
                        else BackupPlan.topLevel(name)?.let { synchronized(top) { top.add(it) } }
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            val buf = ByteArray(BUF)
            var last = 0L
            var pipeOpen = true
            val sink = p.outputStream
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (job.cancelled) break
                scan.update(buf, 0, n)
                total += n
                if (pipeOpen) try { sink.write(buf, 0, n) } catch (_: Exception) { pipeOpen = false } // tar exited early: keep hashing
                val now = System.nanoTime()
                if (now - last > 250_000_000L) { last = now; onProgress(total) }
            }
            try { sink.close() } catch (_: Exception) { }
            if (job.cancelled) { p.destroyForcibly(); return VerifyResult(false, "", total, 0, emptyList(), "Cancelled", true) }
            val exit = p.waitFor()
            lister.join(5000); drain.join()
            onProgress(total)
            val b = bad
            val msg = when {
                b != null -> "Unsafe archive member rejected: $b"
                exit != 0 -> "Not a valid .tar.zst archive" + drain.text().let { if (it.isBlank()) "" else ": $it" }
                else -> null
            }
            val found = scan.finish()
            return VerifyResult(msg == null, found.sha256, total, members.get(), synchronized(top) { top.toList() }, msg, false, found.json?.let(BackupPlan::parseManifest))
        } catch (e: Exception) {
            return VerifyResult(false, "", total, 0, emptyList(), if (job.cancelled) "Cancelled" else (e.message ?: e.javaClass.simpleName), job.cancelled)
        }
    }

    /** Pass 2: pipes the archive into `tar --zstd -x` inside [stagingGuestDir] (created by the command). */
    fun extract(ctx: Context, input: InputStream, stagingGuestDir: String, job: BackupJob, onProgress: (Long) -> Unit): ExtractResult {
        var total = 0L
        try {
            val p = start(ctx, BackupPlan.extractCommand(stagingGuestDir), mergeStderr = true)
            job.attach(p)
            val drain = ErrDrain(p.inputStream, allDiag = true)
            val sink = p.outputStream
            val buf = ByteArray(BUF)
            var last = 0L
            var broken: String? = null
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (job.cancelled) break
                try { sink.write(buf, 0, n) } catch (e: Exception) { broken = e.message; break }
                total += n
                val now = System.nanoTime()
                if (now - last > 250_000_000L) { last = now; onProgress(total) }
            }
            try { sink.close() } catch (_: Exception) { }
            if (job.cancelled) { p.destroyForcibly(); return ExtractResult(false, "Cancelled", true) }
            val exit = p.waitFor()
            drain.join()
            onProgress(total)
            return if (exit == 0 && broken == null) ExtractResult(true, null, false)
            else ExtractResult(false, "tar -x failed (exit $exit)" + drain.text().let { if (it.isBlank()) "" else ": $it" }, false)
        } catch (e: Exception) {
            return ExtractResult(false, if (job.cancelled) "Cancelled" else (e.message ?: e.javaClass.simpleName), job.cancelled)
        }
    }
}
