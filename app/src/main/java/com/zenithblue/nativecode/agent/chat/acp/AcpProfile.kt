package com.zenithblue.nativecode.agent.chat.acp

import com.zenithblue.nativecode.agent.chat.core.RequestOption
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import org.json.JSONObject

/**
 * Per-CLI ACP quirks (plan 9.2.4): argv, env, auth, modes, option labels, extensions. A new ACP agent
 * (kiro, qwen…) is one more entry here, not a new runtime.
 */
data class AcpProfile(
    val id: String,
    val name: String,
    /** Launch argv for a runtime mode (grok carries the permission posture in argv). */
    val argv: (RuntimeMode) -> List<String>,
    val env: Map<String, String> = emptyMap(),
    val modes: List<RuntimeMode>,
    /** True: a mode change respawns the process and reloads the session (grok). */
    val modeInArgv: Boolean = false,
    /** Native session mode per runtime mode (`session/set_mode` or the `mode` config option), applied when advertised. */
    val nativeModes: Map<RuntimeMode, String> = emptyMap(),
    /** Native plan mode id (the plan chip), when advertised. */
    val planMode: String? = null,
    /** Authenticate before session setup when this method is advertised (grok `cached_token`: silent, returns the account). */
    val eagerAuth: String? = null,
    /** `initialize._meta` (grok `clientType: extension`, else Auto refusals are silently denied). */
    val initMeta: JSONObject? = null,
    /** `session/cancel._meta` (grok `cancelTrigger: ctrl_c`). */
    val cancelMeta: JSONObject? = null,
    /** On `-32000`: method to authenticate with, chosen from the advertised ids; null = show the sign-in state. */
    val authMethod: (List<String>) -> String? = { null },
    /** Resume with `session/resume` (no replay) when advertised; else `session/load`. */
    val preferResume: Boolean = false,
    /** agy: `interaction_*` permission requests are questions. */
    val interactionQuestions: Boolean = false,
    /** agy: `stopReason: refusal` = quota exhausted. */
    val refusalIsQuota: Boolean = false,
    /** Text files / PDFs as embedded `resource` / `resource_link` blocks (agy) instead of path lines. */
    val embedDocs: Boolean = false,
    /** grok: Auto mode is the CLI's classifier; whatever it refuses must reach the user (never auto-allowed). */
    val autoAsksAll: Boolean = false,
    /** grok `x.ai` extension methods. */
    val xai: Boolean = false,
    val dropCommands: Set<String> = emptySet(),
    val extraCommands: List<SlashCommand> = emptyList(),
    /** Approval options shown for the agent's ACP options (t3code labels), always with Cancel. */
    val options: (List<JSONObject>) -> List<RequestOption> = ::genericOptions,
    /** Error text when a turn is sent signed out. */
    val signIn: String = "Sign in to $name to chat",
) {
    companion object {
        /** t3code order: Cancel, Decline, session grant, Approve. `reject_always` is never offered. */
        fun genericOptions(opts: List<JSONObject>): List<RequestOption> = listOf(RequestOption(AcpWire.CANCEL, "cancel", "Cancel")) +
            opts.filter { it.optString("kind") == "reject_once" }.map { RequestOption(it.optString("optionId"), "reject", it.optString("name").ifBlank { "Decline" }) } +
            opts.filter { it.optString("kind") == "allow_always" }.map { RequestOption(it.optString("optionId"), "allow_session", it.optString("name").ifBlank { "Always allow" }) } +
            opts.filter { it.optString("kind") == "allow_once" }.map { RequestOption(it.optString("optionId"), "allow_once", it.optString("name").ifBlank { "Approve" }) }

        /** AntigravityProtocol.ts:56-102: Allow once / Allow for this thread (+ `agy.security.warning`) / Deny / Cancel. */
        private fun agyOptions(opts: List<JSONObject>): List<RequestOption> = listOf(RequestOption(AcpWire.CANCEL, "cancel", "Cancel")) +
            opts.filter { it.optString("kind") == "reject_once" }.take(1).map { RequestOption(it.optString("optionId"), "reject", "Deny") } +
            opts.filter { it.optString("kind") == "allow_always" }.take(1).map { o ->
                val w = o.optJSONObject("_meta")?.optJSONObject("agy.security.warning")
                RequestOption(o.optString("optionId"), "allow_session", "Allow for this thread",
                    (w?.optString("message")?.ifBlank { null } ?: w?.optString("title")?.ifBlank { null })?.trim()?.take(512))
            } +
            opts.filter { it.optString("kind") == "allow_once" }.take(1).map { RequestOption(it.optString("optionId"), "allow_once", "Allow once") }

        /** grok acpSupport.ts:104-122: other allow_always ids persist project grants, so only `allow-edits-session`. */
        private fun grokOptions(opts: List<JSONObject>): List<RequestOption> = listOf(RequestOption(AcpWire.CANCEL, "cancel", "Cancel")) +
            opts.filter { it.optString("kind") == "reject_once" }.take(1).map { RequestOption(it.optString("optionId"), "reject", "Decline") } +
            opts.filter { it.optString("kind") == "allow_always" && it.optString("optionId") == "allow-edits-session" }.map { RequestOption(it.optString("optionId"), "allow_session", "Allow all edits this session") } +
            opts.filter { it.optString("kind") == "allow_once" }.take(1).map { RequestOption(it.optString("optionId"), "allow_once", "Approve") }

        val AGY = AcpProfile(
            id = "agy",
            name = "Antigravity",
            argv = { listOf("agy", "acp") },
            // agy 1.5.8 advertises default | accept-edits | auto (its own review: "approve edits and safe commands;
            // ask before irreversible actions") | plan. Full access = default + every request allowed client-side.
            modes = RuntimeMode.entries.toList(),
            nativeModes = mapOf(
                RuntimeMode.APPROVAL_REQUIRED to "default", RuntimeMode.AUTO_ACCEPT_EDITS to "accept-edits",
                RuntimeMode.AUTO to "auto", RuntimeMode.FULL_ACCESS to "default",
            ),
            planMode = "plan",
            autoAsksAll = true, // what agy's Auto review still asks reaches the user
            preferResume = true,
            interactionQuestions = true,
            refusalIsQuota = true,
            embedDocs = true,
            dropCommands = setOf("logout"),
            options = ::agyOptions,
        )

        val GROK = AcpProfile(
            id = "grok",
            name = "Grok",
            argv = { m ->
                when (m) {
                    RuntimeMode.AUTO -> listOf("grok", "--permission-mode", "auto", "agent", "stdio")
                    RuntimeMode.FULL_ACCESS -> listOf("grok", "agent", "--always-approve", "stdio")
                    else -> listOf("grok", "--permission-mode", "default", "agent", "stdio")
                }
            },
            env = mapOf("GROK_DISABLE_AUTOUPDATER" to "1"),
            modes = listOf(RuntimeMode.APPROVAL_REQUIRED, RuntimeMode.AUTO, RuntimeMode.FULL_ACCESS),
            modeInArgv = true,
            initMeta = JSONObject().put("clientType", "extension"),
            cancelMeta = JSONObject().put("cancelTrigger", "ctrl_c"),
            // Signed-in builds offer `cached_token` (+ `grok.com`); the bare spike build only `grok.com`, which is a
            // browser login: never started headless, so that case is the sign-in state.
            authMethod = { ids -> ids.firstOrNull { it == "cached_token" } ?: ids.firstOrNull { it == "xai.api_key" } },
            eagerAuth = "cached_token",
            autoAsksAll = true,
            xai = true,
            dropCommands = setOf("always-approve", "context"),
            extraCommands = listOf(SlashCommand("compact", "Compact the conversation context")),
            options = ::grokOptions,
        )

        fun of(id: String?): AcpProfile? = when (id) {
            "agy" -> AGY
            "grok" -> GROK
            else -> null
        }
    }
}
