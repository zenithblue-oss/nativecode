package com.zenithblue.nativecode.desktop

import com.zenithblue.nativecode.desktop.DesktopController.State
import com.zenithblue.nativecode.telemetry.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopControllerTest {
    private fun s(running: Boolean = false, starting: Boolean = false, enforce: Boolean = true, pro: Boolean = false, left: Long = 60_000) =
        DesktopController.stateOf(running, starting, enforce, pro, left)

    @Test fun stateMath() {
        assertEquals(State.OFF, s())
        assertEquals(State.STARTING, s(starting = true))
        assertEquals(State.RUNNING, s(running = true, starting = true, left = 0))
        assertEquals(State.BUDGET_OUT, s(left = 0))
        assertEquals(State.OFF, s(left = 0, pro = true))
        assertEquals(State.OFF, s(left = 0, enforce = false))
    }

    private fun pill(state: State, stopping: Boolean = false, enforce: Boolean = true, pro: Boolean = false, left: Long = 24 * 60_000L + 10_000) =
        DesktopController.pillModel(state, stopping, enforce, pro, left)

    /** Free-user pill: cannot be seen on the Pro test device, so it is pinned here. */
    @Test fun pillFreeUserShowsMinutesLeft() {
        val off = pill(State.OFF)
        assertEquals("25m", off.label) // 24:10 rounds up
        assertEquals(false, off.filled); assertEquals(false, off.stoppable); assertEquals(false, off.locked)
        assertEquals("Start desktop, 25 min left today", off.description)

        val run = pill(State.RUNNING)
        assertEquals("25m", run.label)
        assertEquals(true, run.filled); assertEquals(true, run.stoppable)

        assertEquals("1m", pill(State.RUNNING, left = 1).label)
        assertEquals("30m", pill(State.OFF, left = 30 * 60_000L).label)
    }

    @Test fun pillBudgetOutLocksAndUpsells() {
        val out = pill(DesktopController.stateOf(false, false, true, false, 0), left = 0)
        assertEquals("0m · PRO", out.label)
        assertEquals(true, out.locked); assertEquals(false, out.stoppable); assertEquals(false, out.filled)
    }

    @Test fun pillProAndUnenforcedNeverShowMinutes() {
        assertEquals("", pill(State.OFF, pro = true).label)
        assertEquals("ON", pill(State.RUNNING, pro = true).label)
        assertEquals("", pill(State.OFF, enforce = false, left = 0).label)
        assertEquals("ON", pill(State.RUNNING, enforce = false, left = 0).label)
    }

    @Test fun pillStartingAndStopping() {
        val starting = pill(State.STARTING)
        assertEquals("…", starting.label); assertEquals(true, starting.stoppable); assertEquals(false, starting.filled)
        val stopping = pill(State.RUNNING, stopping = true)
        assertEquals("Desktop stopping", stopping.description); assertEquals(false, stopping.stoppable)
    }

    @Test fun notificationTextAndMinutes() {
        assertEquals("Phosh desktop running", DesktopController.notificationText(false, 0))
        assertEquals("Phosh desktop running · 3 min left today", DesktopController.notificationText(true, 2 * 60_000L + 1))
        assertEquals(0, DesktopController.minutesLeft(0))
        assertEquals(0, DesktopController.minutesLeft(-5))
        assertEquals(1, DesktopController.minutesLeft(60_000))
    }

    /** Free-form text (paths, names, prompts) must never reach an event or crash key. */
    @Test fun telemetryRejectsFreeFormValues() {
        assertEquals("claude", Telemetry.token("claude"))
        assertEquals("pro_monthly", Telemetry.token("pro_monthly"))
        assertEquals("other", Telemetry.token("/home/user/my project"))
        assertEquals("other", Telemetry.token("fix the login bug"))
        assertEquals("other", Telemetry.token("user@example.com"))
        assertEquals("other", Telemetry.token("a".repeat(41)))
        assertEquals("other", Telemetry.token(null))
    }
}
