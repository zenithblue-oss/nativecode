package com.zenithblue.nativecode.entitlements

/** Free / Pro limits. SSOT for docs/monetisation/plan.md §1. */
object FeatureLimits {
    const val FREE_AI_TOOL_ID = "opencode"
    const val FREE_MAX_AI_SESSIONS = 1
    const val PRO_MAX_AI_SESSIONS = 5
    /** Projects (create / import / workspace) are Pro-only. */
    const val FREE_PROJECTS = 0
    const val FREE_XFCE_DAILY_MS = 30 * 60_000L
}
