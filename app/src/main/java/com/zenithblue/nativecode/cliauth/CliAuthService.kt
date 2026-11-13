package com.zenithblue.nativecode.cliauth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * SSOT for AI CLI browser / device-code / API-key auth in guest (flux).
 * Per-method state (proot ≠ chroot). Mirrors GitHubCliService patterns.
 */
object CliAuthService {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val activeSession = AtomicBoolean(false)

    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    private val OTP_PATTERN = Pattern.compile(
        "\\b([A-Z0-9]{4,5}-[A-Z0-9]{4,6})\\b"
    )
    private const val LOGOUT_TIMEOUT_S = 30L

    fun isSessionActive(): Boolean = activeSession.get()

    /**
     * Start login for [toolId]. Returns null if another session is active
     * or tool needs UI-only path handled before call.
     */
    fun startLogin(
        ctx: Context,
        method: String,
        toolId: String,
        listener: CliAuthListener
    ): CliAuthSession? {
        val def = CliToolCatalog.byId(toolId)
        if (def == null) {
            mainHandler.post { listener.onFailed("Unknown tool: $toolId") }
            return null
        }
        if (def.strategy == CliLoginStrategy.API_KEY_FORM) {
            mainHandler.post {
                listener.onFailed("Use API key form for ${def.displayName}")
            }
            return null
        }
        if (!activeSession.compareAndSet(false, true)) {
            mainHandler.post { listener.onFailed("Auth already in progress") }
            return null
        }
        val session = CliAuthSession()
        val appCtx = ctx.applicationContext
        executor.execute {
            try {
                runLogin(appCtx, method, def, session, listener)
            } catch (e: Exception) {
                if (!session.cancelled) {
                    mainHandler.post {
                        listener.onFailed(e.message ?: "Login error")
                    }
                }
            } finally {
                activeSession.set(false)
            }
        }
        return session
    }

    fun saveApiKey(
        ctx: Context,
        method: String,
        toolId: String,
        apiKey: String,
        onResult: (Boolean, String, CliToolStatus?) -> Unit
    ) {
        executor.execute {
            val def = CliToolCatalog.byId(toolId)
            if (def == null) {
                mainHandler.post { onResult(false, "Unknown tool", null) }
                return@execute
            }
            val key = apiKey.trim()
            if (key.isBlank()) {
                mainHandler.post { onResult(false, "Empty key", null) }
                return@execute
            }
            val envKey = def.envKey ?: when (toolId) {
                "qwen" -> "BAILIAN_CODING_PLAN_API_KEY"
                "grok" -> "XAI_API_KEY"
                "kiro" -> "KIRO_API_KEY"
                "claude" -> "CLAUDE_CODE_OAUTH_TOKEN"
                else -> null
            }
            if (envKey == null) {
                mainHandler.post { onResult(false, "No env key for tool", null) }
                return@execute
            }
            val ok = writeAuthEnvKey(ctx, method, envKey, key)
            if (toolId == "qwen" && ok) {
                writeQwenSettings(ctx, method, key)
            }
            val st = probeOneSync(ctx, method, def)
            mainHandler.post {
                onResult(ok, if (ok) "Saved $envKey" else "Write failed", st)
            }
        }
    }

    fun logout(
        ctx: Context,
        method: String,
        toolId: String,
        onResult: (Boolean, String) -> Unit
    ) {
        executor.execute {
            val def = CliToolCatalog.byId(toolId)
            if (def == null) {
                mainHandler.post { onResult(false, "Unknown tool") }
                return@execute
            }
            // The CLI's own non-interactive logout where it has one; the rest remove only
            // their own auth file below (opencode's `auth logout` is an interactive picker).
            val cmd = when (toolId) {
                "codex" -> CliGuestCommands.logoutCodex()
                "kiro" -> CliGuestCommands.logoutKiro()
                else -> null
            }
            if (cmd != null) {
                try {
                    val (args, env) = fluxCmd(ctx, cmd, method)
                    mergeCliEnv(env)
                    val p = ShellCommandRunner.startStdout(ctx, args, env)
                    p.outputStream.close() // EOF for any prompt
                    Thread { try { p.inputStream.readBytes() } catch (_: Exception) {} }
                        .apply { isDaemon = true }.start()
                    if (!p.waitFor(LOGOUT_TIMEOUT_S, TimeUnit.SECONDS)) p.destroyForcibly()
                } catch (_: Exception) {
                }
            }
            val home = ProjectPathResolver.guestHomeDir(ctx, method)
            try {
                CliCredentialProbe.clearFiles(toolId, home)
            } catch (_: Exception) {
            }
            // Keys saved by the API-key form
            listOfNotNull(def.envKey, "DASHSCOPE_API_KEY".takeIf { toolId == "qwen" },
                "GROK_API_KEY".takeIf { toolId == "grok" }, "ANTHROPIC_API_KEY".takeIf { toolId == "claude" })
                .forEach { clearAuthEnvKey(ctx, method, it) }
            val (ok, msg) = logoutOutcome(CliCredentialProbe.probe(toolId, home).state, def.displayName)
            mainHandler.post { onResult(ok, msg) }
        }
    }

    fun openBrowser(ctx: Context, url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        } catch (_: Exception) {
        }
    }

    fun copyCode(ctx: Context, code: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("cli-auth-code", code))
        } catch (_: Exception) {
        }
    }

    // ── login state machine ─────────────────────────────────────────────────

    private fun runLogin(
        ctx: Context,
        method: String,
        def: CliToolDef,
        session: CliAuthSession,
        listener: CliAuthListener
    ) {
        fun phase(p: CliAuthPhase, msg: String) {
            if (!session.cancelled) mainHandler.post { listener.onPhase(p, msg) }
        }
        fun log(line: String) {
            if (!session.cancelled) mainHandler.post { listener.onLog(line) }
        }
        fun fail(msg: String) {
            phase(CliAuthPhase.FAILED, msg)
            mainHandler.post { listener.onFailed(msg) }
        }

        phase(CliAuthPhase.CHECK_BIN, "Checking ${def.bin}…")
        // Tools mandatory via setup_cli_tools — soft probe only; do not hard-block login
        val installed = isInstalledSync(ctx, method, def)
        if (!installed) {
            log("WARN: ${def.bin} not found on PATH (continuing login attempt)")
        }
        if (session.cancelled) {
            mainHandler.post { listener.onCancelled() }
            return
        }

        when (def.strategy) {
            CliLoginStrategy.DEVICE_CODE -> {
                phase(CliAuthPhase.LOGIN, "Starting device login…")
                val loginCmd = when (def.id) {
                    "codex" -> CliGuestCommands.loginCodexDevice()
                    "kiro" -> CliGuestCommands.loginKiro()
                    "grok" -> CliGuestCommands.loginGrok()
                    else -> null
                }
                if (loginCmd == null) {
                    fail("No device login for ${def.id}")
                    return
                }
                val ok = streamLoginAndCapture(ctx, method, def, loginCmd, session, listener)
                if (session.cancelled) {
                    mainHandler.post { listener.onCancelled() }
                    return
                }
                if (!ok) {
                    // kiro may need terminal; codex fail is hard
                    if (def.id == "kiro") {
                        phase(CliAuthPhase.LOGIN, "Opening guided terminal…")
                        mainHandler.post {
                            listener.onTerminalGuided(def.id, "kiro-cli login")
                        }
                        // Still fail session so overlay closes path can open term
                        fail("Complete login in terminal (device code)")
                        return
                    }
                    fail("Device login failed or timed out")
                    return
                }
            }
            CliLoginStrategy.STREAM_URL -> {
                phase(CliAuthPhase.LOGIN, "Starting browser login…")
                val loginCmd = when (def.id) {
                    "claude" -> CliGuestCommands.loginClaude()
                    else -> null
                }
                if (loginCmd == null) {
                    fail("No stream login for ${def.id}")
                    return
                }
                val ok = streamLoginAndCapture(ctx, method, def, loginCmd, session, listener)
                if (session.cancelled) {
                    mainHandler.post { listener.onCancelled() }
                    return
                }
                if (!ok) {
                    mainHandler.post { listener.onTerminalGuided(def.id, "claude auth login") }
                    fail("Not signed in yet; finish in the terminal if needed")
                    return
                }
            }
            // MainActivity routes these to the key dialog / the tool's own terminal before here.
            CliLoginStrategy.TERMINAL_GUIDED, CliLoginStrategy.API_KEY_FORM -> {
                fail("Sign in from the ${def.displayName} terminal or key form")
                return
            }
        }

        if (session.cancelled) {
            mainHandler.post { listener.onCancelled() }
            return
        }
        phase(CliAuthPhase.VERIFY, "Verifying…")
        // Poll status a few times (credentials may settle)
        var status = probeOneSync(ctx, method, def)
        var attempts = 0
        while (!status.loggedIn && attempts < 8 && !session.cancelled) {
            Thread.sleep(1500)
            status = probeOneSync(ctx, method, def)
            attempts++
            log("Probe ${attempts}: installed=${status.installed} in=${status.loggedIn}")
        }
        if (session.cancelled) {
            mainHandler.post { listener.onCancelled() }
            return
        }
        if (status.loggedIn) {
            phase(CliAuthPhase.SUCCESS, "Signed in")
            mainHandler.post { listener.onDone(status) }
        } else {
            // Device flow may have completed in CLI but probe weak — soft fail with tip
            fail("Auth ran but status still signed-out (refresh or re-check)")
        }
    }

    /**
     * Stream login command; extract URL/OTP; open browser; wait until process ends
     * or status becomes logged-in.
     */
    private fun streamLoginAndCapture(
        ctx: Context,
        method: String,
        def: CliToolDef,
        loginCmd: String,
        session: CliAuthSession,
        listener: CliAuthListener
    ): Boolean {
        val (args, env) = fluxCmd(ctx, loginCmd, method)
        mergeCliEnv(env)
        val timeout = CliGuestCommands.AUTH_TIMEOUT_MS
        val latch = CountDownLatch(1)
        var exitCode = -1
        val lastUrl = AtomicReference<String?>(null)
        val lastOtp = AtomicReference<String?>(null)

        val job = ShellCommandRunner.runStreamedCancelable(
            ctx, args, env,
            onLine = { raw ->
                if (session.cancelled) return@runStreamedCancelable
                val line = ANSI.replace(raw, "")
                mainHandler.post { listener.onLog(line) }
                extractAuthUrl(line)?.let { url ->
                    if (lastUrl.get() != url) {
                        lastUrl.set(url)
                        mainHandler.post {
                            listener.onPhase(CliAuthPhase.WAIT_BROWSER, "Open browser…")
                            listener.onUrl(url)
                        }
                        openBrowser(ctx, url)
                    }
                }
                extractOtp(line)?.let { otp ->
                    if (lastOtp.get() != otp) {
                        lastOtp.set(otp)
                        copyCode(ctx, otp)
                        mainHandler.post { listener.onOtp(otp) }
                    }
                }
            },
            onDone = { code ->
                exitCode = code
                latch.countDown()
            }
        )
        session.attach(job)

        // Parallel poll status while process runs
        val pollThread = Thread {
            var n = 0
            while (!session.cancelled && latch.count > 0 && n < 120) {
                Thread.sleep(3000)
                n++
                if (session.cancelled) break
                val st = probeOneSync(ctx, method, def)
                if (st.loggedIn) {
                    session.cancel() // stop login process once authenticated
                    break
                }
            }
        }
        pollThread.start()

        val finished = latch.await(timeout, TimeUnit.MILLISECONDS)
        if (!finished) {
            session.cancel()
        }
        session.clearJob()
        try {
            pollThread.join(500)
        } catch (_: Exception) {
        }

        if (session.cancelled && probeOneSync(ctx, method, def).loggedIn) {
            return true
        }
        // Success if logged in OR process exited 0 after OTP shown
        val st = probeOneSync(ctx, method, def)
        if (st.loggedIn) return true
        if (finished && exitCode == 0 && lastOtp.get() != null) {
            // give status a moment
            Thread.sleep(2000)
            return probeOneSync(ctx, method, def).loggedIn
        }
        return st.loggedIn
    }

    // ── probe ───────────────────────────────────────────────────────────────

    private fun probeOneSync(ctx: Context, method: String, def: CliToolDef): CliToolStatus {
        if (!isInstalledSync(ctx, method, def)) {
            return CliToolStatus(def.id, method, false, false, detail = "not installed")
        }
        return hostStatus(ctx, method, def.id).copy(installed = true)
    }

    /** Login state from the tool's auth file on the host (no guest process). */
    fun hostStatus(ctx: Context, method: String, toolId: String): CliToolStatus {
        val info = CliCredentialProbe.probe(toolId, ProjectPathResolver.guestHomeDir(ctx, method))
        return CliToolStatus(
            toolId, method, installed = false,
            loggedIn = info.state == AuthState.SIGNED_IN,
            accountLabel = info.account ?: info.detail,
            detail = info.state.name
        )
    }

    /**
     * Guest command -v + host-visible rootfs path check.
     * Host FS is authoritative when proot/chroot capture is flaky (quote/NOMATCH).
     * CLI tools are mandatory onboarding — host hit alone is enough for INSTALLED.
     */
    private fun isInstalledSync(ctx: Context, method: String, def: CliToolDef): Boolean {
        if (hostBinPresent(ctx, method, def)) return true
        return try {
            val (args, env) = fluxCmd(
                ctx,
                CliGuestCommands.detectBin(def.bin, def.altBins),
                method
            )
            mergeCliEnv(env)
            val out = ShellCommandRunner.runCaptureExit(ctx, args, env).second
            out.contains("BIN_OK")
        } catch (_: Exception) {
            // Soft: host miss + capture fail → still try host once more with nvm scan
            hostBinPresent(ctx, method, def, deep = true)
        }
    }

    /** Walk guest home for bin under .local/bin, bin, nvm node bins. */
    private fun hostBinPresent(
        ctx: Context,
        method: String,
        def: CliToolDef,
        deep: Boolean = true
    ): Boolean {
        val names = listOf(def.bin) + def.altBins
        val home = ProjectPathResolver.guestHomeDir(ctx, method)
        val dirs = mutableListOf(
            File(home, ".local/bin"),
            File(home, "bin"),
            File(home, ".cargo/bin")
        )
        if (deep) {
            val nodeRoot = File(home, ".nvm/versions/node")
            if (nodeRoot.isDirectory) {
                nodeRoot.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("v") }
                    ?.sortedBy { it.name }
                    ?.forEach { dirs.add(File(it, "bin")) }
            }
            // system paths on chroot rootfs (visible on host)
            val root = if (method == "chroot") {
                ProjectPathResolver.chrootRootfsDir()
            } else {
                ProjectPathResolver.prootRootfsDir(ctx)
            }
            dirs.add(File(root, "usr/local/bin"))
            dirs.add(File(root, "usr/bin"))
            dirs.add(File(root, "bin"))
        }
        for (d in dirs) {
            for (n in names) {
                val f = File(d, n)
                if ((f.isFile && (f.canExecute() || f.length() > 0L)) ||
                    java.nio.file.Files.isSymbolicLink(f.toPath())) return true
            }
        }
        return false
    }

    // ── file writes ─────────────────────────────────────────────────────────

    private fun authEnvFile(ctx: Context, method: String): File {
        val home = ProjectPathResolver.guestHomeDir(ctx, method)
        // Callers catch: a path symlinked outside the guest home is never written.
        return CliCredentialProbe.inHome(home, CliGuestCommands.AUTH_ENV_REL)
            ?: throw java.io.IOException("auth env path escapes guest home")
    }

    private fun writeAuthEnvKey(
        ctx: Context,
        method: String,
        envKey: String,
        value: String
    ): Boolean {
        return try {
            val f = authEnvFile(ctx, method)
            f.parentFile?.mkdirs()
            val safeKey = envKey.replace(Regex("[^A-Za-z0-9_]"), "")
            if (safeKey.isBlank()) return false
            // Escape single quotes for shell
            val safeVal = value.replace("'", "'\\''")
            val existing = if (f.exists()) f.readText() else ""
            val lines = existing.lines().filter {
                !it.trimStart().startsWith("export $safeKey=") &&
                    !it.trimStart().startsWith("$safeKey=")
            }.toMutableList()
            if (lines.isEmpty() || lines.first() != "# Managed by CliAuthService — do not commit") {
                lines.add(0, "# Managed by CliAuthService — do not commit")
            }
            lines.add("export $safeKey='$safeVal'")
            f.writeText(lines.filter { it.isNotBlank() || it.startsWith("#") }.joinToString("\n") + "\n")
            try {
                f.setReadable(false, false)
                f.setReadable(true, true)
                f.setWritable(false, false)
                f.setWritable(true, true)
            } catch (_: Exception) {
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun clearAuthEnvKey(ctx: Context, method: String, envKey: String) {
        try {
            val f = authEnvFile(ctx, method)
            if (!f.exists()) return
            val safeKey = envKey.replace(Regex("[^A-Za-z0-9_]"), "")
            val lines = f.readText().lines().filter {
                !it.trimStart().startsWith("export $safeKey=") &&
                    !it.trimStart().startsWith("$safeKey=")
            }
            f.writeText(lines.joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
    }

    private fun writeQwenSettings(ctx: Context, method: String, apiKey: String): Boolean {
        return try {
            val f = CliCredentialProbe.inHome(ProjectPathResolver.guestHomeDir(ctx, method), ".qwen/settings.json")
                ?: return false
            f.parentFile?.mkdirs()
            val json = JSONObject()
            val modelProviders = JSONObject()
            val openai = JSONObject()
            openai.put("protocol", "openai")
            val models = org.json.JSONArray()
            val m = JSONObject()
            m.put("id", "qwen3-coder-plus")
            m.put("name", "qwen3-coder-plus (Coding Plan)")
            m.put("baseUrl", "https://coding-intl.dashscope.aliyuncs.com/v1")
            m.put("envKey", "BAILIAN_CODING_PLAN_API_KEY")
            models.put(m)
            openai.put("models", models)
            modelProviders.put("openai", openai)
            json.put("modelProviders", modelProviders)
            val env = JSONObject()
            env.put("BAILIAN_CODING_PLAN_API_KEY", apiKey)
            json.put("env", env)
            val security = JSONObject()
            val auth = JSONObject()
            auth.put("selectedType", "openai")
            security.put("auth", auth)
            json.put("security", security)
            val model = JSONObject()
            model.put("name", "qwen3-coder-plus")
            json.put("model", model)
            f.writeText(json.toString(2))
            true
        } catch (_: Exception) {
            false
        }
    }

    // ── parse helpers ───────────────────────────────────────────────────────

    private fun extractOtp(line: String): String? {
        // The code is alone on its line, or the line says "code" (not e.g. BUILD-TOOLS in an env dump).
        val alone = OTP_PATTERN.matcher(line.trim().uppercase()).let { it.matches() }
        if (!alone && !line.contains("code", ignoreCase = true)) return null
        val m = OTP_PATTERN.matcher(line.uppercase())
        if (!m.find()) return null
        val code = m.group(1) ?: return null
        // skip pure hex that looks like hashes if too long segments
        return code
    }

    private fun fluxCmd(
        ctx: Context,
        shellCmd: String,
        method: String
    ): Pair<Array<String>, HashMap<String, String>> =
        LinuxCommandBuilder.build(ctx, shellCmd, user = "flux", method = method)

    private fun mergeCliEnv(env: HashMap<String, String>) {
        CliGuestCommands.cliEnv().forEach { (k, v) -> env[k] = v }
    }
}

// Top level (not in the object) so JVM tests skip the object's Android Handler init.

private val URL_PATTERN = Pattern.compile("(https?://[^\\s\"'<>\\]\\)]+)")
private val AUTH_URL_HINTS = listOf(
    "auth", "login", "device", "openai", "claude", "anthropic", "kiro", "aws", "x.ai", "grok", "google"
)

/** First auth-looking URL on [line]; null for other links (docs, update notices). */
internal fun extractAuthUrl(line: String): String? {
    val m = URL_PATTERN.matcher(line)
    while (m.find()) {
        val u = m.group(1).trimEnd('.', ',', ';', ')', ']', '"', '\'')
        if (AUTH_URL_HINTS.any { u.lowercase().contains(it) }) return u
    }
    return null
}

/** Only a verified SIGNED_OUT is success; UNKNOWN (opencode 2.x db, agy keyring) is not. */
internal fun logoutOutcome(state: AuthState, name: String): Pair<Boolean, String> = when (state) {
    AuthState.SIGNED_OUT -> true to "Logged out $name"
    AuthState.SIGNED_IN -> false to "Still signed in; open the terminal to sign out"
    AuthState.UNKNOWN -> false to "Could not verify $name signed out; check in its terminal"
}
