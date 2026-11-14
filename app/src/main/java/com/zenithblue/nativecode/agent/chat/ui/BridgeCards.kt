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

/** Look of one bridge action: icon, overline label, accent. */
private data class BridgeLook(val icon: ImageVector, val label: String, val accent: Color)

private fun look(action: String) = when (action) {
    "open_link" -> BridgeLook(Ic.ExternalLink, "Open link", C.primary)
    "install_apk" -> BridgeLook(Ic.Package, "Install APK", C.primary)
    "share_file" -> BridgeLook(Ic.Share, "File for you", C.primary)
    "status" -> BridgeLook(Ic.Activity, "Task status", C.tertiary)
    "toast" -> BridgeLook(Ic.Message, "Message", C.onVar)
    else -> BridgeLook(Ic.Bell, "Notification", C.tertiary)
}

private val TIME = SimpleDateFormat("HH:mm", Locale.getDefault())
private val FULL = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault())

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
        p.action in setOf("notify", "toast") -> "Delivered" to C.outline
        else -> "Done" to C.primary
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
                Overline(lk.label + " · from agent", color = C.outline)
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
        if (p.action == "status") p.detail?.toIntOrNull()?.takeIf { it in 0..100 }?.let { pct ->
            LinearProgressIndicator(
                progress = { pct / 100f }, Modifier.fillMaxWidth().padding(top = S.s).height(4.dp),
                color = if (pct >= 100) C.primary else C.tertiary, trackColor = C.border, gapSize = 0.dp, drawStopIndicator = {},
            )
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = S.s), horizontalArrangement = Arrangement.spacedBy(S.s), verticalArrangement = Arrangement.spacedBy(S.s)) {
            when (p.action) {
                "open_link" -> {
                    if (openableUrl(p.target)) NcButton("Open", kind = if (pending) BtnKind.PRIMARY else BtnKind.OUTLINE, icon = Ic.ExternalLink) {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.target!!.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onSuccess { mark(Bridge.DONE) }.onFailure { Toast.makeText(ctx, "No app can open this link", Toast.LENGTH_SHORT).show() }
                    }
                    NcButton("Copy", icon = Ic.Copy) { copy(ctx, "URL", p.target.orEmpty()) }
                }
                "install_apk" -> {
                    NcButton("Install", kind = if (pending) BtnKind.PRIMARY else BtnKind.OUTLINE, icon = Ic.Download) { p.target?.let { v.host.saveApk(it); mark(Bridge.DONE) } }
                    NcButton("Share", icon = Ic.Share) { p.target?.let { v.host.shareFile(it); mark(Bridge.DONE) } }
                }
                "share_file" -> {
                    NcButton("Save / Share", kind = if (pending) BtnKind.PRIMARY else BtnKind.OUTLINE, icon = Ic.Share) { p.target?.let { v.host.shareFile(it); mark(Bridge.DONE) } }
                    NcButton("Copy path", icon = Ic.Copy) { copy(ctx, "Path", p.target.orEmpty()) }
                }
                else -> NcButton("Copy", icon = Ic.Copy) { copy(ctx, p.title, listOf(p.title, p.body).filter { it.isNotBlank() }.joinToString("\n")) }
            }
            if (pending) NcButton("Dismiss", kind = BtnKind.GHOST, icon = Ic.Close) { mark(Bridge.DISMISSED) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Text((if (full) FULL else TIME).format(Date(at)), color = C.outline, style = T.meta, maxLines = 1)
            }
        }
    }
}

/** URL, or file name + size (+ APK label/package), shown in the mono well. */
private fun targetLine(p: Bridge): Pair<ImageVector, String>? = when (p.action) {
    "open_link" -> p.target?.let { Ic.Globe to it }
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
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
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
            IconBtn(Ic.Back, "Back") { v.pop() }
            Text("Agent bridge", Modifier.weight(1f), color = C.on, style = T.title)
            events?.let { Label("${it.size} event${if (it.size == 1) "" else "s"}", Modifier.padding(end = S.l), color = C.outline) }
        }
        Hairline()
        val list = events
        when {
            list == null -> LoadingRows()
            list.isEmpty() -> Column(Modifier.fillMaxSize().padding(S.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                NcIcon(Ic.History, null, tint = C.outline, size = 40.dp)
                Text("No bridge events yet", Modifier.padding(top = S.l), color = C.on, style = T.heading)
                Text("Links, APKs, files and notifications the agent sends with nativecode-agent show up here.",
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
