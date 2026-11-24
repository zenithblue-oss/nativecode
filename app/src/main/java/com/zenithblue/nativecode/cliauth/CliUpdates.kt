package com.zenithblue.nativecode.cliauth

import android.content.Context
import com.zenithblue.nativecode.agent.chat.core.ChatFailures
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Installed (guest `--version`, [CliVersionCheck]) vs latest (the vendor's own release pointer, the same one its
 * installer reads) for every AI CLI. Latest is cached 6 h in prefs; a failed lookup keeps the last known value, or
 * stays null ("Can't check") when offline. Everything here blocks: call from a worker thread.
 */
object CliUpdates {
    private const val TTL_MS = 6L * 60 * 60 * 1000
    private const val FRESH_MS = 10L * 60 * 1000
    private const val PREFS = "cli_updates"

    /** [installed] null = not installed / unreadable; [latest] null = lookup failed and nothing cached. */
    data class Row(val id: String, val installed: String?, val latest: String?, val at: Long) {
        val available get() = installed != null && latest != null && ChatFailures.compareVersions(latest, installed) > 0
    }

    /** Release pointers, tried in order. npm ones are the packages the installer uses. */
    private val SOURCES = mapOf(
        "opencode" to listOf("https://opencode.ai/update/api/latest/cli/npm"),
        "claude" to listOf("https://downloads.claude.ai/claude-code-releases/latest"),
        "codex" to listOf("https://registry.npmjs.org/@openai/codex/latest"),
        "qwen" to listOf("https://registry.npmjs.org/@qwen-code/qwen-code/latest"),
        "agy" to listOf("https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"),
        "grok" to listOf("https://x.ai/cli/stable", "https://storage.googleapis.com/grok-build-public-artifacts/cli/stable"),
        "kiro" to listOf("https://prod.download.cli.kiro.dev/stable/latest/manifest.json"),
    )

    private val rows = ConcurrentHashMap<String, Row>()

    fun row(id: String): Row? = rows[id]

    fun stale(id: String) = rows[id]?.let { System.currentTimeMillis() - it.at >= FRESH_MS } ?: true

    /** JSON bodies (npm, manifests): the first `"version"`; plain text (claude, grok): the first line. */
    fun parseLatest(body: String): String? {
        val t = body.trim()
        val v = if (t.startsWith("{")) Regex(""""version"\s*:\s*"v?([^"]+)"""").find(t)?.groupValues?.get(1)
        else t.lineSequence().firstOrNull()
        return v?.let { ChatFailures.parseVersion(it) }
    }

    private fun get(url: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("User-Agent", "NativeCode")
            try { if (responseCode == 200) inputStream.bufferedReader().use { it.readText() } else null } finally { disconnect() }
        }
    } catch (_: Exception) { null }

    private fun latest(ctx: Context, id: String, force: Boolean): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = p.getString("v_$id", null)
        if (!force && cached != null && System.currentTimeMillis() - p.getLong("t_$id", 0) < TTL_MS) return cached
        val fresh = SOURCES[id].orEmpty().firstNotNullOfOrNull { u -> get(u)?.let(::parseLatest) }
        if (fresh != null) p.edit().putString("v_$id", fresh).putLong("t_$id", System.currentTimeMillis()).apply()
        return fresh ?: cached
    }

    /** [force] re-probes the guest and the vendor; otherwise a row younger than 10 min is reused. */
    fun status(ctx: Context, id: String, force: Boolean = false): Row? {
        val d = CliVersionCheck.def(id) ?: return null
        rows[d.id]?.takeIf { !force && System.currentTimeMillis() - it.at < FRESH_MS }?.let { return it }
        val r = Row(d.id, CliVersionCheck.check(ctx, d.id, force = true)?.installed, latest(ctx, d.id, force), System.currentTimeMillis())
        rows[d.id] = r
        return r
    }
}
