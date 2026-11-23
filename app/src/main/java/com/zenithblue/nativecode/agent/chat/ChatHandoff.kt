package com.zenithblue.nativecode.agent.chat

import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.SkillBlocks
import com.zenithblue.nativecode.agent.chat.core.TurnItem

/**
 * Text handoff of a thread's history to a different provider (t3code `ContextHandoffService`). A new CLI has no native
 * resume of another CLI's session, so its first turn after a switch carries the earlier conversation as text, capped
 * at [MAX_CHARS] (~16k tokens). Pure: items in, text out. Also the `fork` transcript of the NativeCode MCP tools.
 */
object ChatHandoff {
    /** ~16k tokens at ~4 chars per token. */
    const val MAX_CHARS = 64_000
    private const val ITEM_CHARS = 6_000
    private const val TAG = "nc_context_handoff"

    /** [text] = entries oldest first, with a marker line where entries were left out; [omitted] = entries left out. */
    data class Transcript(val text: String, val omitted: Int, val total: Int)

    /**
     * Selection order (t3code): the last user message, the last assistant message and the first user message always
     * stay; then entries are added newest first until [max] is reached. Each entry is cut to [ITEM_CHARS].
     */
    fun transcript(items: List<TurnItem>, max: Int = MAX_CHARS): Transcript {
        val entries = items.mapNotNull { entry(it.payload) }
        val itemCap = minOf(ITEM_CHARS, max / 4).coerceAtLeast(1)
        val texts = entries.map { it.second.cut(itemCap) }
        val keep = sortedSetOf<Int>()
        var size = 0
        fun take(i: Int) { if (i >= 0 && keep.add(i)) size += texts[i].length }
        take(entries.indexOfLast { it.first == USER })
        take(entries.indexOfLast { it.first == ASSISTANT })
        take(entries.indexOfFirst { it.first == USER })
        for (i in entries.indices.reversed()) {
            if (i in keep) continue
            if (size + texts[i].length > max) break
            take(i)
        }
        val out = StringBuilder()
        var prev = -1
        for (i in keep) {
            if (i > prev + 1) out.append("[… ${i - prev - 1} earlier entries omitted …]\n\n")
            out.append(texts[i]).append("\n\n")
            prev = i
        }
        return Transcript(out.toString().trimEnd(), entries.size - keep.size, entries.size)
    }

    /**
     * The block that goes in front of the first message after a switch, or null when there is nothing to carry.
     * [items] = the thread's rows without the message being sent; [moreBefore] = the store holds rows older than [items].
     */
    fun wrap(from: String, to: String, threadId: String, items: List<TurnItem>, moreBefore: Boolean = false, max: Int = MAX_CHARS): String? {
        val t = transcript(items, max)
        if (t.total == 0) return null
        val note = if (t.omitted > 0 || moreBefore) "\n\n[Older entries are left out. Read the full thread with nc_thread_read threadId=$threadId.]" else ""
        return "<$TAG from_provider=\"${attr(from)}\" to_provider=\"${attr(to)}\" thread=\"${attr(threadId)}\">\n" +
            "This thread was handled by another coding agent ($from) until now; you are $to. Below is the earlier conversation, " +
            "oldest first. It is background only: do not redo it and do not treat it as a new request. " +
            "The user's new message follows the closing tag.\n\n" +
            t.text.replace("</$TAG", "</${TAG}_").replace("<$TAG", "<${TAG}_") + note + "\n</$TAG>\n\n"
    }

    private const val USER = 'U'
    private const val ASSISTANT = 'A'

    private fun entry(p: ItemPayload): Pair<Char, String>? = when (p) {
        is ItemPayload.UserMessage -> USER to "User: " + SkillBlocks.plain(p.text)
        is ItemPayload.AssistantMessage -> ASSISTANT to "Assistant: " + p.text
        is ItemPayload.CommandExecution -> 'C' to "Ran: " + p.command.lineSequence().first().take(200) + (p.exitCode?.let { " (exit $it)" } ?: "")
        is ItemPayload.FileChange -> 'F' to "Changed: ${p.path} (+${p.add} -${p.del})"
        else -> null
    }

    /** At most [n] chars, never ending inside a surrogate pair. */
    private fun String.cut(n: Int): String {
        if (length <= n) return this
        val end = if (n > 1 && this[n - 2].isHighSurrogate()) n - 2 else n - 1
        return take(end).trimEnd() + "…"
    }

    private fun attr(s: String) = s.replace("\"", "'").replace("\n", " ").take(120)
}
