package com.zenithblue.nativecode.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferSelectionTest {
    private val paid = OfferPhase(380_000_000, "₹380.00", "P1M")
    private val free = OfferPhase(0, "Free", "P7D")
    private val base = SubOffer(null, "monthly", emptyList(), listOf(paid), "base")
    private val trial = SubOffer(TRIAL_OFFER_ID, "monthly", listOf(TRIAL_TAG), listOf(free, paid), "trial")

    @Test
    fun eligiblePicksTrialWhateverTheOrder() {
        assertEquals(trial, pickOffer(listOf(base, trial)))
        assertEquals(trial, pickOffer(listOf(trial, base)))
    }

    @Test
    fun trialMatchedByIdTagOrZeroPricePhase() {
        val byId = SubOffer(TRIAL_OFFER_ID, "monthly", emptyList(), listOf(paid))
        val byTag = SubOffer("x", "monthly", listOf(TRIAL_TAG), listOf(paid))
        val byPhase = SubOffer("intro", "monthly", emptyList(), listOf(free, paid))
        listOf(byId, byTag, byPhase).forEach { assertEquals(it, pickOffer(listOf(base, it))) }
    }

    @Test
    fun notEligibleFallsBackToBasePlan() {
        assertEquals(base, pickOffer(listOf(base)))
        // A paid discount offer is not a trial: base plan still wins.
        val discount = SubOffer("promo", "monthly", emptyList(), listOf(OfferPhase(1_000_000, "₹1.00", "P1M"), paid))
        assertFalse(discount.isTrial)
        assertEquals(base, pickOffer(listOf(discount, base)))
    }

    @Test
    fun trialOnAnotherBasePlanIsIgnored() {
        val other = trial.copy(basePlanId = "legacy")
        assertEquals(base, pickOffer(listOf(other, base)))
    }

    @Test
    fun missingOffers() {
        assertNull(pickOffer(emptyList()))
        val only = SubOffer("promo", "monthly", emptyList(), listOf(paid))
        assertEquals(only, pickOffer(listOf(only)))
        assertEquals(trial, pickOffer(listOf(trial))) // no base plan returned
        assertTrue(SubOffer(null, "monthly", emptyList(), emptyList()).recurringPrice == null)
    }

    @Test
    fun labels() {
        assertEquals("MONTHLY · 7-day free trial, then ₹380.00/mo", planLabel("MONTHLY", trial, "/mo", "$3.99/mo"))
        assertEquals("MONTHLY · ₹380.00", planLabel("MONTHLY", base, "/mo", "$3.99/mo"))
        assertEquals("MONTHLY · $3.99/mo", planLabel("MONTHLY", null, "/mo", "$3.99/mo"))
        // Trial matched by id only (no free phase): never claim a free trial.
        val idOnly = SubOffer(TRIAL_OFFER_ID, "monthly", emptyList(), listOf(paid))
        assertEquals("MONTHLY · ₹380.00", planLabel("MONTHLY", idOnly, "/mo", "$3.99/mo"))
    }

    @Test
    fun trialLengths() {
        assertEquals("7-day", trialLength("P7D"))
        assertEquals("1-week", trialLength("P1W"))
        assertEquals("1-month", trialLength("P1M"))
        assertNull(trialLength("P1M2D"))
        val odd = trial.copy(phases = listOf(free.copy(period = "P1M2D"), paid))
        assertEquals("YEARLY · free trial, then ₹380.00/yr", planLabel("YEARLY", odd, "/yr", "$29.99/yr"))
    }
}
