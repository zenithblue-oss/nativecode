package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screen G: This turn · Whole thread · Uncommitted; file list + unified diff (own small renderer). */
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
    val list = rememberLazyListState()
    val co = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(C.lowest).padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("←", Modifier.clickable { v.pop() }.padding(12.dp), color = C.on, size = 16)
            Text("Changes", Modifier.weight(1f), color = C.on, fontSize = 16.sp)
            Box {
                Chip(scope.label + " ▾") { pick = true }
                DropdownMenu(pick, { pick = false }) { DiffScope.entries.forEach { s -> MItem(s.label) { scope = s; pick = false } } }
            }
        }
        Hairline()
        val f = files
        when {
            f == null -> Label("Loading…", Modifier.padding(16.dp))
            f.isEmpty() -> Label(if (scope == DiffScope.UNCOMMITTED) "No uncommitted changes (or not a git repo)" else "No changes recorded", Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), state = list) {
                item("sum") {
                    Label("▾ ${f.size} files   +${f.sumOf { it.add }} −${f.sumOf { it.del }}", Modifier.padding(12.dp), color = C.on, bold = true)
                }
                itemsIndexed(f, key = { i, _ -> "l$i" }) { i, file ->
                    Row(Modifier.fillMaxWidth().clickable { co.launch { list.animateScrollToItem(1 + f.size + i) } }.heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Label("${file.kind}", color = C.tertiary, bold = true)
                        Label(file.path, Modifier.weight(1f).padding(horizontal = 8.dp), color = C.on)
                        Label("+${file.add}", color = C.primary); Label(" −${file.del}", color = C.error)
                    }
                }
                itemsIndexed(f, key = { i, _ -> "f$i" }) { _, file -> DiffFileSection(file, startOpen = true) }
            }
        }
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

/** Collapsible file header + unified lines (capped). */
@Composable
internal fun DiffFileSection(f: DiffFile, startOpen: Boolean) {
    var open by remember { mutableStateOf(startOpen) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().background(C.container).clickable { open = !open }.heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Label(if (open) "▾ " else "▸ ", color = C.primary)
            Label(f.path.substringAfterLast('/'), Modifier.weight(1f), color = C.on, bold = true)
            Label("${f.kind}  ", color = C.tertiary)
            Label("+${f.add}", color = C.primary); Label("−${f.del}", color = C.error)
        }
        if (open) {
            DiffLines(f.lines.take(MAX_LINES))
            if (f.lines.size > MAX_LINES) Label("[truncated: ${f.lines.size - MAX_LINES} more lines]", Modifier.padding(8.dp))
        }
    }
}

private const val MAX_LINES = 1500
