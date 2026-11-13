package com.zenithblue.nativecode.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotLegacyGuestEnvTest {
    @Test
    fun keepsProotDistroEnvAndDropsHostVars() {
        val env = ProotCommandBuilder.legacyGuestEnv(
            mapOf(
                "HOME" to "/home/flux", "USER" to "flux", "TERM" to "xterm-256color",
                "PROOT_LOADER" to "/l", "PROOT_L2S_DIR" to "/r/.l2s", "ANDROID_ROOT" to "/system",
                "PREFIX" to "/host/usr", "TMPDIR" to "/tmp", "LANG" to "C.UTF-8",
                "LD_LIBRARY_PATH" to "/host/lib", "PATH" to "/home/flux/.local/bin:/usr/bin"
            )
        )
        assertEquals("/home/flux", env["HOME"])
        assertEquals("/l", env["PROOT_LOADER"])
        assertEquals("/r/.l2s", env["PROOT_L2S_DIR"])
        assertEquals("/system", env["ANDROID_ROOT"])
        for (k in listOf("PREFIX", "TMPDIR", "LANG", "LD_LIBRARY_PATH")) assertFalse(k, env.containsKey(k))
        assertTrue(env["PATH"]!!.startsWith("/usr/local/sbin:/usr/local/bin:"))
        assertTrue(env["PATH"]!!.endsWith(":${TermuxHostPaths.BIN}"))
    }
}
