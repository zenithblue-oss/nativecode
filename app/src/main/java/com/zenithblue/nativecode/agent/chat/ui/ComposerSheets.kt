package com.zenithblue.nativecode.agent.chat.ui

import android.content.ClipboardManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.zenithblue.nativecode.skills.SkillIcons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.zenithblue.nativecode.agent.chat.ModelCache
import com.zenithblue.nativecode.agent.chat.tools.NcTasks
import com.zenithblue.nativecode.cliauth.AuthState
import com.zenithblue.nativecode.cliauth.CliCredentialProbe
import com.zenithblue.nativecode.cliauth.CliToolCatalog
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.agent.chat.core.ChatThread
import com.zenithblue.nativecode.agent.chat.core.ConfigChange
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.skills.SkillCategory
import com.zenithblue.nativecode.skills.SkillsCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/** Composer bottom sheets (t3code mobile composer pickers). */
internal enum class ComposerSheet { MODEL, PLUS, SKILLS, FILES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerSheets(v: AgentChatView, t: ChatThread, s: ComposerState, onSlash: () -> Unit) {
    val sheet = s.sheet ?: return
    val close = { s.sheet = null }
    if (sheet == ComposerSheet.MODEL) return ModelSheet(v, t, close)
    ModalBottomSheet(close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.bg, shape = Square, dragHandle = { SheetHandle() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = S.l).padding(bottom = S.l).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(S.s)) {
            when (sheet) {
                ComposerSheet.MODEL -> Unit
                ComposerSheet.PLUS -> PlusSheet(v, t, s, onSlash)
                ComposerSheet.SKILLS -> SkillSheet(s, close)
                ComposerSheet.FILES -> FileSheet(v, t, s, close)
            }
        }
    }
}

/**
 * Provider + model picker, a bottom sheet opened from the model chip (t3code ProviderModelPicker): search, a tab per CLI tool
 * (no chat yet = dimmed "Terminal only", Pro tool on Free = lock + upsell, signed out = "Sign in" opens its sign-in popup), then
 * that provider's models (effort has its own composer chip). A model of the current provider applies at once; one of another provider
 * switches the thread to it (handoff, see ChatThreadController.switchProvider). Tabs are locked (with the reason) while a
 * turn runs and for delegated children, which stay on their provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelSheet(v: AgentChatView, t: ChatThread, close: () -> Unit) {
    val ctx = LocalContext.current
    val live = v.live.value
    val busy = live?.runId != null
    val child = NcTasks.isChild(t.id)
    val canSwitch = !busy && !child
    val defs = remember { CliToolCatalog.forMethod(LinuxCommandBuilder.currentMethod).sortedBy { it.chat == null } }
    // Signed-out providers (login optional ones, like opencode, stay usable) show "Sign in" and open their sign-in popup instead of switching.
    var login by remember { mutableStateOf<String?>(null) }
    var authRev by remember { mutableIntStateOf(0) }
    val auth by produceState(emptyMap<String, AuthState>(), authRev) {
        val home = v.host.guestHome()
        value = withContext(Dispatchers.IO) { defs.associate { it.terminalType to CliCredentialProbe.probe(it.id, home).state } }
    }
    var picked by remember { mutableStateOf(t.tool) }
    val sel = if (canSwitch) picked else t.tool
    val own = sel == t.tool
    // Last-known list of another provider (it has no session here); tagged with its tool so a stale one never shows.
    val cached by produceState<Pair<String, ModelCache.Entry?>?>(null, sel) {
        value = if (sel == t.tool) null else sel to withContext(Dispatchers.IO) { ModelCache.load(ctx, sel) }
    }
    val loaded = own || cached?.first == sel
    val models = if (own) live?.models.orEmpty() else cached?.takeIf { it.first == sel }?.second?.models.orEmpty()
    val current = if (own) t.model ?: live?.defaultModel else null
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.bg, shape = Square, dragHandle = { SheetHandle() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = S.l).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(S.m)) {
            Column {
                Text(tr(R.string.chat_picker_title), color = C.on, style = T.title)
                if (!canSwitch) Text(tr(if (child) R.string.chat_provider_child else R.string.chat_provider_busy), color = C.outline, style = T.bodySm)
            }
            if (models.size > 5) SearchField(q, { q = it }, tr(R.string.chat_search_models, models.size))
            LazyRow(Modifier.fillMaxWidth().selectableGroup(), rememberLazyListState(defs.indexOfFirst { it.terminalType == t.tool }.coerceAtLeast(0)), horizontalArrangement = Arrangement.spacedBy(S.s)) {
                items(defs, key = { it.id }) { def ->
                    val chat = def.chat != null
                    val locked = chat && !v.host.canUseTool(def.terminalType)
                    val on = def.terminalType == sel
                    val signIn = chat && !locked && !def.loginOptional && def.terminalType != t.tool && auth[def.terminalType] == AuthState.SIGNED_OUT
                    val note = when { !chat -> tr(R.string.chat_provider_terminal_short); signIn -> tr(R.string.chat_sign_in); else -> null }
                    Row(
                        Modifier.heightIn(min = 48.dp).alpha(if (chat && (canSwitch || on)) 1f else 0.4f)
                            .background(if (on) C.primary.copy(alpha = 0.14f) else C.low).border(1.dp, if (on) C.primary else C.border)
                            .selectable(on, enabled = chat && (canSwitch || on), role = Role.Tab) {
                                when {
                                    locked -> { v.host.showUpgradeSheet(tr(R.string.chat_provider_requires_pro, def.displayName)); close() }
                                    signIn -> login = def.id
                                    else -> { picked = def.terminalType; q = "" }
                                }
                            }
                            .padding(horizontal = S.m)
                            .semantics { if (!chat) stateDescription = tr(R.string.chat_provider_terminal_only) else if (locked) stateDescription = tr(R.string.chat_provider_requires_pro_state) else if (signIn) stateDescription = tr(R.string.chat_sign_in) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s),
                    ) {
                        ProviderLogo(def.terminalType, size = 24.dp)
                        Column {
                            Text(def.displayName, color = if (on) C.primary else C.on, style = T.codeSm, maxLines = 1)
                            if (note != null) Text(note, color = C.tertiary, style = T.meta, maxLines = 1)
                        }
                        if (locked) Row(Modifier.background(C.tertiary.copy(alpha = 0.16f)).padding(horizontal = S.s, vertical = S.xs), verticalAlignment = Alignment.CenterVertically) {
                            NcIcon(Ic.Lock, null, tint = C.tertiary, size = 12.dp)
                            Label("PRO", Modifier.padding(start = S.xs), color = C.tertiary, size = 10, bold = true)
                        }
                    }
                }
            }
            val hits = models.filter { q.isBlank() || it.name.contains(q, true) || it.id.contains(q, true) }
            // claude: the aliases carry the full id in `detail`; the older generations the CLI still accepts have none.
            val older = if (sel == "claude-code" && hits.any { it.detail != null }) hits.filter { it.detail == null && it.id != "default" }.toSet() else emptySet()
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp).weight(1f, fill = false)) {
                when {
                    models.isNotEmpty() -> {
                        items(hits.filter { it !in older }, key = { it.id }) { m -> ModelRow(m, own && m.id == current) { pick(v, t, sel, own, m, close) } }
                        if (older.isNotEmpty()) item("older") { Overline(tr(R.string.chat_previous_models), Modifier.padding(top = S.m, bottom = S.xs), color = C.outline) }
                        items(older.toList(), key = { "o:" + it.id }) { m -> ModelRow(m, own && m.id == current) { pick(v, t, sel, own, m, close) } }
                        if (hits.isEmpty()) item("none") { Text(tr(R.string.chat_no_model_matches), Modifier.padding(vertical = S.m), color = C.outline, style = T.bodySm) }
                    }
                    !loaded -> item("load") { Spinner(18.dp, C.outline) }
                    !own -> item("other") {
                        Column(Modifier.padding(vertical = S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
                            Text(tr(R.string.chat_default_model), color = C.on, style = T.body)
                            Text(tr(R.string.chat_switch_to_load_models), color = C.outline, style = T.bodySm)
                            NcButton(tr(R.string.chat_switch_to_provider, providerName(sel)), icon = Ic.Retry) { v.bg { v.backend.switchProvider(t.id, sel, null) }; close() }
                        }
                    }
                    else -> item("own") {
                        Column(Modifier.padding(vertical = S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
                            Text(current ?: tr(R.string.chat_default_model), color = C.on, style = T.body)
                            if (live?.alive == true) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s)) {
                                Spinner(14.dp, C.outline)
                                Text(tr(R.string.chat_loading_models), color = C.outline, style = T.bodySm)
                            } else {
                                Text(tr(R.string.chat_start_the_session_to_load), color = C.outline, style = T.bodySm)
                                NcButton(tr(R.string.chat_load_models), icon = Ic.Retry) { v.bg { v.backend.resume(t.id) } }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(S.xs))
        }
    }
    login?.let { id ->
        CliLoginSheet(id, onChanged = { authRev++ }, openTerminal = { d -> v.host.openTerminal(d.terminalType, emptyList(), null, false); close() }, close = { login = null })
    }
}

private fun pick(v: AgentChatView, t: ChatThread, sel: String, own: Boolean, m: ModelInfo, close: () -> Unit) {
    val on = own && m.id == (t.model ?: v.live.value?.defaultModel)
    if (!on) v.bg { if (own) v.backend.configure(t.id, ConfigChange(model = m.id)) else v.backend.switchProvider(t.id, sel, m.id) }
    close()
}

/** Model name (one line), id / version + context below, FREE badge and a check on the selected one; 56 dp touch target. */
@Composable
private fun ModelRow(m: ModelInfo, on: Boolean, onClick: () -> Unit) {
    val name = m.name.ifBlank { m.id }
    val sub = listOfNotNull((m.detail ?: m.id).takeIf { it != name }, m.contextLimit?.let { "${ChatLogic.tokens(it)} ctx" }).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton, onClick = onClick)
            .background(if (on) C.primary.copy(alpha = 0.08f) else Color.Transparent).heightIn(min = 56.dp).padding(horizontal = S.s, vertical = S.s),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = if (on) C.primary else C.on, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = C.outline, style = T.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (m.free) FreeBadge()
        if (on) NcIcon(Ic.Check, tr(R.string.chat_selected), tint = C.primary, size = 18.dp)
    }
}

@Composable
internal fun FreeBadge() = Box(Modifier.background(C.primary.copy(alpha = 0.14f)).padding(horizontal = S.s, vertical = S.xs)) {
    Label(tr(R.string.chat_free), color = C.primary, size = 10, bold = true)
}

/** "+": labeled actions; rows the CLI cannot use are hidden. */
@Composable
private fun PlusSheet(v: AgentChatView, t: ChatThread, s: ComposerState, onSlash: () -> Unit) {
    val ctx = LocalContext.current
    val caps = v.live.value?.caps
    val cm = remember { ctx.getSystemService(ClipboardManager::class.java) }
    val clipImage = remember { runCatching { cm?.primaryClipDescription?.hasMimeType("image/*") == true }.getOrDefault(false) }
    Text(tr(R.string.chat_add_to_message), color = C.on, style = T.title)
    if (caps?.images != false) SheetRow(Ic.Image, "Photo", "Attach an image from the gallery") { s.sheet = null; v.host.pickImage { s.add(v, t, it, true) } }
    if (clipImage && caps?.images != false) SheetRow(Ic.ClipboardList, "Clipboard image", "Attach the image you copied") {
        s.sheet = null
        runCatching { cm?.primaryClip?.getItemAt(0)?.uri }.getOrNull()?.let { s.add(v, t, it, true) }
    }
    SheetRow(Ic.FileText, tr(R.string.chat_file_or_document), tr(R.string.chat_copy_a_file_into_the)) { s.sheet = null; v.host.pickFile { s.add(v, t, it, false) } }
    SheetRow(Ic.AtSign, tr(R.string.chat_mention_project_file), tr(R.string.chat_search_the_workspace_insert_path)) { s.sheet = ComposerSheet.FILES }
    SheetRow(Ic.SquareSlash, tr(R.string.chat_slash_command), "Browse ${t.tool} commands") { s.sheet = null; onSlash() }
    SheetRow(Ic.BookOpen, tr(R.string.chat_nativecode_skills), tr(R.string.chat_attach_a_skill_guide_to)) { s.sheet = ComposerSheet.SKILLS }
}

@Composable
private fun SkillSheet(s: ComposerState, close: () -> Unit) {
    val byCat = remember { SkillCategory.entries.associateWith { SkillsCatalog.skills(it) } }
    var cat by remember { mutableStateOf<SkillCategory?>(null) }
    var q by remember { mutableStateOf("") }
    Text(tr(R.string.chat_nativecode_skills), color = C.on, style = T.title)
    SearchField(q, { q = it }, tr(R.string.chat_search_skills))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(S.s)) {
        Chip(tr(R.string.chat_all), on = cat == null) { cat = null }
        SkillCategory.entries.forEach { c -> Chip(c.title, on = cat == c, icon = skillIcon(c)) { cat = c } }
    }
    val pool = cat?.let { byCat.getValue(it).map { sk -> it to sk } } ?: byCat.flatMap { (c, l) -> l.map { c to it } }
    val hits = SkillsCatalog.matching(q, pool.map { it.second }).toSet()
    val shown = pool.filter { it.second in hits }
    if (shown.isEmpty()) Text(tr(R.string.chat_no_skill_matches), color = C.outline, style = T.bodySm)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
        items(shown, key = { it.second.id }) { (c, sk) ->
            val ctx = LocalContext.current
            val logo by produceState<ImageBitmap?>(null, sk.id) {
                value = withContext(Dispatchers.IO) { SkillIcons.decode(ctx, SkillIcons.file(sk.id))?.asImageBitmap() }
            }
            SheetRow(skillIcon(c), sk.title, sk.summary, on = s.skills.any { it.name == sk.title }, logo = logo) { s.addSkill(sk.title, sk.body); close() }
        }
    }
}

internal fun skillIcon(c: SkillCategory): ImageVector = when (c) {
    SkillCategory.INBUILT -> Ic.Sparkles
    SkillCategory.PROJECT -> Ic.Wrench
    SkillCategory.INSTALL -> Ic.Download
}

private val SKIP_DIRS = setOf("node_modules", "build", "dist", "target", "__pycache__", "venv")

/** Workspace files relative to the thread cwd (host side of the guest rootfs); hidden and build dirs skipped. */
private fun workspaceFiles(root: File): List<String> = runCatching {
    root.walkTopDown().maxDepth(12)
        .onEnter { it == root || (!it.name.startsWith(".") && it.name !in SKIP_DIRS) }
        .filter { it.isFile }.take(20_000).map { it.relativeTo(root).path }.toList()
}.getOrDefault(emptyList())

@Composable
private fun FileSheet(v: AgentChatView, t: ChatThread, s: ComposerState, close: () -> Unit) {
    val root = remember(t.cwd) { File(v.host.guestHome().path.removeSuffix(AgentChatView.HOME) + t.cwd) }
    val files by produceState<List<String>?>(null, root) { value = withContext(Dispatchers.IO) { workspaceFiles(root) } }
    var q by remember { mutableStateOf("") }
    Text(tr(R.string.chat_mention_project_file), color = C.on, style = T.title)
    SearchField(q, { q = it }, "Search files in ${ChatLogic.baseName(t.cwd).ifEmpty { "~" }}")
    val all = files
    if (all == null) { Spinner(18.dp); return }
    val hits = all.filter { q.isBlank() || it.contains(q.trim(), true) }
        .sortedWith(compareBy({ !it.substringAfterLast('/').startsWith(q.trim(), true) }, { it.length })).take(100)
    if (hits.isEmpty()) Text(if (all.isEmpty()) tr(R.string.chat_no_files_in_this_folder) else tr(R.string.chat_no_file_matches), color = C.outline, style = T.bodySm)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
        items(hits, key = { it }) { p ->
            SheetRow(Ic.FileText, p.substringAfterLast('/'), p.substringBeforeLast('/', "./")) {
                val sep = if (s.text.isEmpty() || s.text.last().isWhitespace()) "" else " "
                s.text = s.text + sep + "@" + p + " "
                close()
            }
        }
    }
}

/** Sheet row: icon tile, title, one-line subtitle; [on] = selected. */
@Composable
private fun SheetRow(icon: ImageVector, title: String, subtitle: String, on: Boolean = false, logo: ImageBitmap? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = title, onClick = onClick).heightIn(min = 56.dp).padding(vertical = S.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).card(C.low, if (on) C.primary else C.border), contentAlignment = Alignment.Center) {
            if (logo != null) Image(logo, null, Modifier.size(24.dp)) else NcIcon(icon, null, tint = if (on) C.primary else C.onVar, size = 18.dp)
        }
        Column(Modifier.weight(1f).padding(start = S.m)) {
            Text(title, color = C.on, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = C.outline, style = T.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (on) NcIcon(Ic.Check, tr(R.string.chat_added), tint = C.primary, size = 16.dp)
    }
}

@Composable
private fun SearchField(q: String, onQ: (String) -> Unit, hint: String) {
    Row(Modifier.fillMaxWidth().card(C.lowest).heightIn(min = 44.dp).padding(horizontal = S.m), verticalAlignment = Alignment.CenterVertically) {
        NcIcon(Ic.Search, null, tint = C.outline, size = 16.dp)
        BasicTextField(q, onQ, Modifier.weight(1f).padding(start = S.s), singleLine = true, textStyle = T.body.copy(color = C.on),
            cursorBrush = SolidColor(C.primary),
            decorationBox = { inner -> Box { if (q.isEmpty()) Text(hint, color = C.outline, style = T.body); inner() } })
        if (q.isNotEmpty()) IconBtn(Ic.Close, tr(R.string.chat_clear_search), tint = C.onVar, size = 14.dp, box = 36.dp) { onQ("") }
    }
}

/** Approval modes: icon + one-line description (composer menu and thread settings). */
internal fun modeIcon(m: RuntimeMode): ImageVector = when (m) {
    RuntimeMode.APPROVAL_REQUIRED -> Ic.ShieldCheck
    RuntimeMode.AUTO_ACCEPT_EDITS -> Ic.Pencil
    RuntimeMode.AUTO -> Ic.Zap
    RuntimeMode.FULL_ACCESS -> Ic.ShieldAlert
}

internal fun modeHint(m: RuntimeMode) = when (m) {
    RuntimeMode.APPROVAL_REQUIRED -> tr(R.string.chat_asks_before_edits_and_commands)
    RuntimeMode.AUTO_ACCEPT_EDITS -> tr(R.string.chat_edits_freely_asks_before_commands)
    RuntimeMode.AUTO -> tr(R.string.chat_asks_only_for_risky_actions)
    RuntimeMode.FULL_ACCESS -> tr(R.string.chat_never_asks_runs_every_command)
}

@Composable
internal fun FullAccessDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) = AlertDialog(
    onDismissRequest = onDismiss, containerColor = C.low, shape = Square,
    icon = { NcIcon(Ic.ShieldAlert, null, tint = C.tertiary, size = 24.dp) },
    title = { Text(tr(R.string.chat_full_access), style = T.title, color = C.on) },
    text = { Text(tr(R.string.chat_the_agent_runs_every_command), style = T.bodySm, color = C.onVar) },
    confirmButton = { NcButton(tr(R.string.chat_allow_full_access), danger = true) { onConfirm(); onDismiss() } },
    dismissButton = { NcButton(tr(R.string.git_cancel), onClick = onDismiss) },
)
