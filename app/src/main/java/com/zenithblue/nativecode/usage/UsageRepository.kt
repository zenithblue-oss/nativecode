package com.zenithblue.nativecode.usage

import android.content.Context
import com.zenithblue.nativecode.agent.chat.AgentChats
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Runs the bundled ccusage (plan 18). Everything here blocks: call off the main thread.
 * The binary is a static aarch64 executable shipped in the Linux runtime update pack (RuntimeUpdatePack, npm sha512
 * verified when the pack is built), so the app never downloads or installs code itself: when it is missing the Usage
 * page points to Settings > Components. `--offline` makes ccusage read only the local session logs and use its bundled
 * price table.
 */
object UsageRepository {
    private const val METHOD = "proot"
    private const val GUEST_DIR = "/home/flux/.local/share/nativecode/ccusage"
    private const val GUEST_BIN = "$GUEST_DIR/ccusage"
    private const val MAX_RUN_MS = 120_000L

    private fun binary(ctx: Context) = File(ProjectPathResolver.guestHomeDir(ctx, METHOD), GUEST_BIN.removePrefix("/home/flux/"))
    fun isInstalled(ctx: Context) = binary(ctx).canExecute()
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "ccusage-cache.json")

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
