package com.zenithblue.nativecode.agent.chat.ui

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.skills.SkillMarkdown

/** Assistant markdown: plain text while streaming; finished text = SkillMarkdown HTML spans; fences = [CodeBlock]. */
@Composable
internal fun Markdown(text: String, streaming: Boolean, modifier: Modifier = Modifier) {
    if (streaming) {
        Text(text, modifier, color = C.on, fontSize = 15.sp, lineHeight = 22.sp)
        return
    }
    val blocks = remember(text) { ChatLogic.splitFences(text) }
    Column(modifier) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Code -> CodeBlock(b.code, b.lang, Modifier.padding(vertical = 6.dp))
                is MdBlock.Text -> HtmlText(b.md)
            }
        }
    }
}

@Composable
private fun HtmlText(md: String) {
    val spanned = remember(md) {
        val body = SkillMarkdown.html(md).substringAfter("<body>").substringBefore("</body>")
            .replace("<code>", "<tt>").replace("</code>", "</tt>")
        HtmlCompat.fromHtml(body, HtmlCompat.FROM_HTML_MODE_COMPACT).trimEnd()
    }
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(NC.ON_SURFACE)
                setLinkTextColor(NC.PRIMARY)
                textSize = 15f
                setLineSpacing(0f, 1.25f)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { it.text = spanned },
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/** Monospace, horizontal scroll, Copy. */
@Composable
internal fun CodeBlock(code: String, lang: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(modifier.fillMaxWidth().border(1.dp, C.border).background(C.lowest)) {
        Row(Modifier.fillMaxWidth().background(C.container).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(lang.ifBlank { "code" }, Modifier.weight(1f))
            Label("⧉ Copy", Modifier.clickable { copyText(ctx, code) }.padding(6.dp), color = C.primary, bold = true)
        }
        Text(
            code, Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
            color = C.on, fontFamily = Mono, fontSize = 13.sp, softWrap = false,
        )
    }
}
