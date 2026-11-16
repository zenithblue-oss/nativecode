package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.agent.chat.tools.NcTasks
import com.zenithblue.nativecode.agent.chat.tools.NcToolSpec
import com.zenithblue.nativecode.agent.chat.tools.NcTools
import kotlinx.coroutines.delay
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/*
 * Item 12 M7 UI (t3code V2LifecycleRow / SubagentNotificationLink / thread links): delegated tasks on the parent
 * (status, live time, result, jump, stop, upsell), the back-link on a child, the delegated chip in the thread
 * list, and the cross-thread message card. Vector icons only.
 */

/** Live task list (NcTasks listener, posted to main). */
@Composable
private fun rememberTasks(v: AgentChatView): List<NcTasks.Task> {
    val tasks by produceState(NcTasks.all()) {
        val l: () -> Unit = { v.ui { value = NcTasks.all() } }
        NcTasks.addListener(l)
        awaitDispose { NcTasks.removeListener(l) }
    }
    return tasks
}

private fun statusColor(s: String): Color = when (s) {
    "completed" -> C.primary
    "failed" -> C.error
    "waiting" -> C.tertiary
    "cancelled", "interrupted" -> C.outline
    else -> C.primary
}

private fun statusLabel(t: NcTasks.Task): String = when {
    t.gateDenied -> tr(R.string.chat_needs_pro)
    t.status == "waiting" -> "Needs you"
    else -> t.status.replaceFirstChar { it.uppercase() }
}

/** Open [threadId]: back when it is the screen below this one (child to parent round trip), else push. */
private fun AgentChatView.goTo(threadId: String) {
    val below = stack.getOrNull(stack.size - 2)
    if (below is Route.Chat && below.id == threadId) pop() else push(Route.Chat(threadId))
}

private fun AgentChatView.titleOf(threadId: String): String? = rows.value.firstOrNull { it.thread.id == threadId }?.thread?.title

/** Under the top bar: the child's back-link, or the parent's delegated tasks (newest first, collapsible). */
@Composable
internal fun DelegationStrip(v: AgentChatView, threadId: String) {
    val tasks = rememberTasks(v)
    val asChild = remember(tasks, threadId) { tasks.lastOrNull { it.childThreadId == threadId } }
    val mine = remember(tasks, threadId) { tasks.filter { it.parentThreadId == threadId }.asReversed() }
    val running = mine.any { !it.terminal } || asChild?.terminal == false
    val now by produceState(System.currentTimeMillis(), running) { while (running) { value = System.currentTimeMillis(); delay(1000) } }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        asChild?.let { ChildBackLink(v, it, now) }
        if (mine.isNotEmpty()) TaskList(v, mine, now)
    }
}

@Composable
private fun ChildBackLink(v: AgentChatView, t: NcTasks.Task, now: Long) {
    val parent = v.titleOf(t.parentThreadId) ?: "parent thread"
    Row(
        Modifier.fillMaxWidth().background(C.lowest)
            .clickable(role = Role.Button, onClickLabel = "Open $parent") { v.goTo(t.parentThreadId) }
            .heightIn(min = 44.dp).padding(horizontal = S.edge),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NcIcon(Ic.Back, null, tint = C.primary, size = 14.dp)
        Column(Modifier.weight(1f).padding(start = S.s)) {
            Overline(tr(R.string.chat_delegated_task), color = C.outline)
            Text(parent, color = C.on, style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StatusChip(statusLabel(t) + elapsed(t, now), statusColor(t.status), pulse = !t.terminal)
    }
    Hairline(color = C.borderVar)
}

@Composable
private fun TaskList(v: AgentChatView, tasks: List<NcTasks.Task>, now: Long) {
    val active = tasks.count { !it.terminal }
    // Open while a task runs; folds to one line once all settle, so it stops eating the timeline (keyboard up).
    var open by rememberSaveable(active > 0) { mutableStateOf(active > 0) }
    Column(Modifier.fillMaxWidth().background(C.lowest)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = if (open) tr(R.string.chat_collapse_delegated_tasks) else tr(R.string.chat_expand_delegated_tasks)) { open = !open }
                .heightIn(min = 40.dp).padding(horizontal = S.edge),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NcIcon(Ic.Bot, null, tint = if (active > 0) C.primary else C.onVar, size = 14.dp)
            Overline(if (active > 0) tr(R.string.chat_delegated_running, active) else "Delegated · ${tasks.size}", Modifier.weight(1f).padding(start = S.s),
                color = if (active > 0) C.primary else C.onVar)
            NcIcon(if (open) Ic.ChevronDown else Ic.ChevronRight, null, tint = C.outline, size = 16.dp)
        }
        if (open) Column(Modifier.padding(start = S.edge, end = S.edge, bottom = S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
            tasks.take(MAX_SHOWN).forEach { androidx.compose.runtime.key(it.id) { TaskCard(v, it, now) } }
            if (tasks.size > MAX_SHOWN) Label("+${tasks.size - MAX_SHOWN} earlier", color = C.outline)
        }
        Hairline(color = C.borderVar)
    }
}

@Composable
private fun TaskCard(v: AgentChatView, t: NcTasks.Task, now: Long) {
    val color = statusColor(t.status)
    Row(
        Modifier.fillMaxWidth().card(C.low, if (t.terminal) C.border else color.copy(alpha = 0.6f))
            .clickable(role = Role.Button, onClickLabel = tr(R.string.chat_open_delegated_thread)) { v.goTo(t.childThreadId) }
            .semantics(mergeDescendants = true) { contentDescription = "Delegated to ${t.tool}: ${t.title}, ${statusLabel(t)}" }
            .padding(S.inset),
        verticalAlignment = Alignment.Top,
    ) {
        ProviderLogo(t.tool, size = 24.dp)
        Column(Modifier.weight(1f).padding(start = S.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, Modifier.weight(1f), color = C.on, style = T.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusChip(statusLabel(t) + elapsed(t, now), if (t.gateDenied) C.tertiary else color, Modifier.padding(start = S.s), pulse = !t.terminal)
            }
            Label(listOfNotNull(t.tool, t.model).joinToString(" · "), Modifier.padding(top = S.xs), color = C.outline)
            val result = (if (t.terminal) t.summary ?: t.failure else null)?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            if (result != null) Text(Md.inline(result.trimStart('#', '>', '-', '*', ' ').take(200)).text, Modifier.padding(top = S.xs),
                color = if (t.status == "failed") C.error else C.onVar, style = T.bodySm, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (t.gateDenied) NcButton(tr(R.string.quick_upgrade), Modifier.padding(top = S.s), primary = true, icon = Ic.Lock) {
                v.host.showUpgradeSheet(tr(R.string.chat_delegating_to_needs_nativecode_pro, t.tool))
            }
        }
        if (!t.terminal) IconBtn(Ic.Stop, tr(R.string.chat_stop_delegated_task), tint = C.error, size = 16.dp, box = 40.dp) { v.bg { NcTools.cancel(t.id) } }
        else Box(Modifier.width(S.xs))
        NcIcon(Ic.ChevronRight, null, Modifier.align(Alignment.CenterVertically), tint = C.outline, size = 16.dp)
    }
}

private fun elapsed(t: NcTasks.Task, now: Long): String {
    val end = if (t.terminal) t.completedAt ?: return "" else now
    return " · " + ChatLogic.timer((end - t.createdAt).coerceAtLeast(0))
}

/** Thread list: marks a delegated child (it opens with its back-link). */
@Composable
internal fun DelegatedChip(threadId: String) {
    if (!NcTasks.isChild(threadId)) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        NcIcon(Ic.Bot, null, tint = C.tertiary, size = 12.dp)
        Label("delegated", Modifier.padding(start = S.xs), color = C.tertiary)
    }
}

/**
 * A message another thread or a finished task put in this thread (`<nc_thread_message>` / `<nc_task_result>`):
 * a full-width card with its source and a jump there, instead of a user bubble.
 */
@Composable
internal fun CrossThreadCard(v: AgentChatView, inc: NcToolSpec.Incoming, modifier: Modifier) {
    val task = inc.kind == "task"
    val accent = if (task) statusColor(inc.status ?: "completed") else C.tertiary
    val from = v.titleOf(inc.fromThread) ?: if (task) inc.label else inc.label.ifBlank { "another thread" }
    val maxW = (LocalConfiguration.current.screenWidthDp * 0.92f).dp
    Column(modifier, horizontalAlignment = Alignment.End) {
        Column(Modifier.widthIn(max = maxW).fillMaxWidth().background(C.low).border(1.dp, accent.copy(alpha = 0.5f))) {
            Row(
                Modifier.fillMaxWidth().background(accent.copy(alpha = 0.08f))
                    .clickable(enabled = inc.fromThread.isNotEmpty(), role = Role.Button, onClickLabel = "Open $from") { v.goTo(inc.fromThread) }
                    .heightIn(min = 40.dp).padding(horizontal = S.inset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NcIcon(if (task) Ic.Bot else Ic.Message, null, tint = accent, size = 14.dp)
                Column(Modifier.weight(1f).padding(start = S.s)) {
                    Overline(if (task) "Task result · ${inc.label}" else tr(R.string.chat_message_from_thread), color = accent)
                    Text(from, color = C.onVar, style = T.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                inc.status?.let { StatusChip(it.replaceFirstChar { c -> c.uppercase() }, accent) }
                if (inc.fromThread.isNotEmpty()) NcIcon(Ic.ChevronRight, null, Modifier.padding(start = S.xs), tint = C.outline, size = 16.dp)
            }
            Markdown(inc.body, streaming = false, modifier = Modifier.padding(S.inset))
        }
    }
}

private const val MAX_SHOWN = 4
