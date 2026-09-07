package com.zenithblue.nativecode.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupProgressCoordinatorTest {

    @Test
    fun progressSequenceIsStrictlyMonotonicAnd100IsTerminalOnly() {
        val coordinator = SetupProgressCoordinator()
        val phases = listOf(
            SetupPhase("A", "Host", 10),
            SetupPhase("B", "Install", 85),
            SetupPhase("C", "Verify", 5)
        )

        val gen = coordinator.begin()
        val recorded = mutableListOf<Int>()

        // 0 initial
        recorded.add(coordinator.overallPercent)

        // Progress points: 5, 10, 20, 33, 60, 90, 99
        val fractions = listOf(
            0 to 0.5f,  // 5%
            0 to 1.0f,  // 10%
            1 to 0.12f, // ~20%
            1 to 0.28f, // ~33%
            1 to 0.60f, // ~61%
            1 to 0.95f, // ~90%
            1 to 1.0f,  // 95% -> capped/max
            2 to 1.0f   // 100% calculation capped at 99 before success
        )

        for ((idx, frac) in fractions) {
            val pct = coordinator.computePercent(gen, phases, idx, frac)
            if (pct != null) recorded.add(pct)
        }

        // Stale callback from old generation must be rejected
        val oldGen = gen - 1
        val staleResult = coordinator.computePercent(oldGen, phases, 0, 0.33f)
        assertNull(staleResult)

        // Attempting to step backwards within same generation must not decrease percentage
        val regressionAttempt = coordinator.computePercent(gen, phases, 0, 0.1f)
        assertEquals(recorded.last(), regressionAttempt)

        // 100 is only produced by finishSuccess
        val finalPct = coordinator.finishSuccess(gen)
        assertEquals(100, finalPct)
        recorded.add(finalPct!!)

        // Assert monotonic: sequence never decreases
        for (i in 1 until recorded.size) {
            assertTrue(
                "Progress decreased at index $i: ${recorded[i - 1]} -> ${recorded[i]} (sequence: $recorded)",
                recorded[i] >= recorded[i - 1]
            )
        }

        // 100 appears only once and is last
        assertEquals(1, recorded.count { it == 100 })
        assertEquals(100, recorded.last())

        // Start a new generation: old generation token cannot mutate new run
        val newGen = coordinator.begin()
        assertEquals(0, coordinator.overallPercent)
        assertNull(coordinator.computePercent(gen, phases, 1, 0.5f))
        assertNull(coordinator.finishSuccess(gen))
        assertEquals(0, coordinator.overallPercent)

        // New run can proceed
        val newPct = coordinator.computePercent(newGen, phases, 0, 0.5f)
        assertEquals(5, newPct)
    }
}
