package com.zenithblue.nativecode.billing

/** Pure offer choice + paywall text (no Android / Play types, so it is unit-testable). */

const val TRIAL_OFFER_ID = "free-trial-7d"
const val TRIAL_TAG = "free-trial"

data class OfferPhase(val priceMicros: Long, val formattedPrice: String, val period: String)

data class SubOffer(
    val offerId: String?, // null = the base plan itself
    val basePlanId: String,
    val tags: List<String>,
    val phases: List<OfferPhase>,
    val token: String = "",
) {
    /** Leading zero-price phase, if any. */
    val trialPhase get() = phases.firstOrNull()?.takeIf { it.priceMicros == 0L }
    val isTrial get() = offerId == TRIAL_OFFER_ID || TRIAL_TAG in tags || trialPhase != null
    /** Price of the recurring (last) phase. */
    val recurringPrice get() = phases.lastOrNull()?.formattedPrice
}

/**
 * Play only returns offers this account is eligible for, so a trial offer in the list means
 * "eligible". Prefer it (on the base plan we sell); else the base plan; else anything.
 */
fun pickOffer(offers: List<SubOffer>): SubOffer? {
    val base = offers.firstOrNull { it.offerId == null }
    return offers.firstOrNull { it.isTrial && (base == null || it.basePlanId == base.basePlanId) }
        ?: base
        ?: offers.firstOrNull()
}

/** ISO 8601 period (P7D, P1W, P1M) → "7-day"; null when not a single-unit period. */
fun trialLength(period: String): String? {
    val m = Regex("P(\\d+)([DWMY])").matchEntire(period) ?: return null
    val unit = when (m.groupValues[2]) { "D" -> "day"; "W" -> "week"; "M" -> "month"; else -> "year" }
    return "${m.groupValues[1]}-$unit"
}

/** Paywall button text. [unit] ("/mo") is appended to the recurring price only on a trial row. */
fun planLabel(label: String, offer: SubOffer?, unit: String, fallback: String): String {
    val price = offer?.recurringPrice
    val trial = offer?.trialPhase
    if (trial == null || price == null) return "$label · ${price ?: fallback}"
    val length = trialLength(trial.period)?.let { "$it " } ?: ""
    return "$label · ${length}free trial, then $price$unit"
}
