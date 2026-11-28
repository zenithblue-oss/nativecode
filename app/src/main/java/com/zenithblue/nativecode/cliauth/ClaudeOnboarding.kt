package com.zenithblue.nativecode.cliauth

import java.io.File
import org.json.JSONObject

/**
 * `claude auth login` (the in-app sign-in) and a restored/reset home leave `~/.claude.json` without
 * `hasCompletedOnboarding`. The `claude` tile then opens the welcome + theme wizard instead of the TUI, and
 * that wizard checks the network first: offline it exits 1 within a second ("Unable to connect").
 * A signed-in home has nothing left to onboard, so mark it done. Every other key is kept.
 */
object ClaudeOnboarding {
    /** True when [home]'s `.claude.json` was changed. */
    fun ensureDone(home: File): Boolean {
        if (CliCredentialProbe.probe("claude", home).state != AuthState.SIGNED_IN) return false
        val f = File(home, ".claude.json")
        val cfg = try {
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        } catch (_: Exception) {
            return false // unreadable config: leave it for claude's own backup/repair
        }
        if (cfg.optBoolean("hasCompletedOnboarding")) return false
        cfg.put("hasCompletedOnboarding", true)
        f.writeText(cfg.toString(2))
        return true
    }
}
