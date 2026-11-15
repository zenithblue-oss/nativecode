package com.zenithblue.nativecode.usage

import android.content.Context
import com.zenithblue.nativecode.agent.chat.acp.AcpError
import com.zenithblue.nativecode.agent.chat.acp.AcpRpc
import com.zenithblue.nativecode.agent.chat.core.PipeProcess
import com.zenithblue.nativecode.entitlements.GuestGate
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads each signed-in CLI's plan and limit windows (plan 18). Credentials are read from the guest home in memory only:
 * a token is never logged, stored, put in an exception message or shown. Errors carry a short reason, never a body.
 *  - claude: the OAuth usage endpoint Claude Code's own `/usage` calls, with the user's existing sign-in.
 *  - codex: `codex app-server` -> `account/read` + `account/rateLimits/read`.
 *  - grok: the billing proxy and gRPC-web credit pool the Grok CLI uses (`grok usage` itself only works inside its TUI); an
 *    expired token is renewed with the stored refresh token, as the CLI does.
 *  - antigravity: Cloud Code Assist `loadCodeAssist` + `retrieveUserQuotaSummary` with the agy sign-in (renewed in memory when expired).
 *  The grok and antigravity endpoints and request headers follow decolua/9router (MIT), re-implemented here, no code copied.
 *  - opencode: free models, no plan.
 * Everything blocks: call off the main thread.
 */
object LimitsRepository {
    val AGENTS = listOf("claude", "codex", "grok", "antigravity", "opencode")
    private const val TIMEOUT_S = 45L
    private const val UA = "nativecode-usage"

    private fun home(ctx: Context) = ProjectPathResolver.guestHomeDir(ctx, "proot")
    private fun file(ctx: Context, rel: String) = File(home(ctx), rel).takeIf { it.isFile }
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "usage-limits.json")

    fun cached(ctx: Context): List<CliLimits> = runCatching { LimitsModel.decode(cacheFile(ctx).readText()) }.getOrDefault(emptyList())

    /** Reads every [allowed] CLI in parallel and caches the result with its read time. */
    fun refresh(ctx: Context, allowed: (String) -> Boolean): List<CliLimits> {
        val app = ctx.applicationContext
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = AGENTS.filter(allowed).map { a -> a to pool.submit(Callable { read(app, a) }) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S)
            val now = System.currentTimeMillis()
            val last = cached(app).filter { it.state == LimitState.OK }.associateBy { it.agent }
            val out = jobs.map { (a, f) ->
                runCatching { f.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS) }
                    .getOrElse { f.cancel(true); CliLimits(a, LimitState.ERROR, message = "Timed out reading limits.") }
                    .let { if (it.state == LimitState.ERROR) last[a] ?: it.copy(fetchedAt = now) else it.copy(fetchedAt = now) } // a transient error keeps the last good read (its timestamp shows its age)
            }
            runCatching { cacheFile(app).writeText(LimitsModel.encode(out)) }
            return out
        } finally { pool.shutdownNow() }
    }

    private fun read(ctx: Context, agent: String): CliLimits = try {
        when (agent) {
            "claude" -> claude(ctx)
            "codex" -> codex(ctx)
            "grok" -> grok(ctx)
            "antigravity" -> antigravity(ctx)
            else -> CliLimits(agent, LimitState.FREE, message = "Free models, no plan limits.")
        }
    } catch (e: Exception) { CliLimits(agent, LimitState.ERROR, message = (e.message ?: e.javaClass.simpleName).take(120)) }

    private fun notSignedIn(agent: String) = CliLimits(agent, LimitState.NOT_SIGNED_IN, message = "Not signed in.")

    private fun claude(ctx: Context): CliLimits {
        val f = file(ctx, ".claude/.credentials.json") ?: return notSignedIn("claude")
        val auth = LimitsModel.claudeAuth(f.readText()) ?: return notSignedIn("claude")
        if (auth.expiresAtMs in 1..System.currentTimeMillis())
            return CliLimits("claude", LimitState.ERROR, auth.plan, message = "Sign-in is out of date. Open Claude Code once to renew it, then refresh.")
        val (code, body) = get("https://api.anthropic.com/api/oauth/usage",
            mapOf("Authorization" to "Bearer ${auth.accessToken}", "anthropic-beta" to "oauth-2025-04-20", "anthropic-version" to "2023-06-01", "Accept" to "application/json"))
        if (code == 401 || code == 403) return CliLimits("claude", LimitState.ERROR, auth.plan, message = "Claude refused the sign-in (HTTP $code). Open Claude Code once to renew it.")
        if (code == 429) return CliLimits("claude", LimitState.ERROR, auth.plan, message = "Anthropic is rate limiting the usage check. Try again in a few minutes.")
        if (code != 200) return CliLimits("claude", LimitState.ERROR, auth.plan, message = "Claude usage not available (HTTP $code).")
        val w = LimitsModel.parseClaude(body)
        return if (w.isEmpty()) CliLimits("claude", LimitState.UNAVAILABLE, auth.plan, message = "This plan reports no limit windows.")
        else CliLimits("claude", LimitState.OK, auth.plan, w)
    }

    private fun codex(ctx: Context): CliLimits {
        file(ctx, ".codex/auth.json") ?: return notSignedIn("codex")
        GuestGate.syncAsync(ctx)
        lateinit var proc: PipeProcess
        val rpc = AcpRpc({ proc.writeLine(it) }, { _, _ -> }, { _, _, _ -> throw AcpError(AcpError.METHOD_NOT_FOUND, "unsupported") }, tagged = false)
        proc = PipeProcess.forTool(ctx, listOf("codex", "app-server"), "/home/flux", rpc::onLine, { _, _ -> rpc.failAll(IOException("codex stopped")) })
        proc.start()
        try {
            val init = JSONObject().put("clientInfo", JSONObject().put("name", "nativecode").put("title", "NativeCode").put("version", "1"))
                .put("capabilities", JSONObject().put("experimentalApi", true))
            rpc.request("initialize", init).get(TIMEOUT_S - 10, TimeUnit.SECONDS)
            rpc.notify("initialized", null)
            val acc = rpc.request("account/read", JSONObject().put("refreshToken", false)).get(20, TimeUnit.SECONDS)
            if (acc.optJSONObject("account") == null) return notSignedIn("codex")
            val rl = rpc.request("account/rateLimits/read", null).get(20, TimeUnit.SECONDS)
            val (plan, windows) = LimitsModel.parseCodex(rl)
            val p = plan ?: LimitsModel.codexAccountPlan(acc)
            return if (windows.isEmpty()) CliLimits("codex", LimitState.UNAVAILABLE, p, message = "This account reports no limit windows.")
            else CliLimits("codex", LimitState.OK, p, windows)
        } finally { proc.close() }
    }

    // ---- grok: the billing proxy the Grok CLI uses, plus the gRPC-web weekly credit pool (what the CLI's /usage shows) ----

    private fun grok(ctx: Context): CliLimits {
        val f = file(ctx, ".grok/auth.json") ?: return notSignedIn("grok")
        val auth = LimitsModel.grokAuth(f.readText()) ?: return notSignedIn("grok")
        if (auth.apiKey) return CliLimits("grok", LimitState.UNAVAILABLE, "API key", message = "API keys have no plan limits.")
        val ver = LimitsModel.grokVersion(file(ctx, ".grok/version.json")?.readText())
        var token = auth.accessToken
        if ((auth.expiresAtMs ?: Long.MAX_VALUE) <= System.currentTimeMillis() + 60_000) {
            token = grokRefresh(f, auth) ?: return CliLimits("grok", LimitState.ERROR, message = "Sign-in is out of date. Open Grok once to renew it, then refresh.")
        }
        val h = mapOf(
            "Authorization" to "Bearer $token", "Accept" to "application/json", "User-Agent" to "grok-shell/$ver (linux; aarch64)",
            "x-xai-token-auth" to "xai-grok-cli", "x-grok-client-identifier" to "grok-shell", "x-grok-client-version" to ver, "x-grok-client-mode" to "headless",
        )
        val (code, body) = get("https://cli-chat-proxy.grok.com/v1/billing?format=credits", h)
        if (code == 401 || code == 403) return CliLimits("grok", LimitState.ERROR, message = "Grok refused the sign-in (HTTP $code). Open Grok once to renew it.")
        if (code != 200) return CliLimits("grok", LimitState.UNAVAILABLE, message = "Grok usage not available (HTTP $code).")
        val plan = LimitsModel.grokPlan(get("https://cli-chat-proxy.grok.com/v1/user?include=subscription", h).let { if (it.first == 200) it.second else null }, token)
        var w = LimitsModel.parseGrok(body)
        val (gc, gb) = http("POST", "https://grok.com/grok_api_v2.GrokBuildBilling/GetGrokCreditsConfig",
            mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/grpc-web+proto", "X-Grpc-Web" to "1", "Accept" to "application/grpc-web+proto", "User-Agent" to "grok-shell/$ver (linux; aarch64)"),
            byteArrayOf(0, 0, 0, 0, 0))
        val pool = if (gc == 200) LimitsModel.parseGrokCredits(gb) else null
        if (pool != null) { // the weekly pool is the real subscription window; the billing period only gives a fallback
            val sub = w.firstOrNull { it.id == "subscription" }
            w = listOf(LimitWindow("subscription", "Weekly", pool.first, pool.second ?: sub?.resetsAtMs, LimitsModel.WEEK)) + w.filter { it.id != "subscription" }
        }
        return if (w.isEmpty()) CliLimits("grok", LimitState.UNAVAILABLE, plan, message = "Grok reported no plan limit.")
        else CliLimits("grok", LimitState.OK, plan, w)
    }

    /**
     * Renews the Grok access token with the stored refresh token (OIDC refresh_token grant against the issuer's token endpoint, public
     * client, HTTPS on x.ai only) as the CLI itself does. Refresh tokens rotate, so a rotated one is written back to `auth.json` (temp
     * file then rename) before the new token is used. Null when it cannot be renewed.
     */
    private fun grokRefresh(f: File, auth: LimitsModel.GrokAuth): String? {
        val refresh = auth.refreshToken ?: return null
        val client = auth.clientId ?: return null
        val issuer = auth.issuer ?: "https://auth.x.ai"
        if (!onXai(issuer)) return null
        val disc = get("${issuer.trimEnd('/')}/.well-known/openid-configuration", mapOf("Accept" to "application/json"))
        val tokenUrl = runCatching { JSONObject(disc.second).optString("token_endpoint") }.getOrDefault("").takeIf { disc.first == 200 && onXai(it) } ?: "https://auth.x.ai/oauth2/token"
        val (code, body) = http("POST", tokenUrl, mapOf("Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json"),
            "grant_type=refresh_token&client_id=${enc(client)}&refresh_token=${enc(refresh)}".toByteArray())
        if (code != 200) return null
        val o = runCatching { JSONObject(String(body)) }.getOrNull() ?: return null
        val access = o.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        val rotated = o.optString("refresh_token").takeIf { it.isNotBlank() && it != refresh }
        val expires = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000
        if (rotated != null) { // the old refresh token is spent: persist the new one even if this read then fails
            val tmp = File(f.parentFile, f.name + ".nc-tmp")
            try {
                val text = LimitsModel.grokAuthUpdated(f.readText(), auth.entry, access, rotated, expires)
                if (text != null) {
                    tmp.writeText(text)
                    tmp.setReadable(false, false); tmp.setReadable(true, true); tmp.setWritable(true, true)
                    tmp.renameTo(f)
                }
            } catch (_: Exception) {
            } finally { tmp.delete() }
        }
        return access
    }

    private fun onXai(u: String) = runCatching { URL(u).let { it.protocol == "https" && (it.host == "x.ai" || it.host.endsWith(".x.ai")) } }.getOrDefault(false)
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    // ---- antigravity: Cloud Code Assist loadCodeAssist (plan, project) + retrieveUserQuotaSummary (5h and weekly buckets per model family) ----

    @Volatile private var agySecret: String? = null

    private fun antigravity(ctx: Context): CliLimits {
        val f = file(ctx, AGY_TOKEN) ?: return notSignedIn("antigravity")
        val auth = LimitsModel.agyAuth(f.readText()) ?: return notSignedIn("antigravity")
        var token = auth.accessToken
        if ((auth.expiresAtMs ?: 0L) <= System.currentTimeMillis() + 60_000) {
            token = agyRefresh(ctx, auth.refreshToken) ?: return CliLimits("antigravity", LimitState.ERROR, message = "Sign-in is out of date. Open Antigravity once to renew it, then refresh.")
        }
        val h = mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json", "User-Agent" to AGY_UA, "X-Client-Name" to "antigravity", "X-Client-Version" to AGY_VERSION)
        val os = if (System.getProperty("os.arch").orEmpty().let { it.contains("aarch64") || it.contains("arm64") }) 4 else 3
        val (lc, lb) = http("POST", "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist", h,
            """{"metadata":{"ideType":9,"platform":$os,"pluginType":2},"mode":1}""".toByteArray())
        if (lc == 401 || lc == 403) return CliLimits("antigravity", LimitState.ERROR, message = "Google refused the sign-in (HTTP $lc). Open Antigravity once to renew it.")
        val load = if (lc == 200) String(lb) else null
        val plan = load?.let { LimitsModel.antigravityPlan(it) }
        val project = load?.let { LimitsModel.antigravityProject(it) }
        val (qc, qb) = http("POST", "https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary", h,
            JSONObject().apply { if (project != null) put("project", project) }.toString().toByteArray())
        if (qc == 401 || qc == 403) return CliLimits("antigravity", LimitState.ERROR, plan, message = "Antigravity quota refused (HTTP $qc).")
        if (qc != 200) return CliLimits("antigravity", LimitState.UNAVAILABLE, plan, message = "Antigravity limits not available (HTTP $qc).")
        val w = LimitsModel.parseAntigravity(String(qb))
        return if (w.isEmpty()) CliLimits("antigravity", LimitState.UNAVAILABLE, plan, message = "This account reports no limit windows.")
        else CliLimits("antigravity", LimitState.OK, plan, w)
    }

    /**
     * New access token from the refresh token. Antigravity signs in with a Google installed-app client; its secret ships inside the
     * agy binary on this device, so it is read from there (never stored, never sent anywhere but oauth2.googleapis.com) instead of
     * being embedded here. Google does not rotate the refresh token, so nothing is written back.
     */
    private fun agyRefresh(ctx: Context, refresh: String?): String? {
        refresh ?: return null
        val secrets = agySecret?.let { listOf(it) } ?: File(home(ctx), ".local/bin/agy").takeIf { it.isFile }?.inputStream()?.use { LimitsModel.scanClientSecrets(it) }.orEmpty()
        for (s in secrets) {
            val (code, body) = http("POST", "https://oauth2.googleapis.com/token", mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                "grant_type=refresh_token&client_id=${enc(AGY_CLIENT)}&client_secret=${enc(s)}&refresh_token=${enc(refresh)}".toByteArray())
            if (code == 200) {
                val t = runCatching { JSONObject(String(body)).optString("access_token") }.getOrDefault("")
                if (t.isNotBlank()) { agySecret = s; return t }
            }
            if (code != 401) return null // 401 invalid_client: wrong candidate secret, try the next
        }
        agySecret = null
        return null
    }

    private const val AGY_TOKEN = ".gemini/antigravity-cli/antigravity-oauth-token"
    private const val AGY_VERSION = "2.24.0"
    private const val AGY_UA = "antigravity/hub/$AGY_VERSION linux/arm64"
    private const val AGY_CLIENT = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"

    private fun get(url: String, headers: Map<String, String>): Pair<Int, String> = http("GET", url, headers, null).let { it.first to String(it.second, Charsets.UTF_8) }

    /** HTTPS request with timeouts, no redirects; the body is capped. Headers (tokens) are never logged. */
    private fun http(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Pair<Int, ByteArray> {
        require(url.startsWith("https://")) { "https only" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000; c.readTimeout = 15_000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            val out = (if (code in 200..299) c.inputStream else c.errorStream)?.use { s ->
                val b = ByteArray(256 * 1024); var n = 0
                while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) break; n += r }
                b.copyOf(n)
            } ?: ByteArray(0)
            return code to out
        } finally { c.disconnect() }
    }
}
