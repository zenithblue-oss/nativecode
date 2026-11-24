package com.zenithblue.nativecode.cliauth

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.zenithblue.nativecode.agent.chat.core.ChatFailures
import com.zenithblue.nativecode.onboarding.RuntimeUpdatePack
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import com.zenithblue.nativecode.terminal.ShellQuoting
import java.util.concurrent.ConcurrentHashMap

/**
 * `<cli> --version` against [CliToolDef.min] (chat / sign-in). Read before a chat session or sign-in starts ([CliGate]) and
 * after a version-related chat failure. Cached per tool until [update] succeeds (or the process ends), so only the first
 * use per app session pays for the guest shell.
 */
object CliVersionCheck {
    /** [installed] null = `--version` gave nothing usable (not installed, or the Free gate shim answered). */
    data class Result(val tool: String, val name: String, val installed: String?, val canUpdate: Boolean) {
        fun min(need: CliNeed): String? = CliToolCatalog.byId(tool)?.min(need)
        fun tooOld(need: CliNeed): Boolean = ChatFailures.versionTooOld(installed, min(need))
    }

    sealed interface State {
        data class Running(val text: String) : State
        data class Done(val ok: Boolean, val message: String) : State
    }

    private val cache = ConcurrentHashMap<String, Result>()
    private val ANSI = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

    /** [id] = catalog id or terminal type (a chat thread stores the terminal type). */
    fun def(id: String): CliToolDef? = CliToolCatalog.ALL.firstOrNull { it.id == id || it.terminalType == id }

    fun cached(id: String): Result? = def(id)?.let { cache[it.id] }

    /** Blocking (guest shell, a few seconds on a cold PRoot): call from a background thread. */
    fun check(ctx: Context, id: String, force: Boolean = false): Result? {
        val d = def(id) ?: return null
        if (!force) cache[d.id]?.let { return it }
        val out = try {
            val (args, env) = LinuxCommandBuilder.build(ctx, login("${d.bin} --version 2>&1 | head -n 5"), "flux", method = "proot")
            ShellCommandRunner.runCaptureExit(ctx, args, env).second
        } catch (_: Exception) { "" }
        val v = if (out.contains("NativeCode:")) null else ChatFailures.parseVersion(out)
        val r = Result(d.id, d.displayName, v, canUpdate(ctx, d))
        cache[d.id] = r
        return r
    }
    /** [check], but a cached "too old for [need]" is re-read once: the user may have updated it in a terminal since. Blocking. */
    fun probe(ctx: Context, id: String, need: CliNeed): Result? {
        val r = check(ctx, id)
        return if (r?.tooOld(need) == true) check(ctx, id, force = true) else r
    }

    /** A login shell re-applies the CLI PATH (~/.local/bin, ~/.opencode/bin): a plain `sh -c` does not find the CLIs. */
    private fun login(cmd: String) = "bash -lc " + ShellQuoting.singleQuote(cmd)

    private fun canUpdate(ctx: Context, d: CliToolDef) =
        d.updateCmd != null || (d.id == "opencode" && RuntimeUpdatePack.isNeeded(ctx))

    /**
     * The existing update path for this CLI: opencode comes with the Components update pack (download, apply, progress);
     * the others run their own self-update in the guest. [onState] runs on the main thread. Success clears the cached version.
     */
    fun update(activity: Activity, id: String, onState: (State) -> Unit) = exec(activity, id, null, onState)

    /** Installs (or reinstalls) from the vendor's installer: same progress and re-probe as [update]. */
    fun install(activity: Activity, id: String, onState: (State) -> Unit) {
        val c = def(id)?.installCmd ?: return onState(State.Done(false, "No installer for $id"))
        exec(activity, id, c, onState)
    }

    /** [cmd] null = the update path above (opencode: Components pack first); else that guest command. */
    private fun exec(activity: Activity, id: String, cmd: String?, onState: (State) -> Unit) {
        val d = def(id) ?: return onState(State.Done(false, "Unknown tool"))
        val app = activity.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun done(ok: Boolean, msg: String) { if (ok) cache.remove(d.id); main.post { onState(State.Done(ok, msg)) } }
        if (cmd == null && d.id == "opencode" && RuntimeUpdatePack.isNeeded(app)) {
            RuntimeUpdatePack.update(activity) { p ->
                when (p) {
                    is RuntimeUpdatePack.Progress.Running -> onState(State.Running(p.text))
                    is RuntimeUpdatePack.Progress.Finished -> done(p.ok, p.message)
                }
            }
            return
        }
        val sh = cmd ?: d.updateCmd ?: return onState(State.Done(false, "${d.displayName} has no in-app updater. Update it from a terminal."))
        onState(State.Running("${d.displayName}…"))
        Thread {
            try {
                val (args, env) = LinuxCommandBuilder.build(app, login("set -o pipefail; $sh 2>&1"), "flux", method = "proot")
                val proc = ShellCommandRunner.startPiped(app, args, env, mergeStderr = true)
                var last = ""
                var shown = 0L
                proc.inputStream.bufferedReader().forEachLine { raw ->
                    val l = raw.replace(ANSI, "").trim()
                    if (l.isEmpty()) return@forEachLine
                    last = l
                    val now = System.currentTimeMillis()
                    if (now - shown > 400) { shown = now; main.post { onState(State.Running(l.take(90))) } }
                }
                val code = proc.waitFor()
                cache.remove(d.id)
                val now = check(app, d.id, force = true)
                val ok = code == 0 && now?.installed != null && CliNeed.values().none { now.tooOld(it) }
                done(ok, if (ok) "${d.displayName} ${now?.installed}" else last.ifEmpty { "Update failed" })
            } catch (e: Exception) {
                done(false, e.message ?: "Update failed")
            }
        }.start()
    }
}
