package com.zenithblue.nativecode.agent.chat.ui

import com.zenithblue.nativecode.agent.chat.core.RequestOption

/** Pure rules for the docked pending region: one card at a time (oldest first), buttons approve-first, capped height. */
internal object PendingQueue {
    /** The browser consent (asked at [consentAt]) shows before the oldest request when it was asked first. */
    fun consentFirst(consentAt: Long?, oldestRequestAt: Long?) = consentAt != null && (oldestRequestAt == null || consentAt <= oldestRequestAt)

    private fun rank(kind: String) = when {
        kind == "allow_once" -> 0
        kind.startsWith("allow") -> 1
        kind.startsWith("reject") -> 2
        else -> 3 // cancel last
    }

    /** Approve first so it is never the button clipped off the end; stable, so ties keep the agent's order. */
    fun ordered(options: List<RequestOption>) = options.sortedBy { rank(it.kind) }

    /** Max height of the pending region: the composer keeps the rest. Tighter while the keyboard is open. */
    fun capDp(screenDp: Float, imeOpen: Boolean) = screenDp * if (imeOpen) 0.30f else 0.45f

    /** Room for a card's scrolling body once header, warning, buttons and hint take [chromeDp]. */
    fun bodyDp(capDp: Float, chromeDp: Float = 150f) = (capDp - chromeDp).coerceAtLeast(48f)
}
