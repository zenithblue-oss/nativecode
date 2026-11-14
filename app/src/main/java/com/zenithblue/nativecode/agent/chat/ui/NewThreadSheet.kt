package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    ModalBottomSheet(onDismissRequest = { v.sheet.value = false }, containerColor = C.bg, shape = Square, dragHandle = { SheetHandle() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = S.l).padding(bottom = S.l).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("New thread", Modifier.weight(1f), color = C.on, style = T.title)
                Box {
                    val projects = remember { if (v.isWorkspace) emptyList() else v.host.projects() }
                    val canPick = !v.isWorkspace && projects.isNotEmpty()
                    Chip(
                        project?.let { ChatLogic.baseName(it) } ?: "No project",
                        Modifier.semantics { contentDescription = "Project ${project?.let { ChatLogic.baseName(it) } ?: "none"}" + if (canPick) ", change" else "" },
                        icon = Ic.Folder, trailing = if (canPick) Ic.ChevronDown else null,
                    ) { if (canPick) pick = true }
                    DropdownMenu(pick, { pick = false }, containerColor = C.low) {
                        MItem("No project", Ic.Folder) { project = null; pick = false }
                        projects.forEach { (name, path) -> MItem(name, Ic.Folder) { project = path; pick = false } }
                    }
                }
            }
            Text("Pick an agent. Chat opens here; Terminal opens its TUI.", Modifier.padding(top = S.xs, bottom = S.l), color = C.onVar, style = T.bodySm)
            ToolCards(v, project)
        }
    }
}

/** CLI cards from CliToolCatalog + CliCredentialProbe (off main) + FeatureGate. [Chat] only where the tool has a chat runtime. */
@Composable
internal fun ToolCards(v: AgentChatView, project: String?) {
    // Chat-capable CLIs first; terminal-only ones (qwen, kiro) last. Stable sort keeps catalog order within each group.
    val defs = remember { CliToolCatalog.forMethod(LinuxCommandBuilder.currentMethod).sortedBy { it.chat == null } }
    val auth by produceState(emptyMap<String, AuthState>()) {
        val home = v.host.guestHome()
        value = withContext(Dispatchers.IO) { defs.associate { it.id to CliCredentialProbe.probe(it.id, home).state } }
    }
    val cwd = project ?: AgentChatView.HOME
    Column(verticalArrangement = Arrangement.spacedBy(S.m)) {
        defs.forEach { def ->
            val locked = !v.host.canUseTool(def.terminalType)
            fun gated(go: () -> Unit) = if (locked) v.host.showUpgradeSheet("${def.displayName} requires Pro") else go()
            Column(Modifier.fillMaxWidth().brutal(3.dp).card(C.low, if (def.chat != null && !locked) C.primary.copy(alpha = 0.5f) else C.border).padding(S.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProviderLogo(def.terminalType, size = 40.dp)
                    Column(Modifier.weight(1f).padding(horizontal = S.m)) {
                        Text(def.displayName, color = C.on, style = T.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when (auth[def.id]) {
                            AuthState.SIGNED_IN -> AuthLine("Signed in", C.primary)
                            AuthState.SIGNED_OUT -> if (def.loginOptional) AuthLine("Free models · sign in for more", C.onVar, link = true) { v.host.openCliLoginPage(def.id) }
                            else AuthLine("Sign in", C.tertiary, link = true) { v.host.openCliLoginPage(def.id) }
                            else -> if (def.loginOptional) AuthLine("Free models", C.onVar) else Text(def.subtitle, color = C.outline, style = T.meta, maxLines = 1)
                        }
                    }
                    if (def.chat == null) Row(Modifier.card(C.lowest, C.border).padding(horizontal = S.s, vertical = S.xs),
                        verticalAlignment = Alignment.CenterVertically) {
                        NcIcon(Ic.Terminal, null, tint = C.onVar, size = 12.dp)
                        Label("Terminal only", Modifier.padding(start = S.xs), color = C.onVar, size = 10)
                    }
                    if (locked) Row(Modifier.card(C.tertiary.copy(alpha = 0.1f), C.tertiary.copy(alpha = 0.6f)).padding(horizontal = S.s, vertical = S.xs),
                        verticalAlignment = Alignment.CenterVertically) {
                        NcIcon(Ic.Lock, null, tint = C.tertiary, size = 12.dp)
                        Label("PRO", Modifier.padding(start = S.xs), color = C.tertiary, size = 10, bold = true)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = S.m), horizontalArrangement = Arrangement.spacedBy(S.s)) {
                    if (def.chat != null) NcButton("Chat", Modifier.weight(1f), kind = if (locked) BtnKind.OUTLINE else BtnKind.PRIMARY, icon = Ic.Message) {
                        gated { v.newThread(def.terminalType, project) }
                    }
                    NcButton("Terminal", Modifier.weight(1f), icon = Ic.Terminal) {
                        gated { v.sheet.value = false; v.host.openTerminal(def.terminalType, emptyList(), cwd, v.isWorkspace) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().card(C.lowest).padding(S.m), verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo("shell", size = 32.dp)
            Column(Modifier.weight(1f).padding(horizontal = S.m)) {
                Text("Raw shell", color = C.on, style = T.heading)
                Text("bash in ${ChatLogic.baseName(cwd)}", color = C.outline, style = T.meta)
            }
            if (!v.host.canUseTool("shell")) NcIcon(Ic.Lock, "Pro", Modifier.padding(end = S.s), tint = C.tertiary, size = 14.dp)
            NcButton("Shell", icon = Ic.Terminal) { v.sheet.value = false; v.host.openShellHere(cwd, v.isWorkspace) }
        }
    }
}

@Composable
private fun AuthLine(text: String, color: androidx.compose.ui.graphics.Color, link: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        (if (onClick != null) Modifier.clickable(onClickLabel = text, onClick = onClick) else Modifier).heightIn(min = 24.dp).padding(top = S.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (link) Ring(color, size = 8.dp) else StatusDot(color, size = 6.dp)
        Text(text, Modifier.padding(start = S.gap), color = color, style = T.meta)
        if (link) NcIcon(Ic.ChevronRight, null, tint = color, size = 12.dp)
    }
}
