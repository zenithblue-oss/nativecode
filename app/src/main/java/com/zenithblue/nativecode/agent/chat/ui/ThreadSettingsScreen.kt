package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode

/** Screen H: full page (has a search field). */
@Composable
internal fun ThreadSettingsScreen(v: AgentChatView, threadId: String) {
    val d = v.open.value?.takeIf { it.thread.id == threadId }
    val live = v.live.value
    val t = d?.thread
    var q by remember { mutableStateOf("") }
    var confirmFull by remember { mutableStateOf(false) }
    fun cfg(c: ConfigChange) = v.bg { v.backend.configure(threadId, c) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(C.lowest).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, "Back") { v.pop() }
            Text("Thread settings", Modifier.weight(1f), color = C.on, style = T.title)
            t?.let { ProviderLogo(it.tool, Modifier.padding(end = S.l), size = 28.dp) }
        }
        Hairline()
        if (t == null) { LoadingRows(); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(S.l), verticalArrangement = Arrangement.spacedBy(S.l)) {
            Section("Agent", Ic.Bot) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.tool, Modifier.weight(1f), color = C.on, style = T.heading)
                    if (d.runs.isNotEmpty()) {
                        NcIcon(Ic.Lock, null, tint = C.outline, size = 12.dp)
                        Text("locked after the first message", Modifier.padding(start = S.xs), color = C.outline, style = T.meta)
                    }
                }
                live?.account?.let { Text(it, color = C.onVar, style = T.meta) }
            }

            val current = t.model ?: live?.defaultModel
            Section("Model", Ic.Sparkles) {
                val models = live?.models.orEmpty()
                // No cached list (first use of this tool): warm the session up once, through the gate (Pro, session cap).
                var warmed by remember { mutableStateOf(false) }
                LaunchedEffect(live != null) {
                    if (live != null && live.models.isEmpty() && !live.alive && !warmed) { warmed = true; v.bg { v.backend.resume(threadId) } }
                }
                if (models.isEmpty()) {
                    Text(current ?: "Default", color = C.on, style = T.codeSm)
                    if (live?.alive == true) {
                        Text("Loading models…", color = C.outline, style = T.meta)
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = C.primary, trackColor = C.border)
                    } else {
                        Text("Start the session to load the model list (uses an AI session slot).", color = C.outline, style = T.meta)
                        NcButton("Load models", icon = Ic.Retry) { v.bg { v.backend.resume(threadId) } }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().card(C.lowest).heightIn(min = 44.dp).padding(horizontal = S.m), verticalAlignment = Alignment.CenterVertically) {
                        NcIcon(Ic.Search, null, tint = C.outline, size = 16.dp)
                        BasicTextField(q, { q = it }, Modifier.weight(1f).padding(start = S.s), singleLine = true, textStyle = T.body.copy(color = C.on),
                            cursorBrush = SolidColor(C.primary),
                            decorationBox = { inner -> Box { if (q.isEmpty()) Text("Search ${models.size} models", color = C.outline, style = T.body); inner() } })
                        if (q.isNotEmpty()) IconBtn(Ic.Close, "Clear search", tint = C.onVar, size = 14.dp, box = 36.dp) { q = "" }
                    }
                    Column {
                        models.filter { q.isBlank() || it.name.contains(q, true) || it.id.contains(q, true) }.take(200).forEach { m ->
                            val on = m.id == current
                            Row(
                                Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton) { cfg(ConfigChange(model = m.id)) }
                                    .background(if (on) C.primary.copy(alpha = 0.08f) else C.bg).heightIn(min = 52.dp).padding(horizontal = S.s, vertical = S.s),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Ring(if (on) C.primary else C.outline, size = 16.dp, filled = on)
                                Column(Modifier.weight(1f).padding(horizontal = S.m)) {
                                    Text(m.name, color = C.on, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(m.id, m.contextLimit?.let { "${ChatLogic.tokens(it)} ctx" }).joinToString(" · "),
                                        color = C.outline, style = T.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (m.free) Box(Modifier.background(C.primary.copy(alpha = 0.14f)).padding(horizontal = S.s, vertical = S.xs)) {
                                    Label("FREE", color = C.primary, size = 10, bold = true)
                                }
                            }
                        }
                    }
                }
            }

            val variants = live?.models?.firstOrNull { it.id == current }?.variants.orEmpty()
            if (live?.caps?.effort == true && variants.isNotEmpty()) Section("Reasoning effort", Ic.Lightbulb) {
                val sel = t.effort ?: "medium"
                Row(Modifier.fillMaxWidth().border(1.dp, C.border)) {
                    variants.forEachIndexed { i, e ->
                        val on = e == sel
                        if (i > 0) Box(Modifier.size(1.dp, 44.dp).background(C.border))
                        Box(
                            Modifier.weight(1f).heightIn(min = 44.dp).background(if (on) C.primary else C.low)
                                .selectable(on, role = Role.RadioButton) { cfg(ConfigChange(effort = e)) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, color = if (on) C.onPrimary else C.onVar, style = T.codeSm, maxLines = 1) }
                    }
                }
            }

            Section("Approval mode", Ic.ShieldCheck) {
                val modes = live?.caps?.modes ?: listOf(t.runtimeMode)
                modes.forEach { m ->
                    val on = m == t.runtimeMode
                    val full = m == RuntimeMode.FULL_ACCESS
                    Row(
                        Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton) { if (full && !on) confirmFull = true else cfg(ConfigChange(runtimeMode = m)) }
                            .card(if (on) C.primary.copy(alpha = 0.08f) else C.bg, if (on) C.primary else C.border).heightIn(min = 52.dp).padding(horizontal = S.m, vertical = S.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NcIcon(modeIcon(m), null, tint = if (full) C.tertiary else if (on) C.primary else C.outline, size = 18.dp)
                        Column(Modifier.weight(1f).padding(horizontal = S.m)) {
                            Text(m.label, color = C.on, style = T.body)
                            Text(modeHint(m), color = C.outline, style = T.meta)
                        }
                        Ring(if (on) C.primary else C.outline, size = 16.dp, filled = on)
                    }
                }
                if (live == null) Text("More modes appear once the session starts.", color = C.outline, style = T.meta)
            }

            if (live?.caps?.planMode == true) Section("Plan mode", Ic.ClipboardList) {
                val on = t.interactionMode == InteractionMode.PLAN
                Row(
                    Modifier.fillMaxWidth().toggleable(on, role = Role.Switch) { cfg(ConfigChange(interactionMode = if (on) InteractionMode.DEFAULT else InteractionMode.PLAN)) }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (on) "On" else "Off", color = C.on, style = T.body)
                        Text("The agent proposes a plan before it edits.", color = C.outline, style = T.meta)
                    }
                    Switch(on)
                }
            }

            Section("Workspace", Ic.Folder) {
                Text(t.projectPath?.let { ChatLogic.baseName(it) } ?: "No project", color = C.on, style = T.heading)
                Text(t.cwd, color = C.outline, style = T.meta)
            }

            val usage = live?.usage ?: d.runs.lastOrNull { it.usage != null }?.usage
            val used = usage?.contextUsed
            val size = usage?.contextSize ?: live?.models?.firstOrNull { it.id == current }?.contextLimit
            val frac = ChatLogic.contextFraction(used, size)
            val runs = d.runs.filter { it.usage != null }
            if (frac != null || runs.isNotEmpty()) Section("Usage", Ic.Gauge) {
                if (frac != null && used != null && size != null) {
                    Row {
                        Text("Context", Modifier.weight(1f), color = C.onVar, style = T.label)
                        Text("${ChatLogic.tokens(used)} / ${ChatLogic.tokens(size)} · ${(frac * 100).toInt()}%", color = C.on, style = T.codeSm)
                    }
                    LinearProgressIndicator(
                        progress = { frac }, Modifier.fillMaxWidth().height(6.dp),
                        color = when { frac >= 0.95f -> C.error; frac >= 0.8f -> C.tertiary; else -> C.primary },
                        trackColor = C.border, gapSize = 0.dp, drawStopIndicator = {},
                    )
                }
                if (runs.isNotEmpty()) {
                    val tok = runs.sumOf { (it.usage?.inputTokens ?: 0) + (it.usage?.outputTokens ?: 0) }
                    val cost = runs.mapNotNull { it.usage?.costUsd }.takeIf { it.isNotEmpty() }?.sum()
                    Text(listOfNotNull("${runs.size} turn${if (runs.size == 1) "" else "s"}", "${ChatLogic.tokens(tok)} tokens", ChatLogic.cost(cost))
                        .joinToString(" · "), color = C.onVar, style = T.meta)
                }
                live?.rateLimit?.let { r ->
                    Text("Provider quota: ${r.detail ?: r.status}" + (r.resetAt?.let { " · resets " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: ""),
                        color = C.tertiary, style = T.meta)
                }
            }

            Section("Session", Ic.Terminal) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live?.alive == true) {
                        StatusDot(C.primary, size = 8.dp, pulse = live.runStartedAt != null)
                        Text("Live" + (live.runStartedAt?.let { " · working" } ?: ""), Modifier.weight(1f).padding(start = S.s), color = C.primary, style = T.label)
                        NcButton("Stop", danger = true, icon = Ic.Stop) { v.bg { v.backend.stop(threadId) } }
                    } else {
                        Ring(C.outline, size = 10.dp)
                        Text("Idle: no process. The next message starts it.", Modifier.padding(start = S.s), color = C.onVar, style = T.label)
                    }
                }
                if (!v.host.isPro()) {
                    val (u, m) = remember { v.host.sessionUsage() }
                    Text("Free plan: $u/$m AI session${if (m == 1) "" else "s"} in use. Stop frees the slot.", color = C.outline, style = T.meta)
                }
            }
        }
    }
    if (confirmFull) FullAccessDialog({ confirmFull = false }) { cfg(ConfigChange(runtimeMode = RuntimeMode.FULL_ACCESS)) }
}

@Composable
private fun Section(label: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().card(C.low).padding(S.m), verticalArrangement = Arrangement.spacedBy(S.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NcIcon(icon, null, tint = C.primary, size = 14.dp)
            Overline(label, Modifier.padding(start = S.s), color = C.primary)
        }
        content()
    }
}

/** Square drawn switch (app's BrutalistToggle look). */
@Composable
private fun Switch(on: Boolean) {
    val x by animateDpAsState(if (on) 20.dp else 0.dp, label = "sw")
    Box(Modifier.size(44.dp, 24.dp).background(if (on) C.primary.copy(alpha = 0.3f) else C.high).border(1.dp, if (on) C.primary else C.border).padding(2.dp)) {
        Box(Modifier.offset(x = x).size(20.dp).background(if (on) C.primary else C.outline))
    }
}
