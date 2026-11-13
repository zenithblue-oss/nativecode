package com.zenithblue.nativecode.agent.chat.ui

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.ChatThread
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

/** Multiline composer: IME Enter = newline. Slash popup, [+] Photo/File, model + mode chips, send/stop. */
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
    onSend: (steer: Boolean) -> Unit,
    onStop: () -> Unit,
    onSlash: (String) -> Unit,
) {
    var plus by remember { mutableStateOf(false) }
    var sendMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(C.lowest)) {
        val slash = s.text.takeIf { it.startsWith("/") && it.none(Char::isWhitespace) }
        if (slash != null) {
            val all = listOf(SlashCommand("model", "Model, effort, mode"), SlashCommand("clear", "New thread, same tool")) + commands
            val hits = all.filter { it.name.startsWith(slash.drop(1), ignoreCase = true) }.distinctBy { it.name }
            if (hits.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 200.dp).border(1.dp, C.border)) {
                items(hits, key = { it.name }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onSlash(c.name) }.heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Label("/" + c.name, color = C.primary, bold = true)
                        Label(c.description.orEmpty(), Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
        if (s.chips.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.chips.forEach { c ->
                Row(Modifier.border(1.dp, if (c.error != null) C.error else C.border).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        c.att == null && c.error == null -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = C.primary)
                        else -> Label(if (c.image) "📷" else "📄")
                    }
                    Label(" " + (c.error ?: (c.name + (c.att?.let { " · " + size(it.sizeBytes) } ?: " · Copying…"))), Modifier.padding(end = 6.dp),
                        color = if (c.error != null) C.error else C.on)
                    if (c.error != null && c.uri != null) Label("Retry ", Modifier.clickable { s.chips.remove(c); s.add(v, t, c.uri, c.image) }, color = C.primary)
                    Label("✕", Modifier.clickable { s.chips.remove(c) }.padding(4.dp))
                }
            }
        }
        OutlinedTextField(
            s.text, { s.text = it.take(120_000) },
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            placeholder = { Text(placeholder, color = C.outline, fontSize = 14.sp) },
            minLines = 1, maxLines = 6, shape = Square,
            textStyle = androidx.compose.ui.text.TextStyle(color = C.on, fontSize = 15.sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.primary, unfocusedBorderColor = C.border, cursorColor = C.primary),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                Chip(" + ") { plus = true }
                DropdownMenu(plus, { plus = false }) {
                    MItem("📷 Photo") { plus = false; v.host.pickImage { s.add(v, t, it, true) } }
                    MItem("📄 File") { plus = false; v.host.pickFile { s.add(v, t, it, false) } }
                }
            }
            Box(Modifier.weight(1f, fill = false)) { Chip((t.model?.substringAfterLast('/') ?: "model") + (t.effort?.let { " · $it" } ?: "") + " ▾") { v.push(Route.Settings(t.id)) } }
            Chip("⛨ " + t.runtimeMode.label) { v.push(Route.Settings(t.id)) }
            Spacer(Modifier.weight(1f))
            val canSend = (s.text.isNotBlank() || s.ready.isNotEmpty()) && !s.busy
            Box {
                Box(
                    Modifier.size(44.dp).background(if (canSend) C.primary else if (running) C.errorCon else C.high)
                        .combinedClickable(
                            onLongClick = { if (running && canSend && canSteer) sendMenu = true },
                            onClick = { if (canSend) onSend(running && canSteer) else if (running) onStop() },
                        ),
                    contentAlignment = Alignment.Center,
                ) { Label(if (!canSend && running) "■" else "➤", color = if (canSend) C.onPrimary else C.on, size = 18, bold = true) }
                DropdownMenu(sendMenu, { sendMenu = false }) {
                    MItem("Steer now") { sendMenu = false; onSend(true) }
                    MItem("Queue") { sendMenu = false; onSend(false) }
                }
            }
        }
    }
}

private fun size(b: Long) = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576f)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}
