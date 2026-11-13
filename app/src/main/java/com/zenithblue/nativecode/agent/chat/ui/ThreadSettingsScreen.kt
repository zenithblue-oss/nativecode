package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode

/** Screen H: full page (has a search field). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThreadSettingsScreen(v: AgentChatView, threadId: String) {
    val d = v.open.value?.takeIf { it.thread.id == threadId }
    val live = v.live.value
    val t = d?.thread
    var q by remember { mutableStateOf("") }
    var confirmFull by remember { mutableStateOf(false) }
    fun cfg(c: ConfigChange) = v.bg { v.backend.configure(threadId, c) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(C.lowest).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("←", Modifier.clickable { v.pop() }.padding(12.dp), color = C.on, size = 16)
            Text("Thread settings", color = C.on, fontSize = 16.sp)
        }
        Hairline()
        if (t == null) { Label("Loading…", Modifier.padding(16.dp)); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Field("TOOL") { Text("${t.tool}${if (d.runs.isNotEmpty()) "  (locked after first message)" else ""}", color = C.on, fontSize = 14.sp) }

            val current = t.model ?: live?.defaultModel
            Field("MODEL") {
                val models = live?.models.orEmpty()
                if (models.isEmpty()) {
                    Text(current ?: "Default", color = C.on, fontSize = 14.sp)
                    Label("The model list loads once the session starts.")
                } else {
                    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("⌕ Search models") }, singleLine = true, shape = Square)
                    models.filter { q.isBlank() || it.name.contains(q, true) || it.id.contains(q, true) }.take(200).forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable { cfg(ConfigChange(model = m.id)) }.heightIn(min = 44.dp).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Label(if (m.id == current) "(●)" else "( )", color = if (m.id == current) C.primary else C.onVar, size = 13)
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(m.name, color = C.on, fontSize = 14.sp)
                                Label(m.id)
                            }
                            if (m.free) Label("FREE", color = C.primary, bold = true)
                        }
                    }
                }
            }

            val variants = live?.models?.firstOrNull { it.id == current }?.variants.orEmpty()
            if (live?.caps?.effort == true && variants.isNotEmpty()) Field("EFFORT") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    variants.forEach { e -> Chip(e, on = e == (t.effort ?: "medium")) { cfg(ConfigChange(effort = e)) } }
                }
            }

            Field("MODE") {
                val modes = live?.caps?.modes ?: listOf(t.runtimeMode)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { m ->
                        Chip(m.label + if (m == RuntimeMode.FULL_ACCESS) " ⚠" else "", on = m == t.runtimeMode) {
                            if (m == RuntimeMode.FULL_ACCESS) confirmFull = true else cfg(ConfigChange(runtimeMode = m))
                        }
                    }
                }
                if (live == null) Label("More modes appear once the session starts.", Modifier.padding(top = 4.dp))
            }

            if (live?.caps?.planMode == true) Field("PLAN MODE") {
                val on = t.interactionMode == InteractionMode.PLAN
                Chip(if (on) "on" else "off", on = on) { cfg(ConfigChange(interactionMode = if (on) InteractionMode.DEFAULT else InteractionMode.PLAN)) }
            }

            Field("WORKSPACE") {
                Text(t.projectPath?.let { ChatLogic.baseName(it) } ?: "No project", color = C.on, fontSize = 14.sp)
                Label(t.cwd)
            }

            live?.usage?.let { u ->
                val used = u.contextUsed; val size = u.contextSize
                if (used != null && size != null && size > 0) Field("CONTEXT") { Label("${ChatLogic.tokens(used)} / ${ChatLogic.tokens(size)}", color = C.on) }
            }

            Field("SESSION") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live?.alive == true) {
                        Label("● live" + (live.runStartedAt?.let { " · working" } ?: ""), Modifier.weight(1f), color = C.primary)
                        NcButton("Stop", danger = true) { v.bg { v.backend.stop(threadId) } }
                    } else Label("○ idle (no process; the next message starts it)", color = C.onVar)
                }
            }
        }
    }
    if (confirmFull) AlertDialog(
        onDismissRequest = { confirmFull = false }, containerColor = C.low, shape = Square,
        title = { Text("Full access?", fontFamily = Mono, fontSize = 15.sp) },
        text = { Text("The agent runs every command and edit without asking. Use only in a project you can restore.", fontSize = 13.sp, color = C.onVar) },
        confirmButton = { NcButton("Allow full access", danger = true) { cfg(ConfigChange(runtimeMode = RuntimeMode.FULL_ACCESS)); confirmFull = false } },
        dismissButton = { NcButton("Cancel") { confirmFull = false } },
    )
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Label(label, Modifier.padding(bottom = 6.dp), color = C.primary, bold = true)
        content()
    }
}
