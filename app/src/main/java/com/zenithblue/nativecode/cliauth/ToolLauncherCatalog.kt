package com.zenithblue.nativecode.cliauth

import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/**
 * SSOT for Terminal + Workspace tool launcher cards (C6).
 * Prevents Free/Paid/Shell drift between surfaces.
 */
data class LauncherToolDef(
    val type: String,
    val label: String,
    val desc: String
)

object ToolLauncherCatalog {

    val SHELL: List<LauncherToolDef> = listOf(
        LauncherToolDef("shell", tr(R.string.cli_linux_shell), tr(R.string.cli_user_flux)),
        LauncherToolDef("shell-root", tr(R.string.cli_rooted_linux_shell), tr(R.string.cli_user_root))
    )

    val FREE: List<LauncherToolDef> = listOf(
        LauncherToolDef("opencode", "opencode", tr(R.string.cli_open_source))
    )

    val PAID: List<LauncherToolDef> = listOf(
        LauncherToolDef("codex", "codex", tr(R.string.cli_openai_codex)),
        LauncherToolDef("agy", "agy", "Antigravity"),
        LauncherToolDef("claude-code", "claude-code", tr(R.string.cli_claude_code)),
        LauncherToolDef("qwen-code", "qwen-code", tr(R.string.cli_qwen_code)),
        LauncherToolDef("grok", "grok", tr(R.string.cli_grok_cli)),
        LauncherToolDef("kiro", "kiro", tr(R.string.cli_kiro_cli))
    )

    fun freeTools(): List<LauncherToolDef> = FREE

    fun paidTools(method: String): List<LauncherToolDef> =
        if (method == "chroot") PAID.filter { it.type != "codex" } else PAID

    fun shellTools(): List<LauncherToolDef> = SHELL

    fun isAiToolType(type: String): Boolean =
        type != "shell" && type != "shell-root"

    /** Dropdown / open-with pairs. */
    fun openWithPairs(method: String, showAi: Boolean): List<Pair<String, String>> {
        val shells = listOf(
            tr(R.string.cli_linux_shell) to "shell",
            tr(R.string.cli_rooted_linux_shell) to "shell-root"
        )
        if (!showAi) return shells
        val ai = listOf(
            "opencode" to "opencode",
            "codex" to "codex",
            "agy" to "agy",
            "claude-code" to "claude-code",
            "qwen-code" to "qwen-code",
            "grok" to "grok",
            "kiro" to "kiro"
        ).let { list ->
            if (method == "chroot") list.filter { it.second != "codex" } else list
        }
        return shells + ai
    }
}
