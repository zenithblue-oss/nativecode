package com.zenithblue.nativecode

import com.zenithblue.nativecode.terminal.TermuxHostPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlavorConfigTest {
    @Test fun playFlavorKeepsTodaysBehaviour() {
        assertEquals("com.zenithblue.nativecode", BuildConfig.APPLICATION_ID)
        assertFalse(BuildConfig.FDROID)
        assertTrue(BuildConfig.MONETISATION_ENFORCE)
        assertTrue(BuildConfig.ROOT_FEATURES)
        assertFalse(ProotOnly.ON)
        assertTrue(BuildConfig.TELEMETRY_AVAILABLE)
        assertEquals("/data/data/com.zenithblue.nativecode/files/usr", TermuxHostPaths.PREFIX)
    }
}
