package com.zenithblue.nativecode.onboarding

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LinuxGuestDefaultsTest {
    private fun path(start: String): List<String> {
        val f = File.createTempFile("cli-path", ".sh").apply { writeText(LinuxGuestDefaults.cliPathProfile); deleteOnExit() }
        val p = ProcessBuilder("sh", "-c", ". ${f.path}; echo \"\$PATH\"; echo \"\$NPM_CONFIG_PREFIX\"; echo \"\$NPM_CONFIG_ALLOW_SCRIPTS\"")
            .also { it.environment()["HOME"] = "/home/x"; it.environment()["PATH"] = start }
            .start()
        val out = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        return out
    }

    @Test
    fun cliPathsFirstOnceAndIdempotent() {
        assumeTrue(File("/bin/sh").exists())
        val want = "/opt/nativecode/gate/bin:/home/x/.opencode/bin:/home/x/.local/bin:/usr/bin:/bin"
        val out = path("/usr/bin:/bin")
        assertEquals(want, out[0])
        assertEquals("/home/x/.local", out[1])
        // npm 12 rejects NPM_CONFIG_ALLOW_SCRIPTS in project installs (`npm ci` fails with EALLOWSCRIPTS)
        assertEquals("", out[2])
        // /etc/profile.d/termux-profile.sh already put ~/.local/bin first
        assertEquals(want, path("/home/x/.local/bin:/usr/bin:/bin")[0])
        assertEquals(want, path(want)[0])
    }

    @Test
    fun profileHasNoCommandSubstitution() {
        // zsh 5.9 in the 1.7.x rootfs hangs on $(...) and `...`
        assertFalse(LinuxGuestDefaults.cliPathProfile.contains("\$("))
        assertFalse(LinuxGuestDefaults.cliPathProfile.contains("`"))
    }

    @Test
    fun fileEditsAreIdempotentAndKeepUserLines() {
        val root = Files.createTempDirectory("rootfs").toFile()
        try {
            File(root, "usr/bin").mkdirs(); File(root, "usr/bin/zsh").writeText("x")
            File(root, "etc/zsh").mkdirs(); File(root, "etc/zsh/zshenv").writeText("export USER_LINE=1")
            File(root, "home/flux/.local/bin").mkdirs()
            val bin = File(root, "home/flux/.local/bin")
            File(bin, "agy").writeText("live"); File(bin, "agy.123.old").writeText("old")
            File(bin, "agy.keep").writeText("k"); File(bin, "agy.old").writeText("k2")
            File(root, "home/flux/.config/fluxlinux").mkdirs()
            val env = File(root, "home/flux/.config/fluxlinux/cli-tools.env")
            env.writeText("# mine\nexport PATH=\"\$HOME/.local/bin:\$HOME/.grok/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\"\nexport X=1\n")

            LinuxGuestDefaults.applyToRootfs(root)
            val snapshot = root.walk().filter { it.isFile }.associate { it.path to it.readText() }
            LinuxGuestDefaults.applyToRootfs(root) // second run changes nothing
            assertEquals(snapshot, root.walk().filter { it.isFile }.associate { it.path to it.readText() })

            assertTrue(File(root, "etc/profile.d/zz-nativecode-cli.sh").isFile)
            val zshenv = File(root, "etc/zsh/zshenv").readText()
            assertTrue(zshenv.startsWith("export USER_LINE=1\n"))
            assertEquals(1, zshenv.lines().count { it.contains("zz-nativecode-cli") })
            assertTrue(env.readText().contains("# mine\nexport PATH=\"\$HOME/.opencode/bin:\$HOME/.local/bin:"))
            assertTrue(env.readText().contains("export X=1"))
            assertTrue(File(root, "home/flux/.zshrc").isFile) // wizard stopper created
            assertTrue(File(bin, "agy").isFile)
            assertFalse(File(bin, "agy.123.old").exists())
            assertTrue(File(bin, "agy.keep").isFile && File(bin, "agy.old").isFile)

            File(root, "home/flux/.zshrc").writeText("# user\n") // user file is never overwritten
            LinuxGuestDefaults.applyToRootfs(root)
            assertEquals("# user\n", File(root, "home/flux/.zshrc").readText())
            assertEquals("prefix=/home/flux/.local\n", File(root, "home/flux/.npmrc").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    /** 1.8.0 rootfs already has the new PATH line, ~/.npmrc and (user) shell files: nothing may be rewritten. */
    @Test
    fun newRootfsIsLeftAlone() {
        val root = Files.createTempDirectory("rootfs18").toFile()
        try {
            File(root, "usr/bin").mkdirs(); File(root, "usr/bin/zsh").writeText("x")
            File(root, "home/flux/.config/fluxlinux").mkdirs()
            val env = File(root, "home/flux/.config/fluxlinux/cli-tools.env")
            val newEnv = "# Managed by NativeCode prebuilt rootfs\n" +
                "export PATH=\"\$HOME/.opencode/bin:\$HOME/.local/bin:\$HOME/.grok/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\"\n"
            env.writeText(newEnv)
            File(root, "home/flux/.npmrc").writeText("prefix=/home/flux/.local\nfoo=bar\n")
            File(root, "home/flux/.opencode/bin").mkdirs()
            File(root, "home/flux/.opencode/bin/opencode").writeText("v2")

            LinuxGuestDefaults.applyToRootfs(root)
            assertEquals(newEnv, env.readText())
            assertEquals("prefix=/home/flux/.local\nfoo=bar\n", File(root, "home/flux/.npmrc").readText())
            assertEquals("v2", File(root, "home/flux/.opencode/bin/opencode").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
