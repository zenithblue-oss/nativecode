package com.zenithblue.nativecode.cliauth.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.agent.chat.ui.BtnKind
import com.zenithblue.nativecode.agent.chat.ui.C
import com.zenithblue.nativecode.agent.chat.ui.Chevron
import com.zenithblue.nativecode.agent.chat.ui.Chip
import com.zenithblue.nativecode.agent.chat.ui.Ic
import com.zenithblue.nativecode.agent.chat.ui.IconBtn
import com.zenithblue.nativecode.agent.chat.ui.Label
import com.zenithblue.nativecode.agent.chat.ui.MItem
import com.zenithblue.nativecode.agent.chat.ui.NcButton
import com.zenithblue.nativecode.agent.chat.ui.Overline
import com.zenithblue.nativecode.agent.chat.ui.ProviderLogo
import com.zenithblue.nativecode.agent.chat.ui.S
import com.zenithblue.nativecode.agent.chat.ui.Square
import com.zenithblue.nativecode.agent.chat.ui.StatusChip
import com.zenithblue.nativecode.agent.chat.ui.T
import com.zenithblue.nativecode.agent.chat.ui.card
import com.zenithblue.nativecode.cliauth.AuthState
import com.zenithblue.nativecode.cliauth.CliAuthInfo
import com.zenithblue.nativecode.cliauth.CliToolDef
import com.zenithblue.nativecode.cliauth.CliUpdates
import com.zenithblue.nativecode.tr

/** What the Settings > AI CLI tools page shows. MainActivity fills it on the main thread; Compose reads it. */
internal class CliToolsModel {
    var tools by mutableStateOf<List<CliToolDef>>(emptyList())
    val rows: SnapshotStateMap<String, CliUpdates.Row> = mutableStateMapOf()
    /** id -> progress text while that CLI installs / updates. */
    val busy: SnapshotStateMap<String, String> = mutableStateMapOf()
    /** Login state per CLI id, plus "gh". */
    val auth: SnapshotStateMap<String, CliAuthInfo> = mutableStateMapOf()
    /** Pro tools on a Free plan. */
    val locked: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    var checking by mutableStateOf(false)
    var checkedAt by mutableLongStateOf(0L)

    fun pending() = tools.filter { rows[it.id]?.available == true && locked[it.id] != true && it.id !in busy }
}

internal interface CliToolsHost {
    fun check()
    fun updateAll()
    fun update(id: String)
    fun install(id: String)
    fun signIn(id: String)
    fun openTerminal(def: CliToolDef)
    fun upgrade(id: String)
    fun github(signedIn: Boolean)
    fun openSafety()
    fun clearCredentials()
    fun clearSuiteFlag()
}

@Composable
internal fun CliToolsPage(m: CliToolsModel, h: CliToolsHost) {
    val chat = m.tools.filter { it.chat != null }
    val term = m.tools.filter { it.chat == null }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S.l)) {
        Summary(m, h)
        if (chat.isNotEmpty()) Section(tr(R.string.cli_page_chat_ready), tr(R.string.cli_page_chat_ready_sub), chat, m, h)
        if (term.isNotEmpty()) Section(tr(R.string.cli_page_terminal_only), tr(R.string.cli_page_terminal_only_sub), term, m, h)
        Advanced(m, h)
    }
}

@Composable
private fun Summary(m: CliToolsModel, h: CliToolsHost) {
    val n = m.pending().size
    Column(Modifier.fillMaxWidth().card().padding(S.inset), verticalArrangement = Arrangement.spacedBy(S.m)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(S.s)) {
            NcButton(
                tr(if (m.checking) R.string.cli_upd_checking else R.string.cli_upd_check), Modifier.weight(1f),
                icon = Ic.Retry, enabled = !m.checking && m.busy.isEmpty(),
            ) { h.check() }
            if (n > 1) NcButton(tr(R.string.cli_upd_all, n), Modifier.weight(1f), primary = true, icon = Ic.Download, enabled = m.busy.isEmpty()) { h.updateAll() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s)) {
            if (m.checking) CircularProgressIndicator(Modifier.size(12.dp), color = C.primary, strokeWidth = 2.dp)
            else NcIconClock()
            Label(
                if (m.checkedAt == 0L) tr(R.string.cli_page_never_checked)
                else tr(R.string.cli_page_last_checked, DateUtils.getRelativeTimeSpanString(m.checkedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()),
                size = 11,
            )
        }
    }
}

@Composable
private fun NcIconClock() = com.zenithblue.nativecode.agent.chat.ui.NcIcon(Ic.Clock, null, tint = C.onVar, size = 14.dp)

@Composable
private fun Section(title: String, sub: String, tools: List<CliToolDef>, m: CliToolsModel, h: CliToolsHost) {
    Column(verticalArrangement = Arrangement.spacedBy(S.m)) {
        Column(verticalArrangement = Arrangement.spacedBy(S.xs)) {
            Overline(title, color = C.primary)
            Text(sub, color = C.onVar, style = T.bodySm)
        }
        tools.forEach { ToolCard(it, m, h) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolCard(def: CliToolDef, m: CliToolsModel, h: CliToolsHost) {
    val row = m.rows[def.id]
    val busy = m.busy[def.id]
    val locked = m.locked[def.id] == true
    val state = m.auth[def.id]?.state
    val account = m.auth[def.id]?.account
    val installed = row?.installed != null
    val missing = row != null && row.installed == null
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().card().padding(S.inset), verticalArrangement = Arrangement.spacedBy(S.m)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.m)) {
            ProviderLogo(def.terminalType, size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(S.xs)) {
                Text(def.displayName, color = C.on, style = T.heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(def.description, color = C.onVar, style = T.bodySm, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (locked) Chip("PRO", icon = Ic.Lock, color = C.tertiary)
            else Box {
                IconBtn(Ic.More, tr(R.string.cli_page_more), tint = C.onVar) { menu = true }
                DropdownMenu(menu, { menu = false }, containerColor = C.low) {
                    if (state == AuthState.SIGNED_IN) MItem(tr(R.string.main_sign_out), Ic.Lock) { menu = false; h.signIn(def.id) }
                    else if (installed && !def.loginOptional) MItem(tr(R.string.chat_sign_in), Ic.Lock) { menu = false; h.signIn(def.id) }
                    MItem(tr(R.string.chat_terminal), Ic.Terminal) { menu = false; h.openTerminal(def) }
                    MItem(tr(R.string.cli_page_reinstall), Ic.Retry) { menu = false; h.install(def.id) }
                }
            }
        }
        if (!locked) FlowRow(horizontalArrangement = Arrangement.spacedBy(S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
            when {
                row == null -> StatusChip(tr(R.string.cli_upd_checking), C.onVar, pulse = true)
                missing -> StatusChip(tr(R.string.cli_upd_not_installed).uppercase(), C.onVar)
                else -> StatusChip("${tr(R.string.cli_page_installed)} v${row?.installed}".uppercase(), C.primary)
            }
            if (installed) {
                when {
                    row?.latest == null -> StatusChip(tr(R.string.cli_upd_cant_check).uppercase(), C.onVar)
                    row.available -> StatusChip(tr(R.string.cli_upd_to, row.latest.orEmpty()), C.tertiary)
                    else -> StatusChip(tr(R.string.main_up_to_date), C.primary)
                }
                when {
                    state == AuthState.SIGNED_IN -> StatusChip(listOfNotNull(tr(R.string.main_signed_in), account).joinToString(" · "), C.primary)
                    state == AuthState.SIGNED_OUT && def.loginOptional -> StatusChip(tr(R.string.cli_page_login_optional).uppercase(), C.onVar)
                    state == AuthState.SIGNED_OUT -> StatusChip(tr(R.string.main_not_signed_in_2), C.tertiary)
                }
            }
        }
        if (busy != null) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), color = C.primary, strokeWidth = 2.dp)
            Text(busy, Modifier.padding(start = S.s), color = C.onVar, style = T.bodySm, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
            when {
                locked -> NcButton(tr(R.string.usage_see_pro), primary = true, icon = Ic.Lock) { h.upgrade(def.id) }
                missing -> NcButton(tr(R.string.cli_page_install), primary = true, icon = Ic.Download) { h.install(def.id) }
                row?.available == true -> NcButton(tr(R.string.main_update), primary = true, icon = Ic.Download) { h.update(def.id) }
            }
            if (!locked && installed && state == AuthState.SIGNED_OUT && !def.loginOptional) {
                NcButton(tr(R.string.chat_sign_in), primary = row?.available != true) { h.signIn(def.id) }
            }
        }
    }
}

@Composable
private fun Advanced(m: CliToolsModel, h: CliToolsHost) {
    var open by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Int?>(null) }
    val gh = m.auth["gh"]?.state == AuthState.SIGNED_IN
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S.m)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = { open = !open }).padding(vertical = S.s),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s),
        ) {
            Overline(tr(R.string.cli_page_advanced), Modifier.weight(1f), color = C.primary)
            Chevron(open)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().card().padding(S.inset), verticalArrangement = Arrangement.spacedBy(S.m)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.s)) {
                    Text(tr(R.string.main_github_account), Modifier.weight(1f), color = C.on, style = T.heading)
                    StatusChip(tr(if (gh) R.string.main_signed_in else R.string.main_not_signed_in_2), if (gh) C.primary else C.tertiary)
                }
                NcButton(tr(if (gh) R.string.main_sign_out else R.string.main_connect_with_github), Modifier.fillMaxWidth(), primary = !gh) { h.github(gh) }
            }
            NcButton(tr(R.string.main_ai_safety_report), Modifier.fillMaxWidth(), icon = Ic.ShieldCheck) { h.openSafety() }
            NcButton(tr(R.string.cli_page_clear_creds), Modifier.fillMaxWidth(), danger = true, icon = Ic.Trash) { confirm = 0 }
            NcButton(tr(R.string.cli_page_clear_flag), Modifier.fillMaxWidth(), danger = true, icon = Ic.Trash) { confirm = 1 }
        }
    }
    confirm?.let { which ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = C.low, shape = Square,
            title = { Text(tr(if (which == 0) R.string.cli_page_clear_creds else R.string.cli_page_clear_flag), style = T.title, color = C.on) },
            text = { Text(tr(if (which == 0) R.string.cli_page_clear_creds_msg else R.string.cli_page_clear_flag_msg), style = T.bodySm, color = C.onVar) },
            confirmButton = { NcButton(tr(R.string.cli_page_clear), danger = true) { confirm = null; if (which == 0) h.clearCredentials() else h.clearSuiteFlag() } },
            dismissButton = { NcButton(tr(R.string.x11_cancel), kind = BtnKind.GHOST) { confirm = null } },
        )
    }
}
