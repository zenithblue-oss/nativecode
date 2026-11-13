package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.nativecode.agent.chat.core.ChatRequest
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RequestKind

/** User-input progress, hoisted so the composer can type a custom answer for the current question. */
internal class InputDraft(val requestId: String) {
    var index by mutableStateOf(0)
    val answers: SnapshotStateMap<String, List<String>> = mutableStateMapOf()
}

internal fun ChatRequest.rejectOption() = options.firstOrNull { it.kind.startsWith("reject") } ?: options.firstOrNull { it.kind == "cancel" }

/** Screen F: approval / plan card docked over the composer; exactly the agent's options, answered by id. */
@Composable
internal fun ApprovalCard(v: AgentChatView, t: ChatThread, req: ChatRequest, feedback: String, agentGone: Boolean, clearFeedback: () -> Unit) {
    val d = req.detail
    var reasonOpen by remember { mutableStateOf(false) }
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
    Column(Modifier.fillMaxWidth().background(C.low).border(1.dp, C.tertiary).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("⛨ " + d.title.ifBlank { kindTitle(req.kind) }, Modifier.weight(1f), color = C.tertiary, bold = true, size = 13)
            Label(t.tool)
        }
        Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
            d.command?.let {
                Text("$ $it", Modifier.fillMaxWidth().background(C.lowest).border(1.dp, C.border).horizontalScroll(rememberScrollState()).padding(8.dp),
                    color = C.on, fontFamily = Mono, fontSize = 13.sp, softWrap = false)
            }
            d.paths.forEach { Label(it, Modifier.padding(top = 4.dp), color = C.on) }
            d.diff?.let { diff ->
                ChatLogic.parseUnifiedDiff(diff, d.paths.firstOrNull() ?: "file").forEach { f -> DiffFileSection(f, startOpen = false) }
            }
            d.plan?.let { Markdown(it, streaming = false) }
            d.reason?.let {
                Label((if (reasonOpen) "▾ " else "▸ ") + "Reason", Modifier.clickable { reasonOpen = !reasonOpen }.padding(top = 6.dp))
                if (reasonOpen) Text(it, color = C.onVar, fontSize = 13.sp)
            }
        }
        if (agentGone) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Agent stopped", Modifier.weight(1f), color = C.error)
                NcButton("Restart") { v.retry(t, req.runId) }
            }
        } else FlowRowButtons {
            req.options.forEach { o ->
                val reject = o.kind.startsWith("reject") || o.kind == "cancel"
                NcButton(o.label, primary = o.kind == "allow_once", danger = reject) {
                    val fb = feedback.takeIf { reject && it.isNotBlank() }
                    v.bg { v.backend.respond(t.id, req.id, RequestAnswer(optionId = o.id, feedback = fb)) }
                    if (fb != null) clearFeedback()
                }
            }
        }
        req.options.firstNotNullOfOrNull { it.warning }?.let { Label("⚠ $it", Modifier.padding(top = 4.dp), color = C.tertiary) }
    }
}

private fun kindTitle(k: RequestKind) = when (k) {
    RequestKind.COMMAND -> "Command approval"
    RequestKind.FILE_CHANGE -> "Edit approval"
    RequestKind.FILE_READ -> "Read approval"
    RequestKind.PLAN_EXIT -> "Proposed plan"
    else -> "Permission"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowButtons(content: @Composable () -> Unit) =
    FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }

/** User-input variant: options with descriptions, custom answer typed in the composer, Prev/Next, Dismiss. */
@Composable
internal fun UserInputCard(v: AgentChatView, t: ChatThread, req: ChatRequest, draft: InputDraft) {
    val qs = req.detail.questions
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
    fun submit() = v.bg { v.backend.respond(t.id, req.id, RequestAnswer(answers = draft.answers.toMap())) }
    Column(Modifier.fillMaxWidth().background(C.low).border(1.dp, C.tertiary).padding(12.dp)) {
        if (qs.isEmpty()) {
            Label("? " + req.detail.title, color = C.tertiary, bold = true)
        } else {
            val i = draft.index.coerceIn(0, qs.lastIndex)
            val q = qs[i]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("? " + (q.header ?: req.detail.title.ifBlank { "Question" }), Modifier.weight(1f), color = C.tertiary, bold = true, size = 13)
                if (qs.size > 1) Label("${i + 1}/${qs.size}")
            }
            Column(Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())) {
                Text(q.text, Modifier.padding(vertical = 6.dp), color = C.on, fontSize = 15.sp)
                val chosen = draft.answers[q.id].orEmpty()
                q.options.forEach { o ->
                    val on = o.label in chosen
                    Row(Modifier.fillMaxWidth().clickable {
                        draft.answers[q.id] = if (q.multiple) (if (on) chosen - o.label else chosen + o.label) else listOf(o.label)
                    }.heightIn(min = 44.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label(if (q.multiple) (if (on) "[x]" else "[ ]") else (if (on) "(●)" else "( )"), color = if (on) C.primary else C.onVar, size = 13)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(o.label, color = C.on, fontSize = 14.sp)
                            o.description?.let { Text(it, color = C.onVar, fontSize = 12.sp) }
                        }
                    }
                }
                if (q.custom) Label(chosen.firstOrNull { a -> q.options.none { it.label == a } }?.let { "Custom: $it" } ?: "Or type a custom answer below",
                    Modifier.padding(top = 4.dp))
            }
            FlowRowButtons {
                if (i > 0) NcButton("Prev") { draft.index = i - 1 }
                if (i < qs.lastIndex) NcButton("Next", primary = draft.answers[q.id] != null) { draft.index = i + 1 }
                else NcButton("Submit", primary = true, enabled = qs.all { draft.answers[it.id] != null }) { submit() }
                NcButton("Dismiss", danger = true) { v.bg { v.backend.respond(t.id, req.id, RequestAnswer(cancel = true)) } }
            }
        }
        if (qs.isEmpty()) FlowRowButtons {
            req.options.forEach { o -> NcButton(o.label) { v.bg { v.backend.respond(t.id, req.id, RequestAnswer(optionId = o.id)) } } }
            NcButton("Dismiss", danger = true) { v.bg { v.backend.respond(t.id, req.id, RequestAnswer(cancel = true)) } }
        }
    }
}
