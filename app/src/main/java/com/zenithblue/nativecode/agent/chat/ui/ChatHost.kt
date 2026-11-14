package com.zenithblue.nativecode.agent.chat.ui

import android.net.Uri
import java.io.File

/** A live terminal tab shown on the Terminals shelf. */
data class TermTab(val index: Int, val label: String)

/** What MainActivity provides to the chat UI (plan 9.2.2). All calls on the main thread. */
interface ChatHost {
    fun isPro(): Boolean
    /** Tool card gate (Free: opencode only). */
    fun canUseTool(tool: String): Boolean
    fun showUpgradeSheet(reason: String?)
    fun openCliLoginPage(id: String)
    /** CLI TUI in a terminal tab of this surface; [args] resume flags (empty = fresh start). Forces Terminal. */
    fun openTerminal(tool: String, args: List<String>, cwd: String?, workspace: Boolean)
    /** Shell tab at [cwd] (Pro; Free: upsell). Forces Terminal. */
    fun openShellHere(cwd: String, workspace: Boolean)
    fun pickImage(cb: (Uri) -> Unit)
    fun pickFile(cb: (Uri) -> Unit)
    /** Flip this surface to the Terminal / Chat position. */
    fun showTerminal(workspace: Boolean)
    fun showChat(workspace: Boolean)
    fun terminalTabs(workspace: Boolean): List<TermTab>
    fun openTerminalTab(index: Int, workspace: Boolean)
    /** (used, max) AI sessions across terminals and chats. */
    fun sessionUsage(): Pair<Int, Int>
    /** Guest home on the host fs (login probe). */
    fun guestHome(): File
    fun aiToolsInstalled(): Boolean
    fun openInstallTools()
    /** (name, guest path) of the user's projects (Pro). */
    fun projects(): List<Pair<String, String>>
    /** Approval arrived while this surface shows the Terminal: snackbar "Approval needed · Open". */
    fun approvalWhileInTerminal(threadTitle: String, open: () -> Unit)
    /** Bridge card "Save & open": APK copy to Downloads, then the Downloads UI (tapping it opens the system installer). */
    fun saveApk(hostPath: String)
    /** Bridge card "Share": share sheet for a host file (staged copy via FileProvider). */
    fun shareFile(hostPath: String)
}
