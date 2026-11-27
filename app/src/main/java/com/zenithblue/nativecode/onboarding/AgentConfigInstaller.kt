package com.zenithblue.nativecode.onboarding

import android.content.Context
import android.util.Log
import com.zenithblue.nativecode.terminal.ChrootCommandBuilder
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import java.io.File

/**
 * Deploys agent environment instructions, CLI configurations, and hardware bridge
 * documentation into guest rootfs homes (flux, root) and system paths.
 *
 * Verified target paths for CLI tools:
 * - Antigravity (agy): ~/.config/antigravity/AGENTS.md, ~/.antigravity/AGENTS.md, ~/.gemini/AGENTS.md
 * - OpenCode (opencode): ~/.config/opencode/AGENTS.md, ~/.opencode/AGENTS.md, ~/.config/opencode/opencode.jsonc
 * - Claude Code (claude): ~/.claude/CLAUDE.md, ~/.config/claude/CLAUDE.md
 * - OpenAI Codex (codex): ~/.codex/instructions.md, ~/.codex/AGENTS.md, ~/.config/codex/instructions.md
 * - Grok Build (grok): ~/.grok/AGENTS.md, ~/.config/grok/AGENTS.md
 * - Qwen Code (qwen): ~/.qwen/AGENTS.md, ~/.config/qwen/AGENTS.md, ~/.qwen/rules/AGENTS.md
 * - Kiro CLI (kiro): ~/.kiro/AGENTS.md, ~/.config/kiro/AGENTS.md
 * - Universal Homes: ~/AGENTS.md, ~/CLAUDE.md, ~/GEMINI.md, ~/QWEN.md
 * - System: /etc/AGENTS.md, /etc/profile.d/nativecode-agent.sh
 */
object AgentConfigInstaller {

    private const val TAG = "AgentConfigInstaller"
    private const val ASSET_AGENTS = "agents/AGENTS.md"
    private const val ASSET_CLAUDE = "agents/CLAUDE.md"
    private const val ASSET_CODEX = "agents/instructions.md"

    private const val PROFILE_AGENT_SCRIPT = """#!/bin/sh
# NativeCode Agent Bridge & Hardware Environment
export NATIVECODE_ENV="proot"
export NATIVECODE_AGENT_SOCKET="@nativecode.agent.v1"
export NATIVECODE_MEDIA_SOCKET="@nativecode.media.v1"
export NATIVECODE_AGENT_BRIDGE="1"
export NATIVECODE_AGENT_CLI="/usr/local/bin/nativecode-agent"
export NATIVECODE_MEDIA_CLI="/usr/local/bin/nativecode-media"
export NATIVECODE_GPU_LAUNCH="/usr/local/bin/gpu-launch"
"""

    private fun logInfo(msg: String) {
        try {
            Log.i(TAG, msg)
        } catch (_: Throwable) {
            println("[$TAG] $msg")
        }
    }

    private fun logWarn(msg: String, t: Throwable? = null) {
        try {
            Log.w(TAG, msg, t)
        } catch (_: Throwable) {
            println("[$TAG] $msg: ${t?.message}")
        }
    }

    fun install(context: Context): Boolean {
        var deployedAny = false
        val prootRootfs = ProjectPathResolver.prootRootfsDir(context)
        if (prootRootfs.isDirectory && (File(prootRootfs, "etc").exists() || File(prootRootfs, "bin").exists())) {
            if (installToRootfs(context, prootRootfs)) {
                deployedAny = true
            }
        }

        // Also install to chroot rootfs if present and writable
        val chrootRootfs = File(ChrootCommandBuilder.CHROOT_PATH)
        if (chrootRootfs.isDirectory && chrootRootfs.canWrite() && (File(chrootRootfs, "etc").exists() || File(chrootRootfs, "bin").exists())) {
            if (installToRootfs(context, chrootRootfs)) {
                deployedAny = true
            }
        }

        return deployedAny
    }

    internal fun installToRootfs(context: Context?, rootfs: File): Boolean {
        return runCatching {
            val agentsMd = readAssetOrFallback(context, ASSET_AGENTS, DEFAULT_AGENTS_MD)
            val claudeMd = readAssetOrFallback(context, ASSET_CLAUDE, DEFAULT_CLAUDE_MD)
            val codexMd = readAssetOrFallback(context, ASSET_CODEX, DEFAULT_CODEX_MD)

            // The guest's `/run/nativecode_bridge.json` (bridge socket names for this app's uid).
            com.zenithblue.nativecode.agent.BridgeRendezvous.writeToRootfs(rootfs)

            // 1. System global files
            val etcDir = File(rootfs, "etc")
            if (etcDir.isDirectory || etcDir.mkdirs()) {
                writeIfChanged(File(etcDir, "AGENTS.md"), agentsMd)
                val profileDir = File(etcDir, "profile.d").also { it.mkdirs() }
                val profileScript = File(profileDir, "nativecode-agent.sh")
                writeIfChanged(profileScript, PROFILE_AGENT_SCRIPT)
                profileScript.setExecutable(true, false)
            }

            // System CLI binaries: update /usr/local/bin/nativecode-agent and /usr/local/bin/nativecode-media if assets present
            val localBinDir = File(rootfs, "usr/local/bin")
            if (localBinDir.isDirectory || localBinDir.mkdirs()) {
                val agentScript = File(localBinDir, "nativecode-agent")
                val scriptContent = readAssetOrFallback(context, "agents/nativecode-agent", "")
                if (scriptContent.isNotBlank()) {
                    writeIfChanged(agentScript, scriptContent)
                    agentScript.setExecutable(true, false)
                }

                val mediaScript = File(localBinDir, "nativecode-media")
                val mediaContent = readAssetOrFallback(context, "agents/nativecode-media", "")
                if (mediaContent.isNotBlank()) {
                    writeIfChanged(mediaScript, mediaContent)
                    mediaScript.setExecutable(true, false)
                }
            }

            // 2. User homes: flux and root
            val userHomes = listOf(
                File(rootfs, "home/flux"),
                File(rootfs, "root")
            )

            for (home in userHomes) {
                if (!home.exists()) {
                    home.mkdirs()
                }

                // Global home markdown files (universal standards)
                writeIfChanged(File(home, "AGENTS.md"), agentsMd)
                writeIfChanged(File(home, "CLAUDE.md"), claudeMd)
                writeIfChanged(File(home, "GEMINI.md"), agentsMd)
                writeIfChanged(File(home, "QWEN.md"), agentsMd)

                // Antigravity (agy)
                writeIfChanged(File(home, ".config/antigravity/AGENTS.md"), agentsMd)
                writeIfChanged(File(home, ".antigravity/AGENTS.md"), agentsMd)
                writeIfChanged(File(home, ".gemini/AGENTS.md"), agentsMd)
                writeIfChanged(File(home, ".gemini/antigravity-cli/AGENTS.md"), agentsMd)

                // OpenCode
                writeIfChanged(File(home, ".config/opencode/AGENTS.md"), agentsMd)
                writeIfChanged(File(home, ".opencode/AGENTS.md"), agentsMd)
                configureOpenCodeJsonc(home)

                // Claude Code
                writeIfChanged(File(home, ".claude/CLAUDE.md"), claudeMd)
                writeIfChanged(File(home, ".config/claude/CLAUDE.md"), claudeMd)

                // OpenAI Codex
                writeIfChanged(File(home, ".codex/instructions.md"), codexMd)
                writeIfChanged(File(home, ".codex/AGENTS.md"), agentsMd)
                writeIfChanged(File(home, ".config/codex/instructions.md"), codexMd)

                // Grok Build
                writeIfChanged(File(home, ".grok/AGENTS.md"), agentsMd)
                writeIfChanged(File(home, ".config/grok/AGENTS.md"), agentsMd)

                // Qwen Code
                writeIfChanged(File(home, ".qwen/AGENTS.md"), agentsMd)
                writeIfChanged(File(home, ".config/qwen/AGENTS.md"), agentsMd)
                writeIfChanged(File(home, ".qwen/rules/AGENTS.md"), agentsMd)

                // Kiro CLI
                writeIfChanged(File(home, ".kiro/AGENTS.md"), agentsMd)
                writeIfChanged(File(home, ".config/kiro/AGENTS.md"), agentsMd)
            }

            logInfo("Agent configurations deployed successfully to rootfs at ${rootfs.absolutePath}")
            true
        }.onFailure {
            logWarn("Could not deploy agent configurations", it)
        }.getOrDefault(false)
    }

    private fun configureOpenCodeJsonc(home: File) {
        val opencodeDir = File(home, ".config/opencode").also { it.mkdirs() }
        val configFile = File(opencodeDir, "opencode.jsonc")
        if (!configFile.exists()) {
            val content = """
            {
              "${"$"}schema": "https://opencode.ai/config.json",
              "instructions": [
                "AGENTS.md"
              ]
            }
            """.trimIndent() + "\n"
            writeIfChanged(configFile, content)
        } else {
            val current = configFile.readText()
            if (!current.contains("AGENTS.md")) {
                if (current.trim().endsWith("}")) {
                    val trimmed = current.trim()
                    val beforeClose = trimmed.substring(0, trimmed.length - 1).trimEnd()
                    val comma = if (beforeClose.endsWith("{") || beforeClose.endsWith(",")) "" else ","
                    val updated = "$beforeClose$comma\n  \"instructions\": [\n    \"AGENTS.md\"\n  ]\n}\n"
                    writeIfChanged(configFile, updated)
                }
            }
        }
    }

    private fun readAssetOrFallback(context: Context?, assetPath: String, fallback: String): String {
        if (context == null) return fallback
        return runCatching {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }.getOrDefault(fallback)
    }

    private fun writeIfChanged(file: File, content: String) {
        if (file.isFile && file.readText() == content) return
        file.parentFile?.mkdirs()
        file.writeText(content)
        file.setReadable(true, false)
    }

    private val DEFAULT_AGENTS_MD = """
# NativeCode Agent Environment & Hardware Bridge Guidelines

You are executing inside a rootless Linux PRoot guest environment hosted within the **NativeCode** Android application on an ARM64 device.

## 1. Environment & Container Architecture
- **OS:** Alpine / Alpaquita Linux (aarch64).
- **Users:** `flux` (/home/flux) for projects & CLI tools; `root` (/root) for system tasks.
- **Network:** Shared directly with Android host (localhost / 127.0.0.1). Servers on unprivileged ports (3000, 8080) are reachable in host Android browsers.
- **Restrictions:** No systemd; no raw HAL nodes (/dev/binder, /dev/video*, /dev/dri/card0); no kernel modules.

## 2. Host Agent Bridge (`nativecode-agent`)
CLI tool: `/usr/local/bin/nativecode-agent`
- Web preview dialog: `nativecode-agent open-link --url "http://localhost:3000" --title "Web Preview"`
- On-device APK testing: `nativecode-agent install-apk --path "path/to/app-debug.apk"`
- Dismiss active on-screen alert: `nativecode-agent dismiss-alert` (or `dismiss`)
- Single active alert rule: Only ONE alert card can be active on screen at a time. If an alert is already on screen, new requests return `{"ok": false, "error": "alert_already_active"}`. Call `dismiss-alert` or wait for the user to close it.
- System notifications: `nativecode-agent notify --title "Build Done" --message "Success" --priority high`
- System toasts: `nativecode-agent toast --text "Running tests..."`
- Hardware telemetry: `nativecode-agent telemetry`
- Progress indicator: `nativecode-agent status --key "task" --text "Progress" --progress 50`

## 3. GPU Hardware Acceleration (`gpu-launch`)
CLI tool: `/usr/local/bin/gpu-launch`
- Snapdragon/Adreno: Turnip KGSL direct rendering (`gpu-launch --turnip <cmd>`)
- Mali/Dimensity/Exynos: Venus virtio socket (`gpu-launch --venus <cmd>`)
- Auto probe: `gpu-launch --auto <cmd>`
- Diagnostics: `gpu-launch --diagnose`

## 4. VPU MediaCodec Hardware Video (`nativecode-media`)
CLI tool: `/usr/local/bin/nativecode-media`
- Capabilities: `nativecode-media capabilities`
- Decode benchmark: `nativecode-media benchmark_file --path "<video_path>"`
- Live FFmpeg streaming encode:
  `ffmpeg -f lavfi -i testsrc=duration=10:size=1280x720:rate=30 -pix_fmt nv12 -f rawvideo - | nativecode-media encode_stream --width 1280 --height 720 --output "<out.mp4>"`
""".trimIndent() + "\n"

    private val DEFAULT_CLAUDE_MD = """
# Claude Code Guidelines for NativeCode PRoot Linux

You are running inside NativeCode's rootless PRoot Linux environment (ARM64 Alpine/Alpaquita).

## Host Interaction via Agent Bridge (`nativecode-agent`)
- Dev server previews: `nativecode-agent open-link --url "http://localhost:3000" --title "Web Preview"`
- APK testing: `nativecode-agent install-apk --path "path/to/app-debug.apk"`
- Dismiss on-screen alert: `nativecode-agent dismiss-alert` (only 1 active alert on screen allowed)
- Notifications: `nativecode-agent notify --title "Build Complete" --message "Success" --priority high`
- Toasts: `nativecode-agent toast --text "Running tests..."`
- Telemetry: `nativecode-agent telemetry`

## Hardware Acceleration
- GPU: `gpu-launch --auto <cmd>` (Turnip KGSL for Adreno, Venus virtio socket for Mali)
- VPU Video: `nativecode-media capabilities` (MediaCodec hardware decode/encode)

Refer to ~/AGENTS.md for complete technical documentation.
""".trimIndent() + "\n"

    private val DEFAULT_CODEX_MD = """
# OpenAI Codex Instructions for NativeCode PRoot Linux

Environment: Rootless PRoot Linux (ARM64) inside NativeCode on Android.
Default User: flux (/home/flux).

## Host Agent Bridge (`nativecode-agent`)
- Open local web servers in host browser: `nativecode-agent open-link --url "http://localhost:3000"`
- Install built APKs without ADB: `nativecode-agent install-apk --path "path/to/app-debug.apk"`
- Dismiss active on-screen alert: `nativecode-agent dismiss-alert` (only 1 active alert allowed)
- System notifications: `nativecode-agent notify --title "Title" --message "Message"`
- System toasts: `nativecode-agent toast --text "Message"`
- Telemetry: `nativecode-agent telemetry`

## Hardware Acceleration
- GPU acceleration: `gpu-launch --auto <cmd>`
- VPU video acceleration: `nativecode-media capabilities`

Refer to ~/AGENTS.md for complete details.
""".trimIndent() + "\n"
}
