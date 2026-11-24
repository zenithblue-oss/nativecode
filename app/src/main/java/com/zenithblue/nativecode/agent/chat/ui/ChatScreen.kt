package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import com.zenithblue.nativecode.agent.chat.ui.ChatLogic.isInput
import com.zenithblue.nativecode.cliauth.CliToolCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/** Screen D. [embedded] = the empty Threads page's pane: no top bar, the first send opens the thread as a normal chat. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChatScreen(v: AgentChatView, threadId: String, embedded: Boolean = false) {
    val d = v.open.value?.takeIf { it.thread.id == threadId }
    if (d == null) { LoadingRows(); return }
    val t = d.thread
    val live = v.live.value
    val composer = remember(threadId) { ComposerState(t.draftJson) }
    var queueSheet by remember { mutableStateOf(false) }
    var stopAt by remember(threadId) { mutableLongStateOf(0L) }

    // Store rows + the controller's in-memory items of the running turn (newer by id).
    val items = remember(d.items, live?.liveItems) { merge(d.items, live?.liveItems.orEmpty()) }
    val runsById = remember(d.runs) { d.runs.associateBy { it.id } }
    val rows = remember(items, runsById) { ChatLogic.rows(items, runsById) }
    val lastText = remember(items) {
        HashMap<String, String>().also { m -> items.forEach { i -> (i.payload as? ItemPayload.AssistantMessage)?.let { p -> i.runId?.let { m[it] = p.text } } } }
    }
    val activeRun = d.runs.lastOrNull { it.status.active }
    val running = live?.runStartedAt != null || activeRun != null
    val startedAt = live?.runStartedAt ?: activeRun?.startedAt
    val queued = d.runs.filter { it.status == RunStatus.QUEUED }
    val pending = d.requests.filter { it.status == RequestStatus.PENDING }.sortedBy { it.createdAt }.firstOrNull()
    val inputDraft = remember(pending?.id) { pending?.takeIf { it.isInput() }?.let { InputDraft(it.id) } }
    val todo = items.lastOrNull { it.payload is ItemPayload.TodoList }?.payload as? ItemPayload.TodoList
    val lastRun = d.runs.lastOrNull { it.status != RunStatus.QUEUED }
    val liveRunId = live?.runId ?: activeRun?.id
    LaunchedEffect(running) { if (!running) stopAt = 0L }

    // Slash built-ins (t3code): `/clear` arms a fresh session for the next message; `/usage-limits` opens a sheet.
    val ctx = LocalContext.current
    var freshArmed by remember(threadId) { mutableStateOf(false) }
    var usageSheet by remember { mutableStateOf(false) }
    val slashCtx = SlashMenu.Ctx(
        side = live?.caps?.fork == true,
        sideBlocked = when {
            t.nativeId == null -> "Side chats start after the first message."
            running -> tr(R.string.chat_side_chats_start_when_the)
            else -> null
        },
        fresh = pending == null && !running && items.any { it.payload is ItemPayload.UserMessage },
    )

    // Draft persists per thread (debounced, and on leave).
    LaunchedEffect(threadId, composer.text, composer.chips.size, composer.skills.size) { delay(800); v.bg { v.backend.setDraft(threadId, composer.draftJson()) } }
    LaunchedEffect(v.refusedDraft.value) {
        v.refusedDraft.value?.takeIf { it.first == threadId && !embedded }?.let { (_, j) ->
            v.refusedDraft.value = null
            if (composer.text.isEmpty() && composer.chips.isEmpty()) composer.restore(j)
        }
    }
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
            else -> {
                // t3code built-ins typed as the whole message run here and are never sent.
                when (val a = if (atts.isEmpty() && composer.skills.isEmpty()) SlashMenu.parse(text) else null) {
                    SlashMenu.Action.Clear -> if (slashCtx.fresh) { freshArmed = true; composer.clear(); return }
                    SlashMenu.Action.UsageLimits -> { usageSheet = true; composer.clear(); return }
                    is SlashMenu.Action.Model -> {
                        val m = a.arg?.let { SlashMenu.matchModel(live?.models.orEmpty(), it) }
                        if (m != null) {
                            v.bg { v.backend.configure(threadId, ConfigChange(model = m.id)) }
                            Toast.makeText(ctx, "Model: ${m.name.ifBlank { m.id }}", Toast.LENGTH_SHORT).show()
                            composer.clear()
                        } else { composer.text = ""; composer.sheet = ComposerSheet.MODEL }
                        return
                    }
                    is SlashMenu.Action.Side -> if (slashCtx.side) {
                        slashCtx.sideBlocked?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); return }
                        v.sideChat(threadId, a.prompt)
                        composer.clear(); return
                    }
                    null -> Unit
                }
                val prompt = composer.prompt() // NativeCode skills ride ahead of the text as context blocks
                val fresh = freshArmed
                val draft = composer.draftJson()
                // A refused send (6th chat over the session limit, Pro gate) gives the message back instead of losing it.
                v.bg { v.backend.send(threadId, prompt, atts, steer, fresh = fresh) { if (embedded) v.refusedDraft.value = threadId to draft else if (composer.text.isEmpty() && composer.chips.isEmpty()) composer.restore(draft) } }
                freshArmed = false
                v.promoteDraft(threadId)
            }
        }
        composer.clear()
    }

    // Context: live usage, else the last finished turn's; size from the usage or the model list.
    val usage = live?.usage ?: lastRun?.usage
    val ctxSize = usage?.contextSize ?: live?.models?.firstOrNull { it.id == (t.model ?: live.defaultModel) }?.contextLimit
    val compacting = items.any { it.payload is ItemPayload.Compaction && it.status == ItemStatus.RUNNING }
    val status = headerStatus(live, pending != null, running, lastRun?.status)

    Column(Modifier.fillMaxSize()) {
        if (!embedded) ChatTopBar(v, t, status, d.runs, imeOpen = WindowInsets.isImeVisible)
        todo?.let { TodoStrip(it) }
        DelegationStrip(v, threadId) // M7: delegated tasks / back-link to the parent

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
            // Stay pinned while following when content grows without a new item (throttled markdown, the
            // working pill's pad, an approval card shrinking the viewport).
            LaunchedEffect(list) {
                snapshotFlow { list.canScrollForward }.collect { fwd -> if (fwd && follow && !list.isScrollInProgress) list.scrollBy(100_000f) }
            }
            val sig = rows.size to (items.lastOrNull()?.let { it.updatedAt + it.payload.hashCode() } ?: 0)
            LaunchedEffect(sig) {
                // Int.MAX_VALUE: the lazy measure clamps it to the last item. layoutInfo is empty on the first
                // composition (re-entering the chat, back from the terminal), which used to scroll to item 0.
                if (follow && rows.isNotEmpty()) { list.scrollToItem(Int.MAX_VALUE); list.scrollBy(100_000f) }
                else unseen = true
            }
            if (items.isEmpty() && live?.phase != AgentEvent.Phase.STARTING) EmptyThread(t, Modifier.align(Alignment.Center))
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(top = S.s)) {
                if (d.hasMore) item("earlier") {
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = tr(R.string.chat_load_earlier_messages)) { v.loadEarlier() }.heightIn(min = 44.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        NcIcon(Ic.ArrowUp, null, tint = C.primary, size = 14.dp)
                        Text(tr(R.string.chat_load_earlier), Modifier.padding(start = S.s), color = C.primary, style = T.label)
                    }
                }
                items(rows, key = { it.key }, contentType = { it::class }) { r ->
                    TimelineRow(v, t, r, liveRunId, live?.ttftRunId, live?.ttftMs,(r as? Row.Meta)?.run?.id?.let { lastText[it] })
                }
                if (live?.phase == AgentEvent.Phase.STARTING) item("starting") { StartingRow(t.tool) }
                item("pad") { Box(Modifier.height(if (running) 88.dp else S.l)) }
            }
            // Floating working pill (t3code floating-working-control).
            androidx.compose.animation.AnimatedVisibility(
                running && startedAt != null, Modifier.align(Alignment.BottomCenter).padding(bottom = S.m),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            ) {
                WorkingPill(
                    elapsed = tick - (startedAt ?: tick),
                    activity = ChatLogic.currentActivity(items, liveRunId) ?: if (pending != null) "Waiting for you" else null,
                    queued = queued.size,
                    forceStop = live?.forceStopAvailable == true || (stopAt > 0 && tick - stopAt >= 10_000),
                    stopping = stopAt > 0,
                )
            }
            androidx.compose.animation.AnimatedVisibility(!follow,Modifier.align(Alignment.BottomEnd).padding(S.m), enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier.size(44.dp).brutal(3.dp).card(C.high, if (unseen) C.primary else C.border)
                        .clickable(onClickLabel = "Jump to latest") {
                            follow = true; unseen = false
                            scope.launch { list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)); list.scrollBy(100_000f) }
                        }.semantics { contentDescription = if (unseen) "Jump to latest, new messages" else "Jump to latest" },
                    contentAlignment = Alignment.Center,
                ) {
                    NcIcon(Ic.ArrowDown, null, tint = if (unseen) C.primary else C.on, size = 18.dp)
                    if (unseen) StatusDot(C.primary, Modifier.align(Alignment.TopEnd).padding(S.xs), size = 6.dp)
                }
            }
        }

        // Banner slot (one): reconnecting > live banner > held queue > session ended.
        val alive = live?.alive == true
        val banner = (if (freshArmed) Banner("fresh", tr(R.string.chat_fresh_session_armed_the_next), tr(R.string.git_cancel)) else null)
            ?: (if (live?.reconnecting == true) Banner("reconnecting", "Reconnecting to ${t.tool}") else null)
            ?: live?.banner
            ?: (if (queued.isNotEmpty() && !running && !alive) Banner("queue", "${queued.size} queued", tr(R.string.chat_resume_queue)) else null)
            ?: (if (!alive && lastRun?.status == RunStatus.INTERRUPTED && live == null) Banner("ended", "Session ended", "Resume") else null)
        banner?.let { b ->
            BannerRow(b) {
                when (b.kind) {
                    "signin" -> v.host.showCliLogin(CliToolCatalog.ALL.firstOrNull { it.terminalType == t.tool }?.id ?: t.tool)
                    "queue" -> v.bg { v.backend.queueResume(threadId) }
                    "upsell" -> v.host.showUpgradeSheet(b.text)
                    "error" -> openInTerminal(v, t)
                    "fresh" -> freshArmed = false
                    else -> v.bg { v.backend.resume(threadId) }
                }
            }
        }
        if (queued.isNotEmpty()) Row(
            Modifier.fillMaxWidth().background(C.low).clickable(onClickLabel = tr(R.string.chat_manage_queued_messages)) { queueSheet = true }
                .heightIn(min = 40.dp).padding(horizontal = S.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NcIcon(Ic.Clock, null, tint = C.tertiary, size = 14.dp)
            Text(tr(R.string.chat_queued, queued.size), Modifier.weight(1f).padding(start = S.s), color = C.on, style = T.label)
            NcIcon(Ic.ChevronRight, null, tint = C.onVar, size = 16.dp)
        }

        BrowserCard(threadId, approvalPending = pending != null)
        AnimatedVisibility(pending != null, enter = slideInVertically { it / 2 } + fadeIn(), exit = fadeOut()) {
            when {
                pending == null -> {}
                inputDraft != null -> UserInputCard(v, t, pending, inputDraft)
                else -> ApprovalCard(v, t, pending, composer.text, agentGone = live?.alive != true) { composer.text = "" }
            }
        }
        Composer(
            v, t, composer,
            placeholder = when {
                pending != null && inputDraft != null -> "Type a custom answer"
                pending != null -> "Reply to deny with feedback"
                else -> tr(R.string.chat_ask_for_commands, t.tool)
            },
            autoFocus = !embedded && items.isEmpty(), // a fresh thread opens with the keyboard on its composer
            commands = live?.commands.orEmpty(),
            slashCtx = slashCtx,
            running = running && pending == null,
            canSteer = live?.caps?.steer == true,
            context = ContextInfo(usage?.contextUsed, ctxSize, compacting),
            stopping = stopAt > 0,
            forceStop = live?.forceStopAvailable == true || (stopAt > 0 && tick - stopAt >= 10_000),
            onSend = ::send,
            onStop = { stopAt = System.currentTimeMillis(); v.bg { v.backend.interrupt(threadId) } },
            onForceStop = { v.bg { v.backend.forceStop(threadId) } },
            onSlash = { c ->
                val rest = composer.text.substringBeforeLast('\n', "") // the trigger line goes
                when {
                    c.kind != SlashCommand.Kind.BUILTIN -> composer.text = SlashMenu.insert(composer.text, c)
                    c.name == "model" -> { composer.text = rest; composer.sheet = ComposerSheet.MODEL }
                    c.name == "side" || c.name == "btw" ->
                        slashCtx.sideBlocked?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } ?: run { composer.text = "/${c.name} " }
                    c.name == "clear" -> { composer.text = rest; freshArmed = true }
                    c.name == SlashMenu.USAGE_LIMITS.name -> { composer.text = rest; usageSheet = true }
                }
            },
        )
    }

    if (usageSheet) ModalBottomSheet({ usageSheet = false }, containerColor = C.bg, shape = Square, dragHandle = { SheetHandle() }) {
        UsageLimitsSheet(t.tool, live?.rateLimit, usage, ctxSize, d.runs)
    }

    if (queueSheet) ModalBottomSheet({ queueSheet = false }, containerColor = C.bg, shape = Square, dragHandle = { SheetHandle() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = S.l).padding(bottom = S.l).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(S.m)) {
            Text(tr(R.string.chat_queued_messages), color = C.on, style = T.title)
            queued.forEachIndexed { i, r ->
                val msg = items.firstOrNull { it.runId == r.id && it.payload is ItemPayload.UserMessage }?.payload as? ItemPayload.UserMessage
                Column(Modifier.fillMaxWidth().card(C.low).padding(S.m)) {
                    Overline("${i + 1} of ${queued.size}", color = C.outline)
                    Text(msg?.text ?: "", Modifier.padding(vertical = S.s), color = C.on, style = T.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(S.s)) {
                        NcButton(tr(R.string.chat_send_now), primary = true, icon = Ic.ArrowUp) { queueSheet = false; v.bg { v.backend.queueSendNow(threadId, r.id) } }
                        NcButton(tr(R.string.chat_edit), icon = Ic.Pencil) { queueSheet = false; composer.load(msg?.text.orEmpty()); v.bg { v.backend.queueRemove(threadId, r.id) } }
                        NcButton(tr(R.string.chat_remove), danger = true, icon = Ic.Trash) { v.bg { v.backend.queueRemove(threadId, r.id) } }
                    }
                }
            }
        }
    }
}

/** Status shown under the title (t3code thread status). */
private data class HeaderStatus(val text: String, val color: Color, val pulse: Boolean)

private fun headerStatus(live: LiveState?, pending: Boolean, running: Boolean, last: RunStatus?): HeaderStatus = when {
    live?.reconnecting == true -> HeaderStatus("Reconnecting", C.tertiary, true)
    live?.phase == AgentEvent.Phase.STARTING -> HeaderStatus("Starting", C.primary, true)
    pending -> HeaderStatus(tr(R.string.chat_waiting_for_approval), C.tertiary, true)
    running -> HeaderStatus(tr(R.string.chat_working), C.primary, true)
    last == RunStatus.FAILED -> HeaderStatus("Error", C.error, false)
    live?.alive == true -> HeaderStatus("Ready", C.primary, false)
    else -> HeaderStatus(tr(R.string.chat_idle), C.outline, false)
}

/** Row 1: back, provider logo, title + status, terminal page, menu. Row 2 (hidden with the IME): model, project, branch, cost, sessions. */
@Composable
private fun ChatTopBar(v: AgentChatView, t: ChatThread, status: HeaderStatus, runs: List<com.zenithblue.nativecode.agent.chat.core.Run>, imeOpen: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val branch by produceState<String?>(null, t.cwd, runs.size) { value = withContext(Dispatchers.IO) { gitBranch(v, t.cwd) } }
    Column(Modifier.fillMaxWidth().background(C.lowest).animateContentSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = S.xs), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, tr(R.string.chat_back_to_threads)) { v.pop() }
            ProviderLogo(t.tool, size = 32.dp)
            Column(Modifier.weight(1f).padding(horizontal = S.m)) {
                Text(t.title.ifBlank { tr(R.string.chat_new_thread) }, color = C.on, style = T.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "Status: ${status.text}" }, verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(status.color, size = 6.dp, pulse = status.pulse)
                    Text(status.text, Modifier.padding(start = S.gap), color = status.color, style = T.meta, maxLines = 1)
                }
            }
            IconBtn(Ic.Terminal, tr(R.string.chat_open_terminal_page), tint = C.onVar) { v.host.showTerminal(v.isWorkspace) }
            Box {
                IconBtn(Ic.More, tr(R.string.chat_more_actions), tint = C.onVar) { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = C.low) {
                    MItem(tr(R.string.chat_changes), Ic.Compare) { menu = false; v.push(Route.Diff(t.id)) }
                    MItem(tr(R.string.chat_thread_settings), Ic.Sliders) { menu = false; v.push(Route.Settings(t.id)) }
                    MItem(tr(R.string.chat_agent_bridge_history), Ic.History) { menu = false; v.push(Route.Bridge(t.id)) }
                    MItem(tr(R.string.chat_preview_dev_server), Ic.Globe) { menu = false; v.host.openPreview(null) }
                    MItem(tr(R.string.chat_open_terminal_here), Ic.Terminal) { menu = false; v.host.openShellHere(t.cwd, v.isWorkspace) }
                    MItem(tr(R.string.chat_continue_in_terminal), Ic.Terminal) { menu = false; openInTerminal(v, t) }
                    MItem(tr(R.string.term_rename), Ic.Pencil) { menu = false; rename = true }
                    MItem(tr(R.string.chat_delete), Ic.Trash, danger = true) { menu = false; delete = true }
                }
            }
        }
        if (!imeOpen) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = S.l, end = S.l, bottom = S.s),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.m),
        ) {
            InfoBit(Ic.Bot, "${t.tool} · ${ChatLogic.modelLabel(t.model, v.live.value?.defaultModel, v.live.value?.models.orEmpty())}", C.on) { v.push(Route.Settings(t.id)) }
            InfoBit(Ic.Folder, t.projectPath?.let { ChatLogic.baseName(it) } ?: t.cwd.removePrefix(AgentChatView.HOME).ifEmpty { "~" })
            branch?.let { InfoBit(Ic.Branch, it) }
            val cost = runs.mapNotNull { it.usage?.costUsd }
            if (cost.isNotEmpty()) InfoBit(Ic.Gauge, "${ChatLogic.cost(cost.sum())} session")
            if (!v.host.isPro()) {
                val (used, max) = remember(runs.size) { v.host.sessionUsage() }
                InfoBit(Ic.Lock, "Free · $used/$max session${if (max == 1) "" else "s"}", if (used >= max) C.tertiary else C.onVar) { v.host.showUpgradeSheet(null) }
            }
        }
        Hairline()
    }
    if (rename) RenameDialog(t.title, { rename = false }) { n -> v.bg { v.backend.rename(t.id, n) } }
    if (delete) DeleteDialog(t.title, { delete = false }) { v.bg { v.backend.delete(t.id) }; v.pop() }
}

@Composable
private fun InfoBit(icon: ImageVector, text: String, color: Color = C.onVar, onClick: (() -> Unit)? = null) {
    Row(
        (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NcIcon(icon, null, tint = C.outline, size = 12.dp)
        Text(text, Modifier.padding(start = S.gap), color = color, style = T.meta, maxLines = 1)
    }
}

/** `.git/HEAD` of [cwd] or a parent, read on the host side of the guest rootfs. */
private fun gitBranch(v: AgentChatView, cwd: String): String? = runCatching {
    val root = v.host.guestHome().path.removeSuffix(AgentChatView.HOME)
    var dir: File? = File(root + cwd)
    repeat(8) {
        val head = File(dir ?: return null, ".git/HEAD")
        if (head.isFile) return ChatLogic.branch(head.readText())
        dir = dir?.parentFile?.takeIf { it.path.length > root.length }
    }
    null
}.getOrNull()

@Composable
private fun EmptyThread(t: ChatThread, modifier: Modifier) {
    Column(modifier.padding(S.xl).widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ProviderLogo(t.tool, size = 56.dp)
        Text("What should ${providerName(t.tool)} do in ${t.projectPath?.let { ChatLogic.baseName(it) } ?: "~"}?",
            Modifier.padding(top = S.l), color = C.on, style = T.title, textAlign = TextAlign.Center)
        Text(tr(R.string.chat_type_for_commands_attach_files),
            Modifier.padding(top = S.s), color = C.onVar, style = T.bodySm, textAlign = TextAlign.Center)
    }
}

/** "Starting opencode" with shimmer (handshake ≤ 20 s). */
@Composable
private fun StartingRow(tool: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = S.l, vertical = S.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderLogo(tool, size = 20.dp)
            Text("Starting $tool", Modifier.padding(start = S.s).shimmer(), color = C.primary, style = T.label)
        }
        SkeletonBar(220.dp, Modifier.padding(top = S.m))
        SkeletonBar(160.dp, Modifier.padding(top = S.s))
    }
}

@Composable
internal fun LoadingRows() {
    Column(Modifier.fillMaxWidth().padding(S.l), verticalArrangement = Arrangement.spacedBy(S.m)) {
        SkeletonBar(180.dp, height = 14.dp)
        SkeletonBar(260.dp)
        SkeletonBar(220.dp)
        SkeletonBar(140.dp)
    }
}

@Composable
/** Status only; the one Stop is the composer send button (t3code). */
private fun WorkingPill(elapsed: Long, activity: String?, queued: Int, forceStop: Boolean, stopping: Boolean) {
    val accent = if (forceStop) C.error else C.primary
    Row(
        Modifier.brutal(3.dp, if (forceStop) C.errorCon else C.shadowGreen).card(C.low, accent).heightIn(min = 48.dp).padding(horizontal = S.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(accent, size = 8.dp, pulse = !forceStop)
        Column(Modifier.padding(start = S.s).widthIn(max = 260.dp).semantics(mergeDescendants = true) {}) {
            Text(when { forceStop -> tr(R.string.chat_agent_not_responding_tap_force); stopping -> tr(R.string.chat_stopping); else -> "Working ${ChatLogic.timer(elapsed)}" } +
                if (queued > 0) " · $queued queued" else "",
                color = if (forceStop) C.error else C.on, style = T.label, maxLines = 1)
            if (!forceStop && activity != null) Text(activity, color = C.onVar, style = T.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BannerRow(b: Banner, onAction: () -> Unit) {
    val (icon, color) = when (b.kind) {
        "ratelimit", "ended" -> Ic.Alert to C.tertiary
        "error" -> Ic.CircleX to C.error
        "signin", "upsell" -> Ic.Lock to C.tertiary
        "queue" -> Ic.Clock to C.tertiary
        "fresh" -> Ic.Retry to C.primary
        else -> Ic.Alert to C.onVar
    }
    Column {
        Hairline()
        Row(Modifier.fillMaxWidth().background(C.container).heightIn(min = 44.dp).padding(start = S.l, end = S.xs), verticalAlignment = Alignment.CenterVertically) {
            if (b.kind == "reconnecting") Spinner(14.dp, C.tertiary) else NcIcon(icon, null, tint = color, size = 16.dp)
            Text(b.text, Modifier.weight(1f).padding(horizontal = S.s), color = C.on, style = T.bodySm, maxLines = 2, overflow = TextOverflow.Ellipsis)
            b.action?.let { NcButton(it, kind = BtnKind.GHOST, onClick = onAction) }
        }
    }
}

/** `/usage-limits` (t3code usage panel): the provider's quota from the live state, else "not reported"; plus this thread's usage. */
@Composable
private fun UsageLimitsSheet(tool: String, r: AgentEvent.RateLimit?, usage: com.zenithblue.nativecode.agent.chat.core.Usage?, ctxSize: Long?, runs: List<com.zenithblue.nativecode.agent.chat.core.Run>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = S.l).padding(bottom = S.l).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(S.s)) {
        Text(tr(R.string.chat_usage_limits), color = C.on, style = T.title)
        Overline(tool, color = C.outline)
        if (r == null) {
            Text(tr(R.string.chat_not_reported_has_not_sent, tool), color = C.onVar, style = T.body)
        } else {
            val limited = r.status !in setOf("ok", "allowed", "allowed_warning")
            Text(if (limited) tr(R.string.chat_limit_reached) else tr(R.string.chat_within_limits), color = if (limited) C.error else C.primary, style = T.label)
            r.detail?.let { Text(it, color = C.on, style = T.codeSm) }
            r.resetAt?.let { Text("Resets " + java.text.SimpleDateFormat(tr(R.string.chat_mmm_d_hh_mm), java.util.Locale.getDefault()).format(java.util.Date(it)), color = C.onVar, style = T.meta) }
        }
        val used = usage?.contextUsed
        if (used != null && ctxSize != null) Text("Context ${ChatLogic.tokens(used)} / ${ChatLogic.tokens(ctxSize)} tokens", color = C.onVar, style = T.meta)
        val done = runs.filter { it.usage != null }
        if (done.isNotEmpty()) {
            val tok = done.sumOf { (it.usage?.inputTokens ?: 0) + (it.usage?.outputTokens ?: 0) }
            Text(listOfNotNull("This thread: ${done.size} turn${if (done.size == 1) "" else "s"}", "${ChatLogic.tokens(tok)} tokens",
                ChatLogic.cost(done.mapNotNull { it.usage?.costUsd }.takeIf { it.isNotEmpty() }?.sum())).joinToString(" · "), color = C.onVar, style = T.meta)
        }
    }
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
    Column(Modifier.fillMaxWidth().background(C.low).animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) tr(R.string.chat_hide_tasks) else tr(R.string.chat_show_tasks)) { open = !open }.heightIn(min = 44.dp).padding(horizontal = S.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NcIcon(Ic.ListTodo, null, tint = C.primary, size = 16.dp)
            Text(tr(R.string.chat_tasks, done, todo.steps.size), Modifier.padding(start = S.s), color = C.on, style = T.label)
            Text(cur?.text?.let { " · $it" } ?: "", Modifier.weight(1f), color = C.onVar, style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Chevron(open)
        }
        LinearProgressIndicator(
            progress = { if (todo.steps.isEmpty()) 0f else done.toFloat() / todo.steps.size },
            Modifier.fillMaxWidth().height(2.dp), color = C.primary, trackColor = C.border, gapSize = 0.dp, drawStopIndicator = {},
        )
        if (open) Column(Modifier.padding(horizontal = S.l, vertical = S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
            todo.steps.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (s.status) {
                        "completed" -> NcIcon(Ic.CircleCheck, tr(R.string.chat_done), tint = C.primary, size = 14.dp)
                        "in_progress" -> Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { StatusDot(C.primary, size = 6.dp, pulse = true) }
                        else -> Ring(C.outline)
                    }
                    Text(s.text, Modifier.padding(start = S.s), color = if (s.status == "completed") C.outline else C.on, style = T.bodySm)
                }
            }
        }
    }
}

/** Bottom-sheet drag handle: a drawn bar. */
@Composable
internal fun SheetHandle() {
    Box(Modifier.padding(vertical = S.m).size(36.dp, 4.dp).background(C.border))
}
