package com.zenithblue.nativecode.backup

import com.zenithblue.nativecode.agent.chat.core.ChatFailures
import com.zenithblue.nativecode.terminal.ShellQuoting
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class BackupScope(val label: String, val blurb: String) {
    PROJECTS("Projects only", "The repos folder"),
    HOME("Home", "Settings, shell history and projects"),
}

/** One exclusion checkbox: [patterns] are tar `--exclude` globs. */
data class Exclusion(val id: String, val label: String, val patterns: List<String>, val defaultOn: Boolean, val warn: String? = null)

/** What the sidecar `<name>.json` says about a backup archive. */
data class Manifest(
    val format: Int,
    val appVersion: String,
    val createdAt: String,
    val sha256: String,
    val bytes: Long,
    val fileCount: Long,
    val scope: String,
    val excludes: List<String>,
    val components: Map<String, String>,
)

/** Pure planning for backup and restore: tar command lines, exclusions, member safety, manifest. */
object BackupPlan {
    const val FORMAT = 1
    /** Guest home the archive is relative to. */
    const val GUEST_HOME = "/home/flux"

    val EXCLUSIONS: List<Exclusion> = listOf(
        Exclusion("node_modules", "node_modules", listOf("node_modules"), true),
        Exclusion("venv", ".venv", listOf(".venv"), true),
        Exclusion("target", "target (Rust/Maven build output)", listOf("target"), true),
        Exclusion("build", "build (build output)", listOf("build"), true),
        Exclusion("gradle", ".gradle", listOf(".gradle"), true),
        Exclusion("cache", ".cache", listOf(".cache"), true),
        Exclusion("npm", ".npm", listOf(".npm"), true),
        Exclusion("cargo", ".cargo/registry", listOf(".cargo/registry"), true),
        Exclusion(
            "clis", "CLI binaries",
            listOf(".local/lib/node_modules", ".local/share/claude/versions", ".opencode/bin", ".grok/bin"), true,
        ),
        Exclusion(
            "creds", "Credentials and SSH keys",
            listOf(".codex/auth.json", ".claude/.credentials.json", ".config/gh/hosts.yml", ".hermes/auth.json", ".hermes/.env", ".ssh"),
            // ticking "include" (on by default, [INCLUDE_CREDENTIALS_DEFAULT]) lifts this exclusion, see [excludePatterns]
            false, warn = "Archive then holds sign-in tokens and private keys. Keep it private; a restore on another device may not work.",
        ),
    )

    /** "Include credentials" starts ticked: a restore brings the CLI sign-ins back. */
    const val INCLUDE_CREDENTIALS_DEFAULT = true

    /** Top-level archive entry holding the app's own data (chat history), staged in the guest home for tar. */
    const val APP_DIR = "nativecode-app"

    /** Manifest `components` keys: `cli.<tool id>` = CLI version at backup time; `chat` = the archive holds [APP_DIR]. */
    const val COMPONENT_CHAT = "chat"
    fun cliKey(toolId: String) = "cli.$toolId"

    /** A CLI whose installed version is older than the one recorded in the backup. */
    data class CliDelta(val toolId: String, val installed: String, val backup: String)

    /** Tools in [m] whose [installed] version (null = not installed / unknown) is below the backup's: the "Update CLIs" offer. */
    fun outdatedClis(m: Manifest, installed: Map<String, String?>): List<CliDelta> =
        m.components.filterKeys { it.startsWith("cli.") }.mapNotNull { (k, v) ->
            val id = k.removePrefix("cli.")
            val now = installed[id]
            if (now != null && ChatFailures.versionTooOld(now, v)) CliDelta(id, now, v) else null
        }.sortedBy { it.toolId }

    /** Staging and rollback folders this feature creates; never archived. */
    private val ALWAYS = listOf(".restore-*", ".replaced-*")

    /** Ids whose patterns are excluded by default. `creds` is inverted: ticked = include. */
    fun defaultExcludeIds(): Set<String> = EXCLUSIONS.filter { it.defaultOn }.map { it.id }.toSet()

    /** tar globs for the selected exclusion ids; credentials are excluded unless [includeCredentials]. */
    fun excludePatterns(selected: Set<String>, includeCredentials: Boolean): List<String> {
        val out = ArrayList<String>(ALWAYS)
        for (e in EXCLUSIONS) {
            val on = if (e.id == "creds") !includeCredentials else e.id in selected
            if (on) out += e.patterns
        }
        return out
    }

    /** Registered projects outside ~/repos (custom location), relative to the guest home; nested ones are dropped. */
    fun extraProjectDirs(projectPaths: List<String>): List<String> {
        val rel = projectPaths.map { it.trimEnd('/') }
            .filter { it.startsWith("$GUEST_HOME/") }
            .map { it.removePrefix("$GUEST_HOME/") }
            .filter { r -> r != "repos" && !r.startsWith("repos/") && r.split('/').none { it.isEmpty() || it == "." || it == ".." } }
            .distinct().sorted()
        return rel.filter { r -> rel.none { o -> o != r && r.startsWith("$o/") } }
    }

    fun includes(scope: BackupScope, extra: List<String> = emptyList()): List<String> = when (scope) {
        BackupScope.PROJECTS -> listOf("repos") + extra
        BackupScope.HOME -> listOf(".")
    }

    private fun q(s: String) = ShellQuoting.singleQuote(s)

    private fun excludeArgs(patterns: List<String>) = patterns.joinToString(" ") { "--exclude=" + q(it) }

    /** Projects scope: keeps only the include folders that exist into "$@"; [onNone] runs when none does. */
    private fun existingIncludes(inc: List<String>, onNone: String) =
        "set --; for p in ${inc.joinToString(" ") { q(it) }}; do [ -d \"\$p\" ] && set -- \"\$@\" \"\$p\"; done; [ \$# -gt 0 ] || $onNone; "

    /** Guest shell: tar the scope to stdout as zstd. Exit 3 = nothing to back up. */
    fun createCommand(scope: BackupScope, patterns: List<String>, extra: List<String> = emptyList(), app: Boolean = false): String {
        val inc = includes(scope, extra)
        val guard = if (scope == BackupScope.PROJECTS) existingIncludes(inc, "exit 3") else ""
        // Home scope already covers the staged [APP_DIR] (it sits in the home); Projects scope adds it after the guard.
        val args = if (scope == BackupScope.PROJECTS) "\"\$@\"" + (if (app) " ${q(APP_DIR)}" else "") else inc.joinToString(" ") { q(it) }
        return "cd $GUEST_HOME || exit 2; $guard" +
            "ZSTD_NBTHREADS=0 tar --zstd -cvf - ${excludeArgs(patterns)} -- $args"
    }

    /** Guest shell: total size in KiB of what [createCommand] would archive. */
    fun estimateCommand(scope: BackupScope, patterns: List<String>, extra: List<String> = emptyList()): String {
        val inc = includes(scope, extra)
        val guard = if (scope == BackupScope.PROJECTS) existingIncludes(inc, "{ echo 0; exit 0; }") else ""
        val args = if (scope == BackupScope.PROJECTS) "\"\$@\"" else inc.joinToString(" ") { q(it) }
        return "cd $GUEST_HOME || exit 2; $guard" +
            "du -skc ${excludeArgs(patterns)} -- $args 2>/dev/null | tail -n 1"
    }

    /** Guest shell: list members of a zstd tar read from stdin. */
    fun listCommand(): String = "tar --quoting-style=literal --zstd -tf -"

    /** Guest shell: extract a zstd tar from stdin into [stagingGuestDir] (created first). */
    fun extractCommand(stagingGuestDir: String): String =
        "mkdir -p ${q(stagingGuestDir)} && tar --zstd -xpf - --no-same-owner -C ${q(stagingGuestDir)}"

    /** `du -skc` last line -> bytes, or null when unparsable. */
    fun parseEstimateKb(out: String): Long? =
        out.trim().lines().lastOrNull()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()?.times(1024)

    /**
     * Archive member names must be relative and stay inside the target: no absolute path, no `..` segment, no NUL.
     * The listing runs with literal quoting, so a name with a newline splits into lines that are each checked.
     */
    fun isSafeMember(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("/") || name.contains('\u0000')) return false
        return name.split('/').none { it == ".." }
    }

    /** First path segment of a member, or null for the archive root (`./`) or an unusable name. */
    fun topLevel(member: String): String? {
        val seg = member.removePrefix("./").trimStart('/').substringBefore('/')
        return if (seg.isEmpty() || seg == ".") null else seg
    }

    fun defaultName(now: Long = System.currentTimeMillis()): String =
        "nativecode-backup-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".tar.zst"

    fun sidecarName(archiveName: String): String = "$archiveName.json"

    fun isoNow(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))

    fun manifestJson(m: Manifest): String = JSONObject().apply {
        put("format", m.format)
        put("appVersion", m.appVersion)
        put("createdAt", m.createdAt)
        put("sha256", m.sha256)
        put("bytes", m.bytes)
        put("fileCount", m.fileCount)
        put("scope", m.scope)
        put("excludes", JSONArray(m.excludes))
        put("components", JSONObject(m.components))
    }.toString(2)

    fun parseManifest(json: String): Manifest? = try {
        val o = JSONObject(json)
        val ex = o.optJSONArray("excludes")
        val comp = o.optJSONObject("components")
        Manifest(
            format = o.getInt("format"),
            appVersion = o.optString("appVersion"),
            createdAt = o.optString("createdAt"),
            sha256 = o.getString("sha256").lowercase(Locale.ROOT),
            bytes = o.optLong("bytes", -1),
            fileCount = o.optLong("fileCount", -1),
            scope = o.optString("scope"),
            excludes = (0 until (ex?.length() ?: 0)).map { ex!!.getString(it) },
            components = comp?.keys()?.asSequence()?.associateWith { comp.getString(it) } ?: emptyMap(),
        )
    } catch (_: Exception) {
        null
    }

    fun formatBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024L * 1024 -> "%.1f KB".format(Locale.US, b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, b / (1024.0 * 1024))
        else -> "%.2f GB".format(Locale.US, b / (1024.0 * 1024 * 1024))
    }
}
