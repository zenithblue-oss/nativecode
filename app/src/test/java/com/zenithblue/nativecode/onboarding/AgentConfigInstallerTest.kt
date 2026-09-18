package com.zenithblue.nativecode.onboarding

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigInstallerTest {

    @Test
    fun testInstallToRootfsDeploysAllAgentConfigsAndSystemProfile() {
        val tempDir = Files.createTempDirectory("nc_test_rootfs").toFile()
        try {
            File(tempDir, "etc").mkdirs()
            File(tempDir, "home/flux").mkdirs()
            File(tempDir, "root").mkdirs()

            val success = AgentConfigInstaller.installToRootfs(null, tempDir)
            assertTrue("Installation should succeed", success)

            // System global checks
            val etcAgents = File(tempDir, "etc/AGENTS.md")
            assertTrue("etc/AGENTS.md should exist", etcAgents.isFile)
            assertTrue(etcAgents.readText().contains("nativecode-agent"))

            val profileScript = File(tempDir, "etc/profile.d/nativecode-agent.sh")
            assertTrue("Profile script should exist", profileScript.isFile)
            assertTrue(profileScript.readText().contains("NATIVECODE_AGENT_SOCKET"))

            // User checks for both flux and root
            for (userHome in listOf(File(tempDir, "home/flux"), File(tempDir, "root"))) {
                // Universal home files
                assertTrue(File(userHome, "AGENTS.md").isFile)
                assertTrue(File(userHome, "CLAUDE.md").isFile)
                assertTrue(File(userHome, "GEMINI.md").isFile)
                assertTrue(File(userHome, "QWEN.md").isFile)

                // Antigravity (agy)
                val agyConfig = File(userHome, ".config/antigravity/AGENTS.md")
                assertTrue(agyConfig.isFile)
                assertTrue(agyConfig.readText().contains("gpu-launch"))
                assertTrue(File(userHome, ".antigravity/AGENTS.md").isFile)
                assertTrue(File(userHome, ".gemini/AGENTS.md").isFile)
                assertTrue(File(userHome, ".gemini/antigravity-cli/AGENTS.md").isFile)

                // OpenCode
                val opencodeConfig = File(userHome, ".config/opencode/AGENTS.md")
                assertTrue(opencodeConfig.isFile)
                assertTrue(File(userHome, ".opencode/AGENTS.md").isFile)
                val opencodeJson = File(userHome, ".config/opencode/opencode.jsonc")
                assertTrue(opencodeJson.isFile)
                assertTrue(opencodeJson.readText().contains("instructions"))

                // Claude Code
                val claudeConfig = File(userHome, ".claude/CLAUDE.md")
                assertTrue(claudeConfig.isFile)
                assertTrue(File(userHome, ".config/claude/CLAUDE.md").isFile)

                // OpenAI Codex
                val codexConfig = File(userHome, ".codex/instructions.md")
                assertTrue(codexConfig.isFile)
                assertTrue(File(userHome, ".codex/AGENTS.md").isFile)
                assertTrue(File(userHome, ".config/codex/instructions.md").isFile)

                // Grok Build
                assertTrue(File(userHome, ".grok/AGENTS.md").isFile)
                assertTrue(File(userHome, ".config/grok/AGENTS.md").isFile)

                // Qwen Code
                assertTrue(File(userHome, ".qwen/AGENTS.md").isFile)
                assertTrue(File(userHome, ".config/qwen/AGENTS.md").isFile)
                assertTrue(File(userHome, ".qwen/rules/AGENTS.md").isFile)

                // Kiro CLI
                assertTrue(File(userHome, ".kiro/AGENTS.md").isFile)
                assertTrue(File(userHome, ".config/kiro/AGENTS.md").isFile)
            }

            // Idempotency: second run should also succeed without error
            val secondRun = AgentConfigInstaller.installToRootfs(null, tempDir)
            assertTrue("Second run should succeed", secondRun)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
