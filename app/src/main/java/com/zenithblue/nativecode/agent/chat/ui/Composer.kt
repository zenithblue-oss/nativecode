package com.zenithblue.nativecode.agent.chat.ui

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import org.json.JSONArray
import org.json.JSONObject

/** One chip in the attachment strip: copying, done ([att]) or failed ([error], Retry). */
internal data class Pending(val key: Long, val name: String, val uri: Uri?, val image: Boolean, val att: ChatAttachment? = null, val error: String? = null)

/** Composer text + chips of one thread; persisted as `threads.draft_json` {text, attachments[]}. */
internal class ComposerState(draftJson: String?) {
    var text by mutableStateOf("")
    val chips = mutableStateListOf<Pending>()

    init {
        runCatching {
            val o = JSONObject(draftJson ?: return@runCatching)
            text = o.optString("text")
            val a = o.optJSONArray("attachments") ?: JSONArray()
            for (i in 0 until a.length()) a.getJSONObject(i).let { j ->
                val att = ChatAttachment(j.getString("name"), j.optString("mime"), j.optLong("size"), j.optString("guestPath").ifEmpty { null }, j.optString("hostPath").ifEmpty { null })
                chips += Pending(System.nanoTime(), att.name, null, att.isImage, att)
            }
        }
    }

    val ready: List<ChatAttachment> get() = chips.mapNotNull { it.att }
    val busy: Boolean get() = chips.any { it.att == null && it.error == null }

    fun draftJson(): String? {
        if (text.isEmpty() && ready.isEmpty()) return null
        val a = JSONArray()
        ready.forEach { a.put(JSONObject().put("name", it.name).put("mime", it.mime).put("size", it.sizeBytes).put("guestPath", it.guestPath ?: "").put("hostPath", it.hostPath ?: "")) }
        return JSONObject().put("text", text).put("attachments", a).toString()
    }

    fun clear() { text = ""; chips.clear() }
}

/** Copy a pick into the thread cwd and track its chip. */
internal fun ComposerState.add(v: AgentChatView, t: ChatThread, uri: Uri, image: Boolean) {
    val p = Pending(System.nanoTime(), uri.lastPathSegment?.substringAfterLast('/') ?: "file", uri, image)
    chips += p
    v.backend.attach(uri, t.cwd, image) { att, err ->
        val i = chips.indexOfFirst { it.key == p.key }
        if (i >= 0) chips[i] = if (att != null) p.copy(name = att.name, att = att) else p.copy(error = err ?: "Copy failed")
    }
}

/** Context window: tokens used / max; [compacting] = compaction running now. */
internal data class ContextInfo(val used: Long?, val size: Long?, val compacting: Boolean)

/** Multiline composer card: IME Enter = newline. Slash popup, attach, model + mode chips, context ring, send / stop. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Composer(
    v: AgentChatView,
    t: ChatThread,
    s: ComposerState,
    placeholder: String,
    commands: List<SlashCommand>,
    running: Boolean,
    canSteer: Boolean,
    context: ContextInfo,
    freeModel: Boolean,
    onSend: (steer: Boolean) -> Unit,
    onStop: () -> Unit,
    onSlash: (String) -> Unit,
) {
    var plus by remember { mutableStateOf(false) }
    var sendMenu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    Column(Modifier.fillMaxWidth().background(C.lowest)) {
        Hairline()
        val slash = s.text.takeIf { it.startsWith("/") && it.none(Char::isWhitespace) }
        if (slash != null) {
            val all = listOf(SlashCommand("model", "Model, effort, mode"), SlashCommand("clear", "New thread, same tool")) + commands
            val hits = all.filter { it.name.startsWith(slash.drop(1), ignoreCase = true) }.distinctBy { it.name }
            if (hits.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().padding(start = S.m, end = S.m, top = S.s).heightIn(max = 220.dp).brutal(3.dp).card(C.low)) {
                items(hits, key = { it.name }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onSlash(c.name) }.heightIn(min = 44.dp).padding(horizontal = S.m, vertical = S.xs + S.xxs),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("/" + c.name, color = C.primary, style = T.codeSm)
                        Text(c.description.orEmpty(), Modifier.padding(start = S.m), color = C.onVar, style = T.bodySm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = S.m, vertical = S.s).card(C.low, if (focused) C.primary else C.border).animateContentSize(),
        ) {
            if (s.chips.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = S.s, end = S.s, top = S.s),
                horizontalArrangement = Arrangement.spacedBy(S.s - S.xxs)) {
                s.chips.forEach { c -> AttachChip(v, t, s, c) }
            }
            BasicTextField(
                s.text, { s.text = it.take(120_000) },
                Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 168.dp).padding(horizontal = S.m, vertical = S.m - S.xxs),
                textStyle = T.body.copy(color = C.on),
                cursorBrush = SolidColor(C.primary),
                interactionSource = focus,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box {
                        if (s.text.isEmpty()) Text(placeholder, color = C.outline, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(start = S.xs, end = S.xs, bottom = S.xs), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconBtn(Ic.Plus, "Attach a photo or file", tint = C.onVar, box = 40.dp) { plus = true }
                    DropdownMenu(plus, { plus = false }, containerColor = C.low) {
                        MItem("Photo", Ic.Image) { plus = false; v.host.pickImage { s.add(v, t, it, true) } }
                        MItem("File", Ic.FileText) { plus = false; v.host.pickFile { s.add(v, t, it, false) } }
                    }
                }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(S.s - S.xxs)) {
                    Chip(
                        listOfNotNull(t.model?.substringAfterLast('/') ?: "Default model", t.effort, "free".takeIf { freeModel }).joinToString(" · "),
                        Modifier.widthIn(max = 200.dp).semantics { contentDescription = "Model ${t.model ?: "default"}, change" },
                        icon = Ic.Bot, trailing = Ic.ChevronDown,
                    ) { v.push(Route.Settings(t.id)) }
                    Chip(
                        t.runtimeMode.label, Modifier.semantics { contentDescription = "Approval mode ${t.runtimeMode.label}, change" },
                        color = if (t.runtimeMode == RuntimeMode.FULL_ACCESS) C.tertiary else C.onVar,
                        icon = if (t.runtimeMode == RuntimeMode.FULL_ACCESS) Ic.ShieldAlert else Ic.ShieldCheck,
                    ) { v.push(Route.Settings(t.id)) }
                }
                ContextRing(context)
                val canSend = (s.text.isNotBlank() || s.ready.isNotEmpty()) && !s.busy
                val stop = !canSend && running
                Box(Modifier.padding(start = S.xs)) {
                    Box(
                        Modifier.size(40.dp).background(if (canSend) C.primary else if (stop) C.errorCon else C.high)
                            .combinedClickable(
                                role = Role.Button,
                                onClickLabel = when { stop -> "Stop"; running && canSteer -> "Steer"; running -> "Queue"; else -> "Send" },
                                onLongClick = { if (running && canSend && canSteer) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); sendMenu = true } },
                                onClick = { if (canSend) onSend(running && canSteer) else if (running) onStop() },
                            )
                            .semantics { contentDescription = when { stop -> "Stop"; running && canSteer -> "Steer now"; running -> "Queue message"; else -> "Send" } },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            stop -> NcIcon(Ic.Stop, null, tint = C.on, size = 16.dp)
                            running && canSend && !canSteer -> NcIcon(Ic.Clock, null, tint = C.onPrimary, size = 18.dp)
                            else -> NcIcon(Ic.ArrowUp, null, tint = if (canSend) C.onPrimary else C.outline, size = 20.dp)
                        }
                    }
                    DropdownMenu(sendMenu, { sendMenu = false }, containerColor = C.low) {
                        MItem("Steer now", Ic.Steer) { sendMenu = false; onSend(true) }
                        MItem("Queue", Ic.Clock) { sendMenu = false; onSend(false) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachChip(v: AgentChatView, t: ChatThread, s: ComposerState, c: Pending) {
    val failed = c.error != null
    Row(Modifier.heightIn(min = 32.dp).card(C.container, if (failed) C.error else C.border).padding(start = S.s), verticalAlignment = Alignment.CenterVertically) {
        when {
            c.att == null && !failed -> Spinner(12.dp)
            else -> NcIcon(if (c.image) Ic.Image else Ic.FileText, null, tint = if (failed) C.error else C.onVar, size = 14.dp)
        }
        Text(c.error ?: (c.name + (c.att?.let { " · " + size(it.sizeBytes) } ?: " · copying")),
            Modifier.padding(start = S.xs + S.xxs).widthIn(max = 180.dp), color = if (failed) C.error else C.on, style = T.codeSm, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (failed && c.uri != null) IconBtn(Ic.Retry, "Retry attachment", tint = C.primary, size = 14.dp, box = 36.dp) { s.chips.remove(c); s.add(v, t, c.uri, c.image) }
        IconBtn(Ic.Close, "Remove attachment", tint = C.onVar, size = 14.dp, box = 36.dp) { s.chips.remove(c) }
    }
}

/** Drawn context ring: fill = used / max, amber ≥ 80 %, red ≥ 95 %; tap = details. Spinner while compacting. */
@Composable
private fun ContextRing(c: ContextInfo) {
    val f = ChatLogic.contextFraction(c.used, c.size)
    if (f == null && !c.compacting) return
    var open by remember { mutableStateOf(false) }
    val color = when { f == null -> C.primary; f >= 0.95f -> C.error; f >= 0.8f -> C.tertiary; else -> C.primary }
    val pct = f?.let { (it * 100).toInt() }
    Box {
        Box(
            Modifier.size(40.dp).clickable(onClickLabel = "Context details") { open = true }
                .semantics { contentDescription = if (c.compacting) "Compacting context" else "Context $pct percent used" },
            contentAlignment = Alignment.Center,
        ) {
            if (c.compacting) Spinner(18.dp, C.tertiary)
            else Canvas(Modifier.size(20.dp)) {
                val w = 2.5.dp.toPx()
                val box = Size(size.width - w, size.height - w)
                val o = Offset(w / 2, w / 2)
                drawArc(C.border, 0f, 360f, false, o, box, style = Stroke(w))
                drawArc(color, -90f, 360f * (f ?: 0f), false, o, box, style = Stroke(w))
            }
        }
        DropdownMenu(open, { open = false }, containerColor = C.low) {
            Column(Modifier.padding(horizontal = S.l, vertical = S.s).widthIn(max = 260.dp)) {
                Overline("Context window", color = C.outline)
                Text(
                    if (c.used != null && c.size != null) "${ChatLogic.tokens(c.used)} / ${ChatLogic.tokens(c.size)} tokens · $pct%" else "Size not reported",
                    Modifier.padding(top = S.xs), color = C.on, style = T.label,
                )
                Text(
                    when {
                        c.compacting -> "Compacting the conversation now."
                        (f ?: 0f) >= 0.8f -> "Near the limit: the agent compacts older context soon."
                        else -> "The agent compacts older context near the limit."
                    },
                    Modifier.padding(top = S.xs), color = C.onVar, style = T.bodySm,
                )
            }
        }
    }
}

private fun size(b: Long) = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576f)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}
