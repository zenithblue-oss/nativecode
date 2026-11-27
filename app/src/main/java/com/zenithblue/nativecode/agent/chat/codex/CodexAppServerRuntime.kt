package com.zenithblue.nativecode.agent.chat.codex

import com.zenithblue.nativecode.agent.chat.acp.AcpError
import com.zenithblue.nativecode.agent.chat.acp.AcpRpc
import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.AgentRuntime
import com.zenithblue.nativecode.agent.chat.core.ChatMcp
import com.zenithblue.nativecode.agent.chat.core.McpEndpoint
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * codex over `codex app-server` (JSON-RPC lines without `"jsonrpc"`), as t3code `CodexAdapterV2` drives it; one
 * process per thread through [RuntimeHost.spawn] (launch_tool.sh + gate shim: the Pro gate applies).
 *
 * Boot: `initialize` → `initialized` → `account/read` (signed out = sign-in banner, no 18 s of 401 retries) →
 * `model/list` → `skills/list` → `thread/start`, or `thread/resume {excludeTurns}` after a process death.
 * Model, effort, approval policy and plan mode ride on every `turn/start`, so [configure] never respawns.
 * `/compact`, `/goal`, `/feedback` are RPCs (R6), never model text.
 *
 * Threads: setup and RPC sends run in order on one I/O thread; server frames arrive on the pipe reader thread;
 * mapper state is guarded by [lock]. No method blocks the caller.
 */
class CodexAppServerRuntime(private val host: RuntimeHost) : AgentRuntime {
    override val caps = CAPS

    private val io = Executors.newSingleThreadScheduledExecutor { Thread(it, "codex-io").apply { isDaemon = true } }
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var proc: PipeProcess? = null
    @Volatile private var rpc: AcpRpc? = null
    @Volatile private var threadId: String? = null
    @Volatile private var signedOut = false
    private var gen = 0 // guarded by lock: frames/exits of a replaced process are ignored
    private lateinit var sink: (AgentEvent) -> Unit
    private lateinit var mapper: CodexMapper
    @Volatile private lateinit var cfg: SessionConfig

    // I/O thread.
    private var modelRes = JSONObject()
    private var models: List<ModelInfo> = emptyList()
    private var defaultModel: String? = null
    private var commands: List<SlashCommand> = CodexWire.COMMANDS
    private var skills: Set<String> = emptySet()
    private var account: String? = null
    private var interruptPending = false // guarded by lock: Stop before turn/start answered
    private var mcp: McpEndpoint? = null // this process's M7 endpoint: env and thread config must agree

    override fun start(cfg: SessionConfig, nativeId: String?, sink: (AgentEvent) -> Unit) {
        this.cfg = cfg
        this.sink = sink
        mapper = CodexMapper(UUID.randomUUID().toString().take(8), sink).apply { onAuthError = { markSignedOut() } }
        io.execute { boot(nativeId) }
    }

    // ---------------- boot ----------------

    private fun boot(resumeId: String?) {
        try {
            sink(AgentEvent.SessionState(AgentEvent.Phase.STARTING))
            val myGen = synchronized(lock) { ++gen }
            val r = AcpRpc({ proc?.writeLine(it) == true }, { m, p -> onNotification(myGen, m, p) }, { id, m, p -> onRequest(myGen, id, m, p) },
                tagged = false, log = { host.log("NATIVE", it.take(LOG_MAX)) })
            rpc = r
            mcp = ChatMcp.endpoint(cfg.threadId, "codex") // M7 NC tools server; null = none
            proc = host.spawn(CodexWire.ARGV, CodexWire.mcpEnv(mcp), cfg.cwd,
                { line -> if (!isDelta(line)) host.log("NATIVE", line.take(LOG_MAX)); r.onLine(line) },
                { code, tail -> onExit(myGen, code, tail) })
            await(r.request("initialize", CodexWire.initParams(VERSION)), INIT_S)
            r.notify("initialized", null)
            if (!readAccount()) {
                signedOutState()
                return
            }
            readModels()
            readSkills()
            openThread(resumeId)
            sink(AgentEvent.NativeId(threadId!!))
            sink(AgentEvent.SessionState(AgentEvent.Phase.READY, caps, models, defaultModel, commands, account))
            r.request("account/rateLimits/read", null).thenAccept { res ->
                synchronized(lock) { if (myGen == gen) mapper.rateLimits(res.optJSONObject("rateLimitsByLimitId")?.optJSONObject("codex") ?: res.optJSONObject("rateLimits")) }
            }
        } catch (e: Exception) {
            if (closed) return
            host.log("NATIVE", "boot failed: ${e.message}")
            sink(AgentEvent.SessionState(AgentEvent.Phase.ERROR, message = "${CodexWire.NAME}: ${e.message ?: "did not start"}"))
            proc?.close()
        }
    }

    /** True = signed in. */
    private fun readAccount(): Boolean {
        val (out, label) = CodexWire.account(await(rpc!!.request("account/read", JSONObject().put("refreshToken", false)), SETUP_S))
        signedOut = out
        account = label
        return !out
    }

    private fun readModels() {
        val all = ArrayList<Pair<ModelInfo, Boolean>>()
        val data = JSONArray()
        var cursor: String? = null
        do {
            val res = await(rpc!!.request("model/list", JSONObject().putOpt("cursor", cursor)), SETUP_S)
            all += CodexWire.models(res)
            res.optJSONArray("data")?.let { a -> for (i in 0 until a.length()) data.put(a.get(i)) }
            cursor = res.optString("nextCursor").takeIf { !res.isNull("nextCursor") }?.ifEmpty { null }
        } while (cursor != null && all.size < 500)
        modelRes = JSONObject().put("data", data)
        models = all.map { it.first }
        defaultModel = all.firstOrNull { it.second }?.first?.id ?: models.firstOrNull()?.id
    }

    private fun readSkills() {
        val list = runCatching { CodexWire.skills(await(rpc!!.request("skills/list", JSONObject().put("cwds", JSONArray().put(cfg.cwd))), SKILLS_S), cfg.cwd) }
            .onFailure { host.log("NATIVE", "skills: ${it.message}") }.getOrDefault(emptyList())
        skills = list.map { it.name }.toSet()
        commands = CodexWire.COMMANDS + list
    }

    /** `thread/resume` (history stays in the store: `excludeTurns`), else a fresh `thread/start` with a notice. */
    private fun openThread(resumeId: String?) {
        val r = rpc!!
        val base = CodexWire.threadParams(cfg, pickModel(), mcp)
        if (resumeId != null) try {
            await(r.request("thread/resume", JSONObject(base.toString()).put("threadId", resumeId).put("excludeTurns", true)), SETUP_S)
            threadId = resumeId
            return
        } catch (e: AcpError) {
            host.log("NATIVE", "resume failed: ${e.message}")
            sink(AgentEvent.Notice("resume_failed", "Earlier context could not be restored"))
        }
        val res = await(r.request("thread/start", base), SETUP_S)
        threadId = res.getJSONObject("thread").getString("id")
    }

    /** The thread's pick when codex lists it; else codex's own default (null). */
    private fun pickModel(): String? = cfg.model?.takeIf { m -> models.any { it.id == m } }

    private fun pickEffort(model: String?): String? {
        val levels = models.firstOrNull { it.id == (model ?: defaultModel) }?.variants.orEmpty()
        return cfg.effort?.takeIf { it in levels }
    }

    /** Signed out: READY with no models = the sign-in banner (login page). Sends re-check the account first. */
    private fun signedOutState() {
        host.log("NATIVE", "auth required")
        sink(AgentEvent.SessionState(AgentEvent.Phase.READY, caps, emptyList(), null, commands, message = "sign in"))
    }

    private fun markSignedOut() {
        signedOut = true
        sink(AgentEvent.SessionState(AgentEvent.Phase.READY, models = emptyList(), message = "sign in"))
    }

    // ---------------- turns ----------------

    override fun send(turn: TurnInput, steer: Boolean) = onIo {
        if (signedOut && rpc != null && runCatching { readAccount() }.getOrDefault(false)) {
            // Signed in since (login page): a fresh process reads the new credentials, then resumes.
            respawn()
        }
        val r = rpc
        val tid = threadId
        if (signedOut || r == null || tid == null) {
            synchronized(lock) {
                mapper.beginTurn()
                mapper.endTurn(RunStatus.FAILED, Failure(if (signedOut) FailureClass.PERMISSION_ERROR else FailureClass.TRANSPORT_ERROR,
                    if (signedOut) CodexWire.SIGN_IN else "${CodexWire.NAME} is not running"), reusable = false)
            }
            return@onIo
        }
        val text = CodexWire.skillText(turn.text, skills)
        val bare = turn.attachments.isEmpty()
        when {
            steer && bare && text.trim().startsWith("/") && (text.trim() == "/compact" || CodexWire.goal(text) != null || text.trim().startsWith("/feedback")) ->
                sink(AgentEvent.Notice("steer_failed", "Commands run after the current turn"))
            steer -> steerTurn(r, tid, turn, text)
            bare && text.trim() == "/compact" -> rpcTurn(r.request("thread/compact/start", JSONObject().put("threadId", tid)))
            bare && CodexWire.goal(text) != null -> goal(r, tid, CodexWire.goal(text)!!)
            bare && Regex("""^/feedback(\s[\s\S]*)?$""").matches(text.trim()) -> synchronized(lock) {
                mapper.beginTurn()
                mapper.feedbackCard(text.trim().removePrefix("/feedback").trim())
            }
            else -> startTurn(r, tid, CodexWire.input(turn, text))
        }
    }

    private fun startTurn(r: AcpRpc, tid: String, input: JSONArray, after: ((String) -> Unit)? = null) {
        // collaborationMode.settings.model is required by app-server ("missing field `model`" on a thread with no pick).
        val model = pickModel() ?: defaultModel
        val params = CodexWire.turnParams(tid, input, cfg, model, pickEffort(model))
        val myGen = synchronized(lock) { mapper.beginTurn(); gen }
        r.request("turn/start", params).whenComplete { res, err ->
            synchronized(lock) {
                if (myGen != gen) return@whenComplete
                if (err != null) { failTurn(err); return@whenComplete }
                val id = res?.optJSONObject("turn")?.optString("id")?.ifEmpty { null } ?: return@whenComplete
                mapper.onTurnId(id)
                if (interruptPending) { interruptPending = false; sendInterrupt(id) }
            }
            res?.optJSONObject("turn")?.optString("id")?.let { id -> after?.let { a -> onIo { a(id) } } }
        }
    }

    /** A request that starts a turn codex announces itself (`thread/compact/start`): the run waits for `turn/started`. */
    private fun rpcTurn(f: CompletableFuture<JSONObject>) {
        val myGen = synchronized(lock) { mapper.beginTurn(); gen }
        f.whenComplete { _, err -> if (err != null) synchronized(lock) { if (myGen == gen) failTurn(err) } }
    }

    /** Under [lock]. */
    private fun failTurn(err: Throwable) {
        val e = (err as? java.util.concurrent.CompletionException)?.cause ?: err
        val f = CodexWire.failureOf(e)
        if (f.cls == FailureClass.PERMISSION_ERROR) markSignedOut()
        mapper.endTurn(RunStatus.FAILED, f, reusable = e is AcpError)
    }

    private fun steerTurn(r: AcpRpc, tid: String, turn: TurnInput, text: String) {
        val expected = synchronized(lock) { mapper.turnId.takeIf { mapper.active } }
        if (expected == null) { startTurn(r, tid, CodexWire.input(turn, text)); return }
        r.request("turn/steer", JSONObject().put("threadId", tid).put("expectedTurnId", expected).put("input", CodexWire.input(turn, text)))
            .whenComplete { _, err ->
                if (err == null) return@whenComplete
                val e = (err as? java.util.concurrent.CompletionException)?.cause ?: err
                host.log("NATIVE", "steer failed: ${e.message}")
                // The turn ended meanwhile: this message becomes the next turn instead of being lost.
                if (!synchronized(lock) { mapper.active }) onIo { startTurn(r, tid, CodexWire.input(turn, text)) }
                else sink(AgentEvent.Notice("steer_failed", "Steer not delivered: ${e.message}"))
            }
    }

    /** `/goal` (t3code runGoalCommand): show / clear / pause answer in place; set and resume start the goal turn. */
    private fun goal(r: AcpRpc, tid: String, cmd: Pair<String, String?>) {
        fun note(t: String) = synchronized(lock) { mapper.note(t) }
        fun done() = synchronized(lock) { mapper.endTurn(RunStatus.COMPLETED) }
        synchronized(lock) { mapper.beginTurn() }
        try {
            val current = await(r.request("thread/goal/get", JSONObject().put("threadId", tid)), SETUP_S).optJSONObject("goal")
            when (cmd.first) {
                "show" -> { note(CodexWire.describeGoal(current)); done() }
                "clear" -> {
                    await(r.request("thread/goal/clear", JSONObject().put("threadId", tid)), SETUP_S)
                    note(if (current == null) "No goal is set." else "Goal cleared."); done()
                }
                "pause" -> {
                    if (current == null) note("No goal is set.")
                    else note(CodexWire.describeGoal(await(r.request("thread/goal/set", JSONObject().put("threadId", tid).put("status", "paused")), SETUP_S).optJSONObject("goal")))
                    done()
                }
                else -> {
                    if (cmd.first == "resume" && current == null) { note("No goal is set."); done(); return }
                    val objective = cmd.second
                    if (objective != null) {
                        if (current != null) await(r.request("thread/goal/clear", JSONObject().put("threadId", tid)), SETUP_S)
                        // Paused until our turn starts, so codex cannot start one first (t3code).
                        await(r.request("thread/goal/set", JSONObject().put("threadId", tid).put("objective", objective).put("status", "paused")), SETUP_S)
                    }
                    val input = if (objective == null) JSONArray()
                    else JSONArray().put(JSONObject().put("type", "text").put("text", "This thread's goal is already set. Do not call create_goal. Start working on it now: $objective"))
                    startTurn(r, tid, input) { _ -> runCatching { await(r.request("thread/goal/set", JSONObject().put("threadId", tid).put("status", "active")), SETUP_S) } }
                }
            }
        } catch (e: Exception) {
            synchronized(lock) { mapper.endTurn(RunStatus.FAILED, Failure(FailureClass.PROVIDER_ERROR, "Goal: ${e.message}")) }
        }
    }

    override fun interrupt() {
        val myGen = synchronized(lock) {
            if (!mapper.active) return
            mapper.interrupting = true
            mapper.pendingIds().forEach { id -> mapper.settle(id, RequestStatus.CANCELLED)?.let { p -> if (p.method != CodexMapper.FEEDBACK) rpc?.reply(p.rpcId, mapper.cancelReply(p)) } }
            val tid = mapper.turnId
            if (tid == null) interruptPending = true else sendInterrupt(tid)
            gen
        }
        // turn/completed(interrupted) normally follows within ~100 ms; if codex never answers, end the run and let the
        // controller replace the process.
        io.schedule({
            synchronized(lock) { if (myGen == gen && mapper.active && mapper.interrupting) mapper.endTurn(RunStatus.INTERRUPTED, reusable = false) }
        }, CANCEL_WAIT_S, TimeUnit.SECONDS)
    }

    /** Under [lock]. "no active turn" (-32600) = it already ended: benign. */
    private fun sendInterrupt(turnId: String) {
        val tid = threadId ?: return
        if (mapper.turnId == null && !mapper.active) return
        rpc?.request("turn/interrupt", JSONObject().put("threadId", tid).put("turnId", turnId))
    }

    override fun respond(requestNativeId: String, answer: RequestAnswer) {
        val r = rpc ?: return
        synchronized(lock) {
            val p = mapper.pending(requestNativeId) ?: return
            if (p.method == CodexMapper.FEEDBACK) {
                mapper.settle(requestNativeId, if (!answer.cancel && answer.optionId == "upload") RequestStatus.RESOLVED else RequestStatus.CANCELLED)
                val reason = p.params.optString("reason").ifBlank { null }
                if (answer.cancel || answer.optionId != "upload") { mapper.note("Feedback not sent."); mapper.endTurn(RunStatus.COMPLETED); return }
                onIo { uploadFeedback(r, reason) }
                return
            }
            val result = mapper.answer(p, answer)
            mapper.settle(requestNativeId, if (result == null) RequestStatus.CANCELLED else RequestStatus.RESOLVED)
            r.reply(p.rpcId, result ?: mapper.cancelReply(p))
        }
    }

    private fun uploadFeedback(r: AcpRpc, reason: String?) {
        val res = runCatching {
            await(r.request("feedback/upload", JSONObject().put("classification", "bug").put("includeLogs", true).putOpt("reason", reason).putOpt("threadId", threadId)), SETUP_S)
        }
        synchronized(lock) {
            res.onSuccess { o -> mapper.note("Feedback sent" + (o.optString("threadId").ifEmpty { null }?.let { " · id $it" } ?: "") + ".") }
                .onFailure { mapper.note("Feedback upload failed: ${it.message}") }
            mapper.endTurn(RunStatus.COMPLETED)
        }
    }

    /** Model, effort, approvals and plan mode go with the next `turn/start`: nothing to send now. */
    override fun configure(change: ConfigChange) {
        cfg = cfg.copy(
            model = change.model ?: cfg.model, effort = if (change.clearEffort) null else change.effort ?: cfg.effort,
            runtimeMode = change.runtimeMode ?: cfg.runtimeMode, interactionMode = change.interactionMode ?: cfg.interactionMode,
        )
    }

    private fun respawn() {
        val old = proc
        synchronized(lock) { gen++ }
        rpc?.failAll(IOException("respawn"))
        old?.close()
        boot(threadId)
    }

    override fun close() {
        closed = true
        rpc?.failAll(IOException("closed"))
        proc?.close()
        io.shutdown()
        if (::cfg.isInitialized) ChatMcp.release(cfg.threadId)
    }

    override fun kill() {
        closed = true
        rpc?.failAll(IOException("killed"))
        proc?.kill()
        io.shutdownNow()
        if (::cfg.isInitialized) ChatMcp.release(cfg.threadId)
    }

    // ---------------- server → client ----------------

    private fun onExit(myGen: Int, code: Int, tail: String) {
        synchronized(lock) {
            if (myGen != gen) return
            rpc?.failAll(IOException("codex exited"))
            if (mapper.active && !closed) mapper.endTurn(RunStatus.FAILED,
                Failure(FailureClass.TRANSPORT_ERROR, "${CodexWire.NAME} exited (code $code)", details = tail.takeLast(2_000)), reusable = false)
        }
        if (!closed) sink(AgentEvent.Exited(code, tail))
    }

    private fun onNotification(myGen: Int, method: String, params: JSONObject) {
        synchronized(lock) {
            if (myGen != gen) return
            mapper.mainThreadId = threadId
            if (!mapper.notification(method, params)) sink(AgentEvent.Unknown("$method ${params.toString().take(300)}"))
        }
    }

    /**
     * Under [lock]. Every server request gets an answer (or the turn hangs). Client policy on top of codex's own
     * (t3code sessionAllowance + Auto-accept edits): "Approve for session" covers later requests of that kind;
     * Auto-accept edits answers patches; Full access answers anything codex still asks.
     */
    private fun onRequest(myGen: Int, id: Any, method: String, p: JSONObject): JSONObject? {
        synchronized(lock) {
            if (myGen != gen) throw AcpError(AcpError.INTERNAL, "stale session")
            val mode = cfg.runtimeMode
            return when (method) {
                "item/commandExecution/requestApproval", "execCommandApproval" -> {
                    val legacy = method == "execCommandApproval"
                    when {
                        mode == RuntimeMode.FULL_ACCESS -> decision(if (legacy) "approved" else "accept")
                        "command" in mapper.allowance -> decision(if (legacy) "approved_for_session" else "acceptForSession")
                        else -> { mapper.commandApproval(id, method, p); null }
                    }
                }
                "item/fileChange/requestApproval", "applyPatchApproval" -> {
                    val legacy = method == "applyPatchApproval"
                    when {
                        mode == RuntimeMode.FULL_ACCESS || mode == RuntimeMode.AUTO_ACCEPT_EDITS || mode == RuntimeMode.AUTO -> decision(if (legacy) "approved" else "accept")
                        "file-change" in mapper.allowance -> decision(if (legacy) "approved_for_session" else "acceptForSession")
                        else -> { mapper.fileApproval(id, method, p); null }
                    }
                }
                "item/permissions/requestApproval" -> { mapper.permissionsApproval(id, p); null }
                "item/tool/requestUserInput" -> { mapper.userInput(id, p); null }
                "mcpServer/elicitation/request" -> { mapper.elicitation(id, p); null }
                else -> throw AcpError(AcpError.METHOD_NOT_FOUND, "Method not found: $method") // item/tool/call, auth refresh, attestation
            }
        }
    }

    private fun decision(d: String) = JSONObject().put("decision", d)

    // ---------------- plumbing ----------------

    private fun onIo(block: () -> Unit) {
        if (closed) return
        runCatching {
            io.execute {
                try {
                    block()
                } catch (e: Exception) {
                    if (!closed) {
                        host.log("NATIVE", "error: ${e.message}")
                        synchronized(lock) { if (mapper.active) mapper.endTurn(RunStatus.FAILED, CodexWire.failureOf(e), reusable = e is AcpError) }
                    }
                }
            }
        }
    }

    private fun await(f: CompletableFuture<JSONObject>, seconds: Long): JSONObject = try {
        f.get(seconds, TimeUnit.SECONDS)
    } catch (e: java.util.concurrent.ExecutionException) {
        throw e.cause ?: e
    } catch (e: java.util.concurrent.TimeoutException) {
        throw IOException("${CodexWire.NAME} did not answer within $seconds s")
    }

    companion object {
        const val VERSION = "1.8.4"
        private const val INIT_S = 60L
        private const val SETUP_S = 60L
        private const val SKILLS_S = 10L
        private const val CANCEL_WAIT_S = 15L
        private const val LOG_MAX = 8_000

        val CAPS = RuntimeCaps(
            steer = true, images = true, pdf = false, userInput = true, planMode = true,
            modes = RuntimeMode.entries.toList(), effort = true, slashCommands = true, resume = true,
        )

        /** Streaming frames stay out of the protocol log. */
        fun isDelta(line: String) = line.contains("/delta\"") || line.contains("Delta\"")
    }
}
