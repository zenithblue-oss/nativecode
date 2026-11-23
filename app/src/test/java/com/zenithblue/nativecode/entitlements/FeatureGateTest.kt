package com.zenithblue.nativecode.entitlements

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureGateTest {
    private val allow = GateResult.Allow
    private val proOnly = listOf(
        ProFeature.PROJECTS, ProFeature.PROJECT_IMPORT, ProFeature.WORKSPACE, ProFeature.GITHUB_LOGIN
    )

    @Test
    fun enforceOffAllowsEverything() {
        proOnly.forEach { assertEquals(allow, FeatureGate.canUse(false, it, enforce = false)) }
        assertEquals(allow, FeatureGate.canLaunchAiTool(false, "claude", enforce = false))
        assertEquals(allow, FeatureGate.canOpenAiSession(false, 99, enforce = false))
        assertEquals(allow, FeatureGate.canStartXfce(false, Long.MAX_VALUE, enforce = false))
    }

    @Test
    fun freeLimitsWhenEnforced() {
        proOnly.forEach { assertEquals(GateResult.Deny("Pro required", it), FeatureGate.canUse(false, it, enforce = true)) }
        assertEquals(allow, FeatureGate.canLaunchAiTool(false, FeatureLimits.FREE_AI_TOOL_ID, enforce = true))
        assertTrue(FeatureGate.canLaunchAiTool(false, "claude", enforce = true) is GateResult.Deny)
        assertEquals(allow, FeatureGate.canOpenAiSession(false, 0, enforce = true))
        assertTrue(FeatureGate.canOpenAiSession(false, 1, enforce = true) is GateResult.Deny)
        assertEquals(allow, FeatureGate.canStartXfce(false, FeatureLimits.FREE_XFCE_DAILY_MS - 1, enforce = true))
        assertTrue(FeatureGate.canStartXfce(false, FeatureLimits.FREE_XFCE_DAILY_MS, enforce = true) is GateResult.Deny)
    }

    @Test
    fun shellIsProOnly() {
        assertEquals(allow, FeatureGate.canOpenShell(false, enforce = false))
        assertEquals(allow, FeatureGate.canOpenShell(true, enforce = true))
        assertEquals(
            GateResult.Deny("Shell access requires NativeCode Pro", ProFeature.SHELL),
            FeatureGate.canOpenShell(false, enforce = true)
        )
        assertEquals(allow, FeatureGate.canLaunchAiTool(false, FeatureLimits.FREE_AI_TOOL_ID, enforce = true))
    }

    @Test
    fun proLimitsWhenEnforced() {
        proOnly.forEach { assertEquals(allow, FeatureGate.canUse(true, it, enforce = true)) }
        assertEquals(allow, FeatureGate.canLaunchAiTool(true, "claude", enforce = true))
        assertEquals(allow, FeatureGate.canOpenAiSession(true, 4, enforce = true))
        assertEquals(allow, FeatureGate.canOpenAiSession(true, 5, enforce = true))
        assertEquals(allow, FeatureGate.canOpenAiSession(true, 500, enforce = true))
        assertEquals(allow, FeatureGate.canStartXfce(true, Long.MAX_VALUE, enforce = true))
    }

    @Test
    fun proCacheExpiresAfterOfflineTtl() {
        val now = 10 * EntitlementStore.OFFLINE_TTL_MS
        assertTrue(EntitlementStore.isCacheValid(true, now - 1, now))
        assertTrue(!EntitlementStore.isCacheValid(true, now - EntitlementStore.OFFLINE_TTL_MS, now))
        assertTrue(!EntitlementStore.isCacheValid(false, now, now))
        assertTrue(!EntitlementStore.isCacheValid(true, now + 1, now)) // clock rolled back
    }
}
