package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import com.zenithblue.nativecode.agent.chat.ui.ChatLogic.isInput
import com.zenithblue.nativecode.cliauth.CliToolCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Screen D. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(v: AgentChatView, threadId: String) {
    val d = v.open.value?.takeIf { it.thread.id == threadId }
    if (d == null) { Label("Loading…", Modifier.padding(16.dp)); return }
    val t = d.thread
    val live = v.live.value
    val composer = remember(threadId) { ComposerState(t.draftJson) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var queueSheet by remember { mutableStateOf(false) }
    var stopAt by remember(threadId) { mutableLongStateOf(0L) }

    // Store rows + the controller's in-memory items of the running turn (newer by id).
    val items = remember(d.items, live?.liveItems) { merge(d.items, live?.liveItems.orEmpty()) }
    val runsById = remember(d.runs) { d.runs.associateBy { it.id } }
    val rows = remember(items, runsById) { ChatLogic.rows(items, runsById) }
    val activeRun = d.runs.lastOrNull { it.status.active }
    val running = live?.runStartedAt != null || activeRun != null
    val startedAt = live?.runStartedAt ?: activeRun?.startedAt
    val queued = d.runs.filter { it.status == RunStatus.QUEUED }
    val pending = d.requests.filter { it.status == RequestStatus.PENDING }.sortedBy { it.createdAt }.firstOrNull()
    val inputDraft = remember(pending?.id) { pending?.takeIf { it.isInput() }?.let { InputDraft(it.id) } }
    val todo = items.lastOrNull { it.payload is ItemPayload.TodoList }?.payload as? ItemPayload.TodoList
    LaunchedEffect(running) { if (!running) stopAt = 0L }

    // Draft persists per thread (debounced, and on leave).
    LaunchedEffect(threadId, composer.text, composer.chips.size) { delay(800); v.bg { v.backend.setDraft(threadId, composer.draftJson()) } }
    DisposableEffect(threadId) { onDispose { val j = composer.draftJson(); v.bg { v.backend.setDraft(threadId, j) } } }

    val tick by produceState(System.currentTimeMillis(), running) { while (running) { value = System.currentTimeMillis(); delay(1000) } }

    fun send(steer: Boolean) {
        val text = composer.text.trim()
        val atts = composer.ready
        when {
            pending != null && !pending.isInput() -> {
                val reject = pending.rejectOption() ?: return
                v.bg { v.backend.respond(threadId, pending.id, RequestAnswer(optionId = reject.id, feedback = text.ifEmpty { null })) }
            }
            pending != null && inputDraft != null -> {
                val qs = pending.detail.questions
                if (qs.isEmpty()) {
                    v.bg { v.backend.respond(threadId, pending.id, RequestAnswer(answers = mapOf("" to listOf(text)))) }
                } else {
                    val i = inputDraft.index.coerceIn(0, qs.lastIndex)
                    inputDraft.answers[qs[i].id] = listOf(text)
                    if (i < qs.lastIndex) inputDraft.index = i + 1
                    else if (qs.all { inputDraft.answers[it.id] != null }) v.bg { v.backend.respond(threadId, pending.id, RequestAnswer(answers = inputDraft.answers.toMap())) }
                }
            }
            else -> v.bg { v.backend.send(threadId, text, atts, steer) }
        }
        composer.clear()
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar: ← title ⋮ (the Chat|Terminal switch lives in the page bar above).
        Row(Modifier.fillMaxWidth().background(C.lowest).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("←", Modifier.clickable { v.pop() }.padding(12.dp), color = C.on, size = 16)
            Column(Modifier.weight(1f)) {
                Text(t.title.ifBlank { "New thread" }, color = C.on, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Label(listOfNotNull(t.tool, t.model?.substringAfterLast('/'), t.projectPath?.let { ChatLogic.baseName(it) }).joinToString(" · "))
            }
            Box {
                Label("⋮", Modifier.clickable { menu = true }.padding(14.dp), color = C.on, size = 18, bold = true)
                DropdownMenu(menu, { menu = false }) {
                    MItem("Diff") { menu = false; v.push(Route.Diff(threadId)) }
                    MItem("Thread settings") { menu = false; v.push(Route.Settings(threadId)) }
                    MItem("Open terminal here") { menu = false; v.host.openShellHere(t.cwd, v.isWorkspace) }
                    MItem("Open in terminal") { menu = false; openInTerminal(v, t) }
                    MItem("Rename") { menu = false; rename = true }
                    MItem("Delete") { menu = false; delete = true }
                }
            }
        }
        Hairline()
        todo?.let { TodoStrip(it) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = rememberLazyListState()
            val scope = rememberCoroutineScope()
            var follow by remember(threadId) { mutableStateOf(true) }
            var unseen by remember(threadId) { mutableStateOf(false) }
            LaunchedEffect(list) {
                snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, fwd) ->
                    if (scrolling) follow = !fwd
                    if (!fwd) unseen = false
                }
            }
            val sig = rows.size to (items.lastOrNull()?.let { it.updatedAt + it.payload.hashCode() } ?: 0)
            LaunchedEffect(sig) {
                if (follow && rows.isNotEmpty()) { list.scrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1); list.scrollBy(100_000f) }
                else unseen = true
            }
            if (items.isEmpty() && live?.phase != AgentEvent.Phase.STARTING) {
                Text("What should ${t.tool} do in ${t.projectPath?.let { ChatLogic.baseName(it) } ?: "~"}?",
                    Modifier.align(Alignment.Center).padding(24.dp), color = C.onVar, fontSize = 16.sp)
            }
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                if (d.hasMore) item("earlier") { Label("↑ Load earlier", Modifier.fillMaxWidth().clickable { v.loadEarlier() }.padding(14.dp), color = C.primary, bold = true) }
                items(rows, key = { it.key }) { r ->
                    TimelineRow(v, t, r, activeRun?.id) { runId ->
                        items.lastOrNull { it.runId == runId && it.payload is ItemPayload.AssistantMessage }?.let { (it.payload as ItemPayload.AssistantMessage).text }
                    }
                }
                if (live?.phase == AgentEvent.Phase.STARTING) item("starting") { Label("◌ Starting ${t.tool}…", Modifier.padding(12.dp), color = C.primary) }
                item("pad") { Box(Modifier.heightIn(min = if (running) 64.dp else 8.dp)) }
            }
            if (running && startedAt != null) {
                val forceStop = live?.forceStopAvailable == true || (stopAt > 0 && tick - stopAt >= 10_000)
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).background(C.low).border(1.dp, if (forceStop) C.error else C.primary)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (forceStop) {
                        Label("Agent not responding", color = C.error)
                        Label("■ FORCE STOP", Modifier.clickable { v.bg { v.backend.forceStop(threadId) } }.padding(6.dp), color = C.error, bold = true)
                    } else {
                        Label("● Working ${ChatLogic.timer(tick - startedAt)}", color = C.primary)
                        Label(if (stopAt > 0) "Stopping…" else "■ STOP", Modifier.clickable(enabled = stopAt == 0L) {
                            stopAt = System.currentTimeMillis(); v.bg { v.backend.interrupt(threadId) }
                        }.padding(6.dp), color = C.on, bold = true)
                    }
                }
            }
            if (!follow) Label(if (unseen) "↓ New" else "↓", Modifier.align(Alignment.BottomEnd).padding(12.dp).background(C.high).border(1.dp, C.primary)
                .clickable { follow = true; unseen = false; scope.launch { list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)); list.scrollBy(100_000f) } }
                .padding(horizontal = 12.dp, vertical = 8.dp), color = C.primary, bold = true)
        }

        // Banner slot (one): live banner > held queue > session ended.
        val lastRun = d.runs.lastOrNull { it.status != RunStatus.QUEUED }
        val alive = live?.alive == true
        val banner = live?.banner
            ?: (if (queued.isNotEmpty() && !running && !alive) Banner("queue", "${queued.size} queued", "Resume queue") else null)
            ?: (if (!alive && lastRun?.status == RunStatus.INTERRUPTED && live == null) Banner("ended", "Session ended", "Resume") else null)
        banner?.let { b ->
            Row(Modifier.fillMaxWidth().background(C.container).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(b.text, Modifier.weight(1f), color = if (b.kind == "ratelimit" || b.kind == "ended") C.tertiary else C.on)
                b.action?.let { a ->
                    Label(a.uppercase(), Modifier.clickable {
                        when (b.kind) {
                            "signin" -> v.host.openCliLoginPage(CliToolCatalog.ALL.firstOrNull { it.terminalType == t.tool }?.id ?: t.tool)
                            "queue" -> v.bg { v.backend.queueResume(threadId) }
                            "upsell" -> v.host.showUpgradeSheet(b.text)
                            "error" -> openInTerminal(v, t)
                            else -> v.bg { v.backend.resume(threadId) }
                        }
                    }.padding(6.dp), color = C.primary, bold = true)
                }
            }
        }
        if (queued.isNotEmpty()) Label("Queued (${queued.size}) ›", Modifier.fillMaxWidth().clickable { queueSheet = true }.padding(horizontal = 12.dp, vertical = 8.dp), color = C.primary)

        when {
            pending != null && inputDraft != null -> UserInputCard(v, t, pending, inputDraft)
            pending != null -> ApprovalCard(v, t, pending, composer.text, agentGone = live?.alive != true) { composer.text = "" }
        }
        Composer(
            v, t, composer,
            placeholder = when {
                pending != null && inputDraft != null -> "Type a custom answer…"
                pending != null -> "Reply to deny with feedback…"
                else -> "Ask ${t.tool}…  / commands"
            },
            commands = live?.commands.orEmpty(),
            running = running && pending == null,
            canSteer = live?.caps?.steer == true,
            onSend = ::send,
            onStop = { stopAt = System.currentTimeMillis(); v.bg { v.backend.interrupt(threadId) } },
            onSlash = { name ->
                when (name) {
                    "model" -> { composer.text = ""; v.push(Route.Settings(threadId)) }
                    "clear" -> { composer.text = ""; v.newThread(t.tool, t.projectPath) }
                    else -> composer.text = "/$name "
                }
            },
        )
    }

    if (queueSheet) ModalBottomSheet({ queueSheet = false }, containerColor = C.bg, shape = Square) {
        Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
            Label("QUEUED (${queued.size})", color = C.on, bold = true)
            queued.forEach { r ->
                val msg = items.firstOrNull { it.runId == r.id && it.payload is ItemPayload.UserMessage }?.payload as? ItemPayload.UserMessage
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, C.border).padding(10.dp)) {
                    Text(msg?.text ?: "…", color = C.on, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NcButton("Send now", primary = true) { queueSheet = false; v.bg { v.backend.queueSendNow(threadId, r.id) } }
                        NcButton("Edit") { queueSheet = false; composer.text = msg?.text.orEmpty(); v.bg { v.backend.queueRemove(threadId, r.id) } }
                        NcButton("Remove", danger = true) { v.bg { v.backend.queueRemove(threadId, r.id) } }
                    }
                }
            }
        }
    }
    if (rename) RenameDialog(t.title, { rename = false }) { n -> v.bg { v.backend.rename(threadId, n) } }
    if (delete) DeleteDialog(t.title, { delete = false }) { v.bg { v.backend.delete(threadId) }; v.pop() }
}

/** Store rows overlaid with live items (same id = newer snapshot), ordered by ordinal. */
internal fun merge(store: List<TurnItem>, liveItems: List<TurnItem>): List<TurnItem> {
    if (liveItems.isEmpty()) return store
    val byId = LinkedHashMap<String, TurnItem>()
    store.forEach { byId[it.id] = it }
    liveItems.forEach { byId[it.id] = it }
    return byId.values.sortedBy { it.ordinal }
}

@Composable
private fun TodoStrip(todo: ItemPayload.TodoList) {
    var open by remember { mutableStateOf(false) }
    val done = todo.steps.count { it.status == "completed" }
    val cur = todo.steps.firstOrNull { it.status == "in_progress" } ?: todo.steps.firstOrNull { it.status != "completed" }
    Column(Modifier.fillMaxWidth().background(C.low).clickable { open = !open }.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("▣ Tasks $done/${todo.steps.size}" + (cur?.let { " · ${it.text}" } ?: ""), Modifier.weight(1f), color = C.on)
            Label(if (open) "▴" else "▾")
        }
        if (open) todo.steps.forEach { s ->
            Label((when (s.status) { "completed" -> "✓ "; "in_progress" -> "● "; else -> "○ " }) + s.text,
                Modifier.padding(top = 4.dp), color = if (s.status == "completed") C.outline else C.on)
        }
    }
}
