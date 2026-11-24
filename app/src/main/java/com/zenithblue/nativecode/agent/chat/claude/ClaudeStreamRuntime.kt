package com.zenithblue.nativecode.agent.chat.claude

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.AgentRuntime
import com.zenithblue.nativecode.agent.chat.core.ChatMcp
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.PipeProcess
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.RuntimeCaps
import com.zenithblue.nativecode.agent.chat.core.RuntimeHost
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.SessionConfig
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.agent.chat.core.TurnInput
import com.zenithblue.nativecode.agent.chat.core.Usage
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * claude-code over stdio NDJSON (stream-json in/out + control channel), as t3code `ClaudeAdapterV2` drives it
 * through the Agent SDK, but without Node: the same frames written directly (plan 9.2.4, R2 §1, spike §1).
 *
 * One `claude -p --input-format stream-json …` per thread through [RuntimeHost.spawn] (launch_tool.sh + gate, so
 * the Pro gate applies). Handshake = `initialize` control request (models, effort levels, slash commands,
 * account). New threads pass `--session-id <uuid>` (the resume key is known up front); later spawns `--resume`,
 * falling back to a fresh session with a notice when the transcript is gone. Model, effort and permission mode
 * change live (`set_model`, `apply_flag_settings`, `set_permission_mode`); steer = `priority:"now"`.
 *
 * Threads: process writes and handshakes run in order on one I/O thread; stdout is parsed on the pipe thread.
 */
class ClaudeStreamRuntime(private val host: RuntimeHost) : AgentRuntime {
    override val caps = CAPS

    private val io = Executors.newSingleThreadExecutor { Thread(it, "claude-io").apply { isDaemon = true } }
    @Volatile private var closed = false
    @Volatile private var proc: PipeProcess? = null
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val seq = AtomicInteger()
    private lateinit var sink: (AgentEvent) -> Unit
    private lateinit var mapper: ClaudeMapper

    // I/O thread only.
    private lateinit var cfg: SessionConfig
    private var sessionId: String? = null
    private var models: List<ModelInfo> = emptyList()
    private var commands: List<SlashCommand> = emptyList()
    private var account: String? = null

    override fun start(cfg: SessionConfig, nativeId: String?, sink: (AgentEvent) -> Unit) {
        this.cfg = cfg
        this.sink = sink
        mapper = ClaudeMapper(sink) { write(it) }
        io.execute {
            try {
                sink(AgentEvent.SessionState(AgentEvent.Phase.STARTING))
                boot(nativeId)
            } catch (e: Exception) {
                if (closed) return@execute
                host.log("NATIVE", "boot failed: ${e.message}")
                sink(AgentEvent.SessionState(AgentEvent.Phase.ERROR, message = e.message ?: "Claude Code did not start"))
                proc?.close()
            }
        }
    }

    override fun send(turn: TurnInput, steer: Boolean) = onIo {
        if (mapper.signedOut) respawn() // signed in again since the failed turn: a fresh process reads the new credentials
        val msg = ClaudeWire.userMessage(turn, steer) { p -> runCatching { File(p).readBytes() }.getOrNull() }
        if (steer) mapper.steer() else mapper.beginTurn()
        if (!write(msg)) {
            if (steer) sink(AgentEvent.Notice("steer_failed", "Steer not delivered: Claude Code is not running"))
            else sink(AgentEvent.TurnTerminal(RunStatus.FAILED, Failure(FailureClass.TRANSPORT_ERROR, "Claude Code is not running"), reusable = false))
        }
    }

    override fun interrupt() {
        mapper.markInterrupting()
        Thread({ control(JSONObject().put("subtype", "interrupt")) }, "claude-interrupt").apply { isDaemon = true; start() }
    }

    override fun respond(requestNativeId: String, answer: RequestAnswer) = onIo { mapper.respond(requestNativeId, answer) }

    override fun configure(change: ConfigChange) = onIo {
        val old = cfg
        cfg = cfg.copy(
            model = change.model ?: cfg.model, effort = if (change.clearEffort) null else change.effort ?: cfg.effort,
            runtimeMode = change.runtimeMode ?: cfg.runtimeMode, interactionMode = change.interactionMode ?: cfg.interactionMode,
        )
        if (proc == null) return@onIo
        if (change.model != null && change.model != old.model) call("set_model", JSONObject().put("model", change.model))
        if (change.effort != null && change.effort != old.effort)
            call("apply_flag_settings", JSONObject().put("settings", JSONObject().put("effortLevel", change.effort)))
        if (change.clearEffort && old.effort != null)
            call("apply_flag_settings", JSONObject().put("settings", JSONObject().put("effortLevel", JSONObject.NULL)))
        val mode = ClaudeWire.permissionMode(cfg.runtimeMode, cfg.interactionMode)
        if (mode != ClaudeWire.permissionMode(old.runtimeMode, old.interactionMode)) call("set_permission_mode", JSONObject().put("mode", mode))
    }

    override fun close() {
        closed = true
        proc?.close()
        io.shutdown()
        if (::cfg.isInitialized) ChatMcp.release(cfg.threadId)
    }

    override fun kill() {
        closed = true
        proc?.kill()
        io.shutdownNow()
        if (::cfg.isInitialized) ChatMcp.release(cfg.threadId)
    }

    // ---------------- process ----------------

    private fun boot(nativeId: String?) {
        val (sid, init) = await(launch(nativeId, cfg.forkFrom.takeIf { nativeId == null })) ?: return // exited during the handshake: the controller reports it (gate deny included)
        sessionId = sid
        sink(AgentEvent.NativeId(sid))
        val version = init.optString("claude_code_version")
        if (!ClaudeWire.versionOk(version)) throw IOException("Chat not available for Claude Code $version (needs ${ClaudeWire.MIN_VERSION}+)")
        models = ClaudeModels.merge(ClaudeWire.parseModels(init.optJSONArray("models")))
        commands = ClaudeWire.parseCommands(init.optJSONArray("commands"))
        account = ClaudeWire.account(init.optJSONObject("account"))
        val modes = ClaudeWire.modesOf(init.optString("current_permission_mode"))
        mapper.ready = true
        sink(AgentEvent.SessionState(AgentEvent.Phase.READY, caps, models, ClaudeWire.DEFAULT_MODEL, commands, account,
            model = cfg.model ?: ClaudeWire.DEFAULT_MODEL, runtimeMode = modes?.first, interactionMode = modes?.second))
        contextUsage()
    }

    /**
     * Spawn + `initialize`. Null = the process exited first. A `--resume` whose transcript is gone is retried
     * as a fresh session synchronously inside the exit callback, so the controller only ever sees the new process.
     */
    private fun launch(resume: String?, forkFrom: String? = null): CompletableFuture<Pair<String, JSONObject>?> {
        val sid = resume ?: UUID.randomUUID().toString()
        val done = CompletableFuture<Pair<String, JSONObject>?>()
        val mcp = ChatMcp.endpoint(cfg.threadId, TOOL) // M7 NC tools server; null = none
        val argv = ClaudeWire.argv(cfg.model, cfg.effort, ClaudeWire.permissionMode(cfg.runtimeMode, cfg.interactionMode), resume ?: forkFrom, sid.takeIf { resume == null }, mcp)
        val p = host.spawn(argv, ClaudeWire.mcpEnv(mcp), cfg.cwd, ::onLine) { code, tail ->
            if (done.isDone) return@spawn
            if (resume != null && !closed && RESUME_MISSING.containsMatchIn(tail)) {
                host.log("NATIVE", "resume $resume failed: ${tail.takeLast(300)}")
                sink(AgentEvent.Notice("resume_failed", "Earlier context could not be restored"))
                runCatching { launch(null) }.fold({ it.whenComplete { v, e -> if (e != null) done.completeExceptionally(e) else done.complete(v) } }, { done.completeExceptionally(it) })
            } else {
                host.log("NATIVE", "exited $code during handshake: ${tail.takeLast(500)}")
                done.complete(null)
            }
        }
        proc = p
        mapper.processCost = 0.0
        val init = request(JSONObject().put("subtype", "initialize"))
        init.whenComplete { r, e ->
            when {
                e != null -> done.completeExceptionally(e)
                r.optString("subtype") == "error" -> done.completeExceptionally(IOException(r.optString("error", "initialize failed")))
                else -> done.complete(sid to (r.optJSONObject("response") ?: JSONObject()))
            }
        }
        return done
    }

    /** The handshake result; the CLI answers in ~1 s on the phone (spike), a cold PRoot start is slower. */
    private fun await(f: CompletableFuture<Pair<String, JSONObject>?>): Pair<String, JSONObject>? = try {
        f.get(HANDSHAKE_S, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        proc?.kill()
        throw IOException("Claude Code did not answer within $HANDSHAKE_S s")
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    /** After a signed-out turn: new process on the same session, then the old one closes (its exit is not ours any more). */
    private fun respawn() {
        val old = proc
        mapper.ready = false
        mapper.signedOut = false
        val r = try { await(launch(sessionId)) } catch (e: Exception) { null }
        old?.close()
        if (r == null) { sink(AgentEvent.SessionState(AgentEvent.Phase.ERROR, message = "Claude Code did not restart")); return }
        sessionId = r.first
        sink(AgentEvent.NativeId(r.first))
        models = ClaudeModels.merge(ClaudeWire.parseModels(r.second.optJSONArray("models"))).ifEmpty { models }
        account = ClaudeWire.account(r.second.optJSONObject("account")) ?: account
        mapper.ready = true
        sink(AgentEvent.SessionState(AgentEvent.Phase.READY, caps, models, ClaudeWire.DEFAULT_MODEL, commands, account))
    }

    private fun onLine(line: String) {
        host.log("NATIVE", line)
        if (!line.startsWith("{")) return // PRoot / launcher noise
        val f = runCatching { JSONObject(line) }.getOrNull() ?: return
        if (f.optString("type") == "control_response") {
            val r = f.optJSONObject("response") ?: return
            pending.remove(r.optString("request_id"))?.complete(r)
            return
        }
        try {
            mapper.handle(f)
        } catch (e: Exception) {
            host.log("NATIVE", "map error: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun write(f: JSONObject): Boolean {
        val line = f.toString()
        host.log("CLIENT", if (line.length > 4000) line.take(4000) + "…" else line)
        return proc?.writeLine(line) == true
    }

    private fun request(req: JSONObject): CompletableFuture<JSONObject> {
        val id = "nc_${seq.incrementAndGet()}"
        val f = CompletableFuture<JSONObject>()
        pending[id] = f
        if (!write(ClaudeWire.control(id, req))) { pending.remove(id); f.completeExceptionally(IOException("Claude Code is not running")) }
        return f
    }

    /** Fire a control request; an error reply becomes a notice. */
    private fun control(req: JSONObject) {
        request(req).whenComplete { r, e ->
            val err = e?.message ?: r?.takeIf { it.optString("subtype") == "error" }?.optString("error")
            if (err != null && !closed) sink(AgentEvent.Notice("error", "Claude Code: ${req.optString("subtype")}: $err"))
        }
    }

    private fun call(subtype: String, body: JSONObject) = control(body.put("subtype", subtype))

    /** Context ring before the first turn (`get_context_usage`: totalTokens / maxTokens). */
    private fun contextUsage() {
        request(JSONObject().put("subtype", "get_context_usage")).thenAccept { r ->
            val u = r.optJSONObject("response") ?: return@thenAccept
            val max = u.optLong("maxTokens").takeIf { it > 0 } ?: return@thenAccept
            mapper.contextSize = max
            sink(AgentEvent.UsageUpdate(Usage(contextUsed = u.optLong("totalTokens"), contextSize = max)))
        }
    }

    private fun onIo(block: () -> Unit) {
        if (closed) return
        runCatching {
            io.execute {
                try {
                    block()
                } catch (e: Exception) {
                    if (!closed) {
                        host.log("NATIVE", "error: ${e.message}")
                        sink(AgentEvent.Notice("error", "Claude Code: ${e.message}"))
                    }
                }
            }
        }
    }

    companion object {
        val CAPS = RuntimeCaps(
            steer = true, images = true, pdf = true, userInput = true, planMode = true,
            modes = RuntimeMode.entries.toList(), effort = true, slashCommands = true, resume = true, fork = true,
        )
        private const val HANDSHAKE_S = 60L
        private const val TOOL = "claude" // CliToolCatalog id

        private val RESUME_MISSING = Regex("No conversation found|not found.*session|session.*not found", RegexOption.IGNORE_CASE)
    }
}
