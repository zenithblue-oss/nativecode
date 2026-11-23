package com.zenithblue.nativecode.agent.chat.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zenithblue.nativecode.agent.chat.core.ItemPayload.Bridge
import com.zenithblue.nativecode.agent.chat.core.TurnItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/** Look of one bridge action: icon, overline label, accent. */
private data class BridgeLook(val icon: ImageVector, val label: String, val accent: Color)

private fun look(action: String) = when (action) {
    "open_link" -> BridgeLook(Ic.ExternalLink, tr(R.string.chat_open_link), C.primary)
    "preview" -> BridgeLook(Ic.Globe, tr(R.string.chat_preview), C.primary)
    "install_apk" -> BridgeLook(Ic.Package, tr(R.string.chat_install_apk), C.primary)
    "share_file" -> BridgeLook(Ic.Share, tr(R.string.chat_file_for_you), C.primary)
    "status" -> BridgeLook(Ic.Activity, tr(R.string.chat_task_status), C.tertiary)
    "toast" -> BridgeLook(Ic.Message, tr(R.string.chat_message), C.onVar)
    else -> BridgeLook(Ic.Bell, tr(R.string.chat_notification), C.tertiary)
}

private val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
private val FULL = SimpleDateFormat(tr(R.string.chat_eee_d_mmm_hh_mm), Locale.getDefault())

/** http(s) only: an agent-supplied `intent:` / `file:` / `javascript:` URI is never launched (Copy still works). */
internal fun openableUrl(url: String?): Boolean = url != null && Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(url.trim())

internal fun bridgeSize(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Agent bridge request as a timeline card (instead of the terminal popup): icon tile, overline + title, state chip,
 * message, the target (URL / file / package) and the action buttons. [full] = history list (timestamp with date).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BridgeCard(v: AgentChatView, threadId: String, item: TurnItem, p: Bridge, modifier: Modifier, full: Boolean = false) {
    val ctx = LocalContext.current
    val lk = look(p.action)
    val pending = p.state == Bridge.PENDING && p.action in setOf("open_link", "install_apk", "share_file")
    val (stateLabel, stateColor) = when {
        p.state == Bridge.DISMISSED -> "Dismissed" to C.outline
        p.action == "status" && p.state == Bridge.PENDING -> "Running" to C.tertiary
        p.state == Bridge.PENDING -> "Pending" to C.tertiary
        p.action in setOf("notify", "toast") -> tr(R.string.chat_delivered) to C.outline
        p.action == "preview" -> "Detected" to C.primary
        else -> tr(R.string.chat_done) to C.primary
    }
    fun mark(state: String) = v.bg { v.backend.bridgeState(threadId, item.id, state) }
    val at = p.at.takeIf { it > 0 } ?: item.updatedAt
    Column(
        modifier.then(if (pending) Modifier.brutal(3.dp) else Modifier)
            .card(C.low, if (pending) lk.accent.copy(alpha = 0.6f) else C.border).animateContentSize().padding(S.inset)
            .semantics(mergeDescendants = false) { contentDescription = "${lk.label}: ${p.title}, $stateLabel" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).background(lk.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                NcIcon(lk.icon, null, tint = lk.accent, size = 18.dp)
            }
            Column(Modifier.weight(1f).padding(horizontal = S.m)) {
                Overline(lk.label + " · " + (if (full) FULL else TIME).format(Date(at)), color = C.outline)
                Text(p.title.ifBlank { lk.label }, color = C.on, style = T.heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            StatusChip(stateLabel, stateColor, pulse = p.action == "status" && p.state == Bridge.PENDING)
        }
        if (p.body.isNotBlank()) Text(p.body, Modifier.padding(top = S.s), color = C.onVar, style = T.bodySm)
        targetLine(p)?.let { (icon, text) ->
            Row(Modifier.fillMaxWidth().padding(top = S.s).card(C.lowest).padding(horizontal = S.s, vertical = S.s), verticalAlignment = Alignment.CenterVertically) {
                NcIcon(icon, null, tint = C.outline, size = 14.dp)
                Text(text, Modifier.padding(start = S.s), color = C.on, style = T.codeSm, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (p.action == "share_file" && p.target != null) {
            val f = remember(p.target) { java.io.File(p.target) }
            // One stat per card: a file that is gone (temp cleanup) keeps the old Share/Copy-path card without a preview.
            val here by produceState(false, f) { value = withContext(Dispatchers.IO) { f.isFile } }
            if (here) FilePreviewCard(f, f.name, { v.openHostFile(f.absolutePath) }, Modifier.padding(top = S.s))
        }
        if (p.action == "status") p.detail?.toIntOrNull()?.takeIf { it in 0..100 }?.let { pct ->
            LinearProgressIndicator(
                progress = { pct / 100f }, Modifier.fillMaxWidth().padding(top = S.s).height(4.dp),
                color = if (pct >= 100) C.primary else C.tertiary, trackColor = C.border, gapSize = 0.dp, drawStopIndicator = {},
            )
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = S.s), horizontalArrangement = Arrangement.spacedBy(S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
            when (p.action) {
                "open_link" -> {
                    if (openableUrl(p.target)) NcButton(tr(R.string.chat_open), kind = if (pending) BtnKind.PRIMARY else BtnKind.OUTLINE, icon = Ic.ExternalLink) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.target!!.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onSuccess { mark(Bridge.DONE) }.onFailure { Toast.makeText(ctx, tr(R.string.chat_no_app_can_open_this), Toast.LENGTH_SHORT).show() }
                    }
                    NcButton(tr(R.string.chat_copy), icon = Ic.Copy) { copy(ctx, "URL", p.target.orEmpty()) }
                }
                "preview" -> {
                    NcButton(tr(R.string.chat_open), kind = BtnKind.PRIMARY, icon = Ic.Globe) { v.host.openPreview(p.target) }
                    NcButton(tr(R.string.chat_browser), icon = Ic.ExternalLink) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.target.orEmpty())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    NcButton(tr(R.string.chat_copy), icon = Ic.Copy) { copy(ctx, "URL", p.target.orEmpty()) }
                }
                "install_apk" -> {
                    NcButton(tr(R.string.chat_install), kind = if (pending) BtnKind.PRIMARY else BtnKind.OUTLINE, icon = Ic.Download) { p.target?.let { v.host.saveApk(it); mark(Bridge.DONE) } }
                    NcButton(tr(R.string.chat_share), icon = Ic.Share) { p.target?.let { v.host.shareFile(it); mark(Bridge.DONE) } }
                }
                "share_file" -> {
                    NcButton(tr(R.string.chat_save_share), kind = if (pending) BtnKind.PRIMARY else BtnKind.OUTLINE, icon = Ic.Share) { p.target?.let { v.host.shareFile(it); mark(Bridge.DONE) } }
                    NcButton(tr(R.string.chat_copy_path), icon = Ic.Copy) { copy(ctx, "Path", p.target.orEmpty()) }
                }
                else -> NcButton(tr(R.string.chat_copy), icon = Ic.Copy) { copy(ctx, p.title, listOf(p.title, p.body).filter { it.isNotBlank() }.joinToString("\n")) }
            }
            if (pending) NcButton(tr(R.string.chat_dismiss), kind = BtnKind.GHOST, icon = Ic.Close) { mark(Bridge.DISMISSED) }
        }
    }
}

/** URL, or file name + size (+ APK label/package), shown in the mono well. */
private fun targetLine(p: Bridge): Pair<ImageVector, String>? = when (p.action) {
    "open_link", "preview" -> p.target?.let { Ic.Globe to it }
    "install_apk" -> p.target?.let { t ->
        val parts = p.detail?.split(" · ").orEmpty()
        val size = parts.getOrNull(2)?.toLongOrNull()?.let(::bridgeSize)
        Ic.Package to listOfNotNull(parts.getOrNull(0)?.takeIf { it.isNotBlank() }, parts.getOrNull(1)?.takeIf { it.isNotBlank() }, t.substringAfterLast('/'), size).joinToString("\n")
    }
    "share_file" -> p.target?.let { t -> Ic.FileText to (t.substringAfterLast('/') + (p.detail?.toLongOrNull()?.let { " · " + bridgeSize(it) } ?: "")) }
    else -> null
}

private fun copy(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, tr(R.string.chat_copied), Toast.LENGTH_SHORT).show()
}

/** Bridge history of one thread (chat overflow): every card, newest first, with date and time. */
@Composable
internal fun BridgeHistoryScreen(v: AgentChatView, threadId: String) {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(threadId) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val l: (Set<String>) -> Unit = { ids -> if (threadId in ids) main.post { tick++ } }
        v.backend.addListener(l)
        onDispose { v.backend.removeListener(l) }
    }
    val events by produceState<List<TurnItem>?>(null, threadId, tick) { value = withContext(Dispatchers.IO) { runCatching { v.backend.bridgeEvents(threadId) }.getOrDefault(emptyList()) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(C.lowest).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.Back, tr(R.string.onb_back)) { v.pop() }
            Text(tr(R.string.chat_agent_bridge), Modifier.weight(1f), color = C.on, style = T.title)
            events?.let { Label("${it.size} event${if (it.size == 1) "" else "s"}", Modifier.padding(end = S.l), color = C.outline) }
        }
        Hairline()
        val list = events
        when {
            list == null -> LoadingRows()
            list.isEmpty() -> Column(Modifier.fillMaxSize().padding(S.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                NcIcon(Ic.History, null, tint = C.outline, size = 40.dp)
                Text(tr(R.string.chat_no_bridge_events_yet), Modifier.padding(top = S.l), color = C.on, style = T.heading)
                Text(tr(R.string.chat_links_apks_files_and_notifications),
                    Modifier.padding(top = S.s), color = C.onVar, style = T.bodySm)
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = S.s)) {
                items(list, key = { it.id }) { i ->
                    (i.payload as? Bridge)?.let { BridgeCard(v, threadId, i, it, Modifier.fillMaxWidth().padding(horizontal = S.edge, vertical = S.xs), full = true) }
                }
            }
        }
    }
}
