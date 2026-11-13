package com.zenithblue.nativecode.agent.chat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PipeProcessTest {
    private val lines: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val exited = CountDownLatch(1)
    @Volatile private var code = Int.MIN_VALUE
    @Volatile private var tail = ""

    @Before fun shell() = assumeTrue(File("/bin/sh").canExecute())

    private fun spawn(script: String, env: Map<String, String> = mapOf("PATH" to "/usr/bin:/bin")) =
        PipeProcess(listOf("/bin/sh", "-c", script), env, File(System.getProperty("java.io.tmpdir")),
            onLine = { lines += it }, onExit = { c, t -> code = c; tail = t; exited.countDown() })
            .also { it.start() }

    private fun awaitExit(sec: Long = 10) = assertTrue("no exit", exited.await(sec, TimeUnit.SECONDS))

    @Test fun tornLinesUtf8SplitAndNoise() {
        spawn("printf '{\"a\":1}\\n{\"b\":'; sleep 0.2; printf '2}\\r\\nnoise\\n\\303'; sleep 0.2; printf '\\251\\nlast'")
        awaitExit()
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}", "noise", "é", "last"), lines.toList())
        assertEquals(0, code)
    }

    @Test fun oneMebibyteLine() {
        spawn("head -c 1048576 /dev/zero | tr '\\0' a; echo")
        awaitExit()
        assertEquals(1, lines.size)
        assertEquals(1048576, lines[0].length)
    }

    @Test fun stderrFloodDoesNotBlockStdoutAndIsCapped() {
        spawn("head -c 3000000 /dev/zero | tr '\\0' e >&2; echo NativeCode: denied >&2; echo done")
        awaitExit()
        assertEquals(listOf("done"), lines.toList())
        assertEquals(PipeProcess.STDERR_CAP, tail.length)
        assertTrue(tail.trimEnd().endsWith("NativeCode: denied"))
    }

    @Test fun exitCodeAndStderrTail() {
        spawn("echo 'NativeCode: AI session limit (1) reached.' >&2; exit 1")
        awaitExit()
        assertEquals(1, code)
        assertTrue(tail, tail.contains("AI session limit"))
    }

    @Test fun envIsExactlyTheGivenMap() {
        spawn("env", mapOf("PATH" to "/usr/bin:/bin", "NC_X" to "1"))
        awaitExit()
        assertTrue(lines.contains("NC_X=1"))
        assertFalse(lines.toString(), lines.any { it.startsWith("HOME=") || it.startsWith("LD_LIBRARY_PATH=") })
    }

    @Test fun concurrentWritesNeverInterleaveAndCloseSendsEof() {
        val p = spawn("cat")
        val big = "x".repeat(20_000) // larger than a pipe write is atomic for
        val writers = (1..2).map { t ->
            Thread { repeat(100) { assertTrue(p.writeLine("{\"t\":$t,\"i\":$it,\"pad\":\"$big\"}")) } }.apply { start() }
        }
        writers.forEach { it.join() }
        p.close()
        awaitExit()
        assertEquals(0, code)
        assertEquals(200, lines.size)
        assertTrue(lines.all { it.startsWith("{\"t\":") && it.endsWith("\"$big\"}") })
        assertFalse(p.writeLine("after close"))
    }

    @Test fun closeEscalatesToKillWhenEofAndTermAreIgnored() {
        val p = spawn("trap '' TERM; while :; do sleep 0.1; done")
        Thread.sleep(200)
        val t0 = System.nanoTime()
        p.close()
        awaitExit(10)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms in 3_500..8_000)
        assertEquals(137, code) // SIGKILL
        assertFalse(p.isAlive)
    }

    /** A grandchild holding stdout (the orphaned guest `opencode serve` under a killed PRoot) must die too, or onExit never fires. */
    @Test fun killTakesDescendantsHoldingStdout() {
        val p = spawn("sh -c 'trap \"\" TERM; sleep 60' & wait")
        Thread.sleep(300)
        p.kill()
        awaitExit(5)
        assertFalse(p.isAlive)
    }
}
