package com.zenithblue.nativecode.agent.chat.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.cliauth.AuthState
import com.zenithblue.nativecode.cliauth.CliAuthInfo
import com.zenithblue.nativecode.cliauth.CliAuthListener
import com.zenithblue.nativecode.cliauth.CliAuthPhase
import com.zenithblue.nativecode.cliauth.CliAuthService
import com.zenithblue.nativecode.cliauth.CliAuthSession
import com.zenithblue.nativecode.cliauth.CliCredentialProbe
import com.zenithblue.nativecode.cliauth.CliLoginStrategy
import com.zenithblue.nativecode.cliauth.CliToolCatalog
import com.zenithblue.nativecode.cliauth.CliToolDef
import com.zenithblue.nativecode.cliauth.CliToolStatus
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import com.zenithblue.nativecode.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Ph { CHECK, SIGNED, OUT, WAIT, KEY, TERMINAL, DONE, FAILED }

/**
 * The one AI CLI sign-in popup (replaces the old login pages). Runs the tool's existing [CliAuthService] flow: device code, browser URL +
 * pasted code, API key, or hand-over to its terminal. Already signed in = status + Sign out. Closing the sheet cancels a running login;
 * success closes it after a beat. [onChanged] fires after a sign-in / sign-out so every list repaints. Codes, URLs and keys are never logged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CliLoginSheet(id: String, onChanged: () -> Unit, openTerminal: (CliToolDef) -> Unit, close: () -> Unit) {
    val def = remember(id) { CliToolCatalog.byId(id) } ?: return
    val ctx = LocalContext.current
    val method = remember { LinuxCommandBuilder.currentMethod }
    val scope = rememberCoroutineScope()
    val h = remember { object { var session: CliAuthSession? = null; var alive = true } }
    var ph by remember { mutableStateOf(Ph.CHECK) }
    var info by remember { mutableStateOf<CliAuthInfo?>(null) }
    var otp by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf(def.consoleUrl) }
    var msg by remember { mutableStateOf<String?>(null) }
    var secret by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var guided by remember { mutableStateOf(false) }
    var confirmOut by remember { mutableStateOf(false) }

    fun probe(then: (CliAuthInfo) -> Unit) {
        scope.launch { then(withContext(Dispatchers.IO) { CliCredentialProbe.probe(id, ProjectPathResolver.guestHomeDir(ctx, method)) }) }
    }
    fun live() = h.alive && ph == Ph.WAIT   // a stopped / replaced login (Use a key, Retry) must not move the sheet
    val listener = remember {
        object : CliAuthListener {
            override fun onPhase(phase: CliAuthPhase, message: String) { if (live()) msg = message }
            override fun onLog(line: String) {}
            override fun onOtp(code: String) { if (live()) otp = code }
            override fun onUrl(url: String) { if (live()) link = url }
            override fun onDone(status: CliToolStatus) { if (live()) { info = CliAuthInfo(AuthState.SIGNED_IN, status.accountLabel); ph = Ph.DONE } }
            override fun onFailed(message: String) { if (live()) { msg = message; ph = Ph.FAILED } }
            // Also what a timed-out or auto-stopped (already signed in) login reports: the credential file decides.
            override fun onCancelled() {
                if (live()) probe { if (it.state == AuthState.SIGNED_IN) { info = it; ph = Ph.DONE } else { msg = tr(R.string.cli_device_login_failed_or_timed); ph = Ph.FAILED } }
            }
            override fun onTerminalGuided(toolId: String, commandHint: String) { if (live()) guided = true }
        }
    }
    fun begin() {
        secret = ""; otp = null; copied = false; guided = false; msg = null; link = def.consoleUrl
        when (def.strategy) {
            CliLoginStrategy.API_KEY_FORM -> ph = Ph.KEY
            CliLoginStrategy.TERMINAL_GUIDED -> ph = Ph.TERMINAL
            else -> { ph = Ph.WAIT; h.session = CliAuthService.startLogin(ctx, method, id, listener) }
        }
    }
    fun stop() { h.session?.cancel(); h.session = null }
    fun paste(): String? = (ctx.getSystemService(ClipboardManager::class.java)).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()

    LaunchedEffect(Unit) { probe { if (it.state == AuthState.SIGNED_IN) { info = it; ph = Ph.SIGNED } else begin() } }
    LaunchedEffect(ph) { if (ph == Ph.DONE) { onChanged(); delay(900); close() } }
    DisposableEffect(Unit) { onDispose { h.alive = false; stop() } }

    ModalBottomSheet(close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.bg, shape = Square, dragHandle = { SheetHandle() }) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = S.l).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(S.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.m)) {
                ProviderLogo(def.terminalType, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(def.displayName, color = C.on, style = T.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (def.subtitle.isNotBlank()) Text(def.subtitle, color = C.outline, style = T.bodySm, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Hairline()
            when (ph) {
                Ph.CHECK -> Status(null, C.outline, tr(R.string.main_starting))
                Ph.SIGNED, Ph.DONE -> {
                    Status(Ic.CircleCheck, C.primary, tr(R.string.cli_signed_in), info?.account ?: info?.detail)
                    if (ph == Ph.SIGNED) Row(horizontalArrangement = Arrangement.spacedBy(S.s)) {
                        NcButton(tr(R.string.main_sign_out), Modifier.weight(1f), danger = true) { confirmOut = true }
                        NcButton(tr(R.string.chat_close), Modifier.weight(1f), onClick = close)
                    }
                }
                Ph.OUT -> {
                    Status(Ic.Lock, C.tertiary, tr(R.string.main_not_signed_in_2))
                    Row(horizontalArrangement = Arrangement.spacedBy(S.s)) {
                        NcButton(tr(R.string.chat_sign_in), Modifier.weight(1f), primary = true) { begin() }
                        NcButton(tr(R.string.chat_close), Modifier.weight(1f), onClick = close)
                    }
                }
                Ph.WAIT -> {
                    val browser = def.strategy == CliLoginStrategy.STREAM_URL
                    Text(tr(if (browser) R.string.cli_login_steps_browser else R.string.cli_login_steps_device), color = C.onVar, style = T.bodySm)
                    otp?.let { c ->
                        Column(Modifier.fillMaxWidth().card(C.lowest).padding(S.m), verticalArrangement = Arrangement.spacedBy(S.s)) {
                            Overline(tr(R.string.cli_login_code), color = C.outline)
                            Text(c, color = C.primary, style = T.title.copy(fontFamily = Mono, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = 3.sp))
                            NcButton(tr(if (copied) R.string.chat_copied else R.string.chat_copy), icon = if (copied) Ic.Check else Ic.Copy) { CliAuthService.copyCode(ctx, c); copied = true }
                        }
                    }
                    Status(null, C.outline, if (otp != null || link != def.consoleUrl) tr(R.string.cli_waiting_for_browser_approval) else msg ?: tr(R.string.main_starting))
                    link?.let { u -> NcButton(tr(R.string.cli_login_open_page), Modifier.fillMaxWidth(), primary = true, icon = Ic.ExternalLink) { CliAuthService.openBrowser(ctx, u) } }
                    if (browser && link != def.consoleUrl) {
                        SecretField(secret, { secret = it }, tr(R.string.main_paste_the_code_from_the)) { paste()?.let { secret = it } }
                        NcButton(tr(R.string.main_send), Modifier.fillMaxWidth(), enabled = secret.isNotBlank()) {
                            if (h.session?.writeLine(secret.trim()) == true) { secret = ""; msg = tr(R.string.main_code_sent_waiting) }
                        }
                    }
                    if (def.envKey != null) NcButton(tr(R.string.cli_login_use_key), Modifier.fillMaxWidth(), kind = BtnKind.GHOST) { stop(); secret = ""; ph = Ph.KEY }
                    NcButton(tr(R.string.git_cancel), Modifier.fillMaxWidth(), danger = true) { stop(); close() }
                }
                Ph.KEY -> {
                    Text(tr(R.string.cli_login_steps_key), color = C.onVar, style = T.bodySm)
                    def.consoleUrl?.let { u -> NcButton(tr(R.string.main_open_website), Modifier.fillMaxWidth(), icon = Ic.ExternalLink) { CliAuthService.openBrowser(ctx, u) } }
                    SecretField(secret, { secret = it }, def.envKey ?: tr(R.string.main_api_key)) { paste()?.let { secret = it } }
                    msg?.let { Text(it, color = C.error, style = T.bodySm) }
                    Row(horizontalArrangement = Arrangement.spacedBy(S.s)) {
                        NcButton(tr(R.string.chat_save), Modifier.weight(1f), primary = true, enabled = secret.isNotBlank()) {
                            val key = secret; secret = ""; msg = null
                            CliAuthService.saveApiKey(ctx, method, id, key) { ok, m, st ->
                                if (!h.alive) return@saveApiKey
                                if (ok) { info = CliAuthInfo(AuthState.SIGNED_IN, st?.accountLabel); ph = Ph.DONE } else msg = m
                            }
                        }
                        NcButton(tr(R.string.git_cancel), Modifier.weight(1f), onClick = close)
                    }
                }
                Ph.TERMINAL -> {
                    Text(tr(R.string.cli_login_steps_terminal), color = C.onVar, style = T.bodySm)
                    Row(horizontalArrangement = Arrangement.spacedBy(S.s)) {
                        NcButton(tr(R.string.main_open_terminal), Modifier.weight(1f), primary = true, icon = Ic.Terminal) { openTerminal(def); close() }
                        NcButton(tr(R.string.chat_close), Modifier.weight(1f), onClick = close)
                    }
                }
                Ph.FAILED -> {
                    Status(Ic.CircleX, C.error, tr(R.string.chat_failed), msg)
                    Row(horizontalArrangement = Arrangement.spacedBy(S.s)) {
                        NcButton(tr(R.string.chat_retry), Modifier.weight(1f), primary = true, icon = Ic.Retry) { begin() }
                        if (guided) NcButton(tr(R.string.main_open_terminal), Modifier.weight(1f), icon = Ic.Terminal) { openTerminal(def); close() }
                        NcButton(tr(R.string.chat_close), Modifier.weight(1f), onClick = close)
                    }
                }
            }
            Spacer(Modifier.height(S.xs))
        }
    }
    if (confirmOut) AlertDialog(
        onDismissRequest = { confirmOut = false }, containerColor = C.low, shape = Square,
        title = { Text(tr(R.string.main_sign_out_of, def.displayName), style = T.title, color = C.on) },
        text = { Text(tr(R.string.main_this_removes_the_saved_login), style = T.bodySm, color = C.onVar) },
        confirmButton = {
            NcButton(tr(R.string.main_sign_out), danger = true) {
                confirmOut = false
                CliAuthService.logout(ctx, method, id) { _, _ -> if (h.alive) { info = null; ph = Ph.OUT }; onChanged() }
            }
        },
        dismissButton = { NcButton(tr(R.string.git_cancel)) { confirmOut = false } },
    )
}

/** Status line: spinner when [icon] is null, else the icon in [tint]; [sub] (account, reason) below the title. */
@Composable
private fun Status(icon: ImageVector?, tint: Color, title: String, sub: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(S.m)) {
        if (icon == null) Spinner(18.dp, tint) else NcIcon(icon, null, tint = tint, size = 22.dp)
        Column(Modifier.weight(1f)) {
            Text(title, color = if (icon == null) C.onVar else C.on, style = T.heading)
            if (!sub.isNullOrBlank()) Text(sub, color = C.onVar, style = T.bodySm)
        }
    }
}

/** Masked single-line input (key / pasted code) with a Paste action. */
@Composable
private fun SecretField(value: String, onValue: (String) -> Unit, hint: String, onPaste: () -> Unit) {
    Row(Modifier.fillMaxWidth().card(C.lowest).heightIn(min = 48.dp).padding(start = S.m), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value, onValue, Modifier.weight(1f), singleLine = true, textStyle = T.code.copy(color = C.on), cursorBrush = SolidColor(C.primary),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            decorationBox = { inner -> androidx.compose.foundation.layout.Box { if (value.isEmpty()) Text(hint, color = C.outline, style = T.code, maxLines = 1); inner() } },
        )
        NcButton(tr(R.string.term_paste), kind = BtnKind.GHOST, onClick = onPaste)
    }
}
