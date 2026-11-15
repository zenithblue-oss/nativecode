package com.zenithblue.nativecode.cliauth

import android.content.Context
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/**
 * C10 — clear AI credentials / suite provision flag for current isolation.
 * Does not wipe rootfs, projects, or GitHub login.
 */
object CredentialClearService {

    /**
     * Logout every tool in [CliToolCatalog.forMethod]; a tool whose sign-out is unverified fails.
     * Sequential guest cmds + env key wipe via [CliAuthService.logout].
     */
    fun clearAllAiCredentials(
        ctx: Context,
        method: String,
        onDone: (ok: Boolean, msg: String) -> Unit
    ) {
        val tools = CliToolCatalog.forMethod(method)
        if (tools.isEmpty()) {
            onDone(true, "No AI tools for $method")
            return
        }

        fun next(index: Int, failures: MutableList<String>) {
            if (index >= tools.size) {
                val ok = failures.isEmpty()
                val msg = if (ok) {
                    tr(R.string.cli_cleared_ai_credentials_for_tool, tools.size, method)
                } else {
                    "Partial clear ($method). Failed: ${failures.joinToString()}"
                }
                onDone(ok, msg)
                return
            }
            val def = tools[index]
            CliAuthService.logout(ctx, method, def.id) { success, _ ->
                if (!success) failures.add(def.id)
                next(index + 1, failures)
            }
        }

        next(0, mutableListOf())
    }

    /**
     * Host prefs only — hides Free/Paid AI launchers until suite re-install.
     * Does not uninstall guest bins.
     */
    fun clearAiSuiteProvisionFlag(ctx: Context, method: String) {
        AiCliProvisionState.clearAiCliProvisioned(ctx, method)
    }
}
