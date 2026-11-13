package com.zenithblue.nativecode.agent.chat.ui

import com.zenithblue.nativecode.agent.chat.core.ModelInfo
import com.zenithblue.nativecode.agent.chat.core.SlashCommand
import com.zenithblue.nativecode.agent.chat.core.SlashCommand.Kind

/**
 * t3code's slash menu, phone-adapted and pure (`useComposerMenuItems`, `composerSlashCommandSearch`,
 * `composerTrigger`): client built-ins + the provider's commands + skills, filtered and ranked by the typed query.
 */
object SlashMenu {
    /** What this thread can offer now. [sideBlocked] = why `/side` cannot start (shown as its description). */
    data class Ctx(val side: Boolean, val sideBlocked: String? = null, val fresh: Boolean)

    /** The `/word` being typed: [query] without the slash; [atStart] = first line of the prompt. */
    data class Trigger(val query: String, val atStart: Boolean)

    sealed interface Action {
        data class Model(val arg: String?) : Action
        data class Side(val prompt: String) : Action
        data object Clear : Action
        data object UsageLimits : Action
    }

    val USAGE_LIMITS = SlashCommand("usage-limits", "Show this provider's usage limits", kind = Kind.BUILTIN)
    private val SIDE_ONLY_AT_START = setOf("side", "btw", "clear", USAGE_LIMITS.name)

    fun builtins(c: Ctx): List<SlashCommand> = buildList {
        add(SlashCommand("model", "Switch response model for this thread", "<model>", Kind.BUILTIN))
        if (c.side) {
            add(SlashCommand("side", c.sideBlocked ?: "Ask a side question in a new side chat", "<question>", Kind.BUILTIN))
            add(SlashCommand("btw", c.sideBlocked ?: "Same as /side", "<question>", Kind.BUILTIN))
        }
        if (c.fresh) add(SlashCommand("clear", "Start a fresh session; earlier messages stay visible", kind = Kind.BUILTIN))
        add(USAGE_LIMITS)
    }

    /** A `/` opening the last line, with no whitespace typed yet (t3 `detectComposerTrigger`, cursor at the end). */
    fun trigger(text: String): Trigger? {
        val line = text.substringAfterLast('\n')
        if (!line.startsWith("/") || line.any(Char::isWhitespace)) return null
        return Trigger(line.drop(1), atStart = '\n' !in text)
    }

    /**
     * Popup entries: built-ins, then provider commands, then skills (own section), ranked when [Trigger.query] is
     * non-empty. Provider entries named like a skill or a built-in are dropped; the provider `/clear` hides behind
     * ours; `/compact` only when the command would be the whole message ([bare] = no attachments).
     */
    fun items(provider: List<SlashCommand>, c: Ctx, t: Trigger, bare: Boolean): List<SlashCommand> {
        val builtins = builtins(c)
        val skills = provider.filter { it.kind == Kind.SKILL }.distinctBy { it.name }
        val taken = (skills + builtins).map { it.name }.toSet()
        val commands = provider.filter { it.kind != Kind.SKILL && it.name !in taken && (it.name != "compact" || (t.atStart && bare)) }
            .distinctBy { it.name }.map { it.copy(kind = Kind.PROVIDER) }
        var all = builtins + commands + skills
        if (!t.atStart) all = all.filter { it.kind == Kind.SKILL || (it.kind == Kind.BUILTIN && it.name !in SIDE_ONLY_AT_START) }
        return search(all, t.query)
    }

    /** t3 `searchSlashCommandItems`: name exact 0 / prefix 2 / word 4 / substring 6 / subsequence 100+, description 20-26. */
    fun search(items: List<SlashCommand>, query: String): List<SlashCommand> {
        val q = query.trim().lowercase().trimStart('/')
        if (q.isEmpty()) return items
        return items.mapNotNull { i -> score(i, q)?.let { i to it } }
            .sortedWith(compareBy({ it.second }, { it.first.kind.ordinal }, { it.first.name }))
            .map { it.first }
    }

    private fun score(i: SlashCommand, q: String): Int? {
        if (i.kind == Kind.SKILL) {
            if (q == "skill") return 0
            val sq = q.removePrefix("skill:")
            val s = if (sq.isEmpty()) 0 else best(i, sq)
            return s ?: if ("skill".startsWith(q)) Int.MAX_VALUE else null
        }
        return best(i, q)
    }

    private fun best(i: SlashCommand, q: String): Int? = listOfNotNull(
        match(i.name.lowercase(), q, 0, "-_/:") ?: subsequence(i.name.lowercase(), q)?.let { 100 + it },
        i.description?.lowercase()?.let { match(it, q, 20, " ") },
    ).minOrNull()

    private fun match(v: String, q: String, base: Int, markers: String): Int? = when {
        v == q -> base
        v.startsWith(q) -> base + 2
        v.indices.any { it > 0 && v[it - 1] in markers && v.startsWith(q, it) } -> base + 4
        v.contains(q) -> base + 6
        else -> null
    }

    /** Gap count of an in-order match, or null. */
    private fun subsequence(v: String, q: String): Int? {
        var qi = 0; var first = -1; var prev = -1; var gaps = 0
        for (vi in v.indices) {
            if (qi == q.length) break
            if (v[vi] != q[qi]) continue
            if (first < 0) first = vi
            if (prev >= 0) gaps += vi - prev - 1
            prev = vi; qi++
        }
        return if (qi == q.length) first + gaps else null
    }

    /** What selecting [c] writes over the trigger line: `/name ` (command) or `$name ` (skill, t3 inserts a mention). */
    fun insert(text: String, c: SlashCommand): String {
        val head = text.substringBeforeLast('\n', "").let { if ('\n' in text) "$it\n" else "" }
        return head + (if (c.kind == Kind.SKILL) "$" else "/") + c.name + " "
    }

    /** Ghost hint while the composer is exactly `/name ` (no arguments typed yet). */
    fun ghostHint(text: String, all: List<SlashCommand>): String? {
        val m = Regex("""^/(\S+) +$""").find(text.substringAfterLast('\n')) ?: return null
        return all.firstOrNull { it.name == m.groupValues[1] && it.kind != Kind.SKILL }?.argumentHint
    }

    /** Built-in typed as the whole message (t3 `parseSideChatCommand`, `isClearComposerCommand`, `isUsageLimitsCommand`). */
    fun parse(text: String): Action? {
        val s = text.trim()
        Regex("""^/(?:side|btw)(?:\s+([\s\S]*))?$""", RegexOption.IGNORE_CASE).find(s)?.let { return Action.Side(it.groupValues[1].trim()) }
        if (Regex("""^/clear$""", RegexOption.IGNORE_CASE).matches(s)) return Action.Clear
        if (s.equals("/usage-limits", ignoreCase = true)) return Action.UsageLimits
        Regex("""^/model(?:\s+(.*))?$""", RegexOption.IGNORE_CASE).find(s)?.let { return Action.Model(it.groupValues[1].trim().ifEmpty { null }) }
        return null
    }

    /** `/model <arg>`: exact id / name / id tail, else the one model whose id or name contains it; null = open the picker. */
    fun matchModel(models: List<ModelInfo>, arg: String): ModelInfo? {
        val a = arg.trim().lowercase()
        models.firstOrNull { it.id.lowercase() == a || it.name.lowercase() == a || it.id.substringAfterLast('/').lowercase() == a }?.let { return it }
        return models.filter { a in it.id.lowercase() || a in it.name.lowercase() }.singleOrNull()
    }
}
