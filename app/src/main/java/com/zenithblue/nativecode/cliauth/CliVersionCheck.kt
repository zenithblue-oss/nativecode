package com.zenithblue.nativecode.cliauth

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.zenithblue.nativecode.agent.chat.core.ChatFailures
import com.zenithblue.nativecode.onboarding.RuntimeUpdatePack
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import java.util.concurrent.ConcurrentHashMap

/**
 * `<cli> --version` against [CliToolDef.minChatVersion], for the chat error card only: it runs after a version-related
 * chat failure, never on a healthy start. The result is cached per tool until [update] succeeds (or the process ends).
 */
object CliVersionCheck {
    /** [installed] null = `--version` gave nothing usable (not installed, or the Free gate shim answered). */
    data class Result(val tool: String, val name: String, val installed: String?, val min: String?, val tooOld: Boolean, val canUpdate: Boolean)

    sealed interface State {
        data class Running(val text: String) : State
        data class Done(val ok: Boolean, val message: String) : State
    }

    private val cache = ConcurrentHashMap<String, Result>()

    /** [id] = catalog id or terminal type (a chat thread stores the terminal type). */
    fun def(id: String): CliToolDef? = CliToolCatalog.ALL.firstOrNull { it.id == id || it.terminalType == id }

    fun cached(id: String): Result? = def(id)?.let { cache[it.id] }

    /** Blocking (guest shell, a few seconds on a cold PRoot): call from a background thread. */
    fun check(ctx: Context, id: String, force: Boolean = false): Result? {
        val d = def(id) ?: return null
        if (!force) cache[d.id]?.let { return it }
        val out = try {
            val (args, env) = LinuxCommandBuilder.build(ctx, "${d.bin} --version 2>&1 | head -n 5", "flux", method = "proot")
            ShellCommandRunner.runCaptureExit(ctx, args, env).second
        } catch (_: Exception) { "" }
        val v = if (out.contains("NativeCode:")) null else ChatFailures.parseVersion(out)
        val r = Result(d.id, d.displayName, v, d.minChatVersion, ChatFailures.versionTooOld(v, d.minChatVersion), canUpdate(ctx, d))
        cache[d.id] = r
        return r
    }

    private fun canUpdate(ctx: Context, d: CliToolDef) =
        d.updateCmd != null || (d.id == "opencode" && RuntimeUpdatePack.isNeeded(ctx))

    /**
     * The existing update path for this CLI: opencode comes with the Components update pack (download, apply, progress);
     * the others run their own self-update in the guest. [onState] runs on the main thread. Success clears the cached version.
     */
    fun update(activity: Activity, id: String, onState: (State) -> Unit) {
        val d = def(id) ?: return onState(State.Done(false, "Unknown tool"))
        val app = activity.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun done(ok: Boolean, msg: String) { if (ok) cache.remove(d.id); main.post { onState(State.Done(ok, msg)) } }
        if (d.id == "opencode" && RuntimeUpdatePack.isNeeded(app)) {
            RuntimeUpdatePack.update(activity) { p ->
                when (p) {
                    is RuntimeUpdatePack.Progress.Running -> onState(State.Running(p.text))
                    is RuntimeUpdatePack.Progress.Finished -> done(p.ok, p.message)
                }
            }
            return
        }
        val cmd = d.updateCmd ?: return onState(State.Done(false, "${d.displayName} has no in-app updater. Update it from a terminal."))
        onState(State.Running("Updating ${d.displayName}…"))
        Thread {
            try {
                val (args, env) = LinuxCommandBuilder.build(app, "$cmd 2>&1 | tail -n 5", "flux", method = "proot")
                val out = ShellCommandRunner.runCaptureExit(app, args, env).second.trim()
                cache.remove(d.id)
                val now = check(app, d.id, force = true)
                val ok = now?.installed != null && !now.tooOld
                done(ok, if (ok) "${d.displayName} ${now?.installed}" else out.lineSequence().lastOrNull { it.isNotBlank() } ?: "Update failed")
            } catch (e: Exception) {
                done(false, e.message ?: "Update failed")
            }
        }.start()
    }
}
