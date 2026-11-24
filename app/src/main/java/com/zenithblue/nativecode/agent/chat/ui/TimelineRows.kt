package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.ChatFailures
import com.zenithblue.nativecode.agent.chat.core.Failure
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.cliauth.CliNeed
import com.zenithblue.nativecode.cliauth.CliVersionCheck
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/** Every timeline row: screen edge inset, half a gap above and below so consecutive rows sit one gap apart. */
private val ROW_PAD = Modifier.fillMaxWidth().padding(horizontal = S.edge, vertical = S.xs)

/**
 * One timeline row. [liveRun] = the run streaming now (its work group stays expanded, its text carries the caret).
 * [ttft] belongs to [lastRun] (live only).
 */
@Composable
internal fun TimelineRow(v: AgentChatView, t: ChatThread, row: Row, liveRun: String?, lastRun: String?, ttft: Long?, metaText: String?) {
    when (row) {
        is Row.Work -> WorkGroup(v, t, row.items, row.kids, live = row.runId != null && row.runId == liveRun)
        is Row.Meta -> MetaRow(row.run, metaText, ttft.takeIf { row.run.id == lastRun })
        is Row.Item -> ItemRow(v, t, row.item, row.item.runId != null && row.item.runId == liveRun)
    }
}

@Composable
private fun ItemRow(v: AgentChatView, t: ChatThread, item: TurnItem, live: Boolean) {
    when (val p = item.payload) {
        // A user message opens a turn: one block (not one gap) from what came before.
        is ItemPayload.UserMessage -> com.zenithblue.nativecode.agent.chat.tools.NcToolSpec.parseIncoming(p.text) // M7 cross-thread
            ?.let { CrossThreadCard(v, it, ROW_PAD.padding(top = S.block - S.gap)) } ?: UserBubble(p, item.updatedAt, ROW_PAD.padding(top = S.block - S.gap))
        is ItemPayload.AssistantMessage -> Markdown(p.text, streaming = live && item.status == ItemStatus.RUNNING, modifier = ROW_PAD)
        is ItemPayload.Reasoning -> Reasoning(p, item.status == ItemStatus.RUNNING, ROW_PAD)
        is ItemPayload.ProposedPlan -> PlanCard(v, t, p.markdown, ROW_PAD)
        is ItemPayload.ApprovalRequest -> RequestLine(Ic.ShieldAlert, tr(R.string.chat_approval), item.status, ROW_PAD)
        is ItemPayload.UserInputRequest -> RequestLine(Ic.Help, tr(R.string.chat_question), item.status, ROW_PAD)
        is ItemPayload.Error -> ErrorRow(v, t, item, p, ROW_PAD)
        is ItemPayload.Compaction -> Divider(
            if (item.status == ItemStatus.RUNNING) "Compacting context" else "Context compacted" + (p.tokens?.let { " · ${ChatLogic.tokens(it)} tok" } ?: ""),
            Ic.Fold, shimmer = item.status == ItemStatus.RUNNING,
        )
        is ItemPayload.ProviderSwitch -> SwitchDivider(p, ROW_PAD.padding(vertical = S.xs))
        is ItemPayload.Bridge -> BridgeCard(v, t.id, item, p, ROW_PAD)
        is ItemPayload.SystemNotice -> Row(ROW_PAD, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(4.dp).background(C.outline))
            Text(p.text, Modifier.padding(start = S.s), color = C.outline, style = T.meta)
        }
        else -> WorkItem(v, t, item, ROW_PAD.card(C.low))
    }
}

/** "Switched to Codex · gpt-x": the thread moved to another provider here. */
@Composable
private fun SwitchDivider(p: ItemPayload.ProviderSwitch, modifier: Modifier) {
    val label = tr(R.string.chat_switched_to, providerName(p.tool), p.model?.substringAfterLast('/') ?: tr(R.string.chat_default_model))
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
        Hairline(Modifier.weight(1f))
        Row(Modifier.padding(horizontal = S.s), verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(p.tool, size = 18.dp)
            Text(label, Modifier.padding(start = S.s), color = C.outline, style = T.meta)
        }
        Hairline(Modifier.weight(1f))
    }
}

/** Centered rule with a label (compaction, day breaks). */
@Composable
private fun Divider(label: String, icon: ImageVector, shimmer: Boolean = false) {
    Row(ROW_PAD.padding(vertical = S.xs), verticalAlignment = Alignment.CenterVertically) {
        Hairline(Modifier.weight(1f))
        Row(Modifier.padding(horizontal = S.s).shimmer(shimmer), verticalAlignment = Alignment.CenterVertically) {
            NcIcon(icon, null, tint = C.outline, size = 12.dp)
            Text(label, Modifier.padding(start = S.gap), color = C.outline, style = T.meta)
        }
        Hairline(Modifier.weight(1f))
    }
}

@Composable
private fun RequestLine(icon: ImageVector, what: String, status: ItemStatus, modifier: Modifier) {
    val (label, color) = when (status) {
        ItemStatus.WAITING, ItemStatus.PENDING -> tr(R.string.chat_waiting) to C.tertiary
        ItemStatus.COMPLETED -> tr(R.string.chat_answered) to C.primary
        else -> status.wire.replaceFirstChar { it.uppercase() } to C.outline
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        NcIcon(icon, null, tint = color, size = 14.dp)
        Text(what, Modifier.padding(start = S.s, end = S.s), color = C.onVar, style = T.label)
        StatusChip(label, color)
    }
}

private val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
private val FULL = SimpleDateFormat(tr(R.string.chat_eee_d_mmm_yyyy_hh), Locale.getDefault())

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UserBubble(p: ItemPayload.UserMessage, at: Long, modifier: Modifier) {
    val ctx = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var stamp by remember { mutableStateOf(false) }
    val (skills, own) = remember(p.text) { com.zenithblue.nativecode.agent.chat.core.SkillBlocks.split(p.text) }
    var skillOpen by rememberSaveable { mutableStateOf<String?>(null) }
    val (folded, isFolded) = remember(own) { ChatLogic.fold(own) }
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.85f).dp
    Column(modifier, horizontalAlignment = Alignment.End) {
        Column(
            Modifier.widthIn(max = maxW).background(C.high).border(1.dp, C.borderVar)
                .combinedClickable(onClickLabel = null, onLongClickLabel = tr(R.string.chat_show_time), onLongClick = { stamp = !stamp }, onClick = {})
                .padding(S.inset)
                .animateContentSize(),
        ) {
            if (p.intent != "start") Row(Modifier.padding(bottom = S.xs), verticalAlignment = Alignment.CenterVertically) {
                NcIcon(if (p.intent == "steer") Ic.Steer else Ic.Clock, null, tint = C.tertiary, size = 12.dp)
                Overline(if (p.intent == "steer") "Steer" else "Queued", Modifier.padding(start = S.xs), color = C.tertiary)
            }
            if (p.attachments.isNotEmpty()) Row(Modifier.padding(bottom = S.s).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(S.gap)) {
                p.attachments.forEach { AttachmentThumb(it) }
            }
            if (skills.isNotEmpty()) Row(Modifier.padding(bottom = S.s).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(S.gap)) {
                skills.forEach { k ->
                    val on = skillOpen == k.name
                    Row(
                        Modifier.heightIn(min = 32.dp).card(C.container, if (on) C.primary else C.tertiary)
                            .clickable(onClickLabel = if (on) "Hide skill ${k.name}" else "Show skill ${k.name}") { skillOpen = if (on) null else k.name }
                            .padding(horizontal = S.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NcIcon(Ic.BookOpen, null, tint = C.tertiary, size = 14.dp)
                        Text(k.name, Modifier.padding(horizontal = S.gap).widthIn(max = 180.dp), color = C.on, style = T.codeSm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Chevron(on, size = 12.dp)
                    }
                }
            }
            skills.firstOrNull { it.name == skillOpen }?.let { k ->
                Markdown(k.body, streaming = false, modifier = Modifier.padding(bottom = S.s).heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
            }
            if (own.isNotBlank()) Text(if (expanded || !isFolded) own else folded, color = C.on, style = T.body)
            if (isFolded) Text(if (expanded) tr(R.string.chat_show_less) else tr(R.string.chat_show_more), Modifier.clickable { expanded = !expanded }.padding(top = S.xs),
                color = C.primary, style = T.label)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stamp) Text(FULL.format(Date(at)), color = C.outline, style = T.meta)
            IconBtn(Ic.Copy, tr(R.string.chat_copy_message), tint = C.outline, size = 14.dp, box = 36.dp) { copyText(ctx, p.text) }
        }
    }
}

@Composable
internal fun AttachmentThumb(a: ChatAttachment) {
    val bmp by produceState<ImageBitmap?>(null, a.hostPath) {
        val path = a.hostPath
        if (a.isImage && path != null) value = withContext(Dispatchers.IO) { decodeThumb(path) }
    }
    val b = bmp
    if (b != null) Image(b, a.name, Modifier.size(64.dp).border(1.dp, C.border), contentScale = ContentScale.Crop)
    else Row(Modifier.card(C.container).padding(horizontal = S.s, vertical = S.xs), verticalAlignment = Alignment.CenterVertically) {
        NcIcon(if (a.isImage) Ic.Image else Ic.FileText, null, tint = C.onVar, size = 14.dp)
        Text(a.name, Modifier.padding(start = S.gap).widthIn(max = 160.dp), color = C.on, style = T.codeSm, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun decodeThumb(path: String): ImageBitmap? = FileThumbs.image(java.io.File(path), 128)?.asImageBitmap()

@Composable
internal fun Spinner(size: Dp = 12.dp, color: Color = C.primary) =
    CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = 1.5.dp, trackColor = Color.Transparent)

@Composable
private fun Reasoning(p: ItemPayload.Reasoning, running: Boolean, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val secs = p.durationMs?.let { (it + 500) / 1000 }
    Column(modifier.animateContentSize()) {
        Row(
            Modifier.heightIn(min = 32.dp).clickable(enabled = p.text.isNotBlank(), onClickLabel = if (open) tr(R.string.chat_hide_reasoning) else tr(R.string.chat_show_reasoning)) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NcIcon(Ic.Lightbulb, null, tint = if (running) C.primary else C.outline, size = 14.dp)
            Text(if (running) tr(R.string.chat_thinking) else "Thought" + (secs?.let { " for ${it}s" } ?: ""), Modifier.padding(horizontal = S.s).shimmer(running),
                color = C.onVar, style = T.label)
            if (p.text.isNotBlank()) Chevron(open, size = 14.dp)
        }
        if (open && p.text.isNotBlank()) Row(Modifier.height(IntrinsicSize.Min).padding(start = S.gap, top = S.xs)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(C.border))
            Text(p.text, Modifier.padding(start = S.m), color = C.onVar, style = T.bodySm)
        }
    }
}

/** Collapsible work log of one run (t3code work group); the live turn stays expanded. */
@Composable
private fun WorkGroup(v: AgentChatView, t: ChatThread, items: List<TurnItem>, kids: Map<String, List<TurnItem>>, live: Boolean) {
    var open by remember(live) { mutableStateOf(live) }
    val failed = items.count { it.status == ItemStatus.FAILED }
    val running = items.any { it.status == ItemStatus.RUNNING }
    Column(ROW_PAD.card(C.low).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(onClickLabel = if (open) tr(R.string.chat_collapse_work_log) else tr(R.string.chat_expand_work_log)) { open = !open }
                .padding(horizontal = S.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chevron(open, size = 14.dp)
            Text(ChatLogic.workSummary(items), Modifier.weight(1f).padding(start = S.s), color = C.onVar, style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (running) Spinner()
            if (failed > 0) Row(Modifier.padding(start = S.s).semantics(mergeDescendants = true) { contentDescription = "$failed failed" },
                verticalAlignment = Alignment.CenterVertically) {
                NcIcon(Ic.CircleX, null, tint = C.error, size = 14.dp)
                Label("$failed", Modifier.padding(start = S.xs), color = C.error, bold = true)
            }
        }
        if (open) items.forEach { i ->
            Hairline()
            WorkItem(v, t, i, Modifier.fillMaxWidth(), kids)
        }
    }
}

@Composable
private fun WorkItem(v: AgentChatView, t: ChatThread, item: TurnItem, modifier: Modifier, kids: Map<String, List<TurnItem>> = emptyMap()) {
    val running = item.status == ItemStatus.RUNNING
    val mine = kids[item.id].orEmpty()
    if (item.payload is ItemPayload.Subagent || mine.isNotEmpty()) return SubagentCard(v, t, item, mine, kids, modifier)
    when (val p = item.payload) {
        is ItemPayload.CommandExecution -> CommandCard(p, item.status, modifier)
        is ItemPayload.FileChange -> FileChangeRow(v, t, p, modifier)
        is ItemPayload.FileSearch -> ToolLine(Ic.Search, p.pattern, "${p.results.size} results", item.status, modifier)
        is ItemPayload.WebSearch -> ToolLine(Ic.Globe, p.query, "${p.results.size} results", item.status, modifier)
        is ItemPayload.DynamicTool -> ToolLine(Ic.Wrench, p.name, null, item.status, modifier)
        else -> {}
    }
}

/**
 * Subagent / task card (t3code subagent work entry): title, live activity or step count, status; expands to the
 * child's own activity (nested) and its result.
 */
@Composable
private fun SubagentCard(v: AgentChatView, t: ChatThread, item: TurnItem, children: List<TurnItem>, kids: Map<String, List<TurnItem>>, modifier: Modifier) {
    val p = item.payload as? ItemPayload.Subagent
    val running = item.status == ItemStatus.RUNNING || item.status == ItemStatus.PENDING
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val steps = children.count { it.type != "assistant_message" && it.type != "reasoning" }
    val title = p?.title ?: (item.payload as? ItemPayload.DynamicTool)?.name ?: ChatLogic.activity(item.payload) ?: tr(R.string.chat_subagent)
    val meta = when {
        running -> ChatLogic.currentActivity(children, item.runId) ?: "working"
        steps > 0 -> if (steps == 1) "1 step" else "$steps steps"
        else -> null
    }
    val canOpen = children.isNotEmpty() || !p?.result.isNullOrBlank()
    Column(modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = canOpen, onClickLabel = if (open) tr(R.string.chat_hide_subagent_activity) else tr(R.string.chat_show_subagent_activity)) { open = !open }
                .padding(horizontal = S.m, vertical = S.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NcIcon(Ic.Bot, null, tint = if (item.status == ItemStatus.FAILED) C.error else C.tertiary, size = 16.dp)
            Column(Modifier.weight(1f).padding(horizontal = S.s)) {
                Text(title, color = C.on, style = T.codeSm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                meta?.let { Text(it, Modifier.shimmer(running), color = C.outline, style = T.meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            ItemStatusIcon(item.status)
            if (canOpen) Chevron(open, Modifier.padding(start = S.s), size = 14.dp)
        }
        if (open) Row(Modifier.height(IntrinsicSize.Min).padding(start = S.l, end = S.s, bottom = S.s)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(C.tertiary.copy(alpha = 0.4f)))
            Column(Modifier.weight(1f).padding(start = S.s)) {
                children.forEach { c ->
                    when (val cp = c.payload) {
                        is ItemPayload.AssistantMessage -> Text(cp.text, Modifier.padding(vertical = S.xs), color = C.onVar, style = T.bodySm)
                        is ItemPayload.Reasoning -> Reasoning(cp, c.status == ItemStatus.RUNNING, Modifier.fillMaxWidth())
                        else -> WorkItem(v, t, c, Modifier.fillMaxWidth(), kids)
                    }
                }
                p?.result?.takeIf { it.isNotBlank() && !running }?.let {
                    Overline(tr(R.string.chat_result), Modifier.padding(top = S.s), color = C.outline)
                    Markdown(it, streaming = false, modifier = Modifier.padding(top = S.xs).heightIn(max = 320.dp).verticalScroll(rememberScrollState()))
                }
            }
        }
    }
}

/** Icon · mono title · meta · status (spinner / failed). */
@Composable
private fun ToolLine(icon: ImageVector, title: String, meta: String?, status: ItemStatus, modifier: Modifier) {
    Row(modifier.heightIn(min = 40.dp).padding(horizontal = S.m, vertical = S.s), verticalAlignment = Alignment.CenterVertically) {
        NcIcon(icon, null, tint = if (status == ItemStatus.FAILED) C.error else C.primary, size = 16.dp)
        Text(title, Modifier.weight(1f).padding(horizontal = S.s), color = C.on, style = T.codeSm, maxLines = 1, overflow = TextOverflow.Ellipsis)
        meta?.let { Label(it, color = C.outline) }
        ItemStatusIcon(status)
    }
}

@Composable
private fun ItemStatusIcon(status: ItemStatus) {
    when (status) {
        ItemStatus.RUNNING, ItemStatus.PENDING -> Box(Modifier.padding(start = S.s)) { Spinner() }
        ItemStatus.FAILED -> NcIcon(Ic.CircleX, tr(R.string.chat_failed), Modifier.padding(start = S.s), tint = C.error, size = 14.dp)
        ItemStatus.CANCELLED, ItemStatus.INTERRUPTED -> NcIcon(Ic.Stop, tr(R.string.chat_stopped), Modifier.padding(start = S.s), tint = C.outline, size = 12.dp)
        else -> {}
    }
}

/** Command, exit + duration, output ≤ 256 dp with inner scroll, Copy. */
@Composable
private fun CommandCard(p: ItemPayload.CommandExecution, status: ItemStatus, modifier: Modifier) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    val exit = p.exitCode
    val failed = status == ItemStatus.FAILED || (exit != null && exit != 0)
    Column(modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(onClickLabel = if (open) tr(R.string.chat_hide_output) else tr(R.string.chat_show_output)) { open = !open }
                .padding(horizontal = S.m, vertical = S.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NcIcon(Ic.Terminal, null, tint = if (failed) C.error else C.primary, size = 16.dp)
            Text(p.command, Modifier.weight(1f).padding(horizontal = S.s), color = C.on, style = T.codeSm, maxLines = if (open) 8 else 1, overflow = TextOverflow.Ellipsis)
            when {
                status == ItemStatus.RUNNING -> Spinner()
                exit != null && exit != 0 -> Label("exit $exit", color = C.error, bold = true)
                exit == 0 -> NcIcon(Ic.Check, "Exit 0", tint = C.primary, size = 14.dp)
                else -> ItemStatusIcon(status)
            }
            p.durationMs?.let { Label(ChatLogic.duration(it), Modifier.padding(start = S.s), color = C.outline) }
        }
        if (open && p.outputTail.isNotEmpty()) Box(Modifier.fillMaxWidth().background(C.lowest)) {
            Box(Modifier.fillMaxWidth().heightIn(max = 256.dp).verticalScroll(rememberScrollState())) {
                Text(p.outputTail, Modifier.horizontalScroll(rememberScrollState()).padding(start = S.m, top = S.s, bottom = S.s, end = 48.dp),
                    color = C.onVar, style = T.codeSm, softWrap = false)
            }
            IconBtn(Ic.Copy, tr(R.string.chat_copy_output), Modifier.align(Alignment.TopEnd), tint = C.onVar, size = 14.dp, box = 40.dp) { copyText(ctx, p.outputTail) }
        }
    }
}

/** File + a/d, inline mini diff ≤ 40 lines, Diff opens page G. */
@Composable
private fun FileChangeRow(v: AgentChatView, t: ChatThread, p: ItemPayload.FileChange, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable(enabled = p.diff != null, onClickLabel = "Toggle diff") { open = !open }
                .padding(start = S.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NcIcon(Ic.Pencil, null, tint = C.primary, size = 16.dp)
            Column(Modifier.weight(1f).padding(horizontal = S.s)) {
                Text(p.path.substringAfterLast('/'), color = C.on, style = T.codeSm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                p.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { Label(it, color = C.outline, size = 10) }
            }
            AddDel(p.add, p.del)
            IconBtn(Ic.FileDiff, tr(R.string.chat_open_diff), tint = C.onVar, size = 16.dp, box = 44.dp) { v.push(Route.Diff(t.id)) }
        }
        val diff = p.diff
        if (open && diff != null) {
            val lines = remember(diff) { ChatLogic.parseUnifiedDiff(diff, p.path).flatMap { it.lines }.take(40) }
            DiffLines(lines)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanCard(v: AgentChatView, t: ChatThread, md: String, modifier: Modifier) {
    val ctx = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val (folded, isFolded) = remember(md) { ChatLogic.fold(md, 20) }
    Column(modifier) {
        Column(Modifier.fillMaxWidth().brutal(shadow = C.shadowGreen).card(C.low, C.primary).padding(S.m).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NcIcon(Ic.ClipboardList, null, tint = C.primary, size = 16.dp)
                Overline(tr(R.string.chat_proposed_plan), Modifier.padding(start = S.s), color = C.primary)
            }
            Markdown(if (expanded || !isFolded) md else folded, streaming = false, modifier = Modifier.padding(vertical = S.s))
            if (isFolded) Text(if (expanded) tr(R.string.chat_show_less) else tr(R.string.chat_show_more), Modifier.clickable { expanded = !expanded }.padding(vertical = S.xs),
                color = C.primary, style = T.label)
            FlowRow(Modifier.padding(top = S.s), horizontalArrangement = Arrangement.spacedBy(S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
                NcButton(tr(R.string.chat_implement), primary = true, icon = Ic.Check) {
                    v.bg {
                        v.backend.configure(t.id, ConfigChange(interactionMode = InteractionMode.DEFAULT))
                        v.backend.send(t.id, "PLEASE IMPLEMENT THIS PLAN:\n$md", emptyList(), steer = false)
                    }
                }
                NcButton(tr(R.string.chat_copy), icon = Ic.Copy) { copyText(ctx, md) }
            }
        }
    }
}

/** Icon and title of an error card by failure class (the eight cases of [ChatFailures] plus the generic fallback). */
private fun errorLook(cls: FailureClass): Pair<ImageVector, String> = when (cls) {
    FailureClass.CLI_MISSING -> Ic.Package to tr(R.string.chat_err_cli_missing)
    FailureClass.PERMISSION_ERROR -> Ic.Lock to tr(R.string.chat_err_signed_out)
    FailureClass.VERSION_OLD -> Ic.Download to tr(R.string.chat_err_version_old)
    FailureClass.GATE_DENIED -> Ic.ShieldAlert to tr(R.string.chat_err_pro)
    FailureClass.USAGE_LIMIT -> Ic.Gauge to tr(R.string.chat_err_limit)
    FailureClass.CRASHED -> Ic.Alert to tr(R.string.chat_err_crashed)
    FailureClass.PROTOCOL_ERROR -> Ic.Activity to tr(R.string.chat_err_protocol)
    FailureClass.NETWORK_ERROR -> Ic.Globe to tr(R.string.chat_err_network)
    else -> Ic.CircleX to cls.wire.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/**
 * Error card: icon, plain title, a short reason, and Retry / Terminal / Sign in / Update / Copy details. A version-related
 * failure (unknown flag, missing method, protocol mismatch) checks `<cli> --version` once (cached) and, when it is below the
 * catalog minimum, says so with an Update button that opens the update popup ([ChatHost.showCliUpdate]) and then retries.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ErrorRow(v: AgentChatView, t: ChatThread, item: TurnItem, p: ItemPayload.Error, modifier: Modifier) {
    val ctx = LocalContext.current
    var details by remember { mutableStateOf(false) }
    val versionLike = remember(p) { ChatFailures.versionRelated(Failure(p.cls, p.message, p.retryAt, p.details)) }
    val ver by produceState<CliVersionCheck.Result?>(null, p.cls, t.tool) {
        if (versionLike) value = withContext(Dispatchers.IO) { CliVersionCheck.check(ctx, t.tool) }
    }
    val old = ver?.takeIf { it.tooOld(CliNeed.CHAT) }
    val (icon, title) = errorLook(if (old != null) FailureClass.VERSION_OLD else p.cls)
    val reason = old?.let { tr(R.string.chat_err_too_old, it.name, it.installed.orEmpty(), it.min(CliNeed.CHAT).orEmpty()) } ?: ChatFailures.reason(p.message)
    Column(modifier) {
        Column(Modifier.fillMaxWidth().card(C.errorCon.copy(alpha = 0.14f), C.error.copy(alpha = 0.6f)).padding(S.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NcIcon(icon, null, tint = C.error, size = 16.dp)
                Text(title, Modifier.weight(1f).padding(start = S.s), color = C.error, style = T.heading.copy(fontWeight = FontWeight.SemiBold))
                p.retryAt?.let { Label("retry " + TIME.format(Date(it)), color = C.error) }
            }
            Text(reason, Modifier.padding(vertical = S.s), color = C.on, style = T.bodySm)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
                if (old != null && old.canUpdate) NcButton(tr(R.string.chat_err_update), icon = Ic.Download, primary = true) {
                    v.host.showCliUpdate(t.tool, CliNeed.CHAT) { ok -> if (ok) v.retry(t, item.runId) }
                }
                NcButton(tr(R.string.chat_retry), icon = Ic.Retry) { v.retry(t, item.runId) }
                if (p.cls == FailureClass.PERMISSION_ERROR) NcButton(tr(R.string.chat_sign_in), icon = Ic.Lock, primary = true) {
                    v.host.showCliLogin(CliVersionCheck.def(t.tool)?.id ?: t.tool)
                }
                NcButton(tr(R.string.chat_terminal), icon = Ic.Terminal) { openInTerminal(v, t) }
                NcButton(tr(R.string.chat_copy_details), icon = Ic.Copy) {
                    copyText(ctx, listOfNotNull(title, reason, p.message.takeIf { it != reason }, p.details).joinToString("\n"))
                }
                if (!p.details.isNullOrBlank()) NcButton(tr(R.string.chat_details), icon = Ic.FileText) { details = true }
            }
        }
    }
    if (details) AlertDialog(
        onDismissRequest = { details = false }, containerColor = C.low, shape = Square,
        title = { Text(tr(R.string.chat_error_details), style = T.title, color = C.on) },
        text = { Text(p.details.orEmpty(), Modifier.verticalScroll(rememberScrollState()), style = T.codeSm, color = C.onVar) },
        confirmButton = { NcButton(tr(R.string.chat_close)) { details = false } },
    )
}

/** Turn footer: time · duration · tokens · cost · TTFT · status · copy; long-press = full timestamp + token split. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MetaRow(run: Run, text: String?, ttft: Long?) {
    val ctx = LocalContext.current
    var more by remember { mutableStateOf(false) }
    val u = run.usage
    val dur = run.completedAt?.let { c -> run.startedAt?.let { c - it } }
    val parts = listOfNotNull(
        run.completedAt?.let { if (more) FULL.format(Date(it)) else TIME.format(Date(it)) },
        dur?.let { ChatLogic.duration(it) },
        u?.let {
            if (more) "${ChatLogic.tokens(it.inputTokens)} in · ${ChatLogic.tokens(it.outputTokens)} out" +
                (if (it.cacheReadTokens > 0) " · ${ChatLogic.tokens(it.cacheReadTokens)} cached" else "") +
                (if (it.reasoningTokens > 0) " · ${ChatLogic.tokens(it.reasoningTokens)} reasoning" else "")
            else "${ChatLogic.tokens(it.inputTokens + it.outputTokens)} tok"
        },
        ChatLogic.cost(u?.costUsd),
        ttft?.let { "first token ${ChatLogic.duration(it)}" },
    )
    Row(
        Modifier.fillMaxWidth().combinedClickable(onLongClickLabel = tr(R.string.chat_show_details), onLongClick = { more = !more }, onClick = {})
            .padding(start = S.l, end = S.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(S.s), verticalArrangement = Arrangement.spacedBy(S.xs)) {
            parts.forEach { Text(it, color = C.outline, style = T.meta) }
            if (run.status != RunStatus.COMPLETED) StatusChip(
                run.status.wire.replaceFirstChar { it.uppercase() },
                if (run.status == RunStatus.FAILED) C.error else C.tertiary,
            )
        }
        if (text != null) IconBtn(Ic.Copy, "Copy reply", tint = C.outline, size = 14.dp, box = 40.dp) { copyText(ctx, text) }
    }
}

@Composable
internal fun DiffLines(lines: List<DiffLine>) {
    Column(Modifier.fillMaxWidth().background(C.lowest).horizontalScroll(rememberScrollState())) {
        lines.forEach { l ->
            val (bg, fg, bar) = when (l.kind) {
                '+' -> Triple(C.addBg, C.primary, C.primary)
                '-' -> Triple(C.delBg, C.error, C.error)
                '@' -> Triple(C.container, C.outline, Color.Transparent)
                else -> Triple(Color.Transparent, C.onVar, Color.Transparent)
            }
            Row(Modifier.background(bg).height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(bar))
                Text((l.newNo ?: l.oldNo)?.toString()?.padStart(4) ?: "    ", Modifier.padding(start = S.gap, end = S.s), color = C.outline, style = T.codeSm)
                Text(if (l.kind == '@') l.text else "${l.kind} ${l.text}", Modifier.padding(end = S.m), color = fg, style = T.codeSm, softWrap = false,
                    fontWeight = if (l.kind == '@') FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
