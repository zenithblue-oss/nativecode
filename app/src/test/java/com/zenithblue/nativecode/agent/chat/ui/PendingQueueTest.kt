package com.zenithblue.nativecode.agent.chat.ui

import com.zenithblue.nativecode.agent.chat.core.RequestOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingQueueTest {
    @Test fun consentFirstOnlyWhenOlder() {
        assertFalse(PendingQueue.consentFirst(null, 5))
        assertTrue(PendingQueue.consentFirst(5, null))
        assertTrue(PendingQueue.consentFirst(4, 5))
        assertFalse(PendingQueue.consentFirst(6, 5))
    }

    @Test fun approveFirstCancelLast() {
        val o = listOf(
            RequestOption("c", "cancel", "Cancel"), RequestOption("r", "reject", "Decline"),
            RequestOption("s", "allow_session", "Always"), RequestOption("o", "allow_once", "Approve"),
        )
        assertEquals(listOf("o", "s", "r", "c"), PendingQueue.ordered(o).map { it.id })
    }

    @Test fun capShrinksWithKeyboardAndBodyHasFloor() {
        assertTrue(PendingQueue.capDp(800f, true) < PendingQueue.capDp(800f, false))
        assertEquals(240f, PendingQueue.capDp(800f, true), 0.01f)
        assertEquals(48f, PendingQueue.bodyDp(100f), 0.01f)
        assertEquals(90f, PendingQueue.bodyDp(240f), 0.01f)
    }
}
