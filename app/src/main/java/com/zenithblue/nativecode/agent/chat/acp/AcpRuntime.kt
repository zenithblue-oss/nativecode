package com.zenithblue.nativecode.agent.chat.acp

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.AgentRuntime
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Generic ACP v1 client (t3code `provider-acp` AcpSessionRuntime + adapter), one per thread; per-CLI quirks in
 * [AcpProfile]. JSON-RPC over the gated stdio pipe ([RuntimeHost.spawn]); the agent does its own file and shell
 * I/O inside PRoot (no client fs / terminal capabilities). Permission policy per runtime mode is client-side
 * (t3code clientPolicy.ts), since agy only takes the `default` session mode.
 *
 * Threads: setup and config calls run in order on one I/O thread; agent frames arrive on the pipe reader thread;
 * all mapper state is guarded by [lock]. No method blocks the caller.
 */
class AcpRuntime(private val host: RuntimeHost, private val profile: AcpProfile) : AgentRuntime {
    @Volatile override var caps = RuntimeCaps(userInput = true, modes = profile.modes, slashCommands = true, resume = true)
        private set

    private val io = Executors.newSingleThreadScheduledExecutor { Thread(it, "acp-${profile.id}").apply { isDaemon = true } }
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var proc: PipeProcess? = null
    @Volatile private var rpc: AcpRpc? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var signedOut = false
    @Volatile private var loading = false
    @Volatile private var ready = false
    private var gen = 0 // guarded by lock: frames/exits of a replaced process are ignored
    private lateinit var sink: (AgentEvent) -> Unit
    private lateinit var mapper: AcpMapper
    private lateinit var cfg: SessionConfig

    // I/O thread (read under lock elsewhere).
    private var init: JSONObject = JSONObject()
    private var configOptions: JSONArray? = null
    private var models: List<ModelInfo> = emptyList()
    private var model: String? = null
    private var effort: String? = null
    private var modeIds: List<String> = emptyList()
    @Volatile private var currentMode: String? = null
    private var restartAfterTurn = false
    private var waking = false // under lock: the open turn is a wake, not a prompt
    private var wakeTimer: java.util.concurrent.ScheduledFuture<*>? = null
    private var promptSeq = 0
    private val bgTasks = HashMap<String, String>() // grok task id → tool call id

    override fun start(cfg: SessionConfig, nativeId: String?, sink: (AgentEvent) -> Unit) {
        this.cfg = cfg
        this.sink = sink
        mapper = AcpMapper(profile, UUID.randomUUID().toString().take(8), sink).apply {
            onCommands = { c -> if (ready) sink(AgentEvent.SessionState(AgentEvent.Phase.READY, commands = c)) }
            onConfig = { u ->
                u.optJSONArray("configOptions")?.let { configOptions = it }
                modeOption()?.optString("currentValue")?.ifEmpty { null }?.let { modeChanged(it) }
            }
            onMode = { id -> id?.let { modeChanged(it) } }
        }
        io.execute { boot(nativeId) }
    }

    // ---------------- boot ----------------

    private fun boot(resumeId: String?) {
        try {
            ready = false
            sink(AgentEvent.SessionState(AgentEvent.Phase.STARTING))
            val myGen = synchronized(lock) { ++gen }
            val r = AcpRpc({ proc?.writeLine(it) == true }, { m, p -> onNotification(myGen, m, p) }, { id, m, p -> onRequest(myGen, id, m, p) }) { host.log("NATIVE", AcpWire.redact(it)) }
            rpc = r
            proc = host.spawn(profile.argv(cfg.runtimeMode), profile.env, cfg.cwd,
                { line -> if (!ProtocolNoise.isDelta(line)) host.log("NATIVE", line); r.onLine(line) },
                { code, tail -> onExit(myGen, code, tail) })
            init = await(r.request("initialize", initParams()), INIT_S)
            val agentCaps = init.optJSONObject("agentCapabilities") ?: JSONObject()
            val prompt = agentCaps.optJSONObject("promptCapabilities") ?: JSONObject()
            val initMeta = init.optJSONObject("_meta")
            synchronized(lock) { mapper.seedCommands(AcpWire.commands(initMeta?.optJSONArray("availableCommands"), profile.dropCommands)) }
            val authIds = AcpWire.objects(init.optJSONArray("authMethods")).map { it.optString("id") }
            var account: String? = null
            if (profile.eagerAuth != null && profile.eagerAuth in authIds) {
                // grok `cached_token`: silent with a cached login and returns the account; a failure falls through to -32000.
                account = runCatching { await(r.request("authenticate", JSONObject().put("methodId", profile.eagerAuth)), SETUP_S) }
                    .getOrNull()?.optJSONObject("_meta")?.optString("subscription_tier")?.ifEmpty { null }
            }

            val session = try {
                openSession(resumeId, agentCaps)
            } catch (e: AcpError) {
                if (!e.authRequired) throw e
                val method = profile.authMethod(authIds)?.takeIf { it != profile.eagerAuth || account == null }
                if (method == null) return signedOutState()
                await(r.request("authenticate", JSONObject().put("methodId", method)), SETUP_S)
                try { openSession(resumeId, agentCaps) } catch (e2: AcpError) { if (e2.authRequired) return signedOutState() else throw e2 }
            }
            configOptions = session.optJSONArray("configOptions") ?: configOptions
            readModels(session)
            val modeOpt = modeOption()
            modeIds = (AcpWire.objects(session.optJSONObject("modes")?.optJSONArray("availableModes")).map { it.optString("id") } +
                (modeOpt?.let { AcpWire.selectChoices(it).map { c -> c.first } }.orEmpty())).distinct()
            currentMode = session.optJSONObject("modes")?.optString("currentModeId")?.ifEmpty { null } ?: modeOpt?.optString("currentValue")?.ifEmpty { null }
            account = account ?: session.optJSONObject("_meta")?.optJSONObject("antigravity")?.optJSONObject("tier")?.optString("name")?.ifEmpty { null }
            caps = caps.copy(
                images = prompt.optBoolean("image") || profile.xai, // t3code sends grok images even though it advertises none
                pdf = profile.embedDocs && prompt.optBoolean("embeddedContext"),
                planMode = planModeId() != null,
                effort = models.any { it.variants.isNotEmpty() },
            )
            applySelection(cfg.model, cfg.effort)
            runCatching { applyMode() }.onFailure { host.log("NATIVE", "mode: ${it.message}") }
            sink(AgentEvent.NativeId(sessionId!!))
            ready = true
            sink(AgentEvent.SessionState(AgentEvent.Phase.READY, caps, models, model, synchronized(lock) { mapper.commands }, account = account, model = model))
        } catch (e: Exception) {
            if (closed) return
            host.log("NATIVE", "boot failed: ${e.message}")
            sink(AgentEvent.SessionState(AgentEvent.Phase.ERROR, message = "${profile.name}: ${e.message ?: "did not start"}"))
            proc?.close()
        }
    }

    private fun initParams(): JSONObject {
        val p = JSONObject()
            .put("protocolVersion", 1)
            .put("clientCapabilities", JSONObject().put("fs", JSONObject().put("readTextFile", false).put("writeTextFile", false)).put("terminal", false))
            .put("clientInfo", JSONObject().put("name", "nativecode").put("title", "NativeCode").put("version", VERSION))
        profile.initMeta?.let { p.put("_meta", it) }
        return p
    }

    /** New, or resume (`session/resume` when the profile prefers it and it is advertised, else `session/load`). */
    private fun openSession(resumeId: String?, agentCaps: JSONObject): JSONObject {
        val r = rpc ?: throw IOException("agent not running")
        val nc = com.zenithblue.nativecode.agent.chat.core.ChatMcp.endpoint(cfg.threadId, profile.id)
        val base = JSONObject().put("cwd", cfg.cwd).put("mcpServers", AcpWire.mcpServers(agentCaps, nc))
        if (resumeId != null) {
            val canResume = agentCaps.optJSONObject("sessionCapabilities")?.has("resume") == true
            val canLoad = agentCaps.optBoolean("loadSession")
            val method = when {
                profile.preferResume && canResume -> "session/resume"
                canLoad -> "session/load"
                canResume -> "session/resume"
                else -> null
            }
            if (method != null) try {
                loading = method == "session/load"
                val res = await(r.request(method, JSONObject(base.toString()).put("sessionId", resumeId)), LOAD_S)
                sessionId = resumeId
                return res
            } catch (e: AcpError) {
                if (e.authRequired) throw e
                host.log("NATIVE", "resume failed: ${e.message}")
                sink(AgentEvent.Notice("resume_failed", "Earlier context could not be restored"))
            } finally {
                loading = false
            }
        }
        val res = await(r.request("session/new", base), SETUP_S)
        sessionId = res.getString("sessionId")
        return res
    }

    /** Signed out: READY with no models = the sign-in banner (item 10 login page); a send fails until a respawn. */
    private fun signedOutState() {
        signedOut = true
        host.log("NATIVE", "auth required")
        sink(AgentEvent.SessionState(AgentEvent.Phase.READY, caps, emptyList(), null, emptyList(), message = "sign in"))
    }

    private fun readModels(session: JSONObject?) {
        val (fromSession, current) = AcpWire.models(session)
        val state = init.optJSONObject("_meta")?.optJSONObject("modelState")
        val (xai, xaiCurrent) = AcpWire.xaiModels(state)
        model = current ?: xaiCurrent ?: model
        val effortOpt = AcpWire.effortOption(configOptions)
        val effortIds = effortOpt?.let { AcpWire.selectChoices(it).map { c -> c.first } }.orEmpty()
        effort = effortOpt?.optString("currentValue")?.ifEmpty { null } ?: AcpWire.xaiDefaultEffort(state, model) ?: effort
        models = (fromSession.ifEmpty { xai }).map { m ->
            val x = xai.firstOrNull { it.id == m.id }
            m.copy(
                variants = x?.variants?.ifEmpty { null } ?: if (m.id == model) AcpWire.sortEfforts(effortIds) else m.variants,
                contextLimit = x?.contextLimit ?: m.contextLimit,
            )
        }
        synchronized(lock) { mapper.contextSize = models.firstOrNull { it.id == model }?.contextLimit }
    }

    /** agy reports the thought_level option for the selected model only (AntigravityProvider.ts:217). */
    private fun refreshEfforts() {
        val opt = AcpWire.effortOption(configOptions)
        if (opt != null) {
            val ids = AcpWire.sortEfforts(AcpWire.selectChoices(opt).map { it.first })
            models = models.map { if (it.id == model) it.copy(variants = ids) else it }
            effort = opt.optString("currentValue").ifEmpty { null } ?: effort
        }
        caps = caps.copy(effort = models.any { it.variants.isNotEmpty() })
        synchronized(lock) { mapper.contextSize = models.firstOrNull { it.id == model }?.contextLimit }
    }

    /**
     * Model, then effort (t3code order: effort choices are per model). `configOptions` (category model /
     * thought_level) when the agent has them (agy 1.5.8, grok 1.0.77), else `session/set_model`.
     */
    private fun applySelection(wantModel: String?, wantEffort: String?) {
        val r = rpc ?: return
        val sid = sessionId ?: return
        val target = wantModel?.takeIf { m -> models.any { it.id == m } } ?: model
        if (target != null && target != model) {
            val opt = AcpWire.modelOption(JSONObject().put("configOptions", configOptions ?: JSONArray()))
            val res = if (opt != null) await(r.request("session/set_config_option", JSONObject().put("sessionId", sid).put("configId", opt.optString("id")).put("value", target)), SETUP_S)
            else await(r.request("session/set_model", JSONObject().put("sessionId", sid).put("modelId", target)), SETUP_S)
            res.optJSONArray("configOptions")?.let { configOptions = it }
            model = target
            refreshEfforts()
        }
        val effortOpt = AcpWire.effortOption(configOptions) ?: return
        val levels2 = models.firstOrNull { it.id == model }?.variants.orEmpty()
        val e = wantEffort?.takeIf { it in levels2 } ?: return
        if (e == effortOpt.optString("currentValue")) return
        val res = await(r.request("session/set_config_option", JSONObject().put("sessionId", sid).put("configId", effortOpt.optString("id")).put("value", e)), SETUP_S)
        res.optJSONArray("configOptions")?.let { configOptions = it }
        effort = e
    }

    /** The agent switched mode itself (agy `/plan`, plan exit): the plan chip follows. */
    private fun modeChanged(id: String) {
        if (id == currentMode) return
        currentMode = id
        val im = interactionOf(id) ?: return
        if (ready && im != cfg.interactionMode) {
            cfg = cfg.copy(interactionMode = im)
            sink(AgentEvent.SessionState(AgentEvent.Phase.READY, interactionMode = im))
        }
    }

    private fun modeOption(): JSONObject? = AcpWire.objects(configOptions).firstOrNull { it.optString("category") == "mode" }

    private fun planModeId(): String? = profile.planMode?.takeIf { it in modeIds }
        ?: modeIds.firstOrNull { it.substringAfterLast('#').let { s -> s == "plan" || s == "architect" } }

    private fun interactionOf(modeId: String?): InteractionMode? = when {
        modeId == null || modeIds.isEmpty() -> null
        modeId == planModeId() -> InteractionMode.PLAN
        else -> InteractionMode.DEFAULT
    }

    /**
     * The native session mode for the thread: plan when the plan chip is on, else the runtime mode's native id
     * (agy: Auto = its own automatic review). `mode` config option when present, else `session/set_mode`.
     */
    private fun applyMode() {
        val r = rpc ?: return
        val sid = sessionId ?: return
        val target = (if (cfg.interactionMode == InteractionMode.PLAN) planModeId() else profile.nativeModes[cfg.runtimeMode])
            ?.takeIf { it in modeIds } ?: return
        if (target == currentMode) return
        val opt = modeOption()
        val res = if (opt != null) await(r.request("session/set_config_option", JSONObject().put("sessionId", sid).put("configId", opt.optString("id")).put("value", target)), SETUP_S)
        else await(r.request("session/set_mode", JSONObject().put("sessionId", sid).put("modeId", target)), SETUP_S)
        res.optJSONArray("configOptions")?.let { configOptions = it }
        currentMode = target
    }

    // ---------------- turns ----------------

    override fun send(turn: TurnInput, steer: Boolean) = onIo {
        val r = rpc
        val sid = sessionId
        if (signedOut || r == null || sid == null) {
            synchronized(lock) {
                mapper.beginTurn()
                mapper.endTurn(RunStatus.FAILED, Failure(if (signedOut) FailureClass.PERMISSION_ERROR else FailureClass.TRANSPORT_ERROR,
                    if (signedOut) profile.signIn else "${profile.name} is not running"), reusable = false)
            }
            return@onIo
        }
        val text = rewriteSkill(turn.text)
        val params = JSONObject().put("sessionId", sid).put("prompt", AcpWire.promptBlocks(turn.copy(text = text), caps.images, profile.embedDocs && caps.pdf))
        if (profile.xai) "nc-xai-prompt-${++promptSeq}".let { params.put("_meta", JSONObject().put("promptId", it).put("requestId", it)) }
        val myGen = synchronized(lock) {
            if (waking) { waking = false; wakeTimer?.cancel(false); mapper.endTurn(RunStatus.COMPLETED) } // the user's prompt takes over
            mapper.beginTurn(); gen
        }
        r.request("session/prompt", params).whenComplete { res, err -> synchronized(lock) { if (myGen == gen) finishPrompt(res, err) } }
    }

    /** grok expands only a leading `/name`: the composer's `$skill …` becomes `/skill …` for known commands (t3code adapter.ts:333). */
    private fun rewriteSkill(text: String): String {
        if (!profile.xai) return text
        val m = Regex("""^\$([\w.-]+)(\s[\s\S]*)?$""").find(text.trimStart()) ?: return text
        val known = synchronized(lock) { mapper.commands }.any { it.name == m.groupValues[1] }
        return if (known) "/" + m.groupValues[1] + m.groupValues[2] else text
    }

    /** Under [lock]. The `session/prompt` reply (or its error) ends the turn, unless grok's completion already did. */
    private fun finishPrompt(res: JSONObject?, err: Throwable?) {
        val e = (err as? java.util.concurrent.CompletionException)?.cause ?: err
        if (!mapper.active) {
            // grok's turn_completed / prompt_complete already ended it
        } else if (e != null) {
            if (mapper.interrupting || closed) { mapper.endTurn(RunStatus.INTERRUPTED); return }
            val f = AcpWire.failureOf(e, profile.signIn)
            if (e is AcpError && e.authRequired) { signedOut = true; sink(AgentEvent.SessionState(AgentEvent.Phase.READY, models = emptyList(), message = "sign in")) }
            mapper.endTurn(RunStatus.FAILED, f, reusable = !(e is AcpError && e.authRequired) && e is AcpError)
        } else {
            mapper.addUsage(AcpWire.promptUsage(res, mapper.usage))
            val (status, failure) = AcpWire.stop(res?.optString("stopReason"), mapper.interrupting, profile.refusalIsQuota)
            val restart = profile.xai && mapper.interrupting // t3code grok: Stop restarts the runtime, next turn reloads
            mapper.endTurn(status, failure, reusable = !restart)
        }
        if (restartAfterTurn) { restartAfterTurn = false; onIo { respawn() } }
    }

    override fun interrupt() {
        val r = rpc ?: return
        val sid = sessionId ?: return
        val myGen = synchronized(lock) {
            if (!mapper.active) return
            if (waking) { // no prompt to answer `cancelled`: settle the wake here
                waking = false; wakeTimer?.cancel(false)
                mapper.interrupting = true
                mapper.endTurn(RunStatus.INTERRUPTED)
                r.notify("session/cancel", JSONObject().put("sessionId", sid).apply { profile.cancelMeta?.let { put("_meta", it) } })
                return
            }
            mapper.interrupting = true
            mapper.pendingIds().forEach { id -> mapper.settle(id, RequestStatus.CANCELLED)?.let { p -> r.reply(p.rpcId, cancelled(p.method)) } }
            gen
        }
        val p = JSONObject().put("sessionId", sid)
        profile.cancelMeta?.let { p.put("_meta", it) }
        r.notify("session/cancel", p)
        // t3code wait-for-prompt: the agent must answer the prompt `cancelled`; if it never does, end the turn and
        // let the controller replace the process (reusable = false).
        io.schedule({
            synchronized(lock) { if (myGen == gen && mapper.active && mapper.interrupting) mapper.endTurn(RunStatus.INTERRUPTED, reusable = false) }
        }, CANCEL_WAIT_S, TimeUnit.SECONDS)
    }

    override fun respond(requestNativeId: String, answer: RequestAnswer) {
        val r = rpc ?: return
        synchronized(lock) {
            val p = mapper.pending(requestNativeId) ?: return
            val result = when {
                answer.cancel || answer.optionId == AcpWire.CANCEL -> null
                p.method == "x.ai/ask_user_question" -> xaiAnswer(p, answer)
                p.optionByLabel.isNotEmpty() -> answer.answers["q"]?.firstOrNull()?.let { p.optionByLabel[it] ?: it.takeIf { id -> id in p.optionByLabel.values } }?.let(::selected)
                else -> answer.optionId?.let(::selected)
            }
            mapper.settle(requestNativeId, if (result == null) RequestStatus.CANCELLED else RequestStatus.RESOLVED)
            r.reply(p.rpcId, result ?: cancelled(p.method))
        }
    }

    /** Answers keyed by question text; a typed answer = label `Other` + `annotations.notes` (xaiAcpExtension.ts:1147). */
    private fun xaiAnswer(p: AcpMapper.Pending, a: RequestAnswer): JSONObject {
        val answers = JSONObject()
        val notes = JSONObject()
        val questions = p.event.detail.questions
        for (q in questions) {
            val chosen = a.answers[q.id].orEmpty().filter { it.isNotBlank() }
            val labels = q.options.map { it.label }.toSet()
            val known = chosen.filter { it in labels }
            val typed = chosen.filter { it !in labels }
            answers.put(q.text, JSONArray(known + if (typed.isNotEmpty()) listOf("Other") else emptyList()))
            if (typed.isNotEmpty()) notes.put(q.text, JSONObject().put("notes", typed.joinToString("\n")))
        }
        val out = JSONObject().put("outcome", "accepted").put("answers", answers)
        if (notes.length() > 0) out.put("annotations", notes)
        return out
    }

    private fun selected(optionId: String) = JSONObject().put("outcome", JSONObject().put("outcome", "selected").put("optionId", optionId))

    private fun cancelled(method: String) =
        if (method == "x.ai/ask_user_question") JSONObject().put("outcome", "cancelled")
        else JSONObject().put("outcome", JSONObject().put("outcome", "cancelled"))

    override fun configure(change: ConfigChange) = onIo {
        // ponytail: ACP has no unset; Default drops our pick, so the CLI default applies from the next session start
        if (change.clearEffort) effort = null
        if (change.model != null || change.effort != null) {
            applySelection(change.model ?: model, change.effort ?: effort)
            sink(AgentEvent.SessionState(AgentEvent.Phase.READY, caps, models, model = model))
        }
        val im = change.interactionMode?.takeIf { it != cfg.interactionMode }
        val rm = change.runtimeMode?.takeIf { it != cfg.runtimeMode }
        cfg = cfg.copy(interactionMode = im ?: cfg.interactionMode, runtimeMode = rm ?: cfg.runtimeMode)
        if (rm != null && profile.modeInArgv && sessionId != null && !signedOut) {
            synchronized(lock) { if (mapper.active) restartAfterTurn = true else onIo { respawn() } }
        } else if (im != null || rm != null) applyMode()
    }

    /** grok: the permission posture is argv, so a mode change = new process + `session/load` of the same session. */
    private fun respawn() {
        val old = proc
        synchronized(lock) { gen++ }
        rpc?.failAll(IOException("respawn"))
        old?.close()
        boot(sessionId)
    }

    override fun close() {
        closed = true
        rpc?.failAll(IOException("closed"))
        proc?.close()
        io.shutdown()
    }

    override fun kill() {
        closed = true
        rpc?.failAll(IOException("killed"))
        proc?.kill()
        io.shutdownNow()
    }

    // ---------------- agent → client ----------------

    private fun onExit(myGen: Int, code: Int, tail: String) {
        synchronized(lock) {
            if (myGen != gen) return
            rpc?.failAll(IOException("agent exited"))
            if (mapper.active && !closed) mapper.endTurn(RunStatus.FAILED,
                Failure(FailureClass.TRANSPORT_ERROR, "${profile.name} exited (code $code)", details = tail.takeLast(2_000)), reusable = false)
        }
        if (!closed) sink(AgentEvent.Exited(code, tail))
    }

    private fun onNotification(myGen: Int, method: String, params: JSONObject) {
        val m = method.removePrefix("_")
        synchronized(lock) {
            if (myGen != gen) return
            when {
                m == "session/update" -> {
                    if (loading && params.optJSONObject("update")?.optString("sessionUpdate") !in KEEP_WHILE_LOADING) return
                    wake(params)
                    if (!mapper.update(params)) sink(AgentEvent.Unknown(params.toString().take(400)))
                }
                loading -> Unit // session/load replays history (also grok's turn_completed): already in the store
                profile.xai && (m == "x.ai/session_notification" || m == "x.ai/session/update") -> xaiUpdate(params)
                profile.xai && m == "x.ai/session/prompt_complete" -> xaiComplete(params.optString("promptId"), params.optString("stopReason"), params.opt("agentResult"))
                profile.xai && m == "x.ai/task_backgrounded" -> params.optJSONObject("update")?.let { u -> bgTasks[u.optString("task_id")] = u.optString("tool_call_id") }
                profile.xai && m == "x.ai/task_completed" -> params.optJSONObject("update")?.optJSONObject("task_snapshot")?.let { s ->
                    bgTasks.remove(s.optString("task_id"))?.let { mapper.finishTool(it, s.opt("exit_code") as? Int, s.optString("output").ifEmpty { null }) }
                }
                else -> Unit // ignored extensions (x.ai/session/setup, mcp, queue…): protocol log only
            }
        }
    }

    /** Under [lock]. grok `turn_completed` carries usage and may end the turn before the prompt reply. */
    private fun xaiUpdate(params: JSONObject) {
        val u = params.optJSONObject("update") ?: return
        when (u.optString("sessionUpdate")) {
            "turn_completed" -> {
                u.optJSONObject("usage")?.let { mapper.addUsage(AcpWire.usageOf(it, mapper.usage)) }
                xaiComplete(u.optString("prompt_id"), u.optString("stop_reason"), null)
            }
            "auto_compact_completed" -> mapper.compacted(u.optLong("tokens_after").takeIf { it > 0 })
            else -> if (u.optString("sessionUpdate") in setOf("tool_call", "tool_call_update", "agent_message_chunk", "agent_thought_chunk", "plan")) { wake(params); mapper.update(params) }
        }
    }

    /**
     * Under [lock]. Agent output with no prompt open = a wake: background subagents (agy `invoke_subagent` swarm,
     * grok `subagent-completed-*`) finished and the agent carries on by itself (t3code acp adapter wakeBuffer →
     * provider continuation). It becomes its own run, settled after [WAKE_QUIET_MS] of silence with no tool open.
     */
    private fun wake(params: JSONObject) {
        val u = params.optJSONObject("update") ?: return
        if (u.optJSONObject("_meta")?.optBoolean("isReplay") == true || closed) return
        if (!mapper.active) {
            if (u.optString("sessionUpdate") !in WAKE_KINDS) return
            mapper.beginTurn()
            waking = true
        }
        if (!waking) return
        val myGen = gen
        wakeTimer?.cancel(false)
        wakeTimer = io.schedule({ synchronized(lock) { settleWake(myGen) } }, WAKE_QUIET_MS, TimeUnit.MILLISECONDS)
    }

    private fun settleWake(myGen: Int) {
        if (myGen != gen || !waking || !mapper.active) return
        if (mapper.openTools > 0 || mapper.pendingIds().isNotEmpty()) {
            wakeTimer = io.schedule({ synchronized(lock) { settleWake(myGen) } }, WAKE_QUIET_MS, TimeUnit.MILLISECONDS); return
        }
        waking = false
        mapper.endTurn(RunStatus.COMPLETED)
    }

    /** First of `prompt_complete` / prompt reply wins; background-task wakes are not the user's turn. */
    private fun xaiComplete(promptId: String, stop: String, agentResult: Any?) {
        if (promptId.isEmpty() || promptId.startsWith("task-completed-") || promptId.startsWith("subagent-completed-")) return
        if (!mapper.active) return
        val (status, failure) = when (stop) {
            "rate_limit" -> RunStatus.FAILED to Failure(FailureClass.USAGE_LIMIT, "Grok usage limit reached. Try again later.")
            "error" -> RunStatus.FAILED to Failure(FailureClass.PROVIDER_ERROR, (agentResult as? String)?.ifBlank { null } ?: "Grok stopped this turn because of an error.")
            else -> AcpWire.stop(stop.ifEmpty { "end_turn" }, mapper.interrupting, false)
        }
        mapper.endTurn(status, failure, reusable = !mapper.interrupting)
    }

    private fun onRequest(myGen: Int, id: Any, method: String, params0: JSONObject): JSONObject? {
        val m = method.removePrefix("_")
        // grok may wrap extension params as {method, params}
        val params = if (params0.has("params") && params0.has("method")) params0.optJSONObject("params") ?: params0 else params0
        synchronized(lock) {
            if (myGen != gen) throw AcpError(AcpError.INTERNAL, "stale session")
            return when {
                m == "session/request_permission" -> permission(id, params)
                profile.xai && m == "x.ai/ask_user_question" -> { mapper.xaiQuestion(id, params); null }
                profile.xai && m == "x.ai/exit_plan_mode" -> {
                    mapper.proposedPlan(params.optString("planContent").ifBlank { NO_PLAN })
                    JSONObject().put("outcome", "abandoned").put("feedback", PLAN_CAPTURED)
                }
                else -> throw AcpError(AcpError.METHOD_NOT_FOUND, "Method not found: $method")
            }
        }
    }

    /**
     * Under [lock]. t3code clientPolicy.ts: read/search/think always allowed; Auto-accept edits allows edit/delete/move;
     * Auto and Full allow everything (grok Auto: the CLI's classifier already decided, so ask); questions always ask.
     */
    private fun permission(id: Any, p: JSONObject): JSONObject? {
        val call = p.optJSONObject("toolCall") ?: JSONObject()
        val callId = call.optString("toolCallId")
        val question = profile.interactionQuestions && callId.startsWith("interaction_")
        mapper.adoptInput(call)
        if (!question) {
            val kind = call.optString("kind").ifEmpty { mapper.toolKind(callId) ?: "other" }
            if (allow(kind, cfg.runtimeMode)) {
                val opts = AcpWire.objects(p.optJSONArray("options"))
                val pick = opts.firstOrNull { it.optString("kind") == "allow_once" } ?: opts.firstOrNull { it.optString("kind") == "allow_always" }
                return pick?.let { selected(it.optString("optionId")) } ?: cancelled("permission")
            }
        }
        mapper.permission(id, p)
        return null
    }

    private fun allow(kind: String, mode: RuntimeMode): Boolean = when {
        kind in setOf("read", "search", "think") -> true
        mode == RuntimeMode.FULL_ACCESS -> true
        mode == RuntimeMode.AUTO -> !profile.autoAsksAll
        mode == RuntimeMode.AUTO_ACCEPT_EDITS -> kind in setOf("edit", "delete", "move")
        else -> false
    }

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
                        sink(AgentEvent.Notice("error", "${profile.name}: ${e.message}"))
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
        throw IOException("${profile.name} did not answer within $seconds s")
    }

    companion object {
        const val VERSION = "1.8.6"
        private const val INIT_S = 60L
        private const val SETUP_S = 90L
        private const val LOAD_S = 90L
        private const val CANCEL_WAIT_S = 5L
        private const val WAKE_QUIET_MS = 5_000L
        private val WAKE_KINDS = setOf("agent_message_chunk", "agent_thought_chunk", "tool_call")
        private val KEEP_WHILE_LOADING = setOf("available_commands_update", "current_mode_update", "config_option_update")
        const val NO_PLAN = "# No plan written yet\n\n(The agent exited plan mode without writing a plan.)"
        const val PLAN_CAPTURED = "The client captured your proposed plan. Stop here and wait for the user's feedback or implementation request in a later turn."
    }
}

/** Streaming frames stay out of the protocol log (same heuristic as ProtocolLog). */
internal object ProtocolNoise {
    fun isDelta(line: String) = line.contains("\"agent_message_chunk\"") || line.contains("\"agent_thought_chunk\"")
}
