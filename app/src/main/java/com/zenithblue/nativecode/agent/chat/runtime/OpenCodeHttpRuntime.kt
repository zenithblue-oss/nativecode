package com.zenithblue.nativecode.agent.chat.runtime

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.AgentRuntime
import com.zenithblue.nativecode.agent.chat.core.ChatMcp
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.PipeProcess
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.RuntimeCaps
import com.zenithblue.nativecode.agent.chat.core.RuntimeHost
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.SessionConfig
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.agent.chat.core.TurnInput
import com.zenithblue.nativecode.agent.chat.runtime.OpenCodeHttp.Companion.enc
import com.zenithblue.nativecode.agent.chat.runtime.OpenCodeHttp.Companion.location
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * opencode 2.x over HTTP + SSE, as t3code `provider-opencode/src/server/v2` (plan 9.2.4).
 *
 * One private `opencode serve --hostname=127.0.0.1 --port=<free>` per thread, never the TUI's managed service
 * (`serve --service`, 127.0.0.1:49374): a plain `serve` binds its own port (checked on the 2.3.0 binary), so each
 * chat is one gated process and one registry pid, and closing it can never kill the terminal tab's server. Fresh
 * 32-byte `OPENCODE_PASSWORD` per spawn (it overrides any inherited `OPENCODE_SERVER_PASSWORD`).
 *
 * Threads: REST calls run in order on one I/O thread; the event stream has its own thread; interrupt gets a short
 * thread of its own so it never queues behind a slow call. No method blocks the caller.
 */
class OpenCodeHttpRuntime(private val host: RuntimeHost) : AgentRuntime {
    override val caps = CAPS

    private val io = Executors.newSingleThreadExecutor { Thread(it, "opencode-io").apply { isDaemon = true } }
    @Volatile private var closed = false
    @Volatile private var proc: PipeProcess? = null
    @Volatile private var http: OpenCodeHttp? = null
    @Volatile private var stream: HttpURLConnection? = null
    @Volatile private var sessionId: String? = null
    private lateinit var sink: (AgentEvent) -> Unit
    private lateinit var mapper: OpenCodeMapper

    // I/O thread only.
    private lateinit var cfg: SessionConfig
    private var models: List<ModelInfo> = emptyList()
    private var commands: List<SlashCommand> = emptyList()
    private var model: String? = null
    private var effort: String? = null
    private val grants = ArrayList<JSONObject>()

    override fun start(cfg: SessionConfig, nativeId: String?, sink: (AgentEvent) -> Unit) {
        this.sink = sink
        this.cfg = cfg
        mapper = OpenCodeMapper(sink) { sid, fid -> onIo { api.call("DELETE", "/api/session/${enc(sid)}/form/${enc(fid)}") } }
        io.execute {
            try {
                boot(nativeId)
            } catch (e: Exception) {
                if (closed) return@execute
                host.log("NATIVE", "boot failed: ${e.message}")
                sink(AgentEvent.SessionState(AgentEvent.Phase.ERROR, message = e.message ?: "opencode did not start"))
                proc?.close()
            }
        }
    }

    override fun send(turn: TurnInput, steer: Boolean) = onIo {
        try {
            prompt(turn, steer)
        } catch (e: Exception) {
            if (steer) sink(AgentEvent.Notice("steer_failed", "Steer not delivered: ${e.message}"))
            else if (!mapper.active) sink(AgentEvent.TurnTerminal(RunStatus.FAILED, failureOf(e)))
        }
    }

    override fun interrupt() {
        val sid = sessionId ?: return
        mapper.markInterrupting()
        Thread({
            (listOf(sid) + mapper.runningChildren()).forEach { s ->
                runCatching { api.call("POST", "/api/session/${enc(s)}/interrupt", timeoutMs = 10_000) }
            }
        }, "opencode-interrupt").apply { isDaemon = true; start() }
    }

    override fun respond(requestNativeId: String, answer: RequestAnswer) = onIo {
        val req = mapper.request(requestNativeId) ?: return@onIo
        val s = "/api/session/${enc(req.sessionId)}"
        try {
            if (req.fields != null) {
                if (answer.cancel || answer.optionId == OpenCodeMapper.CANCEL) api.call("DELETE", "$s/form/${enc(requestNativeId)}")
                else api.call("POST", "$s/form/${enc(requestNativeId)}/reply", JSONObject().put("answer", OpenCodeWire.formAnswer(req.fields, answer.answers)))
                return@onIo
            }
            val option = if (answer.cancel) OpenCodeMapper.CANCEL else answer.optionId
            if (option == OpenCodeMapper.SESSION) {
                req.save.forEach { r -> if (grants.none { it.optString("action") == req.action && it.optString("resource") == r }) grants += OpenCodeWire.rule(req.action, "allow", r) }
                runCatching { api.call("PATCH", s, JSONObject().put("permissions", rules())) }
            }
            if (option == OpenCodeMapper.REJECT) {
                // Steer first, else the model only sees a failed tool and retries (t3code).
                val note = OpenCodeWire.declinedNote(req.action, req.resources) + (answer.feedback?.takeIf { it.isNotBlank() }?.let { "\nUser feedback: $it" } ?: "")
                runCatching { api.call("POST", "$s/synthetic", JSONObject().put("text", note).put("delivery", "steer").put("resume", false)) }
            }
            val reply = JSONObject().put("decision", if (option == OpenCodeMapper.ONCE || option == OpenCodeMapper.SESSION) "once" else "reject")
            if (option == OpenCodeMapper.REJECT) reply.put("message", OpenCodeWire.DECLINED)
            api.call("POST", "$s/permission/${enc(requestNativeId)}/reply", reply)
        } catch (e: OpenCodeHttp.HttpError) {
            if (e.status == 404) mapper.settleRequest(requestNativeId, RequestStatus.EXPIRED) else throw e
        }
    }

    override fun configure(change: ConfigChange) = onIo {
        val sid = sessionId ?: return@onIo
        val s = "/api/session/${enc(sid)}"
        if (change.model != null || change.effort != null) {
            model = change.model ?: model
            effort = change.effort ?: effort
            mapper.contextSize = models.firstOrNull { it.id == model }?.contextLimit
            OpenCodeWire.modelRef(model, effort, models)?.let { api.call("POST", "$s/model", JSONObject().put("model", it)) }
        }
        if (change.runtimeMode != null || change.interactionMode != null) {
            val planChanged = change.interactionMode != null && change.interactionMode != cfg.interactionMode
            cfg = cfg.copy(runtimeMode = change.runtimeMode ?: cfg.runtimeMode, interactionMode = change.interactionMode ?: cfg.interactionMode)
            api.call("PATCH", s, JSONObject().put("permissions", rules()))
            if (planChanged) api.call("POST", "$s/agent", JSONObject().put("agent", agent()))
        }
    }

    override fun close() {
        closed = true
        stream?.disconnect()
        proc?.close()
        io.shutdown()
    }

    override fun kill() {
        closed = true
        stream?.disconnect()
        proc?.kill()
        io.shutdownNow()
    }

    private val api: OpenCodeHttp get() = http ?: throw IOException("opencode is not running")

    /** Queue [block] on the I/O thread; failures become a protocol-log line + notice, never an exception. */
    private fun onIo(block: () -> Unit) {
        if (closed) return
        runCatching {
            io.execute {
                try {
                    block()
                } catch (e: Exception) {
                    if (!closed) {
                        host.log("NATIVE", "error: ${e.message}")
                        sink(AgentEvent.Notice("error", "opencode: ${e.message}"))
                    }
                }
            }
        }
    }

    private fun boot(nativeId: String?) {
        sink(AgentEvent.SessionState(AgentEvent.Phase.STARTING))
        val password = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val url = spawnServer(password, retryOnBind = true) ?: return
        val client = OpenCodeHttp(url.trimEnd('/'), password) { host.log("NATIVE", it) }
        http = client
        val version = client.call("GET", "/api/info", timeoutMs = 5_000)?.optString("version").orEmpty()
        if ((version.substringBefore('.').toIntOrNull() ?: 0) < 2) throw IOException("Chat not available for opencode $version (needs 2.x)")

        // Subscribe before any prompt: the stream is not replayed.
        val connected = CountDownLatch(1)
        Thread({ eventLoop(connected) }, "opencode-sse").apply { isDaemon = true; start() }
        if (!connected.await(10, TimeUnit.SECONDS)) throw IOException("opencode event stream did not open")

        val loc = location(cfg.cwd)
        // The first call with a location boots opencode's instance for that directory (project scan, git, plugins):
        // well over 15 s on a phone in a fresh project, so it alone gets the long timeout.
        models = OpenCodeWire.parseModels(runCatching { client.call("GET", "/api/model?$loc", timeoutMs = BOOT_CALL_MS)?.optJSONArray("data") }
            .onFailure { host.log("NATIVE", "models: ${it.message}") }.getOrNull())
        val serverDefault = runCatching { OpenCodeWire.slugOf(client.call("GET", "/api/model/default?$loc")?.optJSONObject("data")) }.getOrNull()
        val default = serverDefault?.takeIf { s -> models.any { it.id == s } } ?: models.firstOrNull { it.free }?.id ?: models.firstOrNull()?.id
        commands = loadCommands()
        ChatMcp.endpoint(cfg.threadId, "opencode")?.let { e -> // M7 NC tools (t3 `client.mcp.add`); body carries the token
            runCatching { client.call("PUT", "/api/experimental/mcp/${enc(e.name)}?$loc", OpenCodeSlash.mcpBody(e), secret = true) }
                .onFailure { host.log("NATIVE", "mcp ${e.name}: ${(it as? OpenCodeHttp.HttpError)?.status ?: it.javaClass.simpleName}") }
        }
        // OpenCode Go / Console quota (t3 `readServerUsageLimits`); null data = no Console workspace signed in.
        runCatching { OpenCodeSlash.usageLimits(client.call("GET", "/api/experimental/usage/limits?$loc", timeoutMs = 5_000)?.optJSONObject("data")) }
            .getOrNull()?.let(sink)
        model = cfg.model?.takeIf { s -> models.any { it.id == s } } ?: default
        effort = cfg.effort ?: OpenCodeWire.defaultEffort(models.firstOrNull { it.id == model })
        mapper.contextSize = models.firstOrNull { it.id == model }?.contextLimit

        val sid = nativeId?.let { resume(it) } ?: cfg.forkFrom?.let { fork(it) } ?: create()
        sessionId = sid
        mapper.root = sid
        sink(AgentEvent.NativeId(sid))
        sink(AgentEvent.SessionState(AgentEvent.Phase.READY, caps, models, default, commands, message = OpenCodeWire.SIGN_IN.takeIf { models.isEmpty() }, model = model))
    }

    /** Spawn + wait for `server listening on http://…` (30 s); one retry on a new port when the bind fails. */
    private fun spawnServer(password: String, retryOnBind: Boolean): String? {
        val ready = CompletableFuture<String?>()
        var exit: Pair<Int, String>? = null
        val p = host.spawn(OpenCodeWire.serveArgv(freePort()), mapOf("OPENCODE_PASSWORD" to password), cfg.cwd,
            { line ->
                host.log("NATIVE", "stdout $line")
                LISTENING.find(line)?.let { ready.complete(it.groupValues[1]) }
            },
            { code, tail ->
                if (!ready.isDone) {
                    exit = code to tail
                    ready.complete(null)
                } else {
                    closed = true
                    stream?.disconnect()
                    sink(AgentEvent.Exited(code, tail))
                }
            })
        proc = p
        val url = try {
            ready.get(READY_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            p.kill()
            throw IOException("opencode server did not start within $READY_TIMEOUT_S s")
        }
        if (url != null) return url
        val (code, tail) = exit ?: (-1 to "")
        if (!closed && retryOnBind && tail.contains("in use", ignoreCase = true)) return spawnServer(password, retryOnBind = false)
        sink(AgentEvent.Exited(code, tail)) // gate denial lands here (stderr `NativeCode:`)
        return null
    }

    /** `compact` + `GET /api/command` + `GET /api/skill` (skills as their own kind); a failed read keeps the last list. */
    private fun loadCommands(): List<SlashCommand> = runCatching {
        val loc = location(cfg.cwd)
        val skills = runCatching { api.call("GET", "/api/skill?$loc", timeoutMs = 5_000)?.optJSONArray("data") }.getOrNull()
        OpenCodeSlash.commands(api.call("GET", "/api/command?$loc", timeoutMs = 5_000)?.optJSONArray("data"), skills)
    }.getOrDefault(commands)

    /** Re-list; tell the UI when it changed. */
    private fun refreshCommands() {
        val fresh = loadCommands()
        if (fresh != commands) { commands = fresh; sink(AgentEvent.SessionState(AgentEvent.Phase.READY, commands = fresh)) }
    }

    /** Side chat: copy the parent's whole history (t3 `forkThread`, `POST /api/session/:id/fork`), then set it up like a resume. */
    private fun fork(parent: String): String? = try {
        val id = api.call("POST", "/api/session/${enc(parent)}/fork", JSONObject())!!.getJSONObject("data").getString("id")
        resume(id)
    } catch (e: Exception) {
        host.log("NATIVE", "fork failed: ${e.message}")
        sink(AgentEvent.Notice("fork_failed", "The side chat could not copy the parent conversation; it starts empty"))
        null
    }

    private fun create(): String {
        val body = JSONObject()
            .put("location", JSONObject().put("directory", cfg.cwd))
            .put("permissions", rules())
            .put("agent", agent())
        OpenCodeWire.modelRef(model, effort, models)?.let { body.put("model", it) }
        cfg.title?.trim()?.ifEmpty { null }?.let { body.put("title", it) }
        return api.call("POST", "/api/session", body, timeoutMs = BOOT_CALL_MS)!!.getJSONObject("data").getString("id")
    }

    /** Resume = GET, move if the cwd changed, re-apply rules/model/agent. Any failure: notice + fresh session. */
    private fun resume(id: String): String? = try {
        val s = "/api/session/${enc(id)}"
        val info = api.call("GET", s)!!.getJSONObject("data")
        if (info.optJSONObject("location")?.optString("directory") != cfg.cwd) api.call("POST", "$s/move", JSONObject().put("directory", cfg.cwd))
        api.call("PATCH", s, JSONObject().put("permissions", rules()))
        OpenCodeWire.modelRef(model, effort, models)?.let { api.call("POST", "$s/model", JSONObject().put("model", it)) }
        if (info.optString("agent").ifEmpty { "build" } != agent()) api.call("POST", "$s/agent", JSONObject().put("agent", agent()))
        id
    } catch (e: Exception) {
        host.log("NATIVE", "resume failed: ${e.message}")
        sink(AgentEvent.Notice("resume_failed", "Earlier context could not be restored"))
        null
    }

    private fun prompt(turn: TurnInput, steer: Boolean) {
        val sid = sessionId ?: throw IOException("no opencode session")
        val s = "/api/session/${enc(sid)}"
        val id = OpenCodeWire.promptId(sid, turn.clientId)
        val text = turn.text.trim()
        val bare = turn.attachments.isEmpty()
        if (steer) mapper.expectSteer(id) else mapper.expectTurn(id)
        if (bare && !steer && text == "/compact") {
            api.call("POST", "$s/compact", JSONObject().put("id", id))
            return
        }
        val command = if (bare && !steer) OpenCodeWire.commandOf(text) else null
        if (command != null) {
            refreshCommands() // re-list to confirm the name (t3 v2)
            if (commands.any { it.name == command.first && it.kind == SlashCommand.Kind.PROVIDER }) {
                api.call("POST", "$s/command", JSONObject().put("name", command.first).put("text", command.second))
                return
            }
        }
        // `$skill` mentions → prompt `skills` (t3 `skillsMentioned`: re-list once when one is unknown).
        val own = com.zenithblue.nativecode.agent.chat.core.SkillBlocks.split(text).second // not `$VAR`s inside attached skill md
        val mentions = OpenCodeSlash.skillMentions(own)
        if (mentions.any { m -> commands.none { it.kind == SlashCommand.Kind.SKILL && it.name == m } }) refreshCommands()
        val body = OpenCodeWire.promptBody(id, turn, steer)
        OpenCodeSlash.skillAttachments(own, commands)?.let { body.put("skills", it) }
        api.call("POST", "$s/prompt", body)
    }

    /** SSE with a 45 s silence watchdog (read timeout); reconnect, then refetch history so a turn can always end. */
    private fun eventLoop(connected: CountDownLatch) {
        var failures = 0
        var everConnected = false
        while (!closed) {
            try {
                val c = api.events(SILENCE_MS)
                stream = c
                connected.countDown()
                if (everConnected) { sink(AgentEvent.Connection(false)); onIo { recover() } }
                everConnected = true
                failures = 0
                InputStreamReader(c.inputStream, Charsets.UTF_8).use { r -> SseParser(::onFrame).pump(r) }
            } catch (e: Exception) {
                if (closed) return
                host.log("NATIVE", "event stream: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            }
            if (closed) return
            if (everConnected && failures == 0) sink(AgentEvent.Connection(true))
            if (++failures > RECONNECT_ATTEMPTS) {
                sink(AgentEvent.Connection(false))
                if (mapper.active) sink(AgentEvent.TurnTerminal(RunStatus.FAILED, Failure(FailureClass.TRANSPORT_ERROR, "Lost the connection to opencode"), reusable = false))
                sink(AgentEvent.SessionState(AgentEvent.Phase.ERROR, message = "Lost the connection to opencode"))
                return
            }
            Thread.sleep(RECONNECT_DELAY_MS * failures)
        }
    }

    private fun onFrame(f: SseFrame) {
        if (!DELTA.containsMatchIn(f.data.take(240))) host.log("NATIVE", f.data)
        mapper.handle(f.data)
    }

    private fun recover() {
        val sid = sessionId ?: return
        val page = api.call("GET", "/api/session/${enc(sid)}/message?order=desc&limit=50")
        mapper.replay(page?.optJSONArray("data"))
    }

    private fun rules() = OpenCodeWire.rules(cfg.runtimeMode, cfg.interactionMode, grants)

    private fun agent() = if (cfg.interactionMode == InteractionMode.PLAN) "plan" else "build"

    private fun failureOf(e: Exception): Failure {
        val cls = if (e is OpenCodeHttp.HttpError && e.status in 400..499) FailureClass.VALIDATION_ERROR else FailureClass.TRANSPORT_ERROR
        return Failure(cls, e.message ?: "opencode request failed")
    }

    companion object {
        val CAPS = RuntimeCaps(
            steer = true, images = true, pdf = false, userInput = true, planMode = true,
            modes = RuntimeMode.entries.toList(), effort = true, slashCommands = true, resume = true, fork = true,
        )
        private const val READY_TIMEOUT_S = 30L
        private const val BOOT_CALL_MS = 120_000
        private const val SILENCE_MS = 45_000
        private const val RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_DELAY_MS = 2_000L
        private val LISTENING = Regex("""server listening on\s+(https?://\S+)""", RegexOption.IGNORE_CASE)
        private val DELTA = Regex(""""type":"[a-z.\-]+\.delta"""")

        /** A free loopback port from the host (PRoot shares the network namespace); never the managed-service port. */
        fun freePort(): Int {
            repeat(5) {
                val p = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                if (p != OpenCodeWire.MANAGED_SERVICE_PORT) return p
            }
            throw IOException("no free loopback port")
        }
    }
}
