package com.zenithblue.nativecode.quick

import com.zenithblue.nativecode.desktop.DesktopController.State
import com.zenithblue.nativecode.quick.QuickActions.DeskUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickActionsTest {
    @Test fun onlyFixedActionsPass() {
        assertEquals("start_desktop", QuickActions.validAction("start_desktop"))
        assertEquals("agent", QuickActions.validAction("agent"))
        assertNull(QuickActions.validAction("rm -rf /"))
        assertNull(QuickActions.validAction("/data/data/x"))
        assertNull(QuickActions.validAction(null))
    }

    @Test fun freeDesktopMatrix() {
        val twentyFiveMin = 24 * 60_000L + 10_000
        val off = QuickActions.desktopQuick(State.OFF, true, twentyFiveMin)
        assertEquals(DeskUi.START, off.ui); assertEquals("start_desktop", off.action); assertEquals(25L, off.minutes)
        val run = QuickActions.desktopQuick(State.RUNNING, true, twentyFiveMin)
        assertEquals(DeskUi.STOP, run.ui); assertEquals("stop_desktop", run.action); assertEquals(25L, run.minutes)
        // Budget gone: the tap still goes to the app, whose gate shows the upsell.
        val out = QuickActions.desktopQuick(State.BUDGET_OUT, true, 0)
        assertEquals(DeskUi.UPGRADE, out.ui); assertEquals("start_desktop", out.action); assertNull(out.minutes)
        assertEquals("stop_desktop", QuickActions.desktopQuick(State.STARTING, true, 1).action)
    }

    @Test fun proNeverShowsMinutes() {
        assertNull(QuickActions.desktopQuick(State.OFF, false, 0).minutes)
        assertNull(QuickActions.desktopQuick(State.RUNNING, false, 0).minutes)
    }

    @Test fun agentTool() {
        assertEquals("opencode", QuickActions.agentTool(false, "claude-code")) // Free: opencode only
        assertEquals("claude-code", QuickActions.agentTool(true, "claude-code"))
        assertEquals("opencode", QuickActions.agentTool(true, null))
        assertEquals("opencode", QuickActions.agentTool(true, "shell"))
    }
}
