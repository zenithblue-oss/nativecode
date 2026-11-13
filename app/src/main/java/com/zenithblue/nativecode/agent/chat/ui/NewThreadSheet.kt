package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.nativecode.cliauth.AuthState
import com.zenithblue.nativecode.cliauth.CliCredentialProbe
import com.zenithblue.nativecode.cliauth.CliToolCatalog
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Screen C: pick-list sheet. Project fixed on the workspace page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewThreadSheet(v: AgentChatView) {
    var project by remember { mutableStateOf(v.projectPath) }
    var pick by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { v.sheet.value = false }, containerColor = C.bg, shape = Square) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("NEW THREAD IN", color = C.on, bold = true, size = 12)
                Box(Modifier.padding(start = 8.dp)) {
                    val projects = remember { if (v.isWorkspace) emptyList() else v.host.projects() }
                    Chip((project?.let { ChatLogic.baseName(it) } ?: "No project") + if (!v.isWorkspace && projects.isNotEmpty()) " ▾" else "") {
                        if (!v.isWorkspace && projects.isNotEmpty()) pick = true
                    }
                    DropdownMenu(pick, { pick = false }) {
                        MItem("No project") { project = null; pick = false }
                        projects.forEach { (name, path) -> MItem(name) { project = path; pick = false } }
                    }
                }
            }
            Hairline(Modifier.padding(vertical = 12.dp))
            ToolCards(v, project)
        }
    }
}

/** CLI cards from CliToolCatalog + CliCredentialProbe (off main) + FeatureGate. [Chat] only where the tool has a chat runtime. */
@Composable
internal fun ToolCards(v: AgentChatView, project: String?) {
    val defs = remember { CliToolCatalog.forMethod(LinuxCommandBuilder.currentMethod) }
    val auth by produceState(emptyMap<String, AuthState>()) {
        val home = v.host.guestHome()
        value = withContext(Dispatchers.IO) { defs.associate { it.id to CliCredentialProbe.probe(it.id, home).state } }
    }
    val cwd = project ?: AgentChatView.HOME
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        defs.forEach { def ->
            val locked = !v.host.canUseTool(def.terminalType)
            fun gated(go: () -> Unit) = if (locked) v.host.showUpgradeSheet("${def.displayName} requires Pro") else go()
            Column(Modifier.fillMaxWidth().border(1.dp, C.border).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(def.displayName, Modifier.weight(1f), color = C.on, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    if (locked) Label("PRO 🔒", color = C.tertiary, bold = true)
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (auth[def.id]) {
                        AuthState.SIGNED_IN -> Label("● Signed in", Modifier.weight(1f), color = C.primary)
                        AuthState.SIGNED_OUT ->
                            if (def.loginOptional) Label("Free models · Sign in ›", Modifier.weight(1f).clickable { v.host.openCliLoginPage(def.id) })
                            else Label("○ Sign in ›", Modifier.weight(1f).clickable { v.host.openCliLoginPage(def.id) }, color = C.tertiary)
                        else -> Label(if (def.loginOptional) "Free models" else "", Modifier.weight(1f))
                    }
                    if (def.chat != null) Chip("Chat", on = !locked) { gated { v.newThread(def.terminalType, project) } }
                    Chip("Term") { gated { v.sheet.value = false; v.host.openTerminal(def.terminalType, emptyList(), cwd, v.isWorkspace) } }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("Raw shell", Modifier.weight(1f), color = C.on)
            if (!v.host.canUseTool("shell")) Label("PRO 🔒 ", color = C.tertiary, bold = true)
            Chip(">_ Shell") { v.sheet.value = false; v.host.openShellHere(cwd, v.isWorkspace) }
        }
    }
}
