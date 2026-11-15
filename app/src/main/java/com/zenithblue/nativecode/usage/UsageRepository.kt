package com.zenithblue.nativecode.usage

import android.content.Context
import android.os.Build
import android.system.Os
import com.zenithblue.nativecode.agent.chat.AgentChats
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/** Pure helpers for the pinned download: the npm integrity check and a one-file tar reader. */
object Tgz {
    /** True when [data] hashes to the npm `integrity` string `sha512-<base64>`. */
    fun integrityOk(data: ByteArray, integrity: String): Boolean {
        if (!integrity.startsWith("sha512-")) return false
        val want = runCatching { Base64.getDecoder().decode(integrity.removePrefix("sha512-")) }.getOrNull() ?: return false
        return MessageDigest.isEqual(MessageDigest.getInstance("SHA-512").digest(data), want)
    }

    /** Contents of the regular file [name] in the gzip'd tar [tgz], or null. Reads headers only; never writes a path. */
    fun extract(tgz: ByteArray, name: String): ByteArray? {
        val s = GZIPInputStream(ByteArrayInputStream(tgz))
        val h = ByteArray(512)
        fun fill(b: ByteArray): Boolean { var n = 0; while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) return false; n += r }; return true }
        while (fill(h)) {
            if (h.all { it == 0.toByte() }) return null
            val fname = String(h, 0, 100, Charsets.US_ASCII).substringBefore('\u0000')
            val size = String(h, 124, 12, Charsets.US_ASCII).trim { it == '\u0000' || it == ' ' }.toLongOrNull(8) ?: return null
            val type = h[156].toInt().toChar()
            val padded = (size + 511) / 512 * 512
            if ((type == '0' || type == '\u0000') && fname == name) {
                if (size > 64L * 1024 * 1024) return null
                val out = ByteArray(size.toInt())
                if (!fill(out)) return null
                s.skip(padded - size)
                return out
            }
            var left = padded
            while (left > 0) { val k = s.skip(left); if (k <= 0) { if (s.read() < 0) return null; left-- } else left -= k }
        }
        return null
    }
}

/**
 * Installs the pinned ccusage binary in the guest home and runs it (plan 18). Everything here blocks: call off the main thread.
 * The binary is a static executable downloaded once, checked against the npm integrity hash and written by Kotlin I/O
 * (no scripts in the guest). `--offline` makes ccusage read only the local session logs and use its bundled price table.
 */
object UsageRepository {
    private const val METHOD = "proot"
    private const val GUEST_DIR = "/home/flux/.local/share/nativecode/ccusage"
    private const val GUEST_BIN = "$GUEST_DIR/ccusage"
    private const val MAX_RUN_MS = 120_000L

    private class Pin(val arch: String, val integrity: String)

    private val PINS = mapOf(
        "arm64-v8a" to Pin("arm64", "sha512-c1/4mP5kRNnq65HIoG6F5gvzETAYoocVuDO5N6gaP0n4vR5g58iIe2KVkoERemmKsXVWpI0cp6bsLYfTvFFmRQ=="),
        "x86_64" to Pin("x64", "sha512-MITIo4RpJuskZqpk80VrZ8uE6QsN3uJFY3R0NXO2jZOB1qL7MqVkYuGQC0lFqsTp5ux6UC/nOa+ZubaP3ED/nQ==")
    )

    private fun binary(ctx: Context) = File(ProjectPathResolver.guestHomeDir(ctx, METHOD), GUEST_BIN.removePrefix("/home/flux/"))
    fun isInstalled(ctx: Context) = binary(ctx).canExecute()
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "ccusage-cache.json")

    /** Downloads, verifies and installs ccusage. Returns null on success, else a short reason. */
    fun install(ctx: Context): String? {
        val pin = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { PINS[it] } ?: return "No ccusage build for this CPU."
        return try {
            val url = "https://registry.npmjs.org/@ccusage/ccusage-linux-${pin.arch}/-/ccusage-linux-${pin.arch}-${UsageModel.VERSION}.tgz"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            if (c.responseCode != 200) return "Download failed (HTTP ${c.responseCode})."
            val buf = ByteArrayOutputStream()
            c.inputStream.use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; buf.write(b, 0, n); if (buf.size() > 32 * 1024 * 1024) return "Download too large." } }
            val data = buf.toByteArray()
            if (!Tgz.integrityOk(data, pin.integrity)) return "Downloaded file failed the integrity check. It was not installed."
            val bin = Tgz.extract(data, "package/bin/ccusage") ?: return "Download did not contain the ccusage program."
            val f = binary(ctx)
            f.parentFile!!.mkdirs()
            val tmp = File(f.parentFile, "ccusage.part")
            tmp.writeBytes(bin)
            Os.chmod(tmp.absolutePath, 493)
            if (!tmp.renameTo(f)) return "Could not write ccusage."
            null
        } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
    }

    /** `yyyyMMdd` of the first day of the month five months back, in [zone]: six whole months for the monthly chart. */
    fun sinceDate(zone: TimeZone, now: Long = System.currentTimeMillis()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1); add(Calendar.MONTH, -5) }
        return SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }.format(c.time)
    }

    fun todayString(zone: TimeZone = TimeZone.getDefault(), now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.format(now)

    /** Runs ccusage over the local logs and returns the parsed report (also cached for the Home card). */
    fun load(ctx: Context): Result<UsageReport> = runCatching {
        check(isInstalled(ctx)) { "ccusage is not installed." }
        val zone = TimeZone.getDefault()
        val cmd = "'$GUEST_BIN' daily --sections daily,weekly,monthly,session --by-agent --json --offline --timezone '${zone.id}' --since ${sinceDate(zone)}"
        val (args, env) = LinuxCommandBuilder.build(ctx, cmd, "flux", method = METHOD)
        val p = ShellCommandRunner.startStdout(ctx, args, env)
        val killer = Thread { try { Thread.sleep(MAX_RUN_MS); p.destroyForcibly() } catch (_: InterruptedException) {} }.apply { isDaemon = true; start() }
        val text = try { p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } finally { killer.interrupt() }
        val code = p.waitFor()
        val report = try { UsageModel.parse(text) } catch (e: Exception) {
            error(if (code != 0) "ccusage exited with status $code." else "ccusage printed no usable data.")
        }
        runCatching { cacheFile(ctx).writeText(text.substring(UsageModel.jsonStart(text))) }
        report
    }

    /** Last report written by [load], or null. */
    fun cached(ctx: Context): UsageReport? = runCatching { UsageModel.parse(cacheFile(ctx).readText()) }.getOrNull()
    fun cacheAgeMs(ctx: Context): Long = cacheFile(ctx).takeIf { it.exists() }?.let { System.currentTimeMillis() - it.lastModified() } ?: Long.MAX_VALUE

    /** Per-run usage rows of every chat thread, for [UsageModel.chatThreads]. */
    fun chatRuns(ctx: Context): List<ChatRunUsage> = runCatching {
        val store = AgentChats.store(ctx)
        store.threads(null, allProjects = true).flatMap { t ->
            store.runs(t.id).mapNotNull { r ->
                r.usage?.let { u -> ChatRunUsage(t.id, t.title, t.tool, u.inputTokens + u.outputTokens + u.cacheReadTokens + u.cacheWriteTokens + u.reasoningTokens, u.costUsd) }
            }
        }
    }.getOrDefault(emptyList())
}
