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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
    val sheet = s.sheet?.takeIf { it != ComposerSheet.MODEL } ?: return // MODEL = inline menu on the chip
    val close = { s.sheet = null }
    ModalBottomSheet(close, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.bg, shape = Square, dragHandle = { SheetHandle() }) {
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
 * Unified picker for the menu anchored to the model chip (t3code ProviderModelPicker): provider first (logo strip of the
 * CLI tools; no chat yet = dimmed, Pro tool on Free = lock + upsell), then that provider's models. A model of the current
 * provider applies at once; one of another provider switches the thread to it (handoff, see ChatThreadController.switchProvider).
 * The strip is hidden while a turn runs and for delegated children (they stay on their provider).
 * ponytail: plain Column (DropdownMenu measures intrinsics, a LazyColumn would crash), so rows are capped at 60; search narrows.
 */
@Composable
internal fun ModelMenu(v: AgentChatView, t: ChatThread, close: () -> Unit) {
    val ctx = LocalContext.current
    val live = v.live.value
    val canSwitch = live?.runId == null && !NcTasks.isChild(t.id)
    val defs = remember { CliToolCatalog.forMethod(LinuxCommandBuilder.currentMethod).sortedBy { it.chat == null } }
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
    Column(Modifier.width(300.dp).padding(horizontal = S.m, vertical = S.xs), verticalArrangement = Arrangement.spacedBy(S.xs)) {
        if (canSwitch) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(S.xs)) {
            defs.forEach { def ->
                val chat = def.chat != null
                val locked = chat && !v.host.canUseTool(def.terminalType)
                val on = def.terminalType == sel
                Column(
                    Modifier.width(68.dp).alpha(if (chat) 1f else 0.4f)
                        .selectable(on, enabled = chat, role = Role.RadioButton) {
                            if (locked) { v.host.showUpgradeSheet(tr(R.string.chat_provider_requires_pro, def.displayName)); close() } else { picked = def.terminalType; q = "" }
                        }
                        .background(if (on) C.primary.copy(alpha = 0.08f) else C.low).padding(S.xs)
                        .semantics { if (!chat) stateDescription = tr(R.string.chat_provider_terminal_only) else if (locked) stateDescription = tr(R.string.chat_provider_requires_pro_state) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        ProviderLogo(def.terminalType, size = 32.dp)
                        if (locked) NcIcon(Ic.Lock, null, Modifier.align(Alignment.BottomEnd), tint = C.tertiary, size = 12.dp)
                    }
                    Text(def.displayName, color = if (on) C.primary else C.onVar, style = T.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Overline(tr(R.string.chat_model_header, providerName(sel)), color = C.outline)
        if (models.isEmpty()) {
            if (!own) {
                if (loaded) {
                    Text(tr(R.string.chat_default_model), color = C.on, style = T.codeSm)
                    Text(tr(R.string.chat_switch_to_load_models), color = C.outline, style = T.meta)
                    NcButton(tr(R.string.chat_switch_to_provider, providerName(sel)), icon = Ic.Retry) { v.bg { v.backend.switchProvider(t.id, sel, null) }; close() }
                }
                return@Column
            }
            Text(current ?: tr(R.string.chat_default_model), color = C.on, style = T.codeSm)
            if (live?.alive == true) Text("Loading models…", color = C.outline, style = T.meta)
            else {
                Text(tr(R.string.chat_start_the_session_to_load), color = C.outline, style = T.meta)
                NcButton(tr(R.string.chat_load_models), icon = Ic.Retry) { v.bg { v.backend.resume(t.id) } }
            }
            return@Column
        }
        if (models.size > 8) SearchField(q, { q = it }, tr(R.string.chat_search_models, models.size))
        val hits = models.filter { q.isBlank() || it.name.contains(q, true) || it.id.contains(q, true) }
        hits.take(60).forEach { m ->
            val on = own && m.id == current
            Row(
                Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton) {
                    if (!on) v.bg { if (own) v.backend.configure(t.id, ConfigChange(model = m.id)) else v.backend.switchProvider(t.id, sel, m.id) }
                    close()
                }.background(if (on) C.primary.copy(alpha = 0.08f) else C.low).heightIn(min = 48.dp).padding(horizontal = S.s, vertical = S.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Ring(if (on) C.primary else C.outline, size = 14.dp, filled = on)
                Column(Modifier.weight(1f).padding(horizontal = S.s)) {
                    Text(m.name.ifBlank { m.id }, color = C.on, style = T.bodySm, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(m.detail ?: m.id, m.contextLimit?.let { "${ChatLogic.tokens(it)} ctx" }).joinToString(" · "),
                        color = C.outline, style = T.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (m.free) FreeBadge()
            }
        }
        if (hits.size > 60) Text("${hits.size - 60} more · search to narrow", color = C.outline, style = T.meta)
        if (hits.isEmpty()) Text(tr(R.string.chat_no_model_matches), color = C.outline, style = T.bodySm)
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
