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
    val acpProfile: String? = null
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
            chat = ChatProtocol.CLAUDE_STREAM
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
            chat = ChatProtocol.CODEX_APP_SERVER
        ),
        CliToolDef(
            id = "qwen",
            displayName = tr(R.string.cli_qwen_code),
            bin = "qwen",
            strategy = CliLoginStrategy.API_KEY_FORM,
            terminalType = "qwen-code",
            consoleUrl = "https://modelstudio.console.alibabacloud.com/?tab=coding-plan#/efm/coding-plan-index",
            envKey = "BAILIAN_CODING_PLAN_API_KEY",
            subtitle = tr(R.string.cli_api_key_oauth_free_tier)
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
            chat = ChatProtocol.OPENCODE_HTTP
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
            acpProfile = "agy"
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
            acpProfile = "grok"
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
            subtitle = tr(R.string.cli_kiro_cli_login_device_browser)
        )
    )

    fun byId(id: String): CliToolDef? = ALL.find { it.id == id }

    fun forMethod(method: String): List<CliToolDef> =
        if (method == "chroot") ALL.filter { !it.hideOnChroot } else ALL
}
