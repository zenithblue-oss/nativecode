package com.zenithblue.nativecode.cliauth

import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/**
 * AI CLI tools from setup_cli_tools.sh + login strategy per vendor docs.
 */
enum class CliLoginStrategy {
    /** Stream login cmd; parse OTP + URL (codex device-auth, kiro). */
    DEVICE_CODE,
    /** Stream login; parse https URL, accept a pasted code on stdin (claude auth login). */
    STREAM_URL,
    /** Paste API key (+ optional open console URL). */
    API_KEY_FORM,
    /** Open terminal with login command (interactive TUI). */
    TERMINAL_GUIDED
}

data class CliToolDef(
    val id: String,
    val displayName: String,
    val bin: String,
    /** Alternate bins to probe (e.g. kiro vs kiro-cli). */
    val altBins: List<String> = emptyList(),
    val strategy: CliLoginStrategy,
    /** Terminal session type key used by MainActivity tool selector. */
    val terminalType: String,
    /** Hide on chroot (codex). */
    val hideOnChroot: Boolean = false,
    /** Console URL for API key / account signup. */
    val consoleUrl: String? = null,
    val envKey: String? = null,
    val subtitle: String = "",
    /** Works without login (free built-in models): cards show no LOGIN prompt unless already signed in. */
    val loginOptional: Boolean = false,
    /** Agent chat wire protocol (item 12); null = terminal only until that runtime ships. */
    val chat: ChatProtocol? = null,
    /** [ChatProtocol.ACP] profile id (argv, env, mode map) when [chat] is ACP. */
    val acpProfile: String? = null,
    /**
     * Lowest CLI version the chat runtime is known to work with (dotted). Only consulted after a version-related chat
     * failure (unknown flag, missing app-server method, protocol mismatch, missing endpoint); never on a healthy start.
     */
    val minChatVersion: String? = null,
    /** Guest shell command that updates this CLI itself (null = delivered by the Components update pack). */
    val updateCmd: String? = null,
    /** Guest shell command that installs (or reinstalls) this CLI from its vendor, as setup_cli_tools.sh does. */
    val installCmd: String? = null,
    /** One short line for the Settings card. */
    val description: String = ""
)

enum class ChatProtocol { OPENCODE_HTTP, CLAUDE_STREAM, CODEX_APP_SERVER, ACP }

object CliToolCatalog {

    val ALL: List<CliToolDef> = listOf(
        CliToolDef(
            id = "claude",
            displayName = tr(R.string.cli_claude_code),
            bin = "claude",
            strategy = CliLoginStrategy.STREAM_URL,
            terminalType = "claude-code",
            consoleUrl = "https://claude.ai/login",
            envKey = "CLAUDE_CODE_OAUTH_TOKEN",
            subtitle = tr(R.string.cli_claude_auth_login_browser_oauth),
            chat = ChatProtocol.CLAUDE_STREAM,
            minChatVersion = "2.1.0",
            updateCmd = "claude update",
            installCmd = "curl -fsSL https://claude.ai/install.sh | bash",
            description = tr(R.string.cli_desc_claude)
        ),
        CliToolDef(
            id = "codex",
            displayName = tr(R.string.cli_openai_codex),
            bin = "codex",
            strategy = CliLoginStrategy.DEVICE_CODE,
            terminalType = "codex",
            hideOnChroot = true,
            consoleUrl = "https://auth.openai.com/codex/device",
            subtitle = tr(R.string.cli_codex_login_device_auth),
            chat = ChatProtocol.CODEX_APP_SERVER,
            minChatVersion = "0.130.0",
            updateCmd = "npm install -g @openai/codex@latest",
            installCmd = "npm install -g @openai/codex@latest",
            description = tr(R.string.cli_desc_codex)
        ),
        CliToolDef(
            id = "qwen",
            displayName = tr(R.string.cli_qwen_code),
            bin = "qwen",
            strategy = CliLoginStrategy.API_KEY_FORM,
            terminalType = "qwen-code",
            consoleUrl = "https://modelstudio.console.alibabacloud.com/?tab=coding-plan#/efm/coding-plan-index",
            envKey = "BAILIAN_CODING_PLAN_API_KEY",
            subtitle = tr(R.string.cli_api_key_oauth_free_tier),
            updateCmd = "npm install -g @qwen-code/qwen-code@latest",
            installCmd = "npm install -g @qwen-code/qwen-code@latest",
            description = tr(R.string.cli_desc_qwen)
        ),
        CliToolDef(
            id = "opencode",
            displayName = "OpenCode",
            bin = "opencode",
            strategy = CliLoginStrategy.TERMINAL_GUIDED,
            terminalType = "opencode",
            consoleUrl = "https://opencode.ai/auth",
            subtitle = tr(R.string.cli_opencode_auth_login_providers),
            loginOptional = true,
            chat = ChatProtocol.OPENCODE_HTTP,
            minChatVersion = "2.0.0",
            updateCmd = "opencode upgrade",
            installCmd = "curl -fsSL https://opencode.ai/v2/install | bash",
            description = tr(R.string.cli_desc_opencode)
        ),
        CliToolDef(
            id = "agy",
            displayName = "Antigravity",
            bin = "agy",
            strategy = CliLoginStrategy.STREAM_URL,
            terminalType = "agy",
            consoleUrl = "https://accounts.google.com",
            subtitle = tr(R.string.cli_agy_acp_login_google_oauth),
            chat = ChatProtocol.ACP,
            acpProfile = "agy",
            minChatVersion = "1.4.0",
            updateCmd = "agy update",
            installCmd = "A=~/.local/bin/agy; [ -x \$A ] && mv -f \$A \$A.prev; if curl -fsSL https://antigravity.google/cli/install.sh | bash; then rm -f \$A.prev; else [ -e \$A.prev ] && mv -f \$A.prev \$A; false; fi",
            description = tr(R.string.cli_desc_agy)
        ),
        CliToolDef(
            id = "grok",
            displayName = tr(R.string.cli_grok_build),
            bin = "grok",
            strategy = CliLoginStrategy.DEVICE_CODE,
            terminalType = "grok",
            consoleUrl = "https://console.x.ai",
            envKey = "XAI_API_KEY",
            subtitle = tr(R.string.cli_grok_login_or_xai_api),
            chat = ChatProtocol.ACP,
            acpProfile = "grok",
            minChatVersion = "1.0.50",
            updateCmd = "grok update",
            installCmd = "curl -fsSL https://x.ai/cli/install.sh | bash",
            description = tr(R.string.cli_desc_grok)
        ),
        CliToolDef(
            id = "kiro",
            displayName = tr(R.string.cli_kiro_cli),
            bin = "kiro-cli",
            altBins = listOf("kiro"),
            strategy = CliLoginStrategy.DEVICE_CODE,
            terminalType = "kiro",
            consoleUrl = "https://app.kiro.dev",
            envKey = "KIRO_API_KEY",
            subtitle = tr(R.string.cli_kiro_cli_login_device_browser),
            updateCmd = "kiro-cli update --non-interactive",
            installCmd = "curl -fsSL https://cli.kiro.dev/install | bash",
            description = tr(R.string.cli_desc_kiro)
        )
    )

    fun byId(id: String): CliToolDef? = ALL.find { it.id == id }

    fun forMethod(method: String): List<CliToolDef> =
        if (method == "chroot") ALL.filter { !it.hideOnChroot } else ALL
}
