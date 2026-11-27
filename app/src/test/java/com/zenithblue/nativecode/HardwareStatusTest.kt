package com.zenithblue.nativecode

import com.zenithblue.nativecode.HardwareStatus.Driver
import com.zenithblue.nativecode.HardwareStatus.Host
import com.zenithblue.nativecode.HardwareStatus.Rendezvous
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareStatusTest {
    @Test fun driver() {
        assertEquals(Driver.NOT_APPLICABLE, HardwareStatus.driver(false, "abc", true))
        assertEquals(Driver.MISSING, HardwareStatus.driver(true, null, false))
        assertEquals(Driver.UPDATE, HardwareStatus.driver(true, "abc", true))
        assertEquals(Driver.UP_TO_DATE, HardwareStatus.driver(true, "abc", false))
    }

    @Test fun shortSha() {
        assertEquals("none", HardwareStatus.shortSha(null))
        assertEquals("0123abcd", HardwareStatus.shortSha("0123abcdffff"))
    }

    @Test fun rendezvous() {
        assertEquals(Rendezvous.MISSING, HardwareStatus.rendezvous(null, "{}"))
        assertEquals(Rendezvous.OK, HardwareStatus.rendezvous("{}", "{}"))
        assertEquals(Rendezvous.STALE, HardwareStatus.rendezvous("{\"host_uid\":1}", "{\"host_uid\":2}"))
    }

    @Test fun host() {
        assertEquals(Host.MISSING, HardwareStatus.host(false, false, false))
        assertEquals(Host.NOT_BUNDLED, HardwareStatus.host(false, false, true))
        assertEquals(Host.RUNNING, HardwareStatus.host(true, true, true))
        assertEquals(Host.STOPPED, HardwareStatus.host(true, false, false))
    }

    @Test fun thermal() {
        assertEquals("NONE", HardwareStatus.thermal(0))
        assertEquals("SHUTDOWN", HardwareStatus.thermal(6))
        assertEquals("UNKNOWN", HardwareStatus.thermal(-1))
        assertEquals("UNKNOWN", HardwareStatus.thermal(9))
    }
}
