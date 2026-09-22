package com.zenithblue.nativecode.skills

import android.text.TextUtils

/** Markdown → dark HTML for the skill view. Copy still uses the raw markdown. */
internal object SkillMarkdown {

    fun html(md: String): String {
        val body = toHtml(md)
        return """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
              body { background:#131313; color:#e5e2e1; font-family:-apple-system,sans-serif;
                     padding:12px; line-height:1.6; margin:0; word-wrap:break-word; }
              h1,h2,h3,h4 { color:#60f99e; font-family:monospace,sans-serif;
                     border-bottom:1px solid #3c4a3f; padding-bottom:6px; margin:20px 0 10px; }
              h1{font-size:1.4em} h2{font-size:1.2em} h3{font-size:1.05em}
              a{color:#60f99e; text-decoration:none}
              code{background:#201f1f; color:#60f99e; font-family:monospace; padding:2px 6px; font-size:.9em}
              pre{background:#0e0e0e; border:1px solid #3c4a3f; padding:12px; overflow-x:auto}
              pre code{background:transparent; padding:0; color:#e5e2e1}
              blockquote{border-left:3px solid #60f99e; margin:12px 0; padding:6px 14px; color:#bbcbbc; background:#1c1b1b}
              ul,ol{padding-left:22px; margin:10px 0} li{margin-bottom:4px} p{margin:8px 0}
              hr{border:none; border-top:1px solid #3c4a3f; margin:16px 0}
              table{width:100%; border-collapse:collapse; margin:12px 0; display:block; overflow-x:auto}
              th,td{border:1px solid #3c4a3f; padding:6px 10px; text-align:left}
              th{background:#201f1f; color:#60f99e; font-family:monospace}
            </style></head><body>$body</body></html>
        """.trimIndent()
    }

    private fun toHtml(md: String): String {
        val out = StringBuilder()
        var inCode = false
        val code = StringBuilder()
        var inList = false
        var listType = ""
        var inQuote = false
        var inTable = false
        val table = mutableListOf<String>()

        fun closeList() { if (inList) { out.append("</$listType>\n"); inList = false; listType = "" } }
        fun closeQuote() { if (inQuote) { out.append("</blockquote>\n"); inQuote = false } }
        fun flushTable() {
            if (table.size >= 2) {
                out.append("<table>\n")
                var header = true
                for (row in table) {
                    if (row.contains("---")) { header = false; continue }
                    val cells = row.split('|').map { it.trim() }.drop(1).dropLast(1)
                    val tag = if (header) "th" else "td"
                    out.append("<tr>")
                    cells.forEach { out.append("<$tag>").append(inline(it)).append("</$tag>") }
                    out.append("</tr>\n")
                }
                out.append("</table>\n")
            }
            table.clear(); inTable = false
        }

        for (line in md.lines()) {
            val t = line.trim()
            if (t.startsWith("```")) {
                if (inCode) {
                    out.append("<pre><code>").append(TextUtils.htmlEncode(code.toString())).append("</code></pre>\n")
                    inCode = false; code.clear()
                } else { closeList(); closeQuote(); if (inTable) flushTable(); inCode = true }
                continue
            }
            if (inCode) { code.append(line).append('\n'); continue }
            if (t.startsWith("|") && t.endsWith("|")) { closeList(); closeQuote(); inTable = true; table.add(t); continue }
            else if (inTable) flushTable()
            if (t.isEmpty()) { closeList(); closeQuote(); continue }
            if (t == "---" || t == "***") { closeList(); closeQuote(); out.append("<hr>\n"); continue }
            if (t.startsWith("#")) {
                closeList(); closeQuote()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 4)
                out.append("<h$level>").append(inline(t.drop(level).trim())).append("</h$level>\n")
                continue
            }
            if (t.startsWith(">")) {
                closeList()
                if (!inQuote) { out.append("<blockquote>"); inQuote = true } else out.append("<br>")
                out.append(inline(t.removePrefix(">").trim())); continue
            } else closeQuote()
            val ul = Regex("""^[-*+]\s+(.*)$""").matchEntire(t)
            if (ul != null) {
                if (!inList || listType != "ul") { closeList(); out.append("<ul>\n"); inList = true; listType = "ul" }
                out.append("<li>").append(inline(ul.groupValues[1])).append("</li>\n"); continue
            }
            val ol = Regex("""^\d+\.\s+(.*)$""").matchEntire(t)
            if (ol != null) {
                if (!inList || listType != "ol") { closeList(); out.append("<ol>\n"); inList = true; listType = "ol" }
                out.append("<li>").append(inline(ol.groupValues[1])).append("</li>\n"); continue
            }
            closeList()
            out.append("<p>").append(inline(t)).append("</p>\n")
        }
        if (inCode) out.append("<pre><code>").append(TextUtils.htmlEncode(code.toString())).append("</code></pre>\n")
        closeList(); closeQuote(); if (inTable) flushTable()
        return out.toString()
    }

    private fun inline(raw: String): String {
        var s = TextUtils.htmlEncode(raw)
        s = s.replace(Regex("""\[([^\]]+)\]\(([^)]+)\)""")) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        s = s.replace(Regex("""\*\*([^*]+)\*\*""")) { "<strong>${it.groupValues[1]}</strong>" }
        s = s.replace(Regex("""\*([^*]+)\*""")) { "<em>${it.groupValues[1]}</em>" }
        s = s.replace(Regex("""`([^`]+)`""")) { "<code>${it.groupValues[1]}</code>" }
        return s
    }
}
