package com.zenithblue.nativecode.cliauth

import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/**
 * SSOT for AI safety / report / ToS links (C5 / B16).
 * UI must not hardcode vendor URLs — change here only.
 */
data class VendorSafetyLinks(
    /** Matches [CliToolDef.id], or [NATIVECODE_TOOL_ID] for app-level row. */
    val toolId: String,
    val displayName: String,
    /** HTTPS content-report form when available. */
    val reportUrl: String? = null,
    /** Vendor safety / support mailto (e.g. support@x.ai). */
    val reportMailto: String? = null,
    val tosUrl: String? = null,
    val aupUrl: String? = null,
    /** Short mono subtitle for UI. */
    val notes: String = ""
)

object AiVendorSafetyCatalog {

    const val NATIVECODE_TOOL_ID = "nativecode"
    const val NATIVECODE_REPORT_EMAIL = "zenithblue.dev@gmail.com"

    val ALL: List<VendorSafetyLinks> = listOf(
        VendorSafetyLinks(
            toolId = NATIVECODE_TOOL_ID,
            displayName = "NativeCode",
            reportMailto = NATIVECODE_REPORT_EMAIL,
            notes = tr(R.string.cli_app_bugs_safety_awareness_not)
        ),
        VendorSafetyLinks(
            toolId = "claude",
            displayName = tr(R.string.cli_claude_code),
            reportUrl = "https://claude.com/form/anthropic-content-reporting",
            reportMailto = "usersafety@anthropic.com",
            tosUrl = "https://www.anthropic.com/legal/consumer-terms",
            aupUrl = "https://www.anthropic.com/legal/aup",
            notes = tr(R.string.cli_anthropic_content_form_aup)
        ),
        VendorSafetyLinks(
            toolId = "codex",
            displayName = tr(R.string.cli_openai_codex),
            reportUrl = "https://openai.com/form/report-content/",
            tosUrl = "https://openai.com/policies/terms-of-use",
            aupUrl = "https://openai.com/policies/usage-policies",
            notes = tr(R.string.cli_openai_report_form_usage_policies)
        ),
        VendorSafetyLinks(
            toolId = "grok",
            displayName = tr(R.string.cli_grok_build),
            reportMailto = "support@x.ai",
            tosUrl = "https://x.ai/legal/terms-of-service",
            aupUrl = "https://x.ai/legal/acceptable-use-policy",
            notes = "xAI support · AUP (in-product Report Issue)"
        ),
        VendorSafetyLinks(
            toolId = "agy",
            displayName = "Antigravity",
            reportUrl = "https://reportcontent.google.com",
            tosUrl = "https://policies.google.com/terms/generative-ai",
            notes = tr(R.string.cli_google_report_content_gen_ai)
        ),
        VendorSafetyLinks(
            toolId = "qwen",
            displayName = tr(R.string.cli_qwen_code),
            reportUrl = "https://modelstudio.console.alibabacloud.com/?tab=coding-plan#/efm/coding-plan-index",
            notes = tr(R.string.cli_vendor_console_account_support_no)
        ),
        VendorSafetyLinks(
            toolId = "opencode",
            displayName = "OpenCode",
            reportUrl = "https://opencode.ai",
            notes = tr(R.string.cli_site_project_issues_use_nativecode)
        ),
        VendorSafetyLinks(
            toolId = "kiro",
            displayName = tr(R.string.cli_kiro_cli),
            reportUrl = "https://app.kiro.dev",
            notes = tr(R.string.cli_aws_kiro_app_support_no)
        )
    )

    fun forTool(toolId: String): VendorSafetyLinks? =
        ALL.find { it.idEquals(toolId) }

    private fun VendorSafetyLinks.idEquals(id: String) =
        toolId.equals(id, ignoreCase = true)

    /**
     * Vendor rows for current isolation (hide tools not in [CliToolCatalog.forMethod]).
     * Does not include NativeCode app row — UI paints that separately.
     */
    fun forMethod(method: String): List<VendorSafetyLinks> {
        val ids = CliToolCatalog.forMethod(method).map { it.id }.toSet()
        return ALL.filter { it.toolId != NATIVECODE_TOOL_ID && it.toolId in ids }
    }

    fun nativeCode(): VendorSafetyLinks =
        ALL.first { it.toolId == NATIVECODE_TOOL_ID }
}
