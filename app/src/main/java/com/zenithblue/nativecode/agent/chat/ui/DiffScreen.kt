package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screen G: This turn · Whole thread · Uncommitted; file list + unified diff (own small renderer, sticky file headers). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiffScreen(v: AgentChatView, threadId: String) {
    var scope by remember { mutableStateOf(DiffScope.TURN) }
    var pick by remember { mutableStateOf(false) }
    val files by produceState<List<DiffFile>?>(null, scope) {
        value = null
        val patch = withContext(Dispatchers.IO) { runCatching { v.backend.diff(threadId, scope) }.getOrNull() }
            ?: protocolDiff(v, scope)
        value = withContext(Dispatchers.Default) { ChatLogic.parseUnifiedDiff(patch.orEmpty()) }
    }
    val closed = remember(scope) { mutableStateMapOf<Int, Boolean>() }
    val list = rememberLazyListState()
    val co = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(C.lowest).heightIn(min = 56.dp).padding(end = S.s), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, "Back") { v.pop() }
            Text("Changes", Modifier.weight(1f), color = C.on, style = T.title)
            Box {
                Chip(scope.label, Modifier.semantics { contentDescription = "Scope ${scope.label}, change" }, trailing = Ic.ChevronDown) { pick = true }
                DropdownMenu(pick, { pick = false }, containerColor = C.low) {
                    DiffScope.entries.forEach { s -> MItem(s.label, if (s == scope) Ic.Check else null) { scope = s; pick = false } }
                }
            }
        }
        Hairline()
        val f = files
        when {
            f == null -> LoadingRows()
            f.isEmpty() -> Column(Modifier.fillMaxSize().padding(S.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.padding(top = S.xxl).size(48.dp).card(C.low), contentAlignment = Alignment.Center) { NcIcon(Ic.FileDiff, null, tint = C.outline, size = 22.dp) }
                Text(if (scope == DiffScope.UNCOMMITTED) "No uncommitted changes" else "No changes recorded", Modifier.padding(top = S.l), color = C.on, style = T.heading)
                Text(if (scope == DiffScope.UNCOMMITTED) "The working tree matches HEAD, or this is not a git repository." else "This turn did not edit files, or the project is not a git repository.",
                    Modifier.padding(top = S.xs), color = C.onVar, style = T.bodySm, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(Modifier.fillMaxSize(), state = list) {
                item("sum") {
                    val add = f.sumOf { it.add }; val del = f.sumOf { it.del }
                    Column(Modifier.fillMaxWidth().padding(S.l)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NcIcon(Ic.FileDiff, null, tint = C.primary, size = 16.dp)
                            Text("${f.size} file${if (f.size == 1) "" else "s"} changed", Modifier.weight(1f).padding(start = S.s), color = C.on, style = T.heading)
                            AddDel(add, del, 12)
                        }
                        // Add / delete proportion bar.
                        Row(Modifier.padding(top = S.s).fillMaxWidth().height(4.dp).background(C.border)) {
                            if (add + del > 0) {
                                if (add > 0) Box(Modifier.weight(add.toFloat()).height(4.dp).background(C.primary))
                                if (del > 0) Box(Modifier.weight(del.toFloat()).height(4.dp).background(C.error))
                            }
                        }
                    }
                }
                itemsIndexed(f, key = { i, _ -> "l$i" }) { i, file ->
                    Row(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "Jump to ${file.path.substringAfterLast('/')}") {
                            co.launch { list.animateScrollToItem(2 + f.size + i * 2) }
                        }.heightIn(min = 44.dp).padding(horizontal = S.l),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KindBadge(file.kind)
                        Column(Modifier.weight(1f).padding(horizontal = S.m)) {
                            Text(file.path.substringAfterLast('/'), color = C.on, style = T.codeSm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            file.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { Label(it, color = C.outline, size = 10) }
                        }
                        AddDel(file.add, file.del)
                    }
                }
                item("gap") { Box(Modifier.height(S.s)) }
                f.forEachIndexed { i, file ->
                    val open = closed[i] != true
                    stickyHeader("h$i") { FileHeader(file, open) { closed[i] = open } }
                    item("b$i") {
                        if (open) {
                            DiffLines(file.lines.take(MAX_LINES))
                            if (file.lines.size > MAX_LINES) Label("[truncated: ${file.lines.size - MAX_LINES} more lines]", Modifier.padding(S.m))
                        }
                    }
                }
                item("end") { Box(Modifier.height(S.xl)) }
            }
        }
    }
}

@Composable
private fun KindBadge(kind: Char) {
    val (c, name) = when (kind) {
        'A' -> C.primary to "Added"
        'D' -> C.error to "Deleted"
        'R' -> C.tertiary to "Renamed"
        else -> C.onVar to "Modified"
    }
    Box(
        Modifier.size(20.dp).background(c.copy(alpha = 0.14f)).semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) { Label("$kind", color = c, size = 11, bold = true) }
}

@Composable
private fun FileHeader(f: DiffFile, open: Boolean, toggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.container)) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Collapse file" else "Expand file", onClick = toggle).heightIn(min = 44.dp).padding(horizontal = S.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chevron(open, size = 14.dp)
            Text(f.path, Modifier.weight(1f).padding(horizontal = S.s), color = C.on, style = T.codeSm, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            KindBadge(f.kind)
            Box(Modifier.padding(start = S.s)) { Row { AddDel(f.add, f.del) } }
        }
        Hairline()
    }
}

/** Non-git cwd or no checkpoints: the per-item diffs the protocol sent. */
private fun protocolDiff(v: AgentChatView, scope: DiffScope): String? {
    if (scope == DiffScope.UNCOMMITTED) return null
    val items = v.open.value?.items.orEmpty()
    val lastRun = items.lastOrNull { it.payload is ItemPayload.FileChange }?.runId
    return items.filter { scope == DiffScope.THREAD || it.runId == lastRun }
        .mapNotNull { i -> (i.payload as? ItemPayload.FileChange)?.let { p -> p.diff?.let { d -> if (d.startsWith("diff --git") || d.startsWith("--- ")) d else "--- a/${p.path}\n+++ b/${p.path}\n$d" } } }
        .joinToString("\n").ifEmpty { null }
}

/** Collapsible file header + unified lines (capped); used inline (approval card). */
@Composable
internal fun DiffFileSection(f: DiffFile, startOpen: Boolean) {
    var open by remember { mutableStateOf(startOpen) }
    Column(Modifier.fillMaxWidth().card(C.lowest).animateContentSize()) {
        FileHeader(f, open) { open = !open }
        if (open) {
            DiffLines(f.lines.take(MAX_LINES))
            if (f.lines.size > MAX_LINES) Label("[truncated: ${f.lines.size - MAX_LINES} more lines]", Modifier.padding(S.s))
        }
    }
}

private const val MAX_LINES = 1500
