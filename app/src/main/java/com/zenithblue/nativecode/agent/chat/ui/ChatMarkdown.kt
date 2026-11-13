package com.zenithblue.nativecode.agent.chat.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Streaming-tolerant markdown (t3code renders incrementally the same way): the text is split into block
 * sources; finished blocks are memoised by (index, source) and only the trailing block re-parses per frame.
 * The trailing block of a streaming reply auto-closes what is still open (fence, `**`, `` ` ``, link) and
 * holds back a table line until its row is complete. Same composable while streaming and after: nothing
 * is swapped at completion.
 */
internal object Md {
    sealed class Node {
        data class Heading(val level: Int, val text: String) : Node()
        data class Para(val text: String) : Node()
        data class Li(val ordered: Boolean, val marker: String, val depth: Int, val text: String, val task: Boolean?)
        data class ListBlock(val items: List<Li>) : Node()
        data class Code(val lang: String, val code: String, val closed: Boolean) : Node()
        data class Quote(val text: String) : Node()
        data class Table(val header: List<String>, val rows: List<List<String>>) : Node()
        data object Rule : Node()
        data object Empty : Node()
    }

    private val FENCE = Regex("^ {0,3}(`{3,}|~{3,})\\s*([^`\\s]*).*$")
    private val HEADING = Regex("^ {0,3}(#{1,6})(?:\\s+(.*))?$")
    private val HEADING_START = Regex("^ {0,3}#{1,6}\\s")
    private val RULE = Regex("^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
    /** A bare marker at line end counts (mid-stream "- "), so a list never splits and re-merges. */
    private val LIST = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])(?:\\s+(.*))?$")
    /** Interrupting a paragraph needs marker + space (a lone "-" may become "---" or "-x"). */
    private val BARE_NUM = Regex("^\\s*\\d{1,9}$")
    private val LIST_START =Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+.*$")
    private val QUOTE = Regex("^ {0,3}>\\s?(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    private fun isTableStart(lines: List<String>, i: Int) =
        '|' in lines[i] && i + 1 < lines.size && '|' in lines[i + 1] && TABLE_SEP.matches(lines[i + 1])

    private fun startsBlock(lines: List<String>, i: Int): Boolean {
        val l = lines[i]
        return FENCE.matches(l) || HEADING_START.containsMatchIn(l) || RULE.matches(l) || QUOTE.matches(l) || LIST_START.matches(l) || isTableStart(lines, i)
    }

    /** Block sources in order. Appending text only ever changes the last one. */
    fun split(text: String): List<String> {
        val lines = text.split('\n')
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank()) { i++; continue }
            val start = i
            val fence = FENCE.matchEntire(l)
            when {
                fence != null -> {
                    val f = fence.groupValues[1]
                    i++
                    while (i < lines.size && !(lines[i].trim().startsWith(f) && lines[i].trim().all { it == f[0] })) i++
                    i = (i + 1).coerceAtMost(lines.size)
                }
                HEADING.matches(l) || RULE.matches(l) -> i++
                isTableStart(lines, i) -> { i += 2; while (i < lines.size && '|' in lines[i] && lines[i].isNotBlank()) i++ }
                QUOTE.matches(l) -> while (i < lines.size && QUOTE.matches(lines[i])) i++
                LIST.matches(l) -> {
                    i++
                    while (i < lines.size && lines[i].isNotBlank() &&
                        (LIST.matches(lines[i]) || BARE_NUM.matches(lines[i]) || (lines[i].first().isWhitespace() && !FENCE.matches(lines[i])))) i++
                }
                else -> { i++; while (i < lines.size && lines[i].isNotBlank() && !startsBlock(lines, i)) i++ }
            }
            out += lines.subList(start, i).joinToString("\n")
        }
        return out
    }

    /** Parse one block source. [open] = trailing block of a stream still being written. */
    fun block(src: String, open: Boolean): Node {
        val lines = src.split('\n')
        val first = lines.first()
        FENCE.matchEntire(first)?.let { m ->
            val f = m.groupValues[1]
            val last = lines.lastIndex
            val closed = last > 0 && lines[last].trim().let { it.startsWith(f) && it.all { c -> c == f[0] } }
            val body = lines.subList(1, if (closed) last else lines.size)
            return Node.Code(m.groupValues[2], body.joinToString("\n"), closed)
        }
        HEADING.matchEntire(first)?.let { m ->
            return Node.Heading(m.groupValues[1].length, m.groupValues[2].trimEnd().trimEnd('#').trimEnd())
        }
        if (RULE.matches(first)) return Node.Rule
        if (lines.size >= 2 && isTableStart(lines, 0)) {
            var rows = lines.drop(2)
            if (open && rows.isNotEmpty() && !rows.last().trimEnd().endsWith("|")) rows = rows.dropLast(1) // partial row waits
            return Node.Table(cells(first), rows.map(::cells))
        }
        if (QUOTE.matches(first)) return Node.Quote(lines.joinToString("\n") { QUOTE.matchEntire(it)?.groupValues?.get(1) ?: it })
        if (LIST.matches(first)) {
            val items = ArrayList<Node.Li>()
            lines.forEach { l ->
                val m = LIST.matchEntire(l)
                if (m == null) {
                    if (open && l === lines.last() && BARE_NUM.matches(l)) return@forEach // "2" of a "2." still streaming
                    items.lastOrNull()?.let { items[items.lastIndex] = it.copy(text = it.text + "\n" + l.trim()) }
                    return@forEach
                }
                val marker = m.groupValues[2]
                var body = m.groupValues[3]
                val task = when {
                    body.startsWith("[ ] ") || body == "[ ]" -> false
                    body.startsWith("[x] ", true) || body.equals("[x]", true) -> true
                    else -> null
                }
                if (task != null) body = body.drop(3).trimStart()
                items += Node.Li(marker[0].isDigit(), marker, m.groupValues[1].replace("\t", "    ").length / 2, body, task)
            }
            return Node.ListBlock(items)
        }
        // A table line at the end of a stream waits for its separator / row end.
        var ls = lines
        if (open) while (ls.isNotEmpty() && ls.last().trimStart().startsWith("|")) ls = ls.dropLast(1)
        return if (ls.isEmpty()) Node.Empty else Node.Para(ls.joinToString("\n"))
    }

    private fun cells(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.drop(1)
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.dropLast(1)
        return r.split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
    }

    // ---------------- inline ----------------

    val CODE = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.9.em, background = Color(0xFF2A2A2A), color = Color(0xFFE5E2E1))
    val BOLD = SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(0xFFF2F0EF))
    val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
    val STRIKE = SpanStyle(textDecoration = TextDecoration.LineThrough)
    private val LINK = TextLinkStyles(SpanStyle(color = Color(0xFF60F99E), textDecoration = TextDecoration.Underline))
    private const val PUNCT = "\\`*_{}[]()#+-.!|~<>"

    /** Inline markdown to styled text. [open] = auto-close unterminated markers (streaming tail). */
    fun inline(s: String, open: Boolean = false): AnnotatedString = buildAnnotatedString { emit(this, s, open) }

    private fun emit(b: AnnotatedString.Builder, s: String, open: Boolean) {
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { b.append(sb.toString()); sb.setLength(0) } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] in PUNCT) { sb.append(s[i + 1]); i += 2; continue }
            if (c == '`') {
                var n = 0
                while (i + n < s.length && s[i + n] == '`') n++
                val end = s.indexOf("`".repeat(n), i + n)
                when {
                    end >= 0 -> { flush(); b.withStyle(CODE) { append(s.substring(i + n, end)) }; i = end + n }
                    open -> { flush(); b.withStyle(CODE) { append(s.substring(i + n)) }; i = s.length }
                    else -> { sb.append(s, i, i + n); i += n }
                }
                continue
            }
            val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
            val delim = when {
                two == "**" || two == "__" -> two to BOLD
                two == "~~" -> two to STRIKE
                c == '*' || c == '_' -> c.toString() to ITALIC
                else -> null
            }
            if (delim != null) {
                val (mk, style) = delim
                val after = s.getOrNull(i + mk.length)
                val before = s.getOrNull(i - 1)
                val canOpen = after != null && !after.isWhitespace() && (mk.length == 2 || before == null || !before.isLetterOrDigit())
                val close = if (canOpen) closer(s, i + mk.length, mk) else -1
                when {
                    close >= 0 -> { flush(); b.withStyle(style) { emit(this, s.substring(i + mk.length, close), false) }; i = close + mk.length }
                    canOpen && open -> { flush(); b.withStyle(style) { emit(this, s.substring(i + mk.length), true) }; i = s.length }
                    else -> { sb.append(mk); i += mk.length }
                }
                continue
            }
            if (c == '[' || (c == '!' && s.getOrNull(i + 1) == '[')) {
                val lb = if (c == '!') i + 1 else i
                val rb = s.indexOf(']', lb + 1)
                val hasParen = rb >= 0 && s.getOrNull(rb + 1) == '('
                val rp = if (hasParen) s.indexOf(')', rb + 2) else -1
                when {
                    rp >= 0 -> {
                        flush()
                        b.withLink(LinkAnnotation.Url(s.substring(rb + 2, rp).trim(), LINK)) { emit(this, s.substring(lb + 1, rb), false) }
                        i = rp + 1
                    }
                    open && (rb < 0 || hasParen || rb == s.lastIndex) -> {
                        // Partial link mid-stream: its text only, plain.
                        flush(); emit(b, s.substring(lb + 1, if (rb >= 0) rb else s.length), true); i = s.length
                    }
                    else -> { sb.append(c); i++ }
                }
                continue
            }
            if ((s.startsWith("https://", i) || s.startsWith("http://", i)) && (i == 0 || !s[i - 1].isLetterOrDigit())) {
                var e = i
                while (e < s.length && !s[e].isWhitespace() && s[e] != ')' && s[e] != '>' && s[e] != '<') e++
                while (e > i && s[e - 1] in ".,;:!?\"'") e--
                flush()
                val url = s.substring(i, e)
                b.withLink(LinkAnnotation.Url(url, LINK)) { append(url) }
                i = e
                continue
            }
            sb.append(c); i++
        }
        flush()
    }

    /** Closing [mk] after [from]: not preceded by whitespace; a single `*`/`_` must not be half of a pair. */
    private fun closer(s: String, from: Int, mk: String): Int {
        var j = s.indexOf(mk, from)
        while (j >= 0) {
            val prevOk = j > from && !s[j - 1].isWhitespace()
            val single = mk.length == 1 && (s.getOrNull(j + 1) == mk[0] || s.getOrNull(j - 1) == mk[0])
            val nextOk = mk != "_" || s.getOrNull(j + 1)?.isLetterOrDigit() != true
            if (prevOk && !single && nextOk) return j
            j = s.indexOf(mk, j + if (single) 2 else 1)
        }
        return -1
    }
}

/** At most one value per [ms] (streaming re-parse ~30 fps); the final value always lands. */
@Composable
internal fun throttled(value: String, ms: Long, on: Boolean): String {
    if (!on) return value
    var shown by remember { mutableStateOf(value) }
    var last by remember { mutableLongStateOf(0L) }
    LaunchedEffect(value) {
        val wait = ms - (SystemClock.uptimeMillis() - last)
        if (wait > 0) delay(wait)
        shown = value
        last = SystemClock.uptimeMillis()
    }
    return shown
}

/** Assistant markdown: same blocks while streaming and after; [streaming] adds the caret + auto-closing tail. */
@Composable
internal fun Markdown(text: String, streaming: Boolean, modifier: Modifier = Modifier) {
    val src = throttled(text, 40, streaming)
    val open = streaming && !src.endsWith("\n")
    val blocks = remember(src) { Md.split(src) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(S.s)) {
        blocks.forEachIndexed { i, b ->
            val last = i == blocks.lastIndex
            val tailOpen = last && open
            key(i) {
                val node = remember(b, tailOpen) { Md.block(b, tailOpen) }
                MdBlock(node, tailOpen, caret = last && streaming)
            }
        }
        if (blocks.isEmpty() && streaming) BlinkCaret(Modifier.size(8.dp, 18.dp))
    }
}

private const val CARET = "caret"
private val caretContent = mapOf(
    CARET to InlineTextContent(Placeholder(0.55.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)) {
        BlinkCaret(Modifier.padding(start = 2.dp).fillMaxSize())
    },
)

@Composable
private fun MdText(src: String, open: Boolean, caret: Boolean, style: TextStyle, color: Color = C.on, modifier: Modifier = Modifier) {
    val ann = remember(src, open, caret) {
        val a = Md.inline(src, open)
        if (caret) buildAnnotatedString { append(a); appendInlineContent(CARET, " ") } else a
    }
    Text(ann, modifier, color = color, style = style, inlineContent = if (caret) caretContent else emptyMap())
}

@Composable
private fun MdBlock(n: Md.Node, open: Boolean, caret: Boolean) {
    when (n) {
        is Md.Node.Heading -> MdText(
            n.text, open, caret,
            when (n.level) {
                1 -> TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
                2 -> TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
                3 -> TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
                else -> T.heading
            },
            modifier = Modifier.padding(top = S.xs),
        )
        is Md.Node.Para -> MdText(n.text, open, caret, T.body)
        is Md.Node.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(C.border))
            MdText(n.text, open, caret, T.body, C.onVar, Modifier.padding(start = S.m))
        }
        is Md.Node.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(S.xs)) {
            n.items.forEachIndexed { i, li ->
                Row(Modifier.padding(start = (li.depth * 16).dp)) {
                    Box(Modifier.widthIn(min = 20.dp).padding(end = S.s), contentAlignment = Alignment.TopStart) {
                        when {
                            li.task != null -> Box(Modifier.padding(top = 4.dp).size(14.dp).border(1.5.dp, if (li.task) C.primary else C.outline)
                                .background(if (li.task) C.primary else Color.Transparent), contentAlignment = Alignment.Center) {
                                if (li.task) NcIcon(Ic.Check, null, tint = C.onPrimary, size = 12.dp)
                            }
                            li.ordered -> Text(li.marker, color = C.onVar, style = T.body.copy(fontFamily = Mono, fontSize = 13.sp))
                            else -> Box(Modifier.padding(top = 8.dp, start = 4.dp).size(5.dp).background(if (li.depth == 0) C.primary else C.outline))
                        }
                    }
                    MdText(li.text, open, caret && i == n.items.lastIndex, T.body)
                }
            }
        }
        is Md.Node.Code -> Column {
            CodeBlock(n.code, n.lang, copyable = n.closed || !open)
            if (caret) BlinkCaret(Modifier.padding(top = S.xs).size(8.dp, 16.dp))
        }
        is Md.Node.Table -> Column {
            MdTable(n)
            if (caret) BlinkCaret(Modifier.padding(top = S.xs).size(8.dp, 16.dp))
        }
        Md.Node.Rule -> Hairline(Modifier.padding(vertical = S.xs))
        Md.Node.Empty -> if (caret) BlinkCaret(Modifier.size(8.dp, 18.dp))
    }
}

/** Column-major grid so columns align; single-line cells, horizontal scroll. */
@Composable
private fun MdTable(n: Md.Node.Table) {
    val cols = maxOf(n.header.size, n.rows.maxOfOrNull { it.size } ?: 0)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).border(1.dp, C.border)) {
        for (c in 0 until cols) {
            Column(Modifier.width(IntrinsicSize.Max)) {
                val ann = remember(n.header, c) { Md.inline(n.header.getOrElse(c) { "" }) }
                Text(ann, Modifier.fillMaxWidth().background(C.container).padding(horizontal = S.m, vertical = S.s),
                    color = C.on, style = T.bodySm.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, softWrap = false)
                n.rows.forEachIndexed { r, row ->
                    val cell = remember(row, c) { Md.inline(row.getOrElse(c) { "" }) }
                    Box(Modifier.fillMaxWidth().background(if (r % 2 == 1) C.low else Color.Transparent)) {
                        Hairline()
                        Text(cell, Modifier.padding(horizontal = S.m, vertical = S.s), color = C.on, style = T.bodySm, maxLines = 1, softWrap = false)
                    }
                }
            }
            if (c < cols - 1) Box(Modifier.width(1.dp).height(IntrinsicSize.Min).background(C.border))
        }
    }
}

/** Monospace, horizontal scroll, Copy (icon + label). */
@Composable
internal fun CodeBlock(code: String, lang: String, modifier: Modifier = Modifier, copyable: Boolean = true) {
    val ctx = LocalContext.current
    Column(modifier.fillMaxWidth().card(C.lowest)) {
        Row(Modifier.fillMaxWidth().background(C.container).padding(start = S.m), verticalAlignment = Alignment.CenterVertically) {
            Overline(lang.ifBlank { "code" }, Modifier.weight(1f), color = C.outline)
            if (copyable) IconBtn(Ic.Copy, "Copy code", tint = C.onVar, size = 16.dp) { copyText(ctx, code) }
            else Box(Modifier.size(48.dp, 36.dp))
        }
        Text(
            code, Modifier.horizontalScroll(rememberScrollState()).padding(S.m),
            color = C.on, style = T.code, softWrap = false,
        )
    }
}
