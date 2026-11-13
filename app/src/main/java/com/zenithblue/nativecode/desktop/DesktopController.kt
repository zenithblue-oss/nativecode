package com.zenithblue.nativecode.desktop

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log
import android.widget.Toast
import com.zenithblue.nativecode.BackgroundService
import com.zenithblue.nativecode.BuildConfig
import com.zenithblue.nativecode.NativeCodeFgs
import com.zenithblue.nativecode.entitlements.FeatureGate
import com.zenithblue.nativecode.entitlements.GateResult
import com.zenithblue.nativecode.entitlements.XfceBudget
import com.zenithblue.nativecode.telemetry.Telemetry
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import com.zenithblue.nativecode.terminal.ShellJob
import com.zenithblue.nativecode.terminal.TermuxHostPaths
import java.io.File
import java.util.concurrent.Executor

/**
 * Single source of truth for the Phosh desktop: start / stop / view / state and the free-minute
 * budget. Every entry point (Settings card, top-bar + workspace pills, notification actions)
 * calls only this class, so none can bypass the gate.
 *
 * Run state lives in the companion: the desktop outlives an Activity recreation (font scale,
 * theme), so a new controller picks the running session up instead of showing "off".
 */
class DesktopController(
    private val activity: Activity,
    private val executor: Executor,
    private val mainHandler: Handler,
    private val host: Host,
) {
    /** What the controller needs from its screen. Paint calls must tolerate unbuilt views. */
    interface Host {
        fun isPro(): Boolean
        /** Deny → upsell. True when the action may proceed. */
        fun gateAllows(result: GateResult): Boolean
        fun showUpgradeSheet(reason: String?)
        fun ensureBootstrapExtracted()
        fun deployScripts()
        fun desktopInstalled(): Boolean
        fun openLogs()
        fun paintStartBusy()
        fun paintStopBusy()
        fun paintIdle()
        fun paintRunning()
        fun stopRowVisible(): Boolean
    }

    enum class State { OFF, STARTING, RUNNING, BUDGET_OUT }

    /** Pills etc. Called on the main thread after every state change (and once a minute for free users). */
    val listeners = ArrayList<() -> Unit>()

    init {
        active = this
    }

    /** Callbacks from a session started by an earlier Activity land on the newest controller. */
    private val live: DesktopController get() = active ?: this

    val isRunning: Boolean get() = running

    /** User (or budget) stop in flight. */
    val isStopping: Boolean get() = userStopping

    val state: State
        get() = stateOf(running, starting, BuildConfig.MONETISATION_ENFORCE, host.isPro(), remainingMs)

    /** Free minutes left today; the only counter (XfceBudget). */
    val remainingMs: Long get() = XfceBudget.remainingMs(activity)

    private val free: Boolean get() = BuildConfig.MONETISATION_ENFORCE && !host.isPro()

    /** What every pill shows right now. */
    fun pill(): PillModel = pillModel(state, userStopping, BuildConfig.MONETISATION_ENFORCE, host.isPro(), remainingMs)

    /** Pill tap: off → start (gated), starting → log, running → view, budget out → upsell. */
    fun primaryAction() {
        if (userStopping) return
        when (state) {
            State.OFF -> start()
            State.STARTING -> host.openLogs()
            State.RUNNING -> view()
            State.BUDGET_OUT -> host.showUpgradeSheet("Free desktop time used for today")
        }
    }

    fun openLogs() = host.openLogs()

    /** Gate, then start. Card flips to STOP on the first healthy log line. */
    fun start() {
        if (running || starting) return
        if (!host.desktopInstalled()) {
            Toast.makeText(activity, "Phosh desktop is not installed yet", Toast.LENGTH_LONG).show()
            return
        }
        if (!host.gateAllows(FeatureGate.canStartXfce(host.isPro(), XfceBudget.usedTodayMs(activity)))) return
        userStopping = false
        host.paintStartBusy()
        Telemetry.log(activity, Telemetry.Event.DESKTOP_START)
        startGui()
        notifyState()
    }

    /** User stop; falls back to idle after 6 s if the stop script never reports. */
    fun stop() {
        userStopping = true
        host.paintStopBusy()
        stopGui()
        notifyState()
        mainHandler.postDelayed({
            if (userStopping) {
                userStopping = false
                live.markIdle()
            }
        }, 6000)
    }

    /** Bring the X11 display to front. */
    fun view(flags: Int = 0) {
        activity.startActivity(Intent(activity, com.termux.x11.MainActivity::class.java).addFlags(flags))
    }

    /** Idle card after a session ended. */
    fun markIdle() {
        running = false
        starting = false
        host.paintIdle()
        notifyState()
    }

    private fun markRunning() {
        running = true
        starting = false
        host.paintRunning()
        notifyState()
    }

    /** Repaint the host from the real state (new Activity, setup complete). Never changes state. */
    fun syncUi() {
        when {
            userStopping -> host.paintStopBusy()
            running -> host.paintRunning()
            starting -> host.paintStartBusy()
            else -> host.paintIdle()
        }
        notifyState()
    }

    private fun notifyState() {
        listeners.toList().forEach { it() }
    }

    // ── Budget ───────────────────────────────────────────────────────────────

    private fun startBudget() {
        XfceBudget.start(activity)
        mainHandler.removeCallbacks(budgetStop)
        mainHandler.removeCallbacks(minuteTick)
        if (free) {
            mainHandler.postDelayed(budgetStop, XfceBudget.remainingMs(activity))
            mainHandler.postDelayed(minuteTick, 60_000)
        }
    }

    private fun stopBudget() {
        mainHandler.removeCallbacks(budgetStop)
        mainHandler.removeCallbacks(minuteTick)
        XfceBudget.stop(activity)
    }

    // ── Foreground service ───────────────────────────────────────────────────

    private fun acquireFgs() {
        NativeCodeFgs.ensurePostNotificationsPermission(activity)
        NativeCodeFgs.acquire(
            activity, NativeCodeFgs.OWNER_DESKTOP,
            BackgroundService.desktopIntent(activity, notificationText(free, remainingMs))
        )
    }

    /** Also called by the host on destroy (desktop process keeps running; only the FGS claim drops). */
    fun releaseFgs() {
        NativeCodeFgs.release(activity, NativeCodeFgs.OWNER_DESKTOP)
    }

    // ── Log capture (host-only file in app cache; never enters the guest) ─────

    fun logFile(): File = File(activity.cacheDir, "gui_desktop.log")

    /** Append one line with a ring cap: once over 512 KB, keep only the tail half. */
    private fun logAppend(line: String) {
        try {
            val f = logFile()
            val text = "$line\n"
            if (f.exists() && f.length() + text.length > LOG_MAX_BYTES) {
                val tail = f.readText().takeLast((LOG_MAX_BYTES / 2).toInt())
                f.writeText(tail + text)
            } else {
                f.appendText(text)
            }
        } catch (_: Exception) {
        }
    }

    private fun logHeader(action: String, script: String, method: String) {
        logAppend("")
        logAppend("=== $action method=$method script=$script ===")
    }

    // ── Start / stop scripts ─────────────────────────────────────────────────

    private fun prefs() = activity.getSharedPreferences("nativecode_prefs", Context.MODE_PRIVATE)

    private fun method() = prefs().getString("linux_method", "proot") ?: "proot"

    /** Host bash + script argv (same isolation branch as before; not LinuxCommandBuilder). */
    private fun hostScriptArgs(scriptName: String): Array<String> {
        val bash = TermuxHostPaths.libBash(activity).absolutePath
        val script = File(TermuxHostPaths.HOME, scriptName)
        val gpu = prefs().getString("flux_gpu", "virgl") ?: "virgl"
        return arrayOf(bash, script.absolutePath, ProjectPathResolver.DEFAULT_DISTRO, gpu)
    }

    /**
     * First healthy start/stop log line while STOP row is (or should be) active:
     * show STOP, open X11 once.
     */
    private fun onHealthyLine() {
        if (userStopping) {
            if (running || host.stopRowVisible()) markRunning()
            return
        }
        markRunning()
        if (!x11Launched) {
            x11Launched = true
            mainHandler.postDelayed({
                if (userStopping || !running) return@postDelayed
                live.view(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }, 400)
        }
    }

    /** proot → start_gui.sh; chroot → start_gui_chroot.sh (host X11 + root DE). */
    private fun startGui() {
        // Streamed capture, NOT runScriptInTerminal: GUI is long-lived so it must
        // never hold isScriptRunning (marketplace/back/other scripts stay free).
        job?.cancel()
        userStopping = false
        running = false
        starting = true
        x11Launched = false
        startBudget()
        acquireFgs()

        executor.execute {
            host.ensureBootstrapExtracted()
            host.deployScripts()

            val method = method()
            if (method == "chroot" && !ProjectPathResolver.isChrootInstalled()) {
                mainHandler.post {
                    Toast.makeText(
                        activity,
                        "Chroot not installed. Settings → Chroot Settings or Onboarding.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            val scriptName = if (method == "chroot") "start_gui_chroot.sh" else "start_gui.sh"
            val script = File(TermuxHostPaths.HOME, scriptName)
            if (!script.isFile) {
                logHeader("START", scriptName, method)
                logAppend("ERROR: missing host script ${script.absolutePath} (deployScripts failed?)")
                mainHandler.post {
                    Toast.makeText(activity, "GUI script missing", Toast.LENGTH_LONG).show()
                    live.revertToStart()
                }
                return@execute
            }
            Log.i("GUI", "startGui method=$method script=${script.absolutePath}")
            logHeader("START", scriptName, method)
            val supportLib = File(TermuxHostPaths.LIB, "libandroid-support.so")
            if (!supportLib.isFile) {
                logAppend("WARN: missing ${supportLib.absolutePath} — libbash may fail to link; re-run host setup")
            }

            val args = hostScriptArgs(scriptName)
            mainHandler.post {
                if (userStopping) return@post
                job = ShellCommandRunner.runStreamedCancelable(
                    activity.applicationContext,
                    args,
                    onLine = { line ->
                        logAppend(line)
                        live.onHealthyLine()
                    },
                    onDone = { code ->
                        logAppend("[exit $code]")
                        when {
                            // Cancelled by user STOP — stop click owns flip
                            code == -1 || userStopping -> { /* leave card */ }
                            code != 0 -> {
                                Toast.makeText(
                                    activity.applicationContext,
                                    "Desktop start failed (exit $code)",
                                    Toast.LENGTH_LONG
                                ).show()
                                live.revertToStart()
                            }
                            // Clean desktop exit (session ended)
                            else -> live.revertToStart()
                        }
                    }
                )
            }
        }
        // X11 opens from onHealthyLine (first script output) — not blind 500ms
    }

    /** proot → stop_gui.sh; chroot → stop_gui_chroot.sh (no proot pkill). */
    private fun stopGui() {
        val stopBroadcast = Intent("com.termux.x11.ACTION_STOP")
        stopBroadcast.setPackage(activity.packageName)
        activity.sendBroadcast(stopBroadcast)
        releaseFgs()
        stopBudget()
        starting = false

        job?.cancel()

        executor.execute {
            host.deployScripts()
            val method = method()
            val scriptName = if (method == "chroot") "stop_gui_chroot.sh" else "stop_gui.sh"
            val script = File(TermuxHostPaths.HOME, scriptName)
            Log.i("GUI", "stopGui method=$method script=${script.absolutePath}")
            logHeader("STOP", scriptName, method)
            val args = hostScriptArgs(scriptName)

            mainHandler.post {
                job = ShellCommandRunner.runStreamedCancelable(
                    activity.applicationContext,
                    args,
                    onLine = { line ->
                        logAppend(line)
                        if (!userStopping) live.onHealthyLine()
                    },
                    onDone = { code ->
                        logAppend("[exit $code]")
                        userStopping = false
                        live.markIdle()
                    }
                )
            }
        }
    }

    /** Start stream failed → card back to START (not stuck on STOP). */
    private fun revertToStart() {
        stopBudget()
        releaseFgs()
        markIdle()
    }

    /** What a desktop pill shows. Pure (unit-tested): the free-user states cannot be seen on a Pro device. */
    data class PillModel(
        /** Short text beside the icon; empty = icon only. */
        val label: String,
        val description: String,
        /** Filled tile = desktop is up. */
        val filled: Boolean,
        /** Show the small stop button. */
        val stoppable: Boolean,
        /** Budget used up: lock look, tap = upgrade. */
        val locked: Boolean,
    )

    companion object {
        private const val LOG_MAX_BYTES = 512L * 1024L

        /** Streamed capture job for start/stop scripts (never touches isScriptRunning). */
        @Volatile private var job: ShellJob? = null

        /** True while user-initiated STOP owns the card flip (ignore start cancel onDone). */
        @Volatile private var userStopping = false

        /** True after first healthy start log line (STOP shown). */
        @Volatile private var running = false

        @Volatile private var starting = false

        /** One X11 Activity launch per START attempt. */
        @Volatile private var x11Launched = false

        /** Newest controller; owns the UI for a session an older Activity started. */
        @Volatile private var active: DesktopController? = null

        private val budgetStop = Runnable {
            val c = active ?: return@Runnable
            if (!c.free || !running && job == null) return@Runnable
            Toast.makeText(c.activity, "Free desktop time used for today", Toast.LENGTH_LONG).show()
            userStopping = true
            c.host.paintStopBusy()
            c.stopGui()
            c.notifyState()
            c.host.showUpgradeSheet("Free desktop time used for today")
        }

        /** Free users: minutes left on the pills and the notification, once a minute. */
        private val minuteTick = object : Runnable {
            override fun run() {
                val c = active ?: return
                if (!running && !starting || !c.free) return
                BackgroundService.updateDesktopText(notificationText(true, c.remainingMs))
                c.notifyState()
                c.mainHandler.postDelayed(this, 60_000)
            }
        }

        /** Pure state math (unit-tested). Running wins; budget only matters for enforced free users. */
        fun stateOf(running: Boolean, starting: Boolean, enforce: Boolean, pro: Boolean, remainingMs: Long): State = when {
            running -> State.RUNNING
            starting -> State.STARTING
            enforce && !pro && remainingMs <= 0L -> State.BUDGET_OUT
            else -> State.OFF
        }

        /** Whole minutes left, rounded up so "1m" shows until the budget is really gone. */
        fun minutesLeft(remainingMs: Long): Long = (remainingMs.coerceAtLeast(0) + 59_999) / 60_000

        fun pillModel(state: State, stopping: Boolean, enforce: Boolean, pro: Boolean, remainingMs: Long): PillModel {
            val free = enforce && !pro
            val min = minutesLeft(remainingMs)
            return when {
                stopping -> PillModel("…", "Desktop stopping", filled = false, stoppable = false, locked = false)
                state == State.RUNNING -> PillModel(
                    if (free) "${min}m" else "ON",
                    if (free) "Desktop running, $min min left today. Tap to view, hold for options."
                    else "Desktop running. Tap to view, hold for options.",
                    filled = true, stoppable = true, locked = false
                )
                state == State.STARTING -> PillModel("…", "Desktop starting. Tap for log.", filled = false, stoppable = true, locked = false)
                state == State.BUDGET_OUT -> PillModel(
                    "0m · PRO", "Free desktop time used for today. Tap to upgrade.",
                    filled = false, stoppable = false, locked = true
                )
                else -> PillModel(
                    if (free) "${min}m" else "",
                    if (free) "Start desktop, $min min left today" else "Start desktop",
                    filled = false, stoppable = false, locked = false
                )
            }
        }

        fun notificationText(free: Boolean, remainingMs: Long): String =
            if (free) "Phosh desktop running · ${minutesLeft(remainingMs)} min left today"
            else "Phosh desktop running"
    }
}
