package com.zenithblue.nativecode.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun fitDropsLowestPriorityFirstAndNeverOverflows() {
        val w = listOf(50, 40, 60)
        assertEquals(3, TopBarStats.fitCount(w, 4, 158))
        assertEquals(2, TopBarStats.fitCount(w, 4, 157))
        assertEquals(1, TopBarStats.fitCount(w, 4, 93))
        assertEquals(0, TopBarStats.fitCount(w, 4, 49))
        assertEquals(0, TopBarStats.fitCount(w, 4, 0))
        assertEquals(0, TopBarStats.fitCount(emptyList(), 4, 100))
        // A wide chip in the middle drops itself and everything after it, even if a later one would fit.
        assertEquals(1, TopBarStats.fitCount(listOf(30, 500, 10), 4, 100))
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
}
