package com.zenithblue.nativecode.agent.chat.core

/**
 * One provider wire protocol (plan 9.2.3). Implementations: OpenCodeHttpRuntime (M1); ClaudeStreamRuntime (M2),
 * CodexAppServerRuntime (M3), AcpRuntime + AcpProfile (M4 agy, M5 grok). One instance per live thread.
 *
 * Threading: every method may be called from any thread and must not block on network/process I/O
 * (do it on the runtime's own thread). Events go to the sink from any thread; the controller serialises.
 * The runtime never touches the store or the UI.
 */
interface AgentRuntime {
    val caps: RuntimeCaps

    /** Spawn (via [RuntimeHost.spawn]), handshake, then new session ([nativeId] null) or resume. */
    fun start(cfg: SessionConfig, nativeId: String?, sink: (AgentEvent) -> Unit)

    /** [steer] true while a turn runs and caps.steer; otherwise a new turn. */
    fun send(turn: TurnInput, steer: Boolean)

    fun interrupt()

    fun respond(requestNativeId: String, answer: RequestAnswer)

    /** Model, effort, runtime mode, plan; applied live, or respawn + resume inside the runtime. */
    fun configure(change: ConfigChange)

    /** Graceful stop of the process; idempotent. */
    fun close()

    /** Force stop (hung turn): kill now. */
    fun kill()
}

data class RuntimeCaps(
    val steer: Boolean = false,
    val images: Boolean = false,
    val pdf: Boolean = false,
    val userInput: Boolean = false,
    val planMode: Boolean = false,
    val modes: List<RuntimeMode> = listOf(RuntimeMode.APPROVAL_REQUIRED),
    val effort: Boolean = false,
    val slashCommands: Boolean = false,
    val resume: Boolean = false,
    /** Can start a session as a copy of another one ([SessionConfig.forkFrom]); enables `/side` and `/btw`. */
    val fork: Boolean = false,
)

data class SessionConfig(
    val threadId: String,
    val cwd: String,                   // guest path
    val projectPath: String?,
    val model: String?,
    val effort: String?,
    val runtimeMode: RuntimeMode,
    val interactionMode: InteractionMode,
    val title: String?,
    /** Side chat: native id of the parent session to fork (full history) instead of a new session; only with `caps.fork`. */
    val forkFrom: String? = null,
)

/** [clientId] is the user_message item's native ref (opencode prompt `id`). */
data class TurnInput(val clientId: String, val text: String, val attachments: List<ChatAttachment> = emptyList())

/** Answer to a request: an option id, or question answers (question id → chosen labels / typed text), or cancel; + feedback. */
data class RequestAnswer(
    val optionId: String? = null,
    val answers: Map<String, List<String>> = emptyMap(),
    val cancel: Boolean = false,
    val feedback: String? = null,
)

data class ConfigChange(
    val model: String? = null,
    val effort: String? = null,
    /** Back to the CLI's own default effort (null [effort] means "no change"). */
    val clearEffort: Boolean = false,
    val runtimeMode: RuntimeMode? = null,
    val interactionMode: InteractionMode? = null,
)

/**
 * What the controller gives a runtime. [spawn] starts a gated CLI (`buildToolDirect` → launch_tool.sh → gate shim),
 * registers it with AgentChats (counted as *starting*), and returns the started process.
 */
interface RuntimeHost {
    val appContext: android.content.Context
    fun spawn(
        toolArgv: List<String>,
        extraEnv: Map<String, String>,
        cwd: String?,
        onLine: (String) -> Unit,
        onExit: (code: Int, stderrTail: String) -> Unit,
    ): PipeProcess

    /** Per-thread protocol log, `NATIVE|CANON <json>`; never logcat. */
    fun log(direction: String, line: String)
}
