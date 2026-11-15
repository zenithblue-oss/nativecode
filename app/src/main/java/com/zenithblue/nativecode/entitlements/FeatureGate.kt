package com.zenithblue.nativecode.entitlements

import com.zenithblue.nativecode.BuildConfig

/** Gated features; also selects Upgrade sheet copy. Last four are Pro-only (free = 0 projects). */
enum class ProFeature { AI_TOOL, AI_SESSIONS, XFCE, PROJECTS, PROJECT_IMPORT, WORKSPACE, GITHUB_LOGIN, SHELL, SSH_SERVER }

sealed class GateResult {
    data object Allow : GateResult()
    data class Deny(val reason: String, val upsell: ProFeature) : GateResult()
}

/**
 * Pure gate math. Callers pass [isPro] from [EntitlementStore.isPro].
 * Everything is Allow while [BuildConfig.MONETISATION_ENFORCE] is false.
 */
object FeatureGate {
    private val PRO_ONLY = setOf(
        ProFeature.PROJECTS, ProFeature.PROJECT_IMPORT, ProFeature.WORKSPACE, ProFeature.GITHUB_LOGIN
    )

    /** Pro-only features: project create, project import, workspace, gh auth login. */
    fun canUse(
        isPro: Boolean,
        feature: ProFeature,
        enforce: Boolean = BuildConfig.MONETISATION_ENFORCE
    ): GateResult {
        require(feature in PRO_ONLY) { "$feature has its own gate" }
        return if (!enforce || isPro) GateResult.Allow else GateResult.Deny("Pro required", feature)
    }

    fun canLaunchAiTool(
        isPro: Boolean,
        toolId: String,
        enforce: Boolean = BuildConfig.MONETISATION_ENFORCE
    ): GateResult = when {
        !enforce || isPro || toolId == FeatureLimits.FREE_AI_TOOL_ID -> GateResult.Allow
        else -> GateResult.Deny("$toolId requires Pro", ProFeature.AI_TOOL)
    }

    /** Interactive rootfs shell tabs (user + root): Pro only, else any shell could run any AI CLI. */
    fun canOpenShell(
        isPro: Boolean,
        enforce: Boolean = BuildConfig.MONETISATION_ENFORCE
    ): GateResult =
        if (!enforce || isPro) GateResult.Allow
        else GateResult.Deny("Shell access requires NativeCode Pro", ProFeature.SHELL)

    /** SSH server (plan 17): Pro. The SSH client and saved hosts stay Free. */
    fun canUseSshServer(
        isPro: Boolean,
        enforce: Boolean = BuildConfig.MONETISATION_ENFORCE
    ): GateResult =
        if (!enforce || isPro) GateResult.Allow
        else GateResult.Deny("The SSH server requires NativeCode Pro", ProFeature.SSH_SERVER)

    fun canOpenAiSession(
        isPro: Boolean,
        currentAiSessionCount: Int,
        enforce: Boolean = BuildConfig.MONETISATION_ENFORCE
    ): GateResult {
        val max = if (isPro) FeatureLimits.PRO_MAX_AI_SESSIONS else FeatureLimits.FREE_MAX_AI_SESSIONS
        return when {
            !enforce || currentAiSessionCount < max -> GateResult.Allow
            else -> GateResult.Deny("AI session limit ($max) reached", ProFeature.AI_SESSIONS)
        }
    }

    fun canStartXfce(
        isPro: Boolean,
        usedTodayMs: Long,
        enforce: Boolean = BuildConfig.MONETISATION_ENFORCE
    ): GateResult = when {
        !enforce || isPro || usedTodayMs < FeatureLimits.FREE_XFCE_DAILY_MS -> GateResult.Allow
        else -> GateResult.Deny("Free desktop time used for today", ProFeature.XFCE)
    }
}
