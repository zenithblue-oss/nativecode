package com.zenithblue.nativecode.github

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.zenithblue.nativecode.BuildConfig
import com.zenithblue.nativecode.RootShell
import com.zenithblue.nativecode.marketplace.PackageInstallRunner
import com.zenithblue.nativecode.terminal.ChrootCommandBuilder
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/**
 * SSOT for guest `gh`: probe, install, device-code auth, repo list, logout.
 * Per-method state (proot ≠ chroot). Auth always as flux.
 *
 * OAuth device flow runs on **Android network** (survives browser switch / proot
 * flaky HTTPS). Token is injected into guest via `gh auth login --with-token`.
 */
object GitHubCliService {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private data class StatusHit(val status: GhAuthStatus, val atNanos: Long, val generation: Int)

    private val statusCache = ConcurrentHashMap<String, StatusHit>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<GhAuthStatus>>()
    private val authGeneration = ConcurrentHashMap<String, AtomicInteger>()
    private val activeSession = AtomicBoolean(false)
    private const val STATUS_TTL_NS = 5_000_000_000L

    private fun authGen(method: String): Int =
        authGeneration.getOrPut(method) { AtomicInteger(0) }.get()

    /** method + rootfs path + auth generation. Not method alone. */
    private fun statusKey(ctx: Context, method: String): String {
        val root = if (!com.zenithblue.nativecode.ProotOnly.ON && method == "chroot") {
            ChrootCommandBuilder.CHROOT_PATH
        } else {
            ProjectPathResolver.prootRootfsDir(ctx).absolutePath
        }
        return "$method|$root|${authGen(method)}"
    }

    private fun invalidateAuth(method: String) {
        authGeneration.getOrPut(method) { AtomicInteger(0) }.incrementAndGet()
        val prefix = "$method|"
        statusCache.keys.filter { it.startsWith(prefix) }.forEach { statusCache.remove(it) }
        inFlight.keys.filter { it.startsWith(prefix) }.forEach { key ->
            inFlight.remove(key)?.cancel(false)
        }
    }

    private val OTP_PATTERN = Pattern.compile("\\b([A-Z0-9]{4}-[A-Z0-9]{4})\\b")

    /** Public OAuth client id used by official GitHub CLI (`cli/cli`). */
    private const val GH_CLI_CLIENT_ID = "178c6fc778ccc68e1d6a"
    private const val OAUTH_SCOPES = "repo read:org gist workflow"
    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"

    /** Last probe for this method, including stale. Not a fresh auth check. */
    fun cachedStatus(method: String): GhAuthStatus? =
        statusCache.entries
            .filter { it.key.startsWith("$method|") }
            .maxByOrNull { it.value.atNanos }
            ?.value?.status

    fun isSessionActive(): Boolean = activeSession.get()

    // ── Public API ──────────────────────────────────────────────────────────

    fun probeStatus(
        ctx: Context,
        method: String,
        onResult: (GhAuthStatus) -> Unit
    ) {
        executor.execute {
            val status = probeStatusSync(ctx, method)
            mainHandler.post { onResult(status) }
        }
    }

    fun listRepos(
        ctx: Context,
        method: String,
        onResult: (Result<List<GhRepo>>) -> Unit
    ) {
        executor.execute {
            val result = try {
                Result.success(listReposSync(ctx, method))
            } catch (e: Exception) {
                Result.failure(e)
            }
            mainHandler.post { onResult(result) }
        }
    }

    fun logout(
        ctx: Context,
        method: String,
        onResult: (Boolean, String) -> Unit
    ) {
        executor.execute {
            try {
                val status = try {
                    probeStatusSync(ctx, method)
                } catch (_: Exception) {
                    null
                }
                val user = status?.username ?: readHostsYmlUser(ctx, method)

                val ok = if (!com.zenithblue.nativecode.ProotOnly.ON && method == "chroot") {
                    // Host wipe is SSOT (fast). Full Terminal chroot hung forever.
                    logoutChroot(user)
                } else {
                    logoutProot(ctx, user)
                }
                invalidateAuth(method)
                val label = user?.takeIf { it.isNotBlank() }?.let { " @$it" } ?: ""
                mainHandler.post {
                    onResult(
                        ok,
                        if (ok) tr(R.string.cli_logged_out, label) else tr(R.string.cli_logout_failed)
                    )
                }
            } catch (e: Exception) {
                invalidateAuth(method)
                mainHandler.post { onResult(false, e.message ?: tr(R.string.cli_logout_failed)) }
            }
        }
    }

    /**
     * Chroot logout = wipe hosts.yml via RootShell (probe SSOT).
     * Skip full guest `gh auth logout` — was hanging UI for minutes.
     */
    private fun logoutChroot(@Suppress("UNUSED_PARAMETER") user: String?): Boolean {
        if (com.zenithblue.nativecode.ProotOnly.ON) return false
        if (!RootShell.isRootAvailable()) return false
        val root = ChrootCommandBuilder.CHROOT_PATH
        val wipe = RootShell.executeSync(
            "rm -f '$root/home/flux/.config/gh/hosts.yml' " +
                "'$root/home/flux/.config/gh/hosts.yml.bak' 2>/dev/null; " +
                "true"
        )
        return wipe == 0
    }

    private fun logoutProot(ctx: Context, user: String?): Boolean {
        // Wipe token file first (instant offline sign-out)
        try {
            val hosts = File(
                ProjectPathResolver.guestHomeDir(ctx, "proot"),
                ".config/gh/hosts.yml"
            )
            if (hosts.isFile) hosts.delete()
        } catch (_: Exception) {
        }
        if (user.isNullOrBlank()) return true
        return try {
            val (args, env) = fluxCmd(ctx, GhGuestCommands.authLogout(user), "proot")
            mergeGhEnv(env)
            val (exit, _) = ShellCommandRunner.runCaptureExit(ctx, args, env)
            exit == 0
        } catch (_: Exception) {
            true // hosts already gone
        }
    }

    /**
     * Full connect: ensure gh → status → login if needed → setup-git → verify.
     * Returns null if another session is already active.
     */
    fun connect(
        ctx: Context,
        method: String,
        listener: GhAuthListener
    ): GhAuthSession? {
        if (!activeSession.compareAndSet(false, true)) {
            mainHandler.post { listener.onFailed(tr(R.string.cli_auth_already_in_progress)) }
            return null
        }
        val session = GhAuthSession()
        val appCtx = ctx.applicationContext

        executor.execute {
            try {
                runConnect(appCtx, method, session, listener)
            } catch (e: Exception) {
                if (!session.cancelled) {
                    mainHandler.post {
                        listener.onFailed(e.message ?: tr(R.string.cli_connect_failed))
                    }
                }
            } finally {
                session.clearJob()
                activeSession.set(false)
            }
        }
        return session
    }

    fun openDeviceBrowser(ctx: Context, code: String? = null) {
        val url = if (!code.isNullOrBlank()) {
            "${GhGuestCommands.DEVICE_URL}?user_code=$code"
        } else {
            GhGuestCommands.DEVICE_URL
        }
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    fun copyCode(ctx: Context, code: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(tr(R.string.cli_github_one_time_code), code))
        } catch (_: Exception) {
        }
    }

    // ── Sync helpers ────────────────────────────────────────────────────────

    private fun probeStatusSync(ctx: Context, method: String): GhAuthStatus {
        val key = statusKey(ctx, method)
        val gen = authGen(method)
        val now = System.nanoTime()
        statusCache[key]?.let { hit ->
            if (hit.generation == gen && now - hit.atNanos < STATUS_TTL_NS) return hit.status
        }
        val created = CompletableFuture<GhAuthStatus>()
        val existing = inFlight.putIfAbsent(key, created)
        if (existing != null) {
            return try {
                existing.get()
            } catch (_: Exception) {
                probeStatusUncached(ctx, method)
            }
        }
        try {
            val status = probeStatusUncached(ctx, method)
            if (authGen(method) == gen) {
                statusCache[key] = StatusHit(status, System.nanoTime(), gen)
            }
            created.complete(status)
            return status
        } catch (e: Exception) {
            created.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, created)
        }
    }

    private fun probeStatusUncached(ctx: Context, method: String): GhAuthStatus {
        // Chroot home is not app-readable; use RootShell host paths + timed guest probe.
        if (!com.zenithblue.nativecode.ProotOnly.ON && method == "chroot") {
            return probeStatusChroot(ctx)
        }
        // proot: one guest start for install check + whoami
        val (dArgs, dEnv) = fluxCmd(ctx, GhGuestCommands.detectAndWhoami(), method)
        mergeGhEnv(dEnv)
        val dPair = try {
            ShellCommandRunner.runCaptureExit(ctx, dArgs, dEnv)
        } catch (e: Exception) {
            return GhAuthStatus(method, false, false, error = e.message)
        }
        val dOut = dPair.second
        val installed = dOut.contains("GH_OK") && !dOut.contains("GH_MISSING")
        if (!installed) {
            return GhAuthStatus(method, false, false, raw = dOut)
        }
        val whoamiFrame = dOut.substringAfter("---NC-WHOAMI---", "")
        val wExitMatch = Regex("""NC_WHOAMI_EXIT:(\d+)""").find(whoamiFrame)
        val wExit = wExitMatch?.groupValues?.get(1)?.toIntOrNull()
        val wOut = whoamiFrame.replace(Regex("""NC_WHOAMI_EXIT:\d+"""), "").trim()
        val loginFromApi = if (wExit == 0) parseWhoamiLogin(wOut) else null
        if (wExit == 0 && !loginFromApi.isNullOrBlank()) {
            return GhAuthStatus(method, true, true, loginFromApi, raw = wOut)
        }

        // JSON status (newer gh)
        val (sArgs, sEnv) = fluxCmd(ctx, GhGuestCommands.authStatusJson(), method)
        mergeGhEnv(sEnv)
        val sOut: String = try {
            ShellCommandRunner.runCaptureExit(ctx, sArgs, sEnv).second
        } catch (_: Exception) {
            ""
        }
        val fromJson = parseAuthStatusJson(method, sOut)
        if (fromJson.loggedIn) {
            return fromJson
        }

        // Human status (older gh / offline token file present)
        val (hArgs, hEnv) = fluxCmd(ctx, GhGuestCommands.authStatusHuman(), method)
        mergeGhEnv(hEnv)
        val hOut: String = try {
            ShellCommandRunner.runCaptureExit(ctx, hArgs, hEnv).second
        } catch (e: Exception) {
            e.message ?: ""
        }
        val fromHuman = parseAuthStatusHuman(method, hOut)
        // Also: hosts.yml on disk with user key (no network)
        if (!fromHuman.loggedIn) {
            val fromFile = readHostsYmlUser(ctx, method)
            if (fromFile != null) {
                return GhAuthStatus(method, true, true, fromFile, raw = hOut)
            }
        }
        return fromHuman
    }

    /**
     * Chroot probe: host-side root (fast) first — app cannot read/write chroot home.
     * Guest `gh` only if hosts.yml missing (timed RootShell, no hang).
     */
    private fun probeStatusChroot(ctx: Context): GhAuthStatus {
        if (com.zenithblue.nativecode.ProotOnly.ON) {
            return GhAuthStatus("proot", false, false, error = "chroot unavailable")
        }
        val method = "chroot"
        val root = ChrootCommandBuilder.CHROOT_PATH
        if (!RootShell.isRootAvailable()) {
            return GhAuthStatus(method, false, false, error = "root required")
        }
        val binOut = RootShell.capture(
            "if [ -x '$root/usr/bin/gh' ] || [ -x '$root/usr/local/bin/gh' ] || " +
                "[ -x '$root/bin/gh' ] || [ -x '$root/home/flux/.local/bin/gh' ]; then " +
                "echo GH_OK; else echo GH_MISSING; fi",
            timeoutMs = 12_000L
        )
        val installed = binOut.contains("GH_OK") && !binOut.contains("GH_MISSING")
        if (!installed) {
            return GhAuthStatus(method, false, false, raw = binOut)
        }
        // Offline SSOT: hosts.yml under chroot (root cat)
        val fromFile = readHostsYmlUser(ctx, method)
        if (fromFile != null) {
            return GhAuthStatus(method, true, true, fromFile, raw = "hosts.yml")
        }
        // Optional live whoami (network; bounded)
        val who = RootShell.captureInChroot(
            GhGuestCommands.whoami(),
            user = "flux",
            timeoutMs = 45_000L,
            context = ctx
        )
        val login = if (who.exitCode == 0) parseWhoamiLogin(who.stdout) else null
        if (who.exitCode == 0 && !login.isNullOrBlank()) {
            return GhAuthStatus(method, true, true, login, raw = who.stdout)
        }
        return GhAuthStatus(method, true, false, raw = who.stdout)
    }

    private val GITHUB_LOGIN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")

    private fun parseWhoamiLogin(out: String): String? =
        out.lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.matches(GITHUB_LOGIN) &&
                    !it.equals("Unauthorized", ignoreCase = true)
            }

    private fun listReposSync(ctx: Context, method: String): List<GhRepo> {
        val (args, env) = fluxCmd(ctx, GhGuestCommands.repoList(), method)
        mergeGhEnv(env)
        val (exit, out) = ShellCommandRunner.runCaptureExit(ctx, args, env)
        if (exit != 0) {
            throw IllegalStateException(out.trim().ifBlank { "repo list failed (exit $exit)" })
        }
        return parseRepoList(out)
    }

    private fun runConnect(
        ctx: Context,
        method: String,
        session: GhAuthSession,
        listener: GhAuthListener
    ) {
        fun phase(p: GhAuthPhase, msg: String) {
            if (session.cancelled) return
            mainHandler.post { listener.onPhase(p, msg) }
        }
        fun log(line: String) {
            if (session.cancelled) return
            mainHandler.post { listener.onLog(line) }
        }
        fun fail(msg: String) {
            if (session.cancelled) {
                mainHandler.post { listener.onCancelled() }
            } else {
                mainHandler.post { listener.onFailed(msg) }
            }
        }

        // CHECK_GH
        phase(GhAuthPhase.CHECK_GH, tr(R.string.cli_checking_gh))
        var status = probeStatusSync(ctx, method)
        if (session.cancelled) {
            mainHandler.post { listener.onCancelled() }
            return
        }

        // INSTALL if needed (prebuilt rootfs bakes github-cli; fallback is distro-aware)
        if (!status.ghInstalled) {
            phase(GhAuthPhase.INSTALL_GH, tr(R.string.cli_installing_gh))
            log("\$ apk add github-cli  # or: apt-get install -y gh")
            val installOk = runInstall(ctx, method, session, listener)
            if (session.cancelled) {
                mainHandler.post { listener.onCancelled() }
                return
            }
            if (!installOk) {
                fail(tr(R.string.cli_failed_to_install_gh))
                return
            }
            status = probeStatusSync(ctx, method)
            if (!status.ghInstalled) {
                fail("gh still missing after install")
                return
            }
            log("gh installed")
        }

        // CHECK_AUTH
        phase(GhAuthPhase.CHECK_AUTH, tr(R.string.cli_checking_auth))
        if (status.loggedIn && !status.username.isNullOrBlank()) {
            phase(GhAuthPhase.SUCCESS, tr(R.string.cli_already_signed_in))
            mainHandler.post { listener.onDone(status) }
            // best-effort git helper after UI done (do not block)
            executor.execute { runSetupGit(ctx, method) }
            return
        }

        // LOGIN — Android device flow (not guest gh --web poll; proot often drops HTTPS)
        phase(GhAuthPhase.LOGIN, tr(R.string.cli_starting_device_login))
        log(tr(R.string.cli_oauth_device_flow_via_android))
        val loginOk = runLoginAndroidDeviceFlow(ctx, method, session, listener)
        if (session.cancelled) {
            mainHandler.post { listener.onCancelled() }
            return
        }
        if (!loginOk) {
            fail(tr(R.string.cli_login_failed_or_timed_out))
            return
        }

        // VERIFY — hosts.yml / cache first (fast); never stall UI on setup-git
        phase(GhAuthPhase.VERIFY, tr(R.string.cli_verifying))
        status = probeStatusSync(ctx, method)
        if (status.loggedIn) {
            phase(GhAuthPhase.SUCCESS, tr(R.string.cli_signed_in_as, status.username))
            mainHandler.post { listener.onDone(status) }
            executor.execute { runSetupGit(ctx, method) }
        } else {
            fail(tr(R.string.cli_auth_completed_but_not_logged))
        }
    }

    private fun runInstall(
        ctx: Context,
        method: String,
        session: GhAuthSession,
        listener: GhAuthListener
    ): Boolean {
        // Primary apt
        var ok = streamRoot(ctx, method, GhGuestCommands.installPrimary(), session, listener)
        if (session.cancelled) return false
        if (ok) {
            // re-check installed
            val st = probeStatusSync(ctx, method)
            if (st.ghInstalled) return true
        }
        mainHandler.post {
            listener.onLog(tr(R.string.cli_primary_apt_failed_or_package))
        }
        streamRoot(ctx, method, GhGuestCommands.installFallback(), session, listener)
        if (session.cancelled) return false
        val st = probeStatusSync(ctx, method)
        return st.ghInstalled
    }

    private fun streamRoot(
        ctx: Context,
        method: String,
        guestCmd: String,
        session: GhAuthSession,
        listener: GhAuthListener
    ): Boolean {
        val (args, env) = PackageInstallRunner.buildRootExec(ctx, guestCmd, method)
        return streamBlocking(ctx, args, env, session, listener, timeoutMs = 8 * 60 * 1000L)
    }

    /**
     * Device OAuth on Android HTTPS, then store token for guest flux.
     * 1) Write ~/.config/gh/hosts.yml into rootfs (reliable, no proot pipe)
     * 2) Fallback: token file + `gh auth login --with-token`
     */
    private fun runLoginAndroidDeviceFlow(
        ctx: Context,
        method: String,
        session: GhAuthSession,
        listener: GhAuthListener
    ): Boolean {
        val logLine: (String) -> Unit = { line ->
            if (!session.cancelled) mainHandler.post { listener.onLog(line) }
        }

        val device = try {
            requestDeviceCode()
        } catch (e: Exception) {
            logLine("Device code request failed: ${e.message}")
            return false
        }
        if (session.cancelled) return false

        val userCode = device.userCode
        logLine("One-time code: $userCode")
        logLine("Open: ${device.verificationUri}")
        logLine("(Approve in Android browser; app polls GitHub on device network)")
        copyCode(ctx, userCode)
        mainHandler.post {
            listener.onPhase(GhAuthPhase.WAIT_BROWSER, "Code: $userCode")
            listener.onOtp(userCode)
        }
        openDeviceBrowser(ctx, userCode)

        val token = pollAccessToken(device, session, logLine)
        if (session.cancelled) return false
        if (token.isNullOrBlank()) {
            logLine(tr(R.string.cli_no_access_token_denied_expired))
            return false
        }

        logLine(tr(R.string.cli_token_received_resolving_username))
        mainHandler.post { listener.onPhase(GhAuthPhase.VERIFY, tr(R.string.cli_saving_credentials)) }
        val username = try {
            fetchGithubLogin(token)
        } catch (e: Exception) {
            logLine("api.github.com/user failed: ${e.message}")
            null
        }
        if (username.isNullOrBlank()) {
            logLine(tr(R.string.cli_could_not_resolve_github_username))
        } else {
            logLine(tr(R.string.cli_github_user, username))
        }

        // Primary: write hosts.yml into guest rootfs from Android (no guest network)
        val finalUser = username ?: "user"
        val wrote = writeHostsYml(ctx, method, token, finalUser)
        if (wrote) {
            logLine(tr(R.string.cli_wrote_config_gh_hosts_yml, method))
            // Seed cache now — VERIFY must not re-hang on guest shell
            if (!username.isNullOrBlank()) {
                rememberAuth(ctx, method, GhAuthStatus(
                    method, true, true, username, raw = "hosts.yml-write"
                ))
            }
        } else {
            logLine(tr(R.string.cli_direct_hosts_yml_write_failed))
            if (!injectTokenViaGh(ctx, method, token, logLine)) {
                logLine(tr(R.string.cli_token_inject_failed))
                return false
            }
            if (!username.isNullOrBlank()) {
                rememberAuth(ctx, method, GhAuthStatus(
                    method, true, true, username, raw = "token-inject"
                ))
            }
        }
        // setup-git deferred to VERIFY (timed) — do not block here
        return true
    }

    /** Write gh hosts.yml into guest flux home. Proot = direct FS; chroot = RootShell copy. */
    private fun writeHostsYml(
        ctx: Context,
        method: String,
        token: String,
        username: String
    ): Boolean {
        val safeUser = username.replace(Regex("[^A-Za-z0-9-]"), "")
        if (safeUser.isBlank()) return false
        val safeToken = token.trim()
        // gho_/ghp_/github_pat_* — reject whitespace / YAML-breakers only
        if (safeToken.isEmpty() || safeToken.any { it.isWhitespace() || it == '"' || it == '\'' }) {
            return false
        }
        // Format compatible with gh 2.4x insecure storage
        val body =
            """
            |github.com:
            |    oauth_token: $safeToken
            |    user: $safeUser
            |    git_protocol: https
            |    users:
            |        $safeUser:
            |            oauth_token: $safeToken
            """.trimMargin() + "\n"
        val cfgBody = "git_protocol: https\n"

        if (!com.zenithblue.nativecode.ProotOnly.ON && method == "chroot") {
            return writeHostsYmlChroot(ctx, body, cfgBody)
        }

        val home = ProjectPathResolver.guestHomeDir(ctx, method)
        val dir = File(home, ".config/gh")
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            val hosts = File(dir, "hosts.yml")
            hosts.writeText(body)
            hosts.setReadable(false, false)
            hosts.setReadable(true, true)
            hosts.setWritable(false, false)
            hosts.setWritable(true, true)
            val cfg = File(dir, "config.yml")
            if (!cfg.exists()) cfg.writeText(cfgBody)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Chroot home not app-writable — stage in cache, RootShell copyIntoChroot. */
    private fun writeHostsYmlChroot(ctx: Context, hostsBody: String, cfgBody: String): Boolean {
        if (com.zenithblue.nativecode.ProotOnly.ON) return false
        if (!RootShell.isRootAvailable()) return false
        return try {
            val dir = File(ctx.cacheDir, "nc_gh_stage").also { it.mkdirs() }
            val hostsTmp = File(dir, "hosts.yml").apply { writeText(hostsBody) }
            val cfgTmp = File(dir, "config.yml").apply { writeText(cfgBody) }
            val root = ChrootCommandBuilder.CHROOT_PATH
            // Ensure dir owned by flux before copy
            RootShell.executeSync(
                "mkdir -p '$root/home/flux/.config/gh' && " +
                    "(chown -R flux:flux '$root/home/flux/.config' 2>/dev/null || " +
                    "chown -R 1000:1000 '$root/home/flux/.config' 2>/dev/null || true)"
            )
            val hostsOk = RootShell.copyIntoChroot(
                hostsTmp,
                "/home/flux/.config/gh/hosts.yml"
            ).exitCode == 0
            // config.yml best-effort (may already exist)
            RootShell.copyIntoChroot(cfgTmp, "/home/flux/.config/gh/config.yml")
            RootShell.executeSync(
                "chmod 600 '$root/home/flux/.config/gh/hosts.yml' 2>/dev/null || true; " +
                    "(chown flux:flux '$root/home/flux/.config/gh/hosts.yml' " +
                    "'$root/home/flux/.config/gh/config.yml' 2>/dev/null || true)"
            )
            try {
                hostsTmp.delete()
                cfgTmp.delete()
            } catch (_: Exception) {
            }
            hostsOk
        } catch (_: Exception) {
            false
        }
    }

    private fun readHostsYmlUser(ctx: Context, method: String): String? {
        return try {
            val text = if (!com.zenithblue.nativecode.ProotOnly.ON && method == "chroot") {
                if (!RootShell.isRootAvailable()) return null
                val root = ChrootCommandBuilder.CHROOT_PATH
                RootShell.capture(
                    "cat '$root/home/flux/.config/gh/hosts.yml' 2>/dev/null",
                    timeoutMs = 10_000L
                )
            } else {
                val f = File(ProjectPathResolver.guestHomeDir(ctx, method), ".config/gh/hosts.yml")
                if (!f.isFile) return null
                f.readText()
            }
            if (text.isBlank()) return null
            // user: name
            Regex("""(?m)^\s*user:\s*(\S+)\s*$""").find(text)?.groupValues?.get(1)
                ?.takeIf { it != "github.com" && !it.contains("token") }
        } catch (_: Exception) {
            null
        }
    }

    private fun injectTokenViaGh(
        ctx: Context,
        method: String,
        token: String,
        log: (String) -> Unit
    ): Boolean {
        val guestPath = "/home/flux/.config/gh/.nc_token_inject"
        return try {
            if (!com.zenithblue.nativecode.ProotOnly.ON && method == "chroot") {
                if (!RootShell.isRootAvailable()) {
                    log("inject: no root")
                    return false
                }
                val tmp = File(ctx.cacheDir, "nc_gh_token_inject").apply {
                    writeText(token.trim() + "\n")
                }
                val root = ChrootCommandBuilder.CHROOT_PATH
                RootShell.executeSync("mkdir -p '$root/home/flux/.config/gh'")
                val copied = RootShell.copyIntoChroot(tmp, guestPath)
                try { tmp.delete() } catch (_: Exception) {}
                if (copied.exitCode != 0) {
                    log("inject: stage token failed")
                    return false
                }
                val cap = RootShell.captureInChroot(
                    GhGuestCommands.authLoginWithTokenFile(guestPath),
                    user = "flux",
                    timeoutMs = 90_000L,
                    context = ctx
                )
                cap.stdout.lineSequence().forEach { line ->
                    val safe = if (line.contains(token)) "***" else line
                    if (safe.isNotBlank()) log(safe)
                }
                return cap.exitCode == 0
            }

            // proot: host-visible rootfs
            val home = ProjectPathResolver.guestHomeDir(ctx, method)
            val tokenFile = File(home, ".config/gh/.nc_token_inject")
            tokenFile.parentFile?.mkdirs()
            tokenFile.writeText(token.trim() + "\n")
            val (args, env) = fluxCmd(ctx, GhGuestCommands.authLoginWithTokenFile(guestPath), method)
            mergeGhEnv(env)
            val (exit, out) = ShellCommandRunner.runCaptureExit(ctx, args, env)
            out.lineSequence().forEach { line ->
                val safe = if (line.contains(token)) "***" else line
                if (safe.isNotBlank()) log(safe)
            }
            try { tokenFile.delete() } catch (_: Exception) {}
            exit == 0
        } catch (e: Exception) {
            log("inject: ${e.message}")
            false
        }
    }

    private fun fetchGithubLogin(token: String): String? {
        val conn = (URL("https://api.github.com/user").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "NativeCode-GitHubConnect")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            } ?: ""
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${text.take(120)}")
            }
            return JSONObject(text).optString("login", "").ifBlank { null }
        } finally {
            conn.disconnect()
        }
    }

    private data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSec: Int,
        val expiresInSec: Int
    )

    private fun requestDeviceCode(): DeviceCodeResponse {
        val body = formEncode(
            "client_id" to GH_CLI_CLIENT_ID,
            "scope" to OAUTH_SCOPES
        )
        val raw = httpPost(DEVICE_CODE_URL, body)
        val json = JSONObject(raw)
        if (json.has("error")) {
            throw IllegalStateException(json.optString("error_description", json.optString("error")))
        }
        val userCode = json.getString("user_code")
        val deviceCode = json.getString("device_code")
        val uri = json.optString("verification_uri", GhGuestCommands.DEVICE_URL)
        val interval = json.optInt("interval", 5).coerceAtLeast(1)
        val expires = json.optInt("expires_in", 900)
        return DeviceCodeResponse(deviceCode, userCode, uri, interval, expires)
    }

    private fun pollAccessToken(
        device: DeviceCodeResponse,
        session: GhAuthSession,
        log: (String) -> Unit
    ): String? {
        val deadline = System.currentTimeMillis() +
            (device.expiresInSec * 1000L).coerceAtMost(GhGuestCommands.AUTH_TIMEOUT_MS)
        var intervalMs = device.intervalSec * 1000L
        var lastStatusLog = 0L

        while (System.currentTimeMillis() < deadline) {
            if (session.cancelled) return null
            Thread.sleep(intervalMs)
            if (session.cancelled) return null

            val body = formEncode(
                "client_id" to GH_CLI_CLIENT_ID,
                "device_code" to device.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
            )
            val raw = try {
                httpPost(ACCESS_TOKEN_URL, body)
            } catch (e: Exception) {
                val now = System.currentTimeMillis()
                if (now - lastStatusLog > 15_000) {
                    log("Poll network error (retrying): ${e.message}")
                    lastStatusLog = now
                }
                continue
            }

            val json = try {
                JSONObject(raw)
            } catch (_: Exception) {
                // form-encoded fallback: access_token=...&...
                if (raw.contains("access_token=")) {
                    return raw.substringAfter("access_token=").substringBefore('&').trim()
                }
                continue
            }

            when {
                json.has("access_token") -> {
                    return json.getString("access_token")
                }
                json.optString("error") == "authorization_pending" -> {
                    val now = System.currentTimeMillis()
                    if (now - lastStatusLog > 20_000) {
                        log(tr(R.string.cli_waiting_for_browser_approval))
                        lastStatusLog = now
                    }
                }
                json.optString("error") == "slow_down" -> {
                    intervalMs += 5000
                    log(tr(R.string.cli_slowing_poll))
                }
                json.optString("error") == "expired_token" -> {
                    log(tr(R.string.cli_device_code_expired))
                    return null
                }
                json.optString("error") == "access_denied" -> {
                    log(tr(R.string.cli_access_denied_in_browser))
                    return null
                }
                json.has("error") -> {
                    log(json.optString("error_description", json.optString("error")))
                    return null
                }
            }
        }
        log(tr(R.string.cli_auth_timed_out))
        return null
    }

    private fun formEncode(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

    private fun httpPost(url: String, formBody: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "NativeCode-GitHubConnect")
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(formBody) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { s ->
                BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() }
            } ?: ""
            if (code !in 200..299 && text.isBlank()) {
                throw IllegalStateException("HTTP $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Best-effort `gh auth setup-git`. Never block auth success.
     * Chroot: RootShell timed (full Terminal chroot was hanging after hosts.yml write).
     */
    private fun runSetupGit(ctx: Context, method: String) {
        try {
            if (!com.zenithblue.nativecode.ProotOnly.ON && method == "chroot") {
                if (!RootShell.isRootAvailable()) return
                RootShell.captureInChroot(
                    GhGuestCommands.authSetupGit(),
                    user = "flux",
                    timeoutMs = 25_000L,
                    context = ctx
                )
                return
            }
            val (args, env) = fluxCmd(ctx, GhGuestCommands.authSetupGit(), method)
            mergeGhEnv(env)
            ShellCommandRunner.runCaptureExit(ctx, args, env)
        } catch (_: Exception) {
        }
    }

    /**
     * Block bg thread until stream completes or cancel/timeout.
     * onLine/onDone already on main from runner; we need exit on bg.
     */
    private fun streamBlocking(
        ctx: Context,
        args: Array<String>,
        env: Map<String, String>?,
        session: GhAuthSession,
        listener: GhAuthListener,
        timeoutMs: Long
    ): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var exitCode = -1
        val job = ShellCommandRunner.runStreamedCancelable(
            ctx, args, env,
            onLine = { line -> mainHandler.post { listener.onLog(line) } },
            onDone = { code ->
                exitCode = code
                latch.countDown()
            }
        )
        session.attach(job)
        val finished = latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            session.cancel()
            return false
        }
        session.clearJob()
        return exitCode == 0 && !session.cancelled
    }

    // ── Parse ───────────────────────────────────────────────────────────────

    fun parseAuthStatusJson(method: String, raw: String): GhAuthStatus {
        // Find JSON object in output (may have warnings around it)
        val jsonStart = raw.indexOf('{')
        val jsonEnd = raw.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) {
            return GhAuthStatus(method, true, false, raw = raw)
        }
        return try {
            val obj = JSONObject(raw.substring(jsonStart, jsonEnd + 1))
            val hosts = obj.optJSONObject("hosts")
            val arr = hosts?.optJSONArray(GhGuestCommands.HOST)
            if (arr == null || arr.length() == 0) {
                return GhAuthStatus(method, true, false, raw = raw)
            }
            var username: String? = null
            var loggedIn = false
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val active = e.optBoolean("active", false)
                val state = e.optString("state", "")
                val login = e.optString("login", "").ifBlank { null }
                if (active && state == "success" && login != null) {
                    loggedIn = true
                    username = login
                    break
                }
                if (state == "success" && login != null && username == null) {
                    loggedIn = true
                    username = login
                }
            }
            GhAuthStatus(method, true, loggedIn, username, raw)
        } catch (_: Exception) {
            GhAuthStatus(method, true, false, raw = raw, error = "parse error")
        }
    }

    fun parseAuthStatusHuman(method: String, raw: String): GhAuthStatus {
        // e.g. "✓ Logged in to github.com as zenithblue-oss" / "account zenithblue-oss"
        val patterns = listOf(
            Regex("""Logged in to github\.com as (\S+)""", RegexOption.IGNORE_CASE),
            Regex("""Logged in to github\.com account (\S+)""", RegexOption.IGNORE_CASE),
            Regex("""account\s+(\S+)\s+\(""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            val m = p.find(raw)
            if (m != null) {
                val user = m.groupValues[1].trim().trimEnd(')', ',', '.')
                if (user.isNotBlank()) {
                    return GhAuthStatus(method, true, true, user, raw)
                }
            }
        }
        val notLogged = raw.contains("not logged", true) ||
            raw.contains("You are not logged", true) ||
            raw.contains("no accounts", true)
        return GhAuthStatus(method, true, false, raw = raw, error = if (notLogged) null else null)
    }

    fun parseRepoList(raw: String): List<GhRepo> {
        val jsonStart = raw.indexOf('[')
        val jsonEnd = raw.lastIndexOf(']')
        if (jsonStart < 0 || jsonEnd <= jsonStart) return emptyList()
        val arr = org.json.JSONArray(raw.substring(jsonStart, jsonEnd + 1))
        val out = ArrayList<GhRepo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("nameWithOwner", "")
            val url = o.optString("url", "")
            if (name.isBlank() || url.isBlank()) continue
            val desc = o.optString("description", "").ifBlank { null }
            out.add(
                GhRepo(
                    nameWithOwner = name,
                    url = url,
                    isPrivate = o.optBoolean("isPrivate", false),
                    description = desc
                )
            )
        }
        return out
    }

    fun extractOtp(line: String): String? {
        val m = OTP_PATTERN.matcher(line.uppercase())
        return if (m.find()) m.group(1) else null
    }

    // ── Builders ────────────────────────────────────────────────────────────

    private fun rememberAuth(ctx: Context, method: String, status: GhAuthStatus) {
        val gen = authGen(method)
        statusCache[statusKey(ctx, method)] = StatusHit(status, System.nanoTime(), gen)
    }

    private fun fluxCmd(
        ctx: Context,
        shellCmd: String,
        method: String
    ): Pair<Array<String>, HashMap<String, String>> =
        LinuxCommandBuilder.build(ctx, shellCmd, user = "flux", method = method)

    private fun mergeGhEnv(env: HashMap<String, String>) {
        GhGuestCommands.ghEnv().forEach { (k, v) -> env[k] = v }
    }
}
