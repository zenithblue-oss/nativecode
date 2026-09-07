package com.zenithblue.nativecode.onboarding

import kotlin.math.min

data class SetupPhase(val id: String, val label: String, val weight: Int)

class SetupProgressCoordinator {
    var generation: Long = 0L
        private set
    var setupFinished: Boolean = false
        private set
    var setupFailed: Boolean = false
        private set
    var overallPercent: Int = 0
        private set

    fun begin(): Long {
        generation += 1
        setupFinished = false
        setupFailed = false
        overallPercent = 0
        return generation
    }

    fun computePercent(
        runGeneration: Long,
        phases: List<SetupPhase>,
        phaseIndex: Int,
        phaseFraction: Float
    ): Int? {
        if (runGeneration != generation) return null
        if (setupFinished) return 100
        if (phases.isEmpty()) return 0
        val completed = phases.take(phaseIndex).sumOf { it.weight }
        val curW = phases.getOrNull(phaseIndex)?.weight ?: 0
        val raw = completed + curW * phaseFraction.coerceIn(0f, 1f)
        val total = phases.sumOf { it.weight }.coerceAtLeast(1)
        val pct = ((raw * 100f) / total).toInt()
        val calculated = min(99, pct)
        overallPercent = maxOf(overallPercent, calculated)
        return overallPercent
    }

    fun finishSuccess(runGeneration: Long): Int? {
        if (runGeneration != generation) return null
        setupFinished = true
        setupFailed = false
        overallPercent = 100
        return 100
    }

    fun fail(runGeneration: Long): Boolean {
        if (runGeneration != generation) return false
        setupFailed = true
        return true
    }
}
