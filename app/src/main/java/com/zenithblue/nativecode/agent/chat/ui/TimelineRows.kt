package com.zenithblue.nativecode.agent.chat.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One timeline row. [liveRun] = the run streaming now (its work group stays expanded, text stays plain). */
@Composable
internal fun TimelineRow(v: AgentChatView, t: ChatThread, row: Row, liveRun: String?, lastText: (String?) -> String?) {
    when (row) {
        is Row.Work -> WorkGroup(v, t, row.items, live = row.runId != null && row.runId == liveRun)
        is Row.Meta -> MetaRow(row.run, lastText(row.run.id))
        is Row.Item -> ItemRow(v, t, row.item, row.item.runId != null && row.item.runId == liveRun)
    }
}

@Composable
private fun ItemRow(v: AgentChatView, t: ChatThread, item: TurnItem, live: Boolean) {
    val pad = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    when (val p = item.payload) {
        is ItemPayload.UserMessage -> UserBubble(p, pad)
        is ItemPayload.AssistantMessage -> Markdown(p.text, streaming = live && item.status == ItemStatus.RUNNING, modifier = pad)
        is ItemPayload.Reasoning -> Reasoning(p, item.status == ItemStatus.RUNNING, pad)
        is ItemPayload.ProposedPlan -> PlanCard(v, t, p.markdown, pad)
        is ItemPayload.ApprovalRequest -> Label("⛨ Approval · ${item.status.wire}", pad, color = C.tertiary)
        is ItemPayload.UserInputRequest -> Label("? Question · ${item.status.wire}", pad, color = C.tertiary)
        is ItemPayload.Error -> ErrorRow(v, t, item, p, pad)
        is ItemPayload.Compaction -> Label("── context compacted${p.tokens?.let { " · ${ChatLogic.tokens(it)} tok" } ?: ""} ──", pad, color = C.outline)
        is ItemPayload.SystemNotice -> Label(p.text, pad, color = C.outline)
        else -> WorkItem(v, t, item, pad)
    }
}

@Composable
internal fun UserBubble(p: ItemPayload.UserMessage, modifier: Modifier) {
    val ctx = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val (folded, isFolded) = remember(p.text) { ChatLogic.fold(p.text) }
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.85f).dp
    Box(modifier, contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.widthIn(max = maxW).background(C.container).border(1.dp, C.border).padding(10.dp)) {
            if (p.intent != "start") Label(if (p.intent == "steer") "↪ steer" else "⏳ queued", color = C.tertiary)
            Text(if (expanded || !isFolded) p.text else folded, color = C.on, fontSize = 15.sp, lineHeight = 21.sp)
            if (p.attachments.isNotEmpty()) Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.attachments.forEach { AttachmentThumb(it) }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (isFolded) Label(if (expanded) "Show less" else "Show more", Modifier.clickable { expanded = !expanded }, color = C.primary)
                Label("⧉", Modifier.clickable { copyText(ctx, p.text) }.padding(horizontal = 4.dp))
            }
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
    else Label("📄 ${a.name}", Modifier.border(1.dp, C.border).padding(6.dp))
}

private fun decodeThumb(path: String): ImageBitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var s = 1
    while (o.outWidth / (s * 2) >= 128 && o.outHeight / (s * 2) >= 128) s *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}.getOrNull()

@Composable
private fun Reasoning(p: ItemPayload.Reasoning, running: Boolean, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        val secs = p.durationMs?.let { (it + 500) / 1000 }
        Label((if (open) "▾ " else "▸ ") + if (running) "Thinking…" else "Thought${secs?.let { " for ${it}s" } ?: ""}",
            Modifier.clickable { open = !open }.padding(vertical = 4.dp))
        if (open && p.text.isNotBlank()) Text(p.text, Modifier.padding(start = 12.dp), color = C.onVar, fontSize = 13.sp)
    }
}

/** Collapsible work log of one run; the live turn stays expanded. */
@Composable
private fun WorkGroup(v: AgentChatView, t: ChatThread, items: List<TurnItem>, live: Boolean) {
    var open by remember(live) { mutableStateOf(live) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Label((if (open) "▾ " else "▸ ") + ChatLogic.workSummary(items), Modifier.weight(1f), color = C.onVar)
            if (items.any { it.status == ItemStatus.FAILED }) Label("✗", color = C.error)
        }
        if (open) Column(Modifier.padding(start = 8.dp)) { items.forEach { WorkItem(v, t, it, Modifier.fillMaxWidth().padding(vertical = 3.dp)) } }
    }
}

@Composable
private fun WorkItem(v: AgentChatView, t: ChatThread, item: TurnItem, modifier: Modifier) {
    when (val p = item.payload) {
        is ItemPayload.CommandExecution -> CommandCard(p, item.status, modifier)
        is ItemPayload.FileChange -> FileChangeRow(v, t, p, modifier)
        is ItemPayload.FileSearch -> Label("⌕ ${p.tool} ${p.pattern} · ${p.results.size}", modifier)
        is ItemPayload.WebSearch -> Label("🌐 ${p.query} · ${p.results.size}", modifier)
        is ItemPayload.DynamicTool -> Label("⚙ ${p.name}${if (item.status == ItemStatus.RUNNING) " …" else ""}", modifier)
        is ItemPayload.Subagent -> Label("⧉ ${p.title}${if (p.childCount > 0) " · ${p.childCount}" else ""}", modifier)
        else -> {}
    }
}

/** >_ command, exit + duration, output ≤ 256 dp with inner scroll, Copy. */
@Composable
private fun CommandCard(p: ItemPayload.CommandExecution, status: ItemStatus, modifier: Modifier) {
    val ctx = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.border(1.dp, C.border).background(C.lowest)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(">_", color = C.primary)
            Text(p.command, Modifier.weight(1f).padding(horizontal = 8.dp), color = C.on, fontFamily = Mono, fontSize = 13.sp, maxLines = if (open) 8 else 1)
            val exit = p.exitCode
            when {
                status == ItemStatus.RUNNING -> Label("…", color = C.primary)
                exit != null && exit != 0 -> Label("✗ exit $exit", color = C.error)
                exit == 0 -> Label("✓", color = C.primary)
            }
            p.durationMs?.let { Label(" %.1fs".format(it / 1000f)) }
        }
        if (open && p.outputTail.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(max = 256.dp).verticalScroll(rememberScrollState())) {
                Text(p.outputTail, Modifier.horizontalScroll(rememberScrollState()).padding(8.dp), color = C.onVar, fontFamily = Mono, fontSize = 12.sp, softWrap = false)
            }
            Label("⧉ Copy output", Modifier.clickable { copyText(ctx, p.outputTail) }.padding(8.dp), color = C.primary)
        }
    }
}

/** ✎ path +a −d, inline mini diff ≤ 40 lines, Diff › opens page G. */
@Composable
private fun FileChangeRow(v: AgentChatView, t: ChatThread, p: ItemPayload.FileChange, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.border(1.dp, C.border)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("✎", color = C.primary)
            Text(p.path.substringAfterLast('/'), Modifier.weight(1f).padding(horizontal = 8.dp), color = C.on, fontFamily = Mono, fontSize = 13.sp, maxLines = 1)
            Label("+${p.add}", color = C.primary); Label(" −${p.del}", color = C.error)
            Label("  Diff ›", Modifier.clickable { v.push(Route.Diff(t.id)) }, color = C.primary, bold = true)
        }
        val diff = p.diff
        if (open && diff != null) {
            val lines = remember(diff) { ChatLogic.parseUnifiedDiff(diff, p.path).flatMap { it.lines }.take(40) }
            DiffLines(lines)
        }
    }
}

@Composable
private fun PlanCard(v: AgentChatView, t: ChatThread, md: String, modifier: Modifier) {
    val ctx = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val (folded, isFolded) = remember(md) { ChatLogic.fold(md, 20) }
    Column(modifier.border(1.dp, C.primary).padding(10.dp)) {
        Label("PROPOSED PLAN", color = C.primary, bold = true)
        Markdown(if (expanded || !isFolded) md else folded, streaming = false)
        if (isFolded) Label(if (expanded) "Show less" else "Show more", Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp), color = C.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NcButton("Implement", primary = true) {
                v.bg {
                    v.backend.configure(t.id, ConfigChange(interactionMode = InteractionMode.DEFAULT))
                    v.backend.send(t.id, "PLEASE IMPLEMENT THIS PLAN:\n$md", emptyList(), steer = false)
                }
            }
            NcButton("Copy") { copyText(ctx, md) }
        }
    }
}

@Composable
private fun ErrorRow(v: AgentChatView, t: ChatThread, item: TurnItem, p: ItemPayload.Error, modifier: Modifier) {
    var details by remember { mutableStateOf(false) }
    Column(modifier.border(1.dp, C.error).padding(10.dp)) {
        Label("✗ ${p.cls.wire.replace('_', ' ')}", color = C.error, bold = true)
        Text(p.message, color = C.on, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NcButton("Retry") { v.retry(t, item.runId) }
            NcButton("Open in terminal") { openInTerminal(v, t) }
            if (!p.details.isNullOrBlank()) NcButton("Details") { details = true }
        }
    }
    if (details) AlertDialog(
        onDismissRequest = { details = false }, containerColor = C.low, shape = Square,
        title = { Text("Details", fontFamily = Mono, fontSize = 15.sp) },
        text = { Text(p.details.orEmpty(), Modifier.verticalScroll(rememberScrollState()), fontFamily = Mono, fontSize = 12.sp, color = C.onVar) },
        confirmButton = { NcButton("Close") { details = false } },
    )
}

/** time · tokens · cost · copy. */
@Composable
private fun MetaRow(run: Run, text: String?) {
    val ctx = LocalContext.current
    val u = run.usage
    val parts = listOfNotNull(
        run.completedAt?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) },
        u?.let { "${ChatLogic.tokens(it.inputTokens + it.outputTokens)} tok" },
        u?.costUsd?.takeIf { it > 0 }?.let { "$%.3f".format(it) },
        run.status.wire.takeIf { run.status != com.zenithblue.nativecode.agent.chat.core.RunStatus.COMPLETED },
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(parts.joinToString(" · "), color = C.outline)
        if (text != null) Label("  ⧉", Modifier.clickable { copyText(ctx, text) }.padding(4.dp), color = C.outline)
    }
}

@Composable
internal fun DiffLines(lines: List<DiffLine>) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        lines.forEach { l ->
            val (bg, fg) = when (l.kind) {
                '+' -> C.addBg to C.primary
                '-' -> C.delBg to C.error
                '@' -> C.container to C.outline
                else -> C.bg to C.onVar
            }
            Row(Modifier.background(bg).padding(horizontal = 6.dp)) {
                Text((l.newNo ?: l.oldNo)?.toString()?.padStart(4) ?: "    ", color = C.outline, fontFamily = Mono, fontSize = 12.sp)
                Text(" ${if (l.kind == '@') "" else l.kind}${l.text}", color = fg, fontFamily = Mono, fontSize = 12.sp, softWrap = false,
                    fontWeight = if (l.kind == '@') FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
