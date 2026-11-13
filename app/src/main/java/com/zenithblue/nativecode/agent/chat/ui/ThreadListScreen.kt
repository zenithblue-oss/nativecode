package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Screens A (TERMINAL tab, grouped by project) and B (workspace, one project). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ThreadListScreen(v: AgentChatView) {
    val rows = v.rows.value
    val now = v.now.value
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val collapsed = remember { mutableStateMapOf(ChatLogic.ARCHIVED to true) }
    var termOpen by remember { mutableStateOf(true) }
    val tabs = remember(rows) { v.host.terminalTabs(v.isWorkspace) }

    // Live timers tick once a second while something works.
    LaunchedEffect(rows.any { it.status == ThreadStatus.WORKING }) {
        while (rows.any { it.status == ThreadStatus.WORKING }) { v.now.value = System.currentTimeMillis(); delay(1000) }
        v.now.value = System.currentTimeMillis()
    }

    fun archive(t: ChatThread, on: Boolean) {
        v.bg { v.backend.archive(t.id, on) }
        scope.launch {
            if (snack.showSnackbar(if (on) "Archived" else "Restored", "UNDO", duration = androidx.compose.material3.SnackbarDuration.Short) == SnackbarResult.ActionPerformed)
                v.bg { v.backend.archive(t.id, !on) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(if (v.isWorkspace) "THREADS" else "AGENT THREADS", Modifier.weight(1f), bold = true)
                SessionsChip(v)
            }
            Hairline()
            val groups = ChatLogic.group(rows.map { it.thread }, byProject = !v.isWorkspace)
            val byId = rows.associateBy { it.thread.id }
            val active = rows.count { it.thread.archivedAt == null }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                if (tabs.isNotEmpty()) {
                    item("term-h") { GroupHeader("TERMINALS", tabs.size, !termOpen) { termOpen = !termOpen } }
                    if (termOpen) items(tabs, key = { "tab" + it.index }) { tab ->
                        Row(
                            Modifier.fillMaxWidth().combinedClickable { v.host.openTerminalTab(tab.index, v.isWorkspace) }
                                .heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Label(">_", color = C.primary, size = 13)
                            Text(tab.label, Modifier.weight(1f).padding(start = 10.dp), color = C.on, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Label("›", size = 16)
                        }
                    }
                }
                if (active == 0 && v.loaded.value) item("empty") { EmptyState(v) }
                groups.forEach { g ->
                    val header = g.key == ChatLogic.ARCHIVED || !v.isWorkspace
                    val isCollapsed = collapsed[g.key] ?: false
                    if (header) item("g:" + g.key) { GroupHeader(g.title.uppercase(), g.threads.size, isCollapsed) { collapsed[g.key] = !isCollapsed } }
                    if (!isCollapsed) items(g.threads, key = { it.id }) { t ->
                        byId[t.id]?.let { ThreadRowView(v, it, now) { on -> archive(t, on) } }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            NcButton("✎  New", primary = true) { v.sheet.value = true }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }
}

@Composable
private fun GroupHeader(title: String, count: Int, collapsed: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(C.lowest).combinedClickableCompat(onClick).heightIn(min = 36.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(if (collapsed) "▸" else "▾", color = C.primary, size = 12)
        Label(title, Modifier.weight(1f).padding(start = 8.dp), color = C.on, bold = true)
        Label("($count)")
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLong: (() -> Unit)? = null) =
    combinedClickable(onLongClick = onLong, onClick = onClick)

internal fun statusLabel(s: ThreadStatus): Pair<String, Color>? = when (s) {
    ThreadStatus.APPROVAL -> "⛨ Approval" to C.tertiary
    ThreadStatus.INPUT -> "? Input needed" to C.tertiary
    ThreadStatus.WORKING -> "● Working" to C.primary
    ThreadStatus.FAILED -> "✗ Failed" to C.error
    ThreadStatus.DONE -> "Done •" to C.primary
    ThreadStatus.IDLE -> null
}

@Composable
private fun ThreadRowView(v: AgentChatView, r: ThreadRow, now: Long, onArchive: (Boolean) -> Unit) {
    val t = r.thread
    val ctx = LocalContext.current
    var dx by remember(t.id) { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val archived = t.archivedAt != null
    Box(
        Modifier.fillMaxWidth().background(C.errorCon.copy(alpha = 0.35f))
            .pointerInput(t.id) {
                detectHorizontalDragGestures(
                    onDragEnd = { if (dx < -size.width * 0.35f) onArchive(!archived); dx = 0f },
                    onDragCancel = { dx = 0f },
                ) { _, d -> dx = (dx + d).coerceIn(-size.width.toFloat(), 0f) }
            },
    ) {
        Label(if (archived) "RESTORE" else "ARCHIVE", Modifier.align(Alignment.CenterEnd).padding(end = 20.dp), color = C.on, bold = true)
        Column(
            Modifier.offset { IntOffset(dx.roundToInt(), 0) }.fillMaxWidth().background(C.bg)
                .combinedClickableCompat(onClick = { v.push(Route.Chat(t.id)) }, onLong = { menu = true })
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.pinnedAt != null) Label("📌 ", size = 11)
                Text(
                    t.title.ifBlank { "New thread" }, Modifier.weight(1f), color = C.on, fontSize = 15.sp,
                    fontWeight = if (r.status == ThreadStatus.DONE) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val time = if (r.status == ThreadStatus.WORKING) r.lastRun?.startedAt?.let { ChatLogic.timer(now - it) }
                else ChatLogic.relativeTime(now, t.updatedAt)
                Label(time.orEmpty(), Modifier.padding(start = 8.dp), color = if (r.status == ThreadStatus.WORKING) C.primary else C.onVar)
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                statusLabel(r.status)?.let { (s, c) -> Label(s, color = c, bold = true) }
                Label(t.tool)
                if (!v.isWorkspace && t.projectPath != null) Label(ChatLogic.baseName(t.projectPath), Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                if (r.add + r.del > 0) {
                    Label("+${r.add}", color = C.primary)
                    Label("−${r.del}", color = C.error)
                }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            MItem("Rename") { menu = false; rename = true }
            MItem(if (t.pinnedAt != null) "Unpin" else "Pin") { menu = false; v.bg { v.backend.pin(t.id, t.pinnedAt == null) } }
            MItem("Open in terminal") { menu = false; openInTerminal(v, t) }
            MItem("Open terminal here") { menu = false; v.host.openShellHere(t.cwd, v.isWorkspace) }
            MItem("Copy path") { menu = false; copyText(ctx, t.cwd) }
            MItem(if (archived) "Unarchive" else "Archive") { menu = false; onArchive(!archived) }
            MItem("Delete") { menu = false; delete = true }
        }
    }
    if (rename) RenameDialog(t.title, { rename = false }) { n -> v.bg { v.backend.rename(t.id, n) } }
    if (delete) DeleteDialog(t.title, { delete = false }) { v.bg { v.backend.delete(t.id) } }
}

@Composable
internal fun MItem(label: String, onClick: () -> Unit) =
    DropdownMenuItem({ Text(label, fontFamily = Mono, fontSize = 13.sp) }, onClick)

/** Close the chat process first (Free has one slot; one writer per session), then the CLI TUI. Resume flags unverified → fresh start. */
internal fun openInTerminal(v: AgentChatView, t: ChatThread) {
    v.bg { v.backend.stop(t.id) }
    v.host.openTerminal(t.tool, emptyList(), t.cwd, v.isWorkspace)
}

@Composable
internal fun RenameDialog(current: String, dismiss: () -> Unit, ok: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = dismiss, containerColor = C.low, shape = Square,
        title = { Text("Rename thread", fontFamily = Mono, fontSize = 15.sp) },
        text = { OutlinedTextField(text, { text = it.take(120) }, singleLine = true, shape = Square) },
        confirmButton = { NcButton("Save", primary = true, enabled = text.isNotBlank()) { ok(text.trim()); dismiss() } },
        dismissButton = { NcButton("Cancel") { dismiss() } },
    )
}

@Composable
internal fun DeleteDialog(title: String, dismiss: () -> Unit, ok: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss, containerColor = C.low, shape = Square,
        title = { Text("Delete \"$title\"?", fontFamily = Mono, fontSize = 15.sp) },
        text = { Text("Stops the agent, deletes the history and this thread's change checkpoints. Files in the project are not touched.", fontSize = 13.sp, color = C.onVar) },
        confirmButton = { NcButton("Delete", danger = true) { ok(); dismiss() } },
        dismissButton = { NcButton("Cancel") { dismiss() } },
    )
}

/** ● used/max; tap lists live chats with Stop. */
@Composable
private fun SessionsChip(v: AgentChatView) {
    var open by remember { mutableStateOf(false) }
    // ponytail: 1 s poll; terminal tabs and other scopes' chats have no change feed into this view
    var usage by remember { mutableStateOf(v.host.sessionUsage()) }
    LaunchedEffect(Unit) { while (true) { usage = v.host.sessionUsage(); delay(1_000) } }
    val (used, max) = usage
    Box {
        Chip("● $used/$max${if (!v.host.isPro()) " Free" else ""}", on = used > 0) { open = true }
        DropdownMenu(open, { open = false }) {
            val live = v.backend.liveThreadIds()
            val titles = v.rows.value.associate { it.thread.id to it.thread.title }
            if (live.isEmpty()) DropdownMenuItem({ Text("No live chats · $used/$max sessions in use", fontSize = 13.sp) }, { open = false })
            live.forEach { id ->
                DropdownMenuItem(
                    { Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(titles[id] ?: "Chat", Modifier.width(180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                        Label("STOP", color = C.error, bold = true)
                    } },
                    { open = false; v.bg { v.backend.stop(id) } },
                )
            }
        }
    }
}

/** No threads yet: the tool cards, or "Install AI tools". */
@Composable
private fun EmptyState(v: AgentChatView) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Start an agent thread", color = C.on, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("Chat with a CLI agent here, or flip to Terminal for the raw TUI.", color = C.onVar, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        if (!v.host.aiToolsInstalled()) {
            NcButton("Install AI tools", primary = true) { v.host.openInstallTools() }
        } else ToolCards(v, v.projectPath)
    }
}
