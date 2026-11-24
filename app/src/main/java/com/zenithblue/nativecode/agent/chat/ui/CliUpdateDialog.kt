package com.zenithblue.nativecode.agent.chat.ui
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.agent.chat.core.ChatFailures
import com.zenithblue.nativecode.cliauth.CliNeed
import com.zenithblue.nativecode.cliauth.CliToolCatalog
import com.zenithblue.nativecode.cliauth.CliVersionCheck
import com.zenithblue.nativecode.tr
/**
 * "This CLI is too old for chat / sign-in. Update now?" Shown before a chat session or the sign-in popup starts, and by the error card's
 * Update. [busy] = the update runner's progress line (null = idle), [error] = why the last update failed (Retry + Terminal then).
 */
@Composable
internal fun CliUpdateDialog(r: CliVersionCheck.Result, need: CliNeed, busy: String?, error: String?, onUpdate: () -> Unit, onCancel: () -> Unit, onTerminal: () -> Unit) {
    val def = CliToolCatalog.byId(r.tool) ?: return
    AlertDialog(
        onDismissRequest = { if (busy == null) onCancel() }, containerColor = C.low, shape = Square,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.m)) {
                ProviderLogo(def.terminalType, size = 32.dp)
                Text(tr(R.string.chat_err_version_old), style = T.title, color = C.on)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(S.s)) {
                Text(tr(if (need == CliNeed.CHAT) R.string.cli_gate_chat else R.string.cli_gate_login, r.name, r.installed.orEmpty(), r.min(need).orEmpty()), color = C.on, style = T.bodySm)
                if (busy != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = C.primary, strokeWidth = 2.dp)
                    Text(busy, Modifier.padding(start = S.s), color = C.onVar, style = T.bodySm)
                }
                if (error != null && busy == null) Text(tr(R.string.chat_err_update_failed, ChatFailures.reason(error)), color = C.error, style = T.bodySm)
            }
        },
        confirmButton = {
            if (busy == null) NcButton(tr(if (error != null) R.string.chat_retry else R.string.chat_err_update), icon = Ic.Download, primary = true, onClick = onUpdate)
        },
        dismissButton = {
            if (busy == null) Row(horizontalArrangement = Arrangement.spacedBy(S.s)) {
                if (error != null) NcButton(tr(R.string.chat_terminal), icon = Ic.Terminal, onClick = onTerminal)
                NcButton(tr(R.string.git_cancel), onClick = onCancel)
            }
        },
    )
}
