package com.zenithblue.nativecode.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortProbeTest {
    @Test
    fun findsLoopbackDevServerUrls() {
        val vite = "  VITE v5.4.0  ready in 312 ms\n\n  ➜  Local:   http://localhost:5173/\n  ➜  Network: use --host to expose"
        assertEquals(listOf("http://127.0.0.1:5173/"), PortProbe.findUrls(vite))
        assertEquals(listOf("http://127.0.0.1:8000/"), PortProbe.findUrls("Serving HTTP on 0.0.0.0 port 8000 (http://0.0.0.0:8000/) ..."))
        assertEquals(listOf("http://127.0.0.1:3000/api"), PortProbe.findUrls("open http://127.0.0.1:3000/api."))
        assertEquals(emptyList<String>(), PortProbe.findUrls("see https://example.com:8080/ and localhost without scheme"))
    }

    @Test
    fun onlyLoopbackLoadsInApp() {
        assertTrue(PortProbe.isLoopback("http://127.0.0.1:5173/"))
        assertTrue(PortProbe.isLoopback("http://localhost:3000/x"))
        assertFalse(PortProbe.isLoopback("https://example.com/"))
        assertFalse(PortProbe.isLoopback("http://127.0.0.1.evil.com/"))
        assertFalse(PortProbe.isLoopback("file:///data/data/x"))
        assertFalse(PortProbe.isLoopback("javascript:alert(1)"))
        assertEquals(5173, PortProbe.portOf("http://127.0.0.1:5173/"))
    }
}
