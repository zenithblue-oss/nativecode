package com.zenithblue.nativecode.entitlements

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Runs the real nc-gate.sh with its absolute guest paths pointed at a temp dir. */
class GuestGateTest {
    private val key = "k3y"
    private val template = File("src/main/assets/gate/nc-gate.sh").readText()
    private lateinit var dir: File
    private lateinit var ent: File
    private lateinit var reg: File

    @Before
    fun setUp() {
        assumeTrue(File("/bin/sh").exists() && File("/usr/bin/sha256sum").exists() || File("/bin/sha256sum").exists())
        dir = Files.createTempDirectory("gate").toFile().also { it.deleteOnExit() }
        ent = File(dir, "entitlement")
        reg = File(dir, "reg")
        File(dir, "real").mkdirs()
        GuestGate.GATED_TOOLS.forEach {
            File(dir, "real/$it").apply { writeText("#!/bin/sh\necho REAL $it \"\$@\"\n"); setExecutable(true) }
        }
    }

    private fun gate(tool: String): File {
        val f = File(dir, "bin/$tool")
        f.parentFile.mkdirs()
        f.writeText(
            template.replace("__NC_GATE_KEY__", key)
                .replace("/etc/nativecode/entitlement", ent.path)
                .replace("/tmp/.nc-ai-sessions", reg.path)
                .replace("/opt/nativecode/gate/bin", f.parent)
        )
        f.setExecutable(true)
        return f
    }

    private fun run(tool: String, vararg args: String, hold: Boolean = false): Pair<Int, String> {
        val p = ProcessBuilder(listOf(gate(tool).path) + args).redirectErrorStream(true)
            .also { it.environment()["PATH"] = "${File(dir, "bin")}:${File(dir, "real")}:/usr/bin:/bin" }
            .start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out.trim()
    }

    private fun pro(until: Long = System.currentTimeMillis() / 1000 + 3600) = ent.writeText(GuestGate.render(true, until, key))
    private fun free() = ent.writeText(GuestGate.render(true, 0, key))

    @Test
    fun renderIsStableAndKeyed() {
        val a = GuestGate.render(true, 100, key)
        assertEquals(a, GuestGate.render(true, 100, key))
        assertTrue(a.contains("pro=1\nexp=100\n") && a.contains("max_free=1") && a.contains("max_pro=5"))
        assertFalse(a == GuestGate.render(true, 100, "other"))
        assertTrue(GuestGate.render(true, 0, key).contains("pro=0"))
    }

    @Test
    fun freeOnlyOpencode() {
        free()
        assertEquals(0 to "REAL opencode x", run("opencode", "x"))
        for (t in listOf("codex", "claude", "qwen", "agy", "grok", "kiro", "kiro-cli", "hermes")) {
            val (code, out) = run(t, "x")
            assertEquals(t, 1, code)
            assertTrue(out, out.contains("requires NativeCode Pro"))
        }
    }

    @Test
    fun proAllowsEveryTool() {
        pro()
        GuestGate.GATED_TOOLS.forEach { assertEquals(it, 0 to "REAL $it x", run(it, "x")) }
    }

    @Test
    fun expiredProIsFree() {
        pro(until = System.currentTimeMillis() / 1000 - 10)
        assertEquals(1, run("codex", "x").first)
    }

    @Test
    fun forgedOrMissingStateIsFree() {
        assertEquals(1, run("codex", "x").first) // no file
        ent.writeText(GuestGate.render(true, System.currentTimeMillis() / 1000 + 3600, "wrong-key"))
        assertEquals(1, run("codex", "x").first) // MAC from another key
        pro(); ent.writeText(ent.readText().replace("pro=1", "pro=1\nenforce=0")) // body edited, MAC stale
        assertEquals(1, run("codex", "x").first)
        ent.writeText("enforce=0\npro=1\nexp=9999999999\n") // hand-written, no MAC
        assertEquals(1, run("codex", "x").first)
    }

    @Test
    fun enforceOffAllowsEverything() {
        ent.writeText(GuestGate.render(false, 0, key))
        assertEquals(0 to "REAL codex x", run("codex", "x"))
    }

    @Test
    fun versionAndHelpPassThrough() {
        free()
        assertEquals(0 to "REAL codex --version", run("codex", "--version"))
    }

    @Test
    fun sessionLimitCountsLiveSessionsOnly() {
        free()
        reg.mkdirs()
        // dead pid, stale: ignored and pruned
        File(reg, "999999").writeText("opencode")
        assertEquals(0, run("opencode", "x").first)
        assertFalse(File(reg, "999999").exists())
        // a live session: this JVM's own pid, cmdline contains "java"
        val me = ProcessHandle.current().pid().toString()
        File(reg, me).writeText("java")
        val (code, out) = run("opencode", "x")
        assertEquals(1, code)
        assertTrue(out, out.contains("AI session limit (1) reached"))
        // Pro raises the limit to 5
        pro()
        assertEquals(0, run("opencode", "x").first)
    }

    @Test
    fun simultaneousStartsNeverExceedTheLimit() {
        free()
        File(dir, "real/opencode").writeText("#!/bin/sh\nsleep 4\n") // a live session holds its slot
        val shim = gate("opencode")
        val procs = (1..6).map {
            ProcessBuilder(shim.path).redirectErrorStream(true)
                .also { it.environment()["PATH"] = "${File(dir, "bin")}:${File(dir, "real")}:/usr/bin:/bin" }
                .start()
        }
        val denied = procs.count { it.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && it.exitValue() == 1 }
        procs.forEach { it.destroyForcibly() }
        assertEquals(5, denied)
        assertFalse(File(reg, ".lock").exists())
    }

    @Test
    fun staleLockIsTakenOver() {
        free()
        File(reg, ".lock").mkdirs() // left by a killed shim
        assertEquals(0 to "REAL opencode x", run("opencode", "x"))
        assertFalse(File(reg, ".lock").exists())
    }
}
