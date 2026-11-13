package com.zenithblue.nativecode.cliauth

import java.io.File

enum class AuthState { SIGNED_IN, SIGNED_OUT, UNKNOWN }

/** Tool login state read from the guest home. [account]/[detail] never hold secrets. */
data class CliAuthInfo(
    val state: AuthState,
    val account: String? = null,
    val detail: String? = null
)

/**
 * Host-side login detection: reads each CLI's own auth file under the guest home with plain
 * file I/O (no proot spawn). Values are only tested for presence; tokens are never returned,
 * logged or shown. Only the JWT `email` claim and plan/provider names are surfaced.
 * Paths follow each CLI's Linux default; ids match [CliToolCatalog] plus "gh".
 */
object CliCredentialProbe {

    private const val ENV_REL = CliGuestCommands.AUTH_ENV_REL
    private const val KIRO_DB = ".local/share/kiro-cli/data.sqlite3"
    private const val KIRO_DB_LEGACY = ".local/share/amazon-q/data.sqlite3"

    fun probe(
        toolId: String,
        home: File,
        kiroHasToken: (File) -> Boolean = KiroDb::hasToken
    ): CliAuthInfo {
        // Chroot rootfs is root-only: cannot tell, do not claim signed out.
        if (!home.isDirectory || !home.canRead()) return CliAuthInfo(AuthState.UNKNOWN)
        return try {
            when (toolId) {
                "claude" -> claude(home)
                "codex" -> codex(home)
                "opencode" -> opencode(home)
                "qwen" -> qwen(home)
                "grok" -> grok(home)
                "kiro" -> kiro(home, kiroHasToken)
                "agy" -> agy(home)
                "gh" -> gh(home)
                else -> CliAuthInfo(AuthState.UNKNOWN)
            }
        } catch (_: Exception) {
            CliAuthInfo(AuthState.UNKNOWN)
        }
    }

    /**
     * Deletes only this tool's own credential files (kiro and gh log out through their CLI).
     * Qwen keeps settings.json but blanks its API key values. Returns files changed.
     */
    fun clearFiles(toolId: String, home: File): Int {
        var n = 0
        fun rm(rel: String) { if (inHome(home, rel)?.delete() == true) n++ }
        when (toolId) {
            "claude" -> rm(".claude/.credentials.json")
            "codex" -> rm(".codex/auth.json")
            "opencode" -> rm(".local/share/opencode/auth.json")
            "grok" -> { rm(".grok/auth.json"); rm(".config/grok/auth.json") }
            "agy" -> rm(AGY_TOKEN)
            "qwen" -> {
                rm(".qwen/oauth_creds.json")
                val f = inHome(home, ".qwen/settings.json") ?: return n
                val t = f.textOrNull()
                if (t != null) {
                    val blanked = t.replace(QWEN_KEY) { "${it.groupValues[1]}\"\"" }
                    if (blanked != t) { f.writeText(blanked); n++ }
                }
            }
        }
        return n
    }

    // ── per tool ────────────────────────────────────────────────────────────

    private fun claude(home: File): CliAuthInfo {
        val cred = File(home, ".claude/.credentials.json").textOrNull()
        val fileIn = cred != null && SECRET_STR.containsMatchIn(cred)
        val envIn = envHas(home, "CLAUDE_CODE_OAUTH_TOKEN") || envHas(home, "ANTHROPIC_API_KEY")
        if (!fileIn && !envIn) return CliAuthInfo(AuthState.SIGNED_OUT)
        val email = File(home, ".claude.json").textOrNull()?.let { str(it, "emailAddress") }
        val plan = cred?.let { str(it, "subscriptionType") }
        return CliAuthInfo(AuthState.SIGNED_IN, email, plan ?: if (fileIn) null else "token")
    }

    private fun codex(home: File): CliAuthInfo {
        val a = File(home, ".codex/auth.json").textOrNull() ?: return CliAuthInfo(AuthState.SIGNED_OUT)
        val oauth = str(a, "access_token") != null
        val key = str(a, "OPENAI_API_KEY") != null
        if (!oauth && !key) return CliAuthInfo(AuthState.SIGNED_OUT)
        val email = str(a, "id_token")?.let(::jwtEmail)
        return CliAuthInfo(AuthState.SIGNED_IN, email, if (oauth) "ChatGPT" else "API key")
    }

    private fun opencode(home: File): CliAuthInfo {
        val a = File(home, ".local/share/opencode/auth.json").textOrNull()
        val providers = a?.let { PROVIDER.findAll(it).map { m -> m.groupValues[1] }.distinct().toList() }.orEmpty()
        // opencode 2.x moves credentials into opencode.db (auth.json only legacy): cannot tell.
        if (providers.isEmpty() && File(home, ".local/share/opencode/opencode.db").isFile) return CliAuthInfo(AuthState.UNKNOWN)
        return if (providers.isEmpty()) CliAuthInfo(AuthState.SIGNED_OUT)
        else CliAuthInfo(AuthState.SIGNED_IN, detail = providers.joinToString(", "))
    }

    private fun qwen(home: File): CliAuthInfo {
        val s = File(home, ".qwen/settings.json").textOrNull()
        val key = (s != null && QWEN_KEY_SET.containsMatchIn(s)) ||
            envHas(home, "BAILIAN_CODING_PLAN_API_KEY") || envHas(home, "DASHSCOPE_API_KEY")
        val oauth = File(home, ".qwen/oauth_creds.json").textOrNull()?.let { str(it, "access_token") } != null
        return when {
            key -> CliAuthInfo(AuthState.SIGNED_IN, detail = "API key")
            oauth -> CliAuthInfo(AuthState.SIGNED_IN, detail = "OAuth")
            else -> CliAuthInfo(AuthState.SIGNED_OUT)
        }
    }

    private fun grok(home: File): CliAuthInfo {
        if (envHas(home, "XAI_API_KEY") || envHas(home, "GROK_API_KEY")) {
            return CliAuthInfo(AuthState.SIGNED_IN, detail = "API key")
        }
        // ponytail: ~/.grok/auth.json is the assumed OAuth store; confirm on a signed-in device.
        val a = listOf(".grok/auth.json", ".config/grok/auth.json")
            .firstNotNullOfOrNull { File(home, it).textOrNull() }
        return if (a != null && TOKENISH.containsMatchIn(a)) CliAuthInfo(AuthState.SIGNED_IN)
        else CliAuthInfo(AuthState.SIGNED_OUT)
    }

    private fun kiro(home: File, hasToken: (File) -> Boolean): CliAuthInfo {
        if (envHas(home, "KIRO_API_KEY")) return CliAuthInfo(AuthState.SIGNED_IN, detail = "API key")
        val db = listOf(KIRO_DB, KIRO_DB_LEGACY).map { File(home, it) }.firstOrNull { it.isFile }
        return if (db != null && hasToken(db)) CliAuthInfo(AuthState.SIGNED_IN)
        else CliAuthInfo(AuthState.SIGNED_OUT)
    }

    /**
     * agy 1.5.x keeps the Google OAuth token in the OS keyring, or in this JSON file when there is
     * no D-Bus session (always in the guest). Verified against the 1.5.8 binary: it reads this
     * path and fails with "failed to unmarshal token" on a garbage file.
     */
    private fun agy(home: File): CliAuthInfo {
        val t = File(home, AGY_TOKEN).textOrNull()
        return if (t != null && TOKENISH.containsMatchIn(t)) CliAuthInfo(AuthState.SIGNED_IN, detail = "Google")
        else CliAuthInfo(AuthState.SIGNED_OUT)
    }

    private const val AGY_TOKEN = ".gemini/antigravity-cli/antigravity-oauth-token"

    private fun gh(home: File): CliAuthInfo {
        val y = File(home, ".config/gh/hosts.yml").textOrNull() ?: return CliAuthInfo(AuthState.SIGNED_OUT)
        val user = Regex("""(?m)^\s+user:\s*(\S+)""").find(y)?.groupValues?.get(1)
        val token = Regex("""(?m)^\s+oauth_token:\s*\S+""").containsMatchIn(y)
        return if (user != null || token) CliAuthInfo(AuthState.SIGNED_IN, user)
        else CliAuthInfo(AuthState.SIGNED_OUT)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private val SECRET_STR = Regex(""""(accessToken|apiKey|access_token)"\s*:\s*"[^"]+"""")
    private val TOKENISH = Regex("""(?i)"[a-z_]*(token|api_?key)[a-z_]*"\s*:\s*"[^"]+"""")
    private val PROVIDER = Regex(""""([A-Za-z0-9._/-]+)"\s*:\s*\{""")
    private val QWEN_KEY = Regex("""("[A-Z_]*API_KEY"\s*:\s*)"[^"]*"""")
    private val QWEN_KEY_SET = Regex(""""[A-Z_]*API_KEY"\s*:\s*"[^"]+"""")

    /** Non-empty value of `export KEY='…'` in the managed env file. */
    private fun envHas(home: File, key: String): Boolean {
        val t = File(home, ENV_REL).textOrNull() ?: return false
        return Regex("""(?m)^[ \t]*(export[ \t]+)?$key=[ \t]*['"]?[^'"\s]""").containsMatchIn(t)
    }

    /**
     * [rel] under [home], or null when symlinks resolve it outside home (an absolute guest link
     * resolves against the Android root here). Guards every host write/delete.
     * ponytail: refuses absolute guest links instead of re-rooting them onto the rootfs.
     */
    internal fun inHome(home: File, rel: String): File? = try {
        File(home, rel).takeIf { it.canonicalFile.startsWith(home.canonicalFile) }
    } catch (_: Exception) {
        null
    }

    private fun File.textOrNull(): String? =
        try { if (isFile && length() in 1..1_000_000) readText() else null } catch (_: Exception) { null }

    /** Value of the first `"key": "value"` string, null if absent or blank. */
    internal fun str(json: String, key: String): String? =
        Regex(""""${Regex.escape(key)}"\s*:\s*"([^"\\]*)"""").find(json)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }

    /** `email` claim of a JWT payload; the signature and the other claims are ignored. */
    internal fun jwtEmail(jwt: String): String? = try {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        str(String(java.util.Base64.getUrlDecoder().decode(payload)), "email")
    } catch (_: Exception) {
        null
    }
}

/** Kiro keeps its login in a sqlite `auth_kv` table; presence of a token row means signed in. */
object KiroDb {
    fun hasToken(db: File): Boolean = try {
        android.database.sqlite.SQLiteDatabase.openDatabase(
            db.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        ).use { d ->
            d.rawQuery("SELECT 1 FROM auth_kv WHERE key LIKE '%token%' LIMIT 1", null)
                .use { it.moveToFirst() }
        }
    } catch (_: Exception) {
        false
    }
}
