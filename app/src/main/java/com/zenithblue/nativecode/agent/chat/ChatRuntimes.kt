package com.zenithblue.nativecode.agent.chat

import android.content.Context
import com.zenithblue.nativecode.agent.chat.core.AgentRuntime
import com.zenithblue.nativecode.agent.chat.core.PipeProcess
import com.zenithblue.nativecode.agent.chat.core.RuntimeHost
import com.zenithblue.nativecode.agent.chat.runtime.OpenCodeHttpRuntime
import com.zenithblue.nativecode.cliauth.ChatProtocol
import com.zenithblue.nativecode.cliauth.CliToolCatalog
import com.zenithblue.nativecode.entitlements.GuestGate
import java.io.File
import java.io.FileOutputStream

/** Runtime per catalog `chat` protocol (plan 9.2.2). Null = terminal only. */
object ChatRuntimes {
    fun protocolOf(tool: String): ChatProtocol? = CliToolCatalog.ALL.firstOrNull { it.terminalType == tool }?.chat

    fun create(tool: String, host: RuntimeHost): AgentRuntime? = when (protocolOf(tool)) {
        ChatProtocol.OPENCODE_HTTP -> OpenCodeHttpRuntime(host)
        ChatProtocol.CLAUDE_STREAM -> com.zenithblue.nativecode.agent.chat.claude.ClaudeStreamRuntime(host)
        ChatProtocol.CODEX_APP_SERVER -> com.zenithblue.nativecode.agent.chat.codex.CodexAppServerRuntime(host)
        ChatProtocol.ACP -> com.zenithblue.nativecode.agent.chat.acp.AcpProfile.of(CliToolCatalog.ALL.firstOrNull { it.terminalType == tool }?.acpProfile)
            ?.let { com.zenithblue.nativecode.agent.chat.acp.AcpRuntime(host, it) }
        else -> null // CLAUDE_STREAM (M2), CODEX_APP_SERVER (M3), ACP (M4/M5)
    }

    /** Caps fixed in code (ACP: null, reported per session). Beats a [ModelCache] entry saved by an older app version. */
    fun staticCaps(tool: String) = when (protocolOf(tool)) {
        ChatProtocol.OPENCODE_HTTP -> OpenCodeHttpRuntime.CAPS
        ChatProtocol.CLAUDE_STREAM -> com.zenithblue.nativecode.agent.chat.claude.ClaudeStreamRuntime.CAPS
        ChatProtocol.CODEX_APP_SERVER -> com.zenithblue.nativecode.agent.chat.codex.CodexAppServerRuntime.CAPS
        else -> null
    }
}

/**
 * What a thread's runtime gets: gated spawn registered in [AgentChats] (counted as starting until the
 * controller sees READY), and the per-thread protocol log.
 */
internal class ThreadHost(
    ctx: Context,
    private val threadId: String,
    private val tool: String,
    private val projectPath: String?,
    private val defaultCwd: String,
    private val onSpawned: (AgentChats.Entry) -> Unit,
    private val onProcessExit: (PipeProcess, Int, String) -> Unit,
) : RuntimeHost {
    override val appContext: Context = ctx.applicationContext
    private val log = ProtocolLog.of(appContext, threadId)

    override fun spawn(
        toolArgv: List<String>,
        extraEnv: Map<String, String>,
        cwd: String?,
        onLine: (String) -> Unit,
        onExit: (code: Int, stderrTail: String) -> Unit,
    ): PipeProcess {
        GuestGate.syncAsync(appContext)
        lateinit var entry: AgentChats.Entry
        lateinit var proc: PipeProcess
        proc = PipeProcess.forTool(appContext, toolArgv, cwd ?: defaultCwd, onLine, { code, tail ->
            AgentChats.remove(entry)
            runCatching { onExit(code, tail) }
            onProcessExit(proc, code, tail)
        }, extraEnv - "OPENCODE_SERVER_PASSWORD")
        entry = AgentChats.Entry(tool, projectPath, proc)
        AgentChats.add(entry)
        onSpawned(entry)
        try {
            proc.start()
        } catch (e: Exception) {
            AgentChats.remove(entry)
            throw e
        }
        return proc
    }

    override fun log(direction: String, line: String) = log.append(direction, line)
}

/**
 * `filesDir/agent/logs/<thread>.log`: `NATIVE|CANON <json>` lines, delta frames dropped, 2 x 1 MiB rotation.
 * Never logcat (prompts, code, tokens). Callers must not pass auth headers or passwords.
 */
internal class ProtocolLog(private val file: File, private val cap: Long = 1L shl 20) {
    private var out: FileOutputStream? = null

    @Synchronized
    fun append(direction: String, line: String) {
        if (isDelta(line)) return
        runCatching {
            val o = out ?: run { file.parentFile?.mkdirs(); FileOutputStream(file, true) }.also { out = it }
            o.write("$direction ${redact(line)}\n".toByteArray(Charsets.UTF_8))
            if (file.length() > cap) {
                o.close(); out = null
                file.renameTo(File(file.path + ".1"))
            }
        }
    }

    @Synchronized
    fun close() { runCatching { out?.close() }; out = null }

    companion object {
        // ponytail: substring heuristic for streaming frames (text_delta, session.text.delta, outputDelta); exact per-runtime filter if logs get noisy
        fun isDelta(line: String) = line.contains("delta\"", ignoreCase = true)

        /** Bearer tokens (the M7 tools server's per-thread token rides in MCP configs) never reach the log. */
        private val BEARER = Regex("""(?i)bearer\s+[^"\s,}\]]+""")
        fun redact(line: String) = if (line.contains("earer", ignoreCase = true)) BEARER.replace(line, "Bearer <redacted>") else line

        private val open = java.util.concurrent.ConcurrentHashMap<String, ProtocolLog>()

        /** One writer per thread (runtime NATIVE frames and controller CANON events share it). */
        fun of(ctx: Context, threadId: String): ProtocolLog = open.computeIfAbsent(threadId) { ProtocolLog(files(ctx, threadId)[0]) }

        /** Current + rotated file (opened from an error row's Details). */
        fun files(ctx: Context, threadId: String): List<File> =
            File(ctx.filesDir, "agent/logs").let { listOf(File(it, "$threadId.log"), File(it, "$threadId.log.1")) }

        fun delete(ctx: Context, threadId: String) {
            open.remove(threadId)?.close()
            files(ctx, threadId).forEach { it.delete() }
        }
    }
}
