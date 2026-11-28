package com.zenithblue.nativecode.terminal

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Runs the generated launch_tool.sh with stub CLIs and checks the argv each one receives. */
class LaunchToolScriptTest {

    /** [oldCodex]: a codex 0.153 stand-in that rejects --no-daemon (exit 2) and does not list it in --help. */
    private fun run(vararg args: String, oldCodex: Boolean = false): String {
        assumeTrue(File("/bin/bash").canExecute())
        val home = Files.createTempDirectory("lt").toFile()
        try {
            val bin = File(home, ".local/bin").apply { mkdirs() }
            for (tool in listOf("opencode", "codex")) {
                val body = if (tool == "codex") {
                    "case \"\$1\" in\n  --help) " + (if (oldCodex) "echo usage" else "echo '      --no-daemon'") + " ;;\n" +
                        "  --no-daemon) " + (if (oldCodex) "echo 'error: unexpected argument' >&2; exit 2" else "echo \"codex \$*\"") + " ;;\n  *) echo \"codex \$*\" ;;\nesac\n"
                } else "echo \"$tool \$*\"\n"
                File(bin, tool).apply { writeText("#!/bin/sh\n$body"); setExecutable(true) }
            }
            val script = File(home, "launch_tool.sh")
            script.writeText(ChrootCommandBuilder.launcherScript().replace("export HOME=/home/flux", "export HOME=${home.path}"))
            val p = ProcessBuilder(listOf("/bin/bash", script.path) + args).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            assertEquals(out, 0, p.waitFor())
            return out
        } finally {
            home.deleteRecursively()
        }
    }

    @Test fun opencodeTabGetsAuto() = assertEquals("opencode --auto", run("opencode"))
    @Test fun opencodeServeStaysPlain() =
        assertEquals("opencode serve --hostname=127.0.0.1 --port=4096", run("opencode", "serve", "--hostname=127.0.0.1", "--port=4096"))
    @Test fun opencodeAcpStaysPlain() = assertEquals("opencode acp", run("opencode", "acp"))
    @Test fun codexTabGetsNoDaemon() = assertEquals("codex --no-daemon", run("codex"))
    @Test fun codexResumeGetsNoDaemon() = assertEquals("codex --no-daemon resume abc", run("codex", "resume", "abc"))
    @Test fun codexForkGetsNoDaemon() = assertEquals("codex --no-daemon fork abc", run("codex", "fork", "abc"))
    @Test fun codexAppServerStaysPlain() = assertEquals("codex app-server", run("codex", "app-server"))
    @Test fun codexLoginStaysPlain() = assertEquals("codex login", run("codex", "login"))
    @Test fun oldCodexTabStaysPlain() = assertEquals("codex", run("codex", oldCodex = true))
    @Test fun oldCodexResumeStaysPlain() = assertEquals("codex resume abc", run("codex", "resume", "abc", oldCodex = true))
    @Test fun versionIsV8() = assertEquals(true, ChrootCommandBuilder.launcherScript().contains("nativecode-launch-tool v8"))
}
