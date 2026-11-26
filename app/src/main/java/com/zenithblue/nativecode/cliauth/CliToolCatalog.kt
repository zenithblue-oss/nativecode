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
     * Lowest CLI version the chat runtime works with (dotted): the flags / JSON-RPC methods / endpoints it calls exist from
     * there. Checked before a chat session starts ([CliGate]) and again after a version-related chat failure.
     */
    val minChatVersion: String? = null,
    /** Lowest CLI version whose sign-in command (the one [CliGuestCommands] runs) works; checked before the sign-in popup starts. */
    val minLoginVersion: String? = null,
    /** Guest shell command that updates this CLI itself (null = delivered by the Components update pack). */
    val updateCmd: String? = null,
    /** Guest shell command that installs (or reinstalls) this CLI from its vendor, as setup_cli_tools.sh does. */
    val installCmd: String? = null,
    /** One short line for the Settings card. */
    val description: String = ""
) {
    fun min(need: CliNeed): String? = if (need == CliNeed.CHAT) minChatVersion else minLoginVersion
}

/** What a CLI is about to be used for; picks [CliToolDef.minChatVersion] or [CliToolDef.minLoginVersion]. */
enum class CliNeed { CHAT, LOGIN }
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
            // 2.1.119 = `${ENV_VAR}` in MCP `headers` is substituted (the app's own MCP server config relies on it; 2.1.94 added the hidden
            // --thinking-display flag the stream runtime passes, the rest, --permission-prompt-tool stdio, --effort, --setting-sources,
            // --session-id / --fork-session, is older). Chat verified on device with 2.1.263 (its initialize answer has no claude_code_version). 2.1.126: `claude auth login` takes the pasted code,
            // which on-device (no localhost callback) is the only way to finish (auth subcommand itself: 2.1.41). Rootfs 2.1.263 passes.
            minChatVersion = "2.1.119",
            minLoginVersion = "2.1.126",
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
            // app-server-protocol common.rs per tag: thread/start|resume 0.56, turn/steer 0.99, thread/compact/start 0.96, permissions approval
            // 0.113, thread/goal/* 0.128 (experimental until 0.133, which makes goals stable); `login --device-auth` 0.46. Rootfs 0.153.2 passes.
            minChatVersion = "0.133.0",
            minLoginVersion = "0.46.0",
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
            // opencode 2.2.0 = first public 2.x release with the /api/session v2 routes, forms and usage limits (releases page). The rootfs
            // npm opencode-ai 1.18.27 only has session create/fetch; the update pack brings 2.4.1. Sign-in is the terminal `opencode auth login`.
            minChatVersion = "2.2.0",
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
            // CHANGELOG: `agy acp` + modes + session/resume + `acp --login` arrive in 1.5.0; 1.5.9 makes a failed turn report its real
            // quota / model error (the app maps stopReason refusal to quota). The rootfs ships 1.1.26, which has no `agy acp` at all; the update pack brings 1.5.16.
            minChatVersion = "1.5.9",
            minLoginVersion = "1.5.0",
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
            // Release notes: `agent stdio` listing and --always-approve are documented from 1.0.51 (ACP effort at session open from 1.0.5);
            // earlier builds lack the ACP config options / clientType the app sets. The rootfs ships 1.0.13; the update pack brings 1.0.92.
            minChatVersion = "1.0.50",
            minLoginVersion = "1.0.50",
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
