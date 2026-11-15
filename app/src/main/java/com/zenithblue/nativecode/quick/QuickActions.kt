package com.zenithblue.nativecode.quick

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.util.Log
import com.zenithblue.nativecode.BuildConfig
import com.zenithblue.nativecode.MainActivity
import com.zenithblue.nativecode.SplashActivity
import com.zenithblue.nativecode.desktop.DesktopController
import com.zenithblue.nativecode.desktop.DesktopController.State
import com.zenithblue.nativecode.entitlements.EntitlementStore
import com.zenithblue.nativecode.entitlements.XfceBudget

/**
 * Quick Settings tile, home-screen widget and app shortcuts (plan 19). Every one of them only
 * opens the app with ONE fixed [EXTRA] value; MainActivity validates it ([parse]) and runs it
 * through the usual gates (DesktopController, aiLaunchAllowed, projectGateAllows). No path or
 * command ever travels in an intent, so another app cannot steer them.
 */
object QuickActions {
    const val EXTRA = "QUICK_ACTION"
    const val START_DESKTOP = "start_desktop"
    const val STOP_DESKTOP = "stop_desktop"
    const val AGENT = "agent"
    const val NEW_TERMINAL = "new_terminal"
    const val LAST_PROJECT = "last_project"

    private val ALL = setOf(START_DESKTOP, STOP_DESKTOP, AGENT, NEW_TERMINAL, LAST_PROJECT)
    private const val PREFS = "nativecode_prefs"
    private const val KEY_LAST_TOOL = "quick_last_tool"
    private const val KEY_DESKTOP_ON = "quick_desktop_on"
    private const val DEFAULT_TOOL = "opencode"

    /** Fixed vocabulary only; anything else is ignored. */
    fun validAction(raw: String?): String? = raw?.takeIf { it in ALL }

    fun parse(intent: Intent): String? = validAction(intent.getStringExtra(EXTRA))

    /**
     * Straight into MainActivity once onboarding is done (no 1.2 s splash over a running app);
     * through the splash/onboarding router otherwise, which forwards the same fixed extra.
     */
    fun launchIntent(ctx: Context, action: String): Intent {
        val done = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("onboarding_completed", false)
        return Intent(ctx, if (done) MainActivity::class.java else SplashActivity::class.java)
            .putExtra(EXTRA, action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun pending(ctx: Context, action: String): PendingIntent =
        PendingIntent.getActivity(
            ctx, action.hashCode(), launchIntent(ctx, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    // ── Agent: opencode for Free, the last-used tool for Pro ──────────────────

    /** Pure (unit-tested). Free: opencode only. Pro: remembered tool, opencode when none. */
    fun agentTool(pro: Boolean, last: String?): String =
        if (pro && !last.isNullOrBlank() && last != "shell" && last != "shell-root") last else DEFAULT_TOOL

    fun rememberTool(ctx: Context, tool: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_TOOL, tool).apply()
    }

    fun agentTool(ctx: Context): String = agentTool(
        EntitlementStore.isPro(ctx),
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_TOOL, null)
    )

    // ── Desktop state for the tile and the widget ────────────────────────────

    enum class DeskUi { START, STARTING, STOP, UPGRADE }

    /** What the tile / widget show and send. [minutes] is only set for free users (minutes left today). */
    data class DesktopQuick(val ui: DeskUi, val action: String, val minutes: Long?)

    /**
     * Pure (unit-tested). Off → start in the app (it shows the upsell when the budget is out),
     * starting or running → stop. Pro and unenforced builds never show minutes.
     */
    fun desktopQuick(state: State, free: Boolean, remainingMs: Long): DesktopQuick {
        val min = if (free) DesktopController.minutesLeft(remainingMs) else null
        return when (state) {
            State.OFF -> DesktopQuick(DeskUi.START, START_DESKTOP, min)
            State.STARTING -> DesktopQuick(DeskUi.STARTING, STOP_DESKTOP, min)
            State.RUNNING -> DesktopQuick(DeskUi.STOP, STOP_DESKTOP, min)
            State.BUDGET_OUT -> DesktopQuick(DeskUi.UPGRADE, START_DESKTOP, null)
        }
    }

    /** Works with the app process dead: persisted flag + budget prefs, never MainActivity fields. */
    fun desktopQuick(ctx: Context): DesktopQuick {
        val enforce = BuildConfig.MONETISATION_ENFORCE
        val pro = EntitlementStore.isPro(ctx)
        val state = DesktopController.quickState(ctx, enforce, pro, XfceBudget.remainingMs(ctx))
        return desktopQuick(state, enforce && !pro, XfceBudget.remainingMs(ctx))
    }

    /** Controller state changes land here (main thread): persist, then repaint tile and widget. */
    fun onDesktopState(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DESKTOP_ON, on).apply()
        refresh(ctx)
    }

    fun persistedDesktopOn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DESKTOP_ON, false)

    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        try {
            NativeCodeWidget.updateAll(app)
            TileService.requestListeningState(app, ComponentName(app, DesktopTileService::class.java))
        } catch (e: Exception) {
            Log.w("QuickActions", "refresh failed", e)
        }
    }
}
