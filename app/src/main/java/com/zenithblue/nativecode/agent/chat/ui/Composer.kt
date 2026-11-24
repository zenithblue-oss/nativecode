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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.SkillBlocks
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.skills.SkillsCatalog
import org.json.JSONArray
import org.json.JSONObject
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/** One chip in the attachment strip: copying, done ([att]) or failed ([error], Retry). */
internal data class Pending(val key: Long, val name: String, val uri: Uri?, val image: Boolean, val att: ChatAttachment? = null, val error: String? = null)

/** Composer text + chips of one thread; persisted as `threads.draft_json` {text, attachments[]}. */
internal class ComposerState(draftJson: String?) {
    /** Field value incl. selection; only the text field's own edits write it directly. */
    var input by mutableStateOf(TextFieldValue(""))
    /** Programmatic writes (slash/skill/@file/$skill inserts, restore, clear) put the caret at the end of the new text. */
    var text: String
        get() = input.text
        set(v) { input = TextFieldValue(v, TextRange(v.length)) }
    val chips = mutableStateListOf<Pending>()
    /** NativeCode skills attached to the next message (chip = name; the md goes with the prompt). */
    val skills = mutableStateListOf<SkillBlocks.Block>()
    var sheet by mutableStateOf<ComposerSheet?>(null)

    init { restore(draftJson) }

    /** Load a [draftJson] (persisted draft, or a refused send given back). */
    fun restore(draftJson: String?) {
        runCatching {
            val o = JSONObject(draftJson ?: return@runCatching)
            clear()
            text = o.optString("text")
            val a = o.optJSONArray("attachments") ?: JSONArray()
            for (i in 0 until a.length()) a.getJSONObject(i).let { j ->
                val att = ChatAttachment(j.getString("name"), j.optString("mime"), j.optLong("size"), j.optString("guestPath").ifEmpty { null }, j.optString("hostPath").ifEmpty { null })
                chips += Pending(System.nanoTime(), att.name, null, att.isImage, att)
            }
            val sk = o.optJSONArray("skills") ?: JSONArray()
            for (i in 0 until sk.length()) sk.getJSONObject(i).let { skills += SkillBlocks.Block(it.getString("name"), it.getString("body")) }
        }
    }

    val ready: List<ChatAttachment> get() = chips.mapNotNull { it.att }
    val busy: Boolean get() = chips.any { it.att == null && it.error == null }

    fun addSkill(name: String, body: String) { if (skills.none { it.name == name }) skills += SkillBlocks.Block(name, body) }

    /** Prompt as sent: skill blocks first, then the typed text. */
    fun prompt(): String = SkillBlocks.wrap(skills, text.trim())

    /** Load a stored message back (queue Edit): skill blocks become chips again. */
    fun load(message: String) {
        val (b, rest) = SkillBlocks.split(message)
        skills.clear(); skills += b; text = rest
    }

    fun draftJson(): String? {
        if (text.isEmpty() && ready.isEmpty() && skills.isEmpty()) return null
        val a = JSONArray()
        ready.forEach { a.put(JSONObject().put("name", it.name).put("mime", it.mime).put("size", it.sizeBytes).put("guestPath", it.guestPath ?: "").put("hostPath", it.hostPath ?: "")) }
        val sk = JSONArray()
        skills.forEach { sk.put(JSONObject().put("name", it.name).put("body", it.body)) }
        return JSONObject().put("text", text).put("attachments", a).put("skills", sk).toString()
    }

    fun clear() { text = ""; chips.clear(); skills.clear() }
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

/**
 * Multiline composer card: IME Enter = newline. Slash popup, "+" action sheet, model / effort / mode chips (inline
 * pickers, applied at once), context ring, send button that turns into the one Stop while a turn runs (t3code).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Composer(
    v: AgentChatView,
    t: ChatThread,
    s: ComposerState,
    placeholder: String,
    commands: List<SlashCommand>,
    slashCtx: SlashMenu.Ctx,
    running: Boolean,
    canSteer: Boolean,
    context: ContextInfo,
    autoFocus: Boolean,
    stopping: Boolean,
    forceStop: Boolean,
    onSend: (steer: Boolean) -> Unit,
    onStop: () -> Unit,
    onForceStop: () -> Unit,
    onSlash: (SlashCommand) -> Unit,
) {
    var sendMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var confirmFull by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val focus = remember { MutableInteractionSource() }
    val focused by focus.collectIsFocusedAsState()
    val field = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { field.requestFocus() } }
    val catalog = remember { SkillsCatalog.all() }
    val live = v.live.value
    val currentModel = t.model ?: live?.defaultModel
    val model = live?.models?.firstOrNull { it.id == currentModel }
    val efforts = if (live?.caps?.effort == true) model?.variants.orEmpty() else emptyList()
    fun cfg(c: ConfigChange) = v.bg { v.backend.configure(t.id, c) }
    Column(Modifier.fillMaxWidth().background(C.lowest)) {
        Hairline()
        val trigger = SlashMenu.trigger(s.text)
        if (trigger != null) {
            val hits = SlashMenu.items(commands, slashCtx, trigger, bare = s.chips.isEmpty())
            val (cmds, skills) = hits.partition { it.kind != SlashCommand.Kind.SKILL }
            val nc = SkillsCatalog.matching(trigger.query.removePrefix("skill:"), catalog)
            if (hits.isNotEmpty() || nc.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().padding(start = S.m, end = S.m, top = S.s).heightIn(max = 260.dp).brutal(3.dp).card(C.low)) {
                items(cmds, key = { "c:" + it.name }) { c -> SlashRow(c, "/" + c.name) { onSlash(c) } }
                if (skills.isNotEmpty()) item("skills") {
                    Overline(tr(R.string.chat_skills), Modifier.padding(start = S.m, end = S.m, top = S.s, bottom = S.xs), color = C.outline)
                }
                items(skills, key = { "s:" + it.name }) { c -> SlashRow(c, "/skill:" + c.name) { onSlash(c) } }
                if (nc.isNotEmpty()) item("nc") {
                    Overline(tr(R.string.chat_nativecode_skills), Modifier.padding(start = S.m, end = S.m, top = S.s, bottom = S.xs), color = C.outline)
                }
                items(nc, key = { "n:" + it.id }) { sk ->
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Attach skill ${sk.title}") {
                        s.text = s.text.substringBeforeLast('\n', ""); s.addSkill(sk.title, sk.body)
                    }.heightIn(min = 44.dp).padding(horizontal = S.m, vertical = S.gap), verticalAlignment = Alignment.CenterVertically) {
                        NcIcon(Ic.BookOpen, null, tint = C.tertiary, size = 14.dp)
                        Text(sk.title, Modifier.padding(start = S.s), color = C.primary, style = T.codeSm, maxLines = 1)
                        Text(sk.summary, Modifier.weight(1f).padding(start = S.m), color = C.onVar, style = T.bodySm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = S.m, vertical = S.s).card(C.low, if (focused) C.primary else C.border).animateContentSize(),
        ) {
            if (s.chips.isNotEmpty() || s.skills.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = S.s, end = S.s, top = S.s),
                horizontalArrangement = Arrangement.spacedBy(S.gap)) {
                s.skills.forEach { k -> SkillChip(s, k) }
                s.chips.forEach { c -> AttachChip(v, t, s, c) }
            }
            BasicTextField(
                s.input, { s.input =if (it.text.length > 120_000) TextFieldValue(it.text.take(120_000), TextRange(120_000)) else it },
                Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 168.dp).focusRequester(field).padding(horizontal = S.m, vertical = S.inset),
                textStyle = T.body.copy(color = C.on),
                cursorBrush = SolidColor(C.primary),
                interactionSource = focus,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box {
                        if (s.text.isEmpty()) Text(placeholder, color = C.outline, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Argument hint as ghost text after `/name ` until arguments are typed.
                        SlashMenu.ghostHint(s.text, SlashMenu.builtins(slashCtx) + commands)?.let { h ->
                            Text(buildAnnotatedString { withStyle(SpanStyle(color = Color.Transparent)) { append(s.text) }; append(h) },
                                color = C.outline, style = T.body, maxLines = 4)
                        }
                        inner()
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(start = S.xs, end = S.xs, bottom = S.xs), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.Plus, tr(R.string.chat_add_photo_file_file_command), tint = C.onVar, box = 40.dp) { s.sheet = ComposerSheet.PLUS }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(S.gap)) {
                    val modelName = model?.name?.ifBlank { null } ?: currentModel?.substringAfterLast('/') ?: tr(R.string.chat_default_model)
                    Chip(
                        modelName + if (model?.free == true) " · free" else "",
                        Modifier.widthIn(max = 140.dp).semantics { contentDescription = tr(if (model?.free == true) R.string.chat_model_chip_desc_free else R.string.chat_model_chip_desc, providerName(t.tool), modelName) },
                        trailing = Ic.ChevronDown, leading = { ProviderLogo(t.tool, size = 20.dp) },
                    ) { s.sheet = ComposerSheet.MODEL }
                    if (efforts.isNotEmpty()) Box {
                        val level = t.effort ?: tr(R.string.chat_effort_default)
                        Chip(
                            level, Modifier.semantics { contentDescription = tr(R.string.chat_effort_chip_desc, level) },
                            icon = Ic.Lightbulb, trailing = Ic.ChevronDown,
                        ) { effortMenu = true }
                        DropdownMenu(effortMenu, { effortMenu = false }, containerColor = C.low) {
                            Overline(tr(R.string.chat_reasoning_effort), Modifier.padding(horizontal = S.l, vertical = S.xs), color = C.outline)
                            efforts.forEach { x ->
                                val on = x == t.effort
                                DropdownMenuItem(
                                    text = { Text(x, style = T.codeSm, color = if (on) C.primary else C.on) },
                                    onClick = { effortMenu = false; if (!on) cfg(ConfigChange(effort = x)) },
                                    trailingIcon = if (on) ({ NcIcon(Ic.Check, tr(R.string.chat_selected), tint = C.primary, size = 16.dp) }) else null,
                                )
                            }
                        }
                    }
                    Box {
                        val full = t.runtimeMode == RuntimeMode.FULL_ACCESS
                        Chip(
                            t.runtimeMode.label, Modifier.semantics { contentDescription = "Access mode ${t.runtimeMode.label}. Change mode" },
                            color = if (full) C.tertiary else C.onVar, icon = modeIcon(t.runtimeMode), trailing = Ic.ChevronDown,
                        ) { modeMenu = true }
                        DropdownMenu(modeMenu, { modeMenu = false }, containerColor = C.low) {
                            (live?.caps?.modes ?: RuntimeMode.entries).forEach { m ->
                                val on = m == t.runtimeMode
                                DropdownMenuItem(
                                    text = {
                                        Column(Modifier.padding(vertical = S.xs)) {
                                            Text(m.label, style = T.body, color = C.on)
                                            Text(modeHint(m), style = T.meta, color = C.outline)
                                        }
                                    },
                                    onClick = { modeMenu = false; if (m == RuntimeMode.FULL_ACCESS && !on) confirmFull = true else cfg(ConfigChange(runtimeMode = m)) },
                                    leadingIcon = { NcIcon(modeIcon(m), null, tint = if (m == RuntimeMode.FULL_ACCESS) C.tertiary else if (on) C.primary else C.onVar, size = 18.dp) },
                                    trailingIcon = if (on) ({ NcIcon(Ic.Check, "Selected", tint = C.primary, size = 16.dp) }) else null,
                                )
                            }
                        }
                    }
                }
                ContextRing(context)
                val canSend = (s.text.isNotBlank() || s.ready.isNotEmpty() || s.skills.isNotEmpty()) && !s.busy
                val stop = !canSend && running
                val label = when { stop && forceStop -> tr(R.string.chat_force_stop); stop -> tr(R.string.quick_stop); running && canSteer -> tr(R.string.chat_steer_now); running -> tr(R.string.chat_queue_message); else -> tr(R.string.chat_send) }
                Box(Modifier.padding(start = S.xs)) {
                    Box(
                        Modifier.size(40.dp).background(if (canSend) C.primary else if (stop) C.errorCon else C.high)
                            .combinedClickable(
                                role = Role.Button,
                                enabled = !(stop && stopping && !forceStop),
                                onClickLabel = label,
                                onLongClick = { if (running && canSend && canSteer) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); sendMenu = true } },
                                onClick = { if (canSend) onSend(running && canSteer) else if (running) { if (forceStop) onForceStop() else onStop() } },
                            )
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            stop && stopping && !forceStop -> Spinner(16.dp, C.on)
                            stop -> NcIcon(Ic.Stop, null, tint = if (forceStop) C.error else C.on, size = 16.dp)
                            running && canSend && !canSteer -> NcIcon(Ic.Clock, null, tint = C.onPrimary, size = 18.dp)
                            else -> NcIcon(Ic.ArrowUp, null, tint = if (canSend) C.onPrimary else C.outline, size = 20.dp)
                        }
                    }
                    DropdownMenu(sendMenu, { sendMenu = false }, containerColor = C.low) {
                        MItem(tr(R.string.chat_steer_now), Ic.Steer) { sendMenu = false; onSend(true) }
                        MItem(tr(R.string.chat_queue), Ic.Clock) { sendMenu = false; onSend(false) }
                    }
                }
            }
        }
    }
    ComposerSheets(v, t, s) {
        val sep = if (s.text.isEmpty() || s.text.endsWith("\n")) "" else "\n"
        s.text = s.text + sep + "/"
    }
    if (confirmFull) FullAccessDialog({ confirmFull = false }) { cfg(ConfigChange(runtimeMode = RuntimeMode.FULL_ACCESS)) }
}

/** Attached NativeCode skill: name only; the md stays out of the text field. */
@Composable
private fun SkillChip(s: ComposerState, k: SkillBlocks.Block) {
    Row(Modifier.heightIn(min = 32.dp).card(C.container, C.tertiary).padding(start = S.s), verticalAlignment = Alignment.CenterVertically) {
        NcIcon(Ic.BookOpen, null, tint = C.tertiary, size = 14.dp)
        Text(k.name, Modifier.padding(start = S.gap).widthIn(max = 180.dp), color = C.on, style = T.codeSm, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconBtn(Ic.Close, "Remove skill ${k.name}", tint = C.onVar, size = 14.dp, box = 36.dp) { s.skills.remove(k) }
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
            Modifier.padding(start = S.gap).widthIn(max = 180.dp), color = if (failed) C.error else C.on, style = T.codeSm, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (failed && c.uri != null) IconBtn(Ic.Retry, "Retry attachment", tint = C.primary, size = 14.dp, box = 36.dp) { s.chips.remove(c); s.add(v, t, c.uri, c.image) }
        IconBtn(Ic.Close, tr(R.string.chat_remove_attachment), tint = C.onVar, size = 14.dp, box = 36.dp) { s.chips.remove(c) }
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
            Modifier.size(40.dp).clickable(onClickLabel = tr(R.string.chat_context_details)) { open = true }
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
                Overline(tr(R.string.chat_context_window), color = C.outline)
                Text(
                    if (c.used != null && c.size != null) "${ChatLogic.tokens(c.used)} / ${ChatLogic.tokens(c.size)} tokens · $pct%" else "Size not reported",
                    Modifier.padding(top = S.xs), color = C.on, style = T.label,
                )
                Text(
                    when {
                        c.compacting -> tr(R.string.chat_compacting_the_conversation_now)
                        (f ?: 0f) >= 0.8f -> tr(R.string.chat_near_the_limit_the_agent)
                        else -> tr(R.string.chat_the_agent_compacts_older_context)
                    },
                    Modifier.padding(top = S.xs), color = C.onVar, style = T.bodySm,
                )
            }
        }
    }
}

/** Popup row: label, greyed argument hint, description; skills carry a "skill" tag. */
@Composable
private fun SlashRow(c: SlashCommand, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Insert $label", onClick = onClick).heightIn(min = 44.dp).padding(horizontal = S.m, vertical = S.gap),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.primary, style = T.codeSm, maxLines = 1)
        c.argumentHint?.let { Text(it, Modifier.padding(start = S.xs), color = C.outline, style = T.codeSm, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(c.description.orEmpty(), Modifier.weight(1f).padding(start = S.m), color = C.onVar, style = T.bodySm, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (c.kind == SlashCommand.Kind.SKILL) Text("skill", Modifier.padding(start = S.s).card(C.container).padding(horizontal = S.xs), color = C.tertiary, style = T.meta)
    }
}

private fun size(b: Long) = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576f)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}
