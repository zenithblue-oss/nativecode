package com.zenithblue.nativecode.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarStatsTest {
    @Test fun parseDefaultsEmptyAndGarbage() {
        assertEquals(TopBarStats.DEFAULT, TopBarStats.parse(null))
        assertEquals(emptyList<Stat>(), TopBarStats.parse(""))
        assertEquals(listOf(Stat.RAM, Stat.BATTERY), TopBarStats.parse("ram, bogus,bat,ram"))
    }

    @Test fun serializeRoundTripKeepsOrder() {
        val order = listOf(Stat.NET, Stat.CPU, Stat.DISK)
        assertEquals(order, TopBarStats.parse(TopBarStats.serialize(order)))
        assertEquals(emptyList<Stat>(), TopBarStats.parse(TopBarStats.serialize(emptyList())))
    }

    @Test fun toggleAndMove() {
        val e = listOf(Stat.BATTERY, Stat.CPU, Stat.RAM)
        assertEquals(listOf(Stat.BATTERY, Stat.RAM), TopBarStats.toggle(e, Stat.CPU))
        assertEquals(e + Stat.NET, TopBarStats.toggle(e, Stat.NET))
        assertEquals(listOf(Stat.CPU, Stat.BATTERY, Stat.RAM), TopBarStats.move(e, Stat.CPU, -1))
        assertEquals(listOf(Stat.BATTERY, Stat.RAM, Stat.CPU), TopBarStats.move(e, Stat.CPU, 1))
        assertSame(e, TopBarStats.move(e, Stat.BATTERY, -1))
        assertSame(e, TopBarStats.move(e, Stat.RAM, 1))
        assertSame(e, TopBarStats.move(e, Stat.NET, 1))
        assertEquals(Stat.values().toList(), TopBarStats.allOrdered(emptyList()))
        assertEquals(Stat.RAM, TopBarStats.allOrdered(listOf(Stat.RAM)).first())
    }

    @Test fun titleDecidesRows() {
        assertTrue(TopBarStats.singleRow(titleW = 100, statsW = 150, railW = 120, fixedW = 60, totalW = 430))
        assertFalse(TopBarStats.singleRow(titleW = 101, statsW = 150, railW = 120, fixedW = 60, totalW = 430))
        assertTrue(TopBarStats.singleRow(titleW = 250, statsW = 0, railW = 120, fixedW = 60, totalW = 430))
    }

    @Test fun rateFormat() {
        assertEquals("0K", TopBarStats.rate(0))
        assertEquals("12K", TopBarStats.rate(12_400))
        assertEquals("1.5M", TopBarStats.rate(1_500_000))
    }

    @Test fun wattsAreSignedAndScaled() {
        // 2 A at 4.2 V into the battery = +8.4 W; same out = -8.4 W
        assertEquals("+8.4W", Wattage.format(Wattage.watts(2_000_000, 4200, 1f, true)!!))
        assertEquals("-8.4W", Wattage.format(Wattage.watts(-2_000_000, 4200, 1f, false)!!))
        // magnitude only: raw sign never leaks into the result
        assertEquals(Wattage.watts(-500_000, 4000, 1f, true), Wattage.watts(500_000, 4000, 1f, true))
        // device reports mA as if microamps: x1000 restores it; the reverse needs /1000
        assertEquals("-3.1W", Wattage.format(Wattage.watts(-775, 4000, 1000f, false)!!))
        assertEquals("+4.0W", Wattage.format(Wattage.watts(1_000_000_000, 4000, 0.001f, true)!!))
        assertEquals("0.0W", Wattage.format(0.0))
        assertNull(Wattage.watts(1_000_000_000, 4000, 1f, true)) // 4 kW: unit error
        assertEquals("\u00F71000", Wattage.scaleLabel(0.001f))
        assertEquals("\u00D71", Wattage.scaleLabel(1f))
    }

    @Test fun polarityFollowsPluggedUntilKernelSignLearned() {
        // nothing learned: plugged decides
        assertTrue(Wattage.intoBattery(-1, 0, false, true))
        assertFalse(Wattage.intoBattery(1, 0, false, false))
        // learn the discharge sign while unplugged, then see the flip while plugged
        var st = Wattage.learn(-1, 900_000, false, 0, false)
        assertEquals(-1 to false, st)
        st = Wattage.learn(1, 1_500_000, true, st.first, st.second)
        assertEquals(-1 to true, st)
        // now the raw sign decides, even plugged-but-discharging
        assertTrue(Wattage.intoBattery(1, -1, true, true))
        assertFalse(Wattage.intoBattery(-1, -1, true, true))
        // tiny currents never teach anything
        assertEquals(0 to false, Wattage.learn(1, 5_000, false, 0, false))
    }
}
