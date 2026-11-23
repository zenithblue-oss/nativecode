package com.zenithblue.nativecode.entitlements

/** Free / Pro limits. SSOT for docs/monetisation/plan.md §1. */
object FeatureLimits {
    const val FREE_AI_TOOL_ID = "opencode"
    const val FREE_MAX_AI_SESSIONS = 1
    /** Pro has no session cap. Also written as `max_pro` into the guest entitlement file: the shim only does `[ "$n" -lt "$max" ]`, so a huge number means unlimited. */
    const val UNLIMITED = Int.MAX_VALUE
    const val PRO_MAX_AI_SESSIONS = UNLIMITED
    /** Projects (create / import / workspace) are Pro-only. */
    const val FREE_PROJECTS = 0
    const val FREE_XFCE_DAILY_MS = 30 * 60_000L
}
