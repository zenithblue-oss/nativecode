package com.zenithblue.nativecode.desktop

import com.zenithblue.nativecode.desktop.ClipSync.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun statusMapping() {
        assertEquals(Status.NOT_INSTALLED, ClipSync.status(hasGi = false, disabled = false))
        assertEquals(Status.NOT_INSTALLED, ClipSync.status(hasGi = false, disabled = true))
        assertEquals(Status.ON, ClipSync.status(hasGi = true, disabled = false))
        assertEquals(Status.OFF, ClipSync.status(hasGi = true, disabled = true))
    }

    private fun file(root: File, rel: String) = File(root, rel).apply { parentFile!!.mkdirs(); writeText("") }

    @Test fun hasGiNeedsPythonAndBindings() {
        val root = tmp.newFolder()
        assertFalse(ClipSync.hasGi(root))
        file(root, "usr/bin/python3")
        assertFalse(ClipSync.hasGi(root))
        file(root, "usr/lib/python3.14/site-packages/other/__init__.py")
        assertFalse(ClipSync.hasGi(root))
        file(root, "usr/lib/python3.14/site-packages/gi/__init__.py")
        assertTrue(ClipSync.hasGi(root))
    }
}
