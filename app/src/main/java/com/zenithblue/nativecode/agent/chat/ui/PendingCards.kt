package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/** User-input progress, hoisted so the composer can type a custom answer for the current question. */
internal class InputDraft(val requestId: String) {
    var index by mutableIntStateOf(0)
    val answers: SnapshotStateMap<String, List<String>> = mutableStateMapOf()
}

internal fun ChatRequest.rejectOption() = options.firstOrNull { it.kind.startsWith("reject") } ?: options.firstOrNull { it.kind == "cancel" }

/** Docked card shell: amber accent edge, hard shadow, header icon tile + overline + title. */
@Composable
internal fun PendingShell(icon: ImageVector, overline: String, title: String, trailing: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = S.m, end = S.m, top = S.s)) {
        Column(Modifier.fillMaxWidth().brutal(4.dp, C.tertiary.copy(alpha = 0.25f)).card(C.low, C.tertiary).padding(S.m).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).background(C.tertiary.copy(alpha = 0.14f)).border(1.dp, C.tertiary.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    NcIcon(icon, null, tint = C.tertiary, size = 18.dp)
                }
                Column(Modifier.weight(1f).padding(start = S.m)) {
                    Overline(overline, color = C.tertiary)
                    Text(title, color = C.on, style = T.heading, maxLines = 2)
                }
                trailing?.let { Label(it, color = C.outline) }
            }
            content()
        }
    }
}

/** Screen F: approval card docked over the composer; exactly the agent's options, answered by id. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApprovalCard(v: AgentChatView, t: ChatThread, req: ChatRequest, feedback: String, agentGone: Boolean, bodyMax: androidx.compose.ui.unit.Dp? = null, clearFeedback: () -> Unit) {
    val d = req.detail
    val haptics = LocalHapticFeedback.current
    var reasonOpen by remember { mutableStateOf(false) }
    val maxH = bodyMax ?: (LocalConfiguration.current.screenHeightDp * 0.4f).dp
    PendingShell(if (req.kind == RequestKind.PLAN_EXIT) Ic.ClipboardList else Ic.ShieldAlert, kindTitle(req.kind) + " · " + t.tool, d.title.ifBlank { kindTitle(req.kind) }, null) {
        Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState()).padding(top = S.m), verticalArrangement = Arrangement.spacedBy(S.s)) {
            d.command?.let {
                Row(Modifier.fillMaxWidth().card(C.lowest).padding(S.m), verticalAlignment = Alignment.Top) {
                    NcIcon(Ic.Terminal, null, tint = C.primary, size = 14.dp, modifier = Modifier.padding(top = S.xs))
                    Text(it, Modifier.padding(start = S.s).horizontalScroll(rememberScrollState()), color = C.on, style = T.code, softWrap = false)
                }
            }
            d.paths.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NcIcon(Ic.FileText, null, tint = C.onVar, size = 14.dp)
                    Text(p, Modifier.padding(start = S.s), color = C.on, style = T.codeSm, maxLines = 2)
                }
            }
            d.diff?.let { diff ->
                ChatLogic.parseUnifiedDiff(diff, d.paths.firstOrNull() ?: "file").forEach { f -> DiffFileSection(f, startOpen = false) }
            }
            d.plan?.let { Markdown(it, streaming = false) }
            d.reason?.let {
                Row(Modifier.clickable(onClickLabel = if (reasonOpen) tr(R.string.chat_hide_reason) else tr(R.string.chat_show_reason)) { reasonOpen = !reasonOpen }.heightIn(min = 36.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Chevron(reasonOpen, size = 14.dp)
                    Text(tr(R.string.chat_reason), Modifier.padding(start = S.xs), color = C.onVar, style = T.label)
                }
                if (reasonOpen) Text(it, color = C.onVar, style = T.bodySm)
            }
        }
        req.options.firstNotNullOfOrNull { it.warning }?.let { w ->
            Row(Modifier.padding(top = S.s), verticalAlignment = Alignment.CenterVertically) {
                NcIcon(Ic.Alert, null, tint = C.tertiary, size = 14.dp)
                Text(w, Modifier.padding(start = S.s), color = C.tertiary, style = T.bodySm)
            }
        }
        if (agentGone) {
            Row(Modifier.padding(top = S.m), verticalAlignment = Alignment.CenterVertically) {
                NcIcon(Ic.CircleX, null, tint = C.error, size = 14.dp)
                Text(tr(R.string.chat_agent_stopped), Modifier.weight(1f).padding(start = S.s), color = C.error, style = T.label)
                NcButton(tr(R.string.chat_restart), icon = Ic.Retry) { v.retry(t, req.runId) }
            }
        } else FlowRow(Modifier.fillMaxWidth().padding(top = S.m), horizontalArrangement = Arrangement.spacedBy(S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
            PendingQueue.ordered(req.options).forEach { o ->
                val reject = o.kind.startsWith("reject") || o.kind == "cancel"
                NcButton(
                    o.label, primary = o.kind == "allow_once", danger = reject,
                    icon = when { reject -> Ic.Close; o.kind == "allow_once" -> Ic.Check; else -> Ic.ShieldCheck },
                ) {
                    haptics.performHapticFeedback(if (reject) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
                    val fb = feedback.takeIf { reject && it.isNotBlank() }
                    v.bg { v.backend.respond(t.id, req.id, RequestAnswer(optionId = o.id, feedback = fb)) }
                    if (fb != null) clearFeedback()
                }
            }
        }
        if (!agentGone && req.rejectOption() != null) Text("Type below to decline with feedback.", Modifier.padding(top = S.s), color = C.outline, style = T.meta)
    }
}

private fun kindTitle(k: RequestKind) = when (k) {
    RequestKind.COMMAND -> tr(R.string.chat_command_approval)
    RequestKind.FILE_CHANGE -> tr(R.string.chat_edit_approval)
    RequestKind.FILE_READ -> tr(R.string.chat_read_approval)
    RequestKind.PLAN_EXIT -> tr(R.string.chat_proposed_plan)
    RequestKind.USER_INPUT -> tr(R.string.chat_question)
    else -> tr(R.string.chat_permission)
}

/** User-input variant: options with descriptions, custom answer typed in the composer, Prev/Next, Dismiss. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UserInputCard(v: AgentChatView, t: ChatThread, req: ChatRequest, draft: InputDraft, bodyMax: androidx.compose.ui.unit.Dp? = null) {
    val qs = req.detail.questions
    val haptics = LocalHapticFeedback.current
    val maxH = bodyMax ?: (LocalConfiguration.current.screenHeightDp * 0.4f).dp
    fun respond(a: RequestAnswer, ok: Boolean = true) {
        haptics.performHapticFeedback(if (ok) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
        v.bg { v.backend.respond(t.id, req.id, a) }
    }
    val i = draft.index.coerceIn(0, (qs.size - 1).coerceAtLeast(0))
    val q = qs.getOrNull(i)
    PendingShell(Ic.Help, kindTitle(req.kind) + " · " + t.tool, q?.header ?: req.detail.title.ifBlank { tr(R.string.chat_question) }, if (qs.size > 1) "${i + 1}/${qs.size}" else null) {
        if (q == null) {
            FlowRow(Modifier.fillMaxWidth().padding(top = S.m), horizontalArrangement = Arrangement.spacedBy(S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
                req.options.forEach { o -> NcButton(o.label, icon = Ic.Check) { respond(RequestAnswer(optionId = o.id)) } }
                NcButton(tr(R.string.chat_dismiss), danger = true, icon = Ic.Close) { respond(RequestAnswer(cancel = true), ok = false) }
            }
        } else QuestionBody(q, draft, maxH)
        if (q != null) FlowRow(Modifier.fillMaxWidth().padding(top = S.m), horizontalArrangement = Arrangement.spacedBy(S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
            if (i > 0) NcButton(tr(R.string.chat_prev), icon = Ic.Back) { draft.index = i - 1 }
            if (i < qs.lastIndex) NcButton(tr(R.string.chat_next), primary = draft.answers[q.id] != null, icon = Ic.ChevronRight) { draft.index = i + 1 }
            else NcButton(tr(R.string.chat_submit), primary = true, enabled = qs.all { draft.answers[it.id] != null }, icon = Ic.Check) { respond(RequestAnswer(answers = draft.answers.toMap())) }
            NcButton(tr(R.string.chat_dismiss), danger = true, icon = Ic.Close) { respond(RequestAnswer(cancel = true), ok = false) }
        }
    }
}

@Composable
private fun QuestionBody(q: com.zenithblue.nativecode.agent.chat.core.Question, draft: InputDraft, maxH: androidx.compose.ui.unit.Dp) {
        Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState()).padding(top = S.m), verticalArrangement = Arrangement.spacedBy(S.s)) {
            Text(q.text, color = C.on, style = T.body)
            val chosen = draft.answers[q.id].orEmpty()
            q.options.forEach { o ->
                val on = o.label in chosen
                val pick = { draft.answers[q.id] = if (q.multiple) (if (on) chosen - o.label else chosen + o.label) else listOf(o.label) }
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (q.multiple) Modifier.toggleable(on, role = Role.Checkbox) { pick() } else Modifier.selectable(on, role = Role.RadioButton) { pick() })
                        .card(if (on) C.primary.copy(alpha = 0.1f) else C.lowest, if (on) C.primary else C.border)
                        .heightIn(min = 48.dp).padding(horizontal = S.m, vertical = S.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (q.multiple) Box(Modifier.size(16.dp).border(1.5.dp, if (on) C.primary else C.outline).background(if (on) C.primary else Color.Transparent),
                        contentAlignment = Alignment.Center) { if (on) NcIcon(Ic.Check, null, tint = C.onPrimary, size = 12.dp) }
                    else Ring(if (on) C.primary else C.outline, size = 16.dp, filled = on)
                    Column(Modifier.padding(start = S.m)) {
                        Text(o.label, color = C.on, style = T.body)
                        o.description?.let { Text(it, color = C.onVar, style = T.bodySm) }
                    }
                }
            }
            if (q.custom) Text(chosen.firstOrNull { a -> q.options.none { it.label == a } }?.let { "Custom: $it" } ?: "Or type a custom answer below.",
                color = C.outline, style = T.meta)
        }
}
