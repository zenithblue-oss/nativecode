package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/** Screens A (TERMINAL tab, grouped by project) and B (workspace, one project). */
@Composable
internal fun ThreadListScreen(v: AgentChatView) {
    val rows = v.rows.value.filterNot { it.draft } // unsent drafts are not threads yet
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
            if (snack.showSnackbar(if (on) tr(R.string.chat_archived) else tr(R.string.chat_restored), tr(R.string.chat_undo), duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed)
                v.bg { v.backend.archive(t.id, !on) }
        }
    }

    val active = rows.count { it.thread.archivedAt == null }
    // No thread at all: the page is a new-thread composer (t3code), unless the AI tools are not installed yet.
    val pane = rows.isEmpty() && v.loaded.value && v.host.aiToolsInstalled()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val working = rows.count { it.status == ThreadStatus.WORKING }
            val waiting = rows.count { it.status == ThreadStatus.APPROVAL || it.status == ThreadStatus.INPUT }
            Row(Modifier.fillMaxWidth().background(C.lowest).heightIn(min = 56.dp).padding(start = S.l, end = S.xs), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (v.isWorkspace) tr(R.string.chat_threads) else tr(R.string.chat_agent_threads), color = C.on, style = T.title)
                    Text(
                        listOfNotNull("$active thread${if (active == 1) "" else "s"}", "$working working".takeIf { working > 0 }, "$waiting waiting".takeIf { waiting > 0 })
                            .joinToString(" · "),
                        color = C.outline, style = T.meta,
                    )
                }
                IconBtn(Ic.Terminal, tr(R.string.chat_open_terminal_page), tint = C.onVar) { v.host.showTerminal(v.isWorkspace) }
                SessionsChip(v)
            }
            Hairline()
            val groups = ChatLogic.group(rows.map { it.thread }, byProject = !v.isWorkspace)
            val byId = rows.associateBy { it.thread.id }
            if (pane) NewThreadPane(v) else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = FAB_CLEAR)) {
                if (!v.loaded.value) item("loading") { LoadingRows() }
                if (tabs.isNotEmpty()) {
                    item("term-h") { GroupHeader(tr(R.string.chat_terminals), tabs.size, !termOpen, Ic.Terminal) { termOpen = !termOpen } }
                    if (termOpen) items(tabs, key = { "tab" + it.index }) { tab ->
                        Row(
                            Modifier.fillMaxWidth().combinedClickable(onClickLabel = tr(R.string.chat_open_terminal_tab)) { v.host.openTerminalTab(tab.index, v.isWorkspace) }
                                .heightIn(min = 48.dp).padding(horizontal = S.l),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(28.dp).card(C.lowest), contentAlignment = Alignment.Center) { NcIcon(Ic.Terminal, null, tint = C.primary, size = 14.dp) }
                            Text(tab.label, Modifier.weight(1f).padding(start = S.m), color = C.on, style = T.codeSm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            NcIcon(Ic.ChevronRight, null, tint = C.outline, size = 16.dp)
                        }
                    }
                }
                if (active == 0 && v.loaded.value && !v.host.aiToolsInstalled()) item("empty") { EmptyState(v) }
                groups.forEach { g ->
                    val header = g.key == ChatLogic.ARCHIVED || !v.isWorkspace
                    val isCollapsed = collapsed[g.key] ?: false
                    if (header) item("g:" + g.key) {
                        GroupHeader(g.title, g.threads.size, isCollapsed, if (g.key == ChatLogic.ARCHIVED) Ic.Archive else Ic.Folder) { collapsed[g.key] = !isCollapsed }
                    }
                    if (!isCollapsed) items(g.threads, key = { it.id }) { t ->
                        byId[t.id]?.let { ThreadRowView(v, it, now) { on -> archive(t, on) } }
                    }
                }
            }
        }
        if (!pane) Row(
            Modifier.align(Alignment.BottomEnd).padding(S.l).brutal(4.dp, C.shadowGreen).background(C.primary)
                .combinedClickable(onClickLabel = tr(R.string.chat_new_thread)) { v.newThread() }
                .heightIn(min = 48.dp).padding(horizontal = S.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NcIcon(Ic.SquarePen, null, tint = C.onPrimary, size = 18.dp)
            Text(tr(R.string.chat_new_thread_2), Modifier.padding(start = S.s), color = C.onPrimary, style = T.overline)
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = FAB_CLEAR)) { d ->
            Snackbar(d, shape = Square, containerColor = C.high, contentColor = C.on, actionColor = C.primary)
        }
    }
}

private val ROW_LOGO = 36.dp
/** Room the list and snackbar keep free under the NEW THREAD button (48 dp + its 16 dp margins + shadow). */
private val FAB_CLEAR = 48.dp + S.l * 2 + S.s

@Composable
private fun GroupHeader(title: String, count: Int, collapsed: Boolean, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(C.bg).combinedClickable(onClickLabel = if (collapsed) "Expand $title" else "Collapse $title", onClick = onClick)
            .heightIn(min = 40.dp).padding(start = S.l, end = S.l, top = S.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(!collapsed, size = 14.dp)
        NcIcon(icon, null, Modifier.padding(start = S.xs), tint = C.outline, size = 14.dp)
        Overline(title, Modifier.weight(1f).padding(start = S.s), color = C.onVar)
        Box(Modifier.background(C.container).padding(horizontal = S.s, vertical = S.xs)) { Label("$count", color = C.onVar, size = 10, bold = true) }
    }
}

internal fun statusLabel(s: ThreadStatus): Pair<String, Color>? = when (s) {
    ThreadStatus.APPROVAL -> tr(R.string.chat_approval) to C.tertiary
    ThreadStatus.INPUT -> tr(R.string.chat_input_needed) to C.tertiary
    ThreadStatus.WORKING -> tr(R.string.chat_working) to C.primary
    ThreadStatus.FAILED -> tr(R.string.chat_failed) to C.error
    ThreadStatus.DONE -> tr(R.string.chat_done) to C.primary
    ThreadStatus.IDLE -> null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadRowView(v: AgentChatView, r: ThreadRow, now: Long, onArchive: (Boolean) -> Unit) {
    val t = r.thread
    val ctx = LocalContext.current
    var dx by remember(t.id) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(dx, label = "swipe")
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val archived = t.archivedAt != null
    val st = statusLabel(r.status)
    Box(
        Modifier.fillMaxWidth().background(C.container)
            .pointerInput(t.id) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { if (dx < -size.width * 0.35f) onArchive(!archived); dx = 0f; dragging = false },
                    onDragCancel = { dx = 0f; dragging = false },
                ) { _, d -> dx = (dx + d).coerceIn(-size.width.toFloat(), 0f) }
            },
    ) {
        Row(Modifier.align(Alignment.CenterEnd).padding(end = S.xl), verticalAlignment = Alignment.CenterVertically) {
            NcIcon(Ic.Archive, null, tint = C.tertiary, size = 18.dp)
            Overline(if (archived) tr(R.string.chat_restore) else tr(R.string.chat_archive), Modifier.padding(start = S.s), color = C.tertiary)
        }
        Column(
            Modifier.offset { IntOffset((if (dragging) dx else shown).roundToInt(), 0) }.fillMaxWidth().background(C.bg)
                .combinedClickable(onClick = { v.push(Route.Chat(t.id)) }, onLongClick = { menu = true }, onLongClickLabel = tr(R.string.chat_thread_actions))
                .semantics {
                    customActions = listOf(CustomAccessibilityAction(if (archived) tr(R.string.chat_restore) else tr(R.string.chat_archive)) { onArchive(!archived); true })
                },
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = S.l, vertical = S.m), verticalAlignment = Alignment.Top) {
                Box {
                    ProviderLogo(t.tool, size = ROW_LOGO)
                    if (st != null) Box(Modifier.align(Alignment.BottomEnd).offset(S.xs, S.xs).size(12.dp).background(C.bg, CircleShape), contentAlignment = Alignment.Center) {
                        StatusDot(st.second, size = 8.dp, pulse = r.status == ThreadStatus.WORKING)
                    }
                }
                Column(Modifier.weight(1f).padding(start = S.m)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (t.pinnedAt != null) NcIcon(Ic.Pin, "Pinned", Modifier.padding(end = S.xs), tint = C.primary, size = 12.dp)
                        Text(
                            t.title.ifBlank { tr(R.string.chat_new_thread) }, Modifier.weight(1f), color = C.on,
                            style = T.heading.copy(fontWeight = if (r.status == ThreadStatus.DONE) FontWeight.Bold else FontWeight.Medium),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val time = if (r.status == ThreadStatus.WORKING) r.lastRun?.startedAt?.let { ChatLogic.timer(now - it) }
                        else ChatLogic.relativeTime(now, t.updatedAt)
                        Label(time.orEmpty(), Modifier.padding(start = S.s), color = if (r.status == ThreadStatus.WORKING) C.primary else C.outline)
                    }
                    if (r.preview != null) Text(r.preview, Modifier.padding(top = S.xs), color = C.onVar, style = T.bodySm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = S.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s)) {
                        st?.let { (s, c) -> StatusChip(s, c, pulse = r.status == ThreadStatus.WORKING) }
                        Label(t.tool, color = C.outline)
                        DelegatedChip(t.id) // M7
                        if (!v.isWorkspace && t.projectPath != null) Label(ChatLogic.baseName(t.projectPath), Modifier.weight(1f, fill = false), color = C.outline)
                        Box(Modifier.weight(1f))
                        if (r.add + r.del > 0) Row { AddDel(r.add, r.del) }
                    }
                }
            }
            Hairline(Modifier.padding(start = S.edge + ROW_LOGO + S.m), color = C.borderVar) // under the text column
        }
        DropdownMenu(menu, { menu = false }, containerColor = C.low) {
            MItem(tr(R.string.term_rename), Ic.Pencil) { menu = false; rename = true }
            MItem(if (t.pinnedAt != null) "Unpin" else "Pin", Ic.Pin) { menu = false; v.bg { v.backend.pin(t.id, t.pinnedAt == null) } }
            MItem(tr(R.string.chat_continue_in_terminal), Ic.Terminal) { menu = false; openInTerminal(v, t) }
            MItem(tr(R.string.chat_open_terminal_here), Ic.Terminal) { menu = false; v.host.openShellHere(t.cwd, v.isWorkspace) }
            MItem(tr(R.string.chat_copy_path), Ic.Copy) { menu = false; copyText(ctx, t.cwd) }
            MItem(if (archived) tr(R.string.chat_unarchive) else tr(R.string.chat_archive), Ic.Archive) { menu = false; onArchive(!archived) }
            MItem(tr(R.string.chat_delete), Ic.Trash, danger = true) { menu = false; delete = true }
        }
    }
    if (rename) RenameDialog(t.title, { rename = false }) { n -> v.bg { v.backend.rename(t.id, n) } }
    if (delete) DeleteDialog(t.title, { delete = false }) { v.bg { v.backend.delete(t.id) } }
}

/** Close the chat process first (Free has one slot; one writer per session), then the CLI TUI. Resume flags unverified: fresh start. */
internal fun openInTerminal(v: AgentChatView, t: ChatThread) {
    v.bg { v.backend.stop(t.id) }
    v.host.openTerminal(t.tool, emptyList(), t.cwd, v.isWorkspace)
}

@Composable
internal fun RenameDialog(current: String, dismiss: () -> Unit, ok: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = dismiss, containerColor = C.low, shape = Square,
        title = { Text(tr(R.string.chat_rename_thread), style = T.title, color = C.on) },
        text = {
            OutlinedTextField(
                text, { text = it.take(120) }, singleLine = true, shape = Square, textStyle = T.body.copy(color = C.on),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.primary, unfocusedBorderColor = C.border, cursorColor = C.primary),
            )
        },
        confirmButton = { NcButton(tr(R.string.chat_save), primary = true, enabled = text.isNotBlank()) { ok(text.trim()); dismiss() } },
        dismissButton = { NcButton(tr(R.string.git_cancel)) { dismiss() } },
    )
}

@Composable
internal fun DeleteDialog(title: String, dismiss: () -> Unit, ok: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss, containerColor = C.low, shape = Square,
        icon = { NcIcon(Ic.Trash, null, tint = C.error, size = 24.dp) },
        title = { Text(tr(R.string.chat_delete_2, title), style = T.title, color = C.on) },
        text = { Text(tr(R.string.chat_stops_the_agent_deletes_the), style = T.bodySm, color = C.onVar) },
        confirmButton = { NcButton(tr(R.string.chat_delete), danger = true, icon = Ic.Trash) { ok(); dismiss() } },
        dismissButton = { NcButton(tr(R.string.git_cancel)) { dismiss() } },
    )
}

/** Drawn dot + used/max; tap lists live chats with Stop. */
@Composable
private fun SessionsChip(v: AgentChatView) {
    var open by remember { mutableStateOf(false) }
    // ponytail: 1 s poll; terminal tabs and other scopes' chats have no change feed into this view
    var usage by remember { mutableStateOf(v.host.sessionUsage()) }
    LaunchedEffect(Unit) { while (true) { usage = v.host.sessionUsage(); delay(1_000) } }
    val (used, max) = usage
    val full = used >= max
    Box(Modifier.padding(end = S.s)) {
        Row(
            Modifier.heightIn(min = 36.dp).card(if (used > 0) C.primary.copy(alpha = 0.1f) else C.low, if (full) C.tertiary else if (used > 0) C.primary else C.border)
                .combinedClickable(onClickLabel = tr(R.string.chat_live_sessions)) { open = true }
                .semantics(mergeDescendants = true) { contentDescription = "$used of $max AI sessions in use" + if (!v.host.isPro()) ", Free plan" else "" }
                .padding(horizontal = S.inset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (full) C.tertiary else if (used > 0) C.primary else C.outline, size = 6.dp, pulse = used > 0)
            Label("$used/$max", Modifier.padding(start = S.gap), color = C.on, size = 12, bold = true)
            if (!v.host.isPro()) Label(tr(R.string.chat_free), Modifier.padding(start = S.gap), color = C.tertiary, size = 10, bold = true)
        }
        DropdownMenu(open, { open = false }, containerColor = C.low) {
            val live = v.backend.liveThreadIds()
            val titles = v.rows.value.associate { it.thread.id to it.thread.title }
            if (live.isEmpty()) DropdownMenuItem({ Text(tr(R.string.chat_no_live_chats_sessions_in, used, max), style = T.bodySm, color = C.onVar) }, { open = false })
            live.forEach { id ->
                DropdownMenuItem(
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(titles[id] ?: tr(R.string.chat_chat), Modifier.width(180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = T.bodySm, color = C.on)
                            NcIcon(Ic.Stop, null, tint = C.error, size = 12.dp)
                            Label(tr(R.string.chat_stop), Modifier.padding(start = S.xs), color = C.error, bold = true)
                        }
                    },
                    { open = false; v.bg { v.backend.stop(id) } },
                )
            }
        }
    }
}

/** No thread yet: the draft thread's composer (provider, model, effort, access in its chips); the first send opens the chat. */
@Composable
private fun NewThreadPane(v: AgentChatView) {
    val id = v.draftId.value
    // stack.size: while the chat route fades in the list may still compose with draftId already cleared; it must not start another draft.
    LaunchedEffect(id, v.stack.size) { if (id == null && v.stack.size == 1) v.newThread(openChat = false) }
    if (id == null) LoadingRows() else ChatScreen(v, id, embedded = true)
}

/** Archived-only list or AI tools missing: "Install AI tools". */
@Composable
private fun EmptyState(v: AgentChatView) {
    Column(Modifier.fillMaxWidth().padding(S.l)) {
        Box(Modifier.size(48.dp).brutal(3.dp, C.shadowGreen).border(1.dp, C.primary).background(C.low), contentAlignment = Alignment.Center) {
            NcIcon(Ic.Message, null, tint = C.primary, size = 22.dp)
        }
        Text(tr(R.string.chat_start_an_agent_thread), Modifier.padding(top = S.l), color = C.on, style = T.title)
        Text(tr(R.string.chat_chat_with_a_cli_agent), Modifier.padding(top = S.xs, bottom = S.l), color = C.onVar, style = T.bodySm)
        NcButton(tr(R.string.chat_install_ai_tools), primary = true, icon = Ic.Download) { v.host.openInstallTools() }
    }
}
