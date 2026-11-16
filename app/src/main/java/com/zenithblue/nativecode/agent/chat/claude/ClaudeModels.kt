package com.zenithblue.nativecode.agent.chat.claude

import com.zenithblue.nativecode.agent.chat.core.ModelInfo

/**
 * The claude model list, like t3code's: versioned display names with the alias resolved ("Opus 5.5") and the full model id
 * as the subtitle, plus the previous generations the CLI still accepts through `--model` / `set_model`.
 *
 * Source of truth, in order: the CLI's `initialize.models[]` (`displayName`, `resolvedModel`, `value`); then [STATIC], a
 * maintained list (claude 2.1.323) merged underneath. A signed-out or older CLI answers with bare aliases ("Opus", no
 * numbers, no previous models); the merge gives those the same names and the full list.
 */
object ClaudeModels {
    private val ALL = listOf("low", "medium", "high", "xhigh", "max")
    private val NO_XHIGH = listOf("low", "medium", "high", "max")

    /** [id], display name, full id (alias rows only), effort levels. Aliases first, then newest to oldest. */
    private data class S(val id: String, val name: String, val full: String?, val efforts: List<String>)

    private val STATIC = listOf(
        S("opus", "Opus 5.5", "claude-opus-5-5", ALL),
        S("sonnet", "Sonnet 5.5", "claude-sonnet-5-5", ALL),
        S("fable", "Fable 5.2", "claude-fable-5-2", ALL),
        S("haiku", "Haiku 5.5", "claude-haiku-5-5", ALL),
        S("claude-fable-5-1", "Fable 5.1", null, ALL),
        S("claude-opus-5", "Opus 5", null, ALL),
        S("claude-sonnet-5", "Sonnet 5", null, ALL),
        S("claude-fable-5", "Fable 5", null, ALL),
        S("claude-opus-4-8", "Opus 4.8", null, ALL),
        S("claude-opus-4-7", "Opus 4.7", null, ALL),
        S("claude-opus-4-6", "Opus 4.6", null, NO_XHIGH),
        S("claude-sonnet-4-6", "Sonnet 4.6", null, NO_XHIGH),
        S("claude-haiku-4-5-20251001", "Haiku 4.5", null, emptyList()),
    )

    private val DEFAULT = ModelInfo("default", "Default (recommended)", "anthropic", variants = ALL)

    private val FULL_ID = Regex("""^claude-([a-z]+)-(\d+)(?:-(\d{1,2}))?(?:-\d{8})?$""")

    /** "Opus 5.5" from `claude-opus-5-5`, "Haiku 4.5" from `claude-haiku-4-5-20251001`; null for anything else. */
    fun nameOf(fullId: String): String? = FULL_ID.matchEntire(fullId)?.let { m ->
        val (family, major, minor) = m.destructured
        family.replaceFirstChar { it.uppercase() } + " " + major + (if (minor.isNotEmpty()) ".$minor" else "")
    }

    /**
     * [live] (already parsed, names fixed by [ClaudeWire.parseModels]) plus every [STATIC] model it lacks. Alias rows with a bare
     * name or no full id take both from the static list; a live row always keeps its own effort levels and a real versioned name.
     */
    fun merge(live: List<ModelInfo>): List<ModelInfo> {
        val byId = STATIC.associateBy { it.id }
        val fixed = live.map { m ->
            val s = byId[m.id]
            val named = m.id == "default" || m.name.any { it.isDigit() }
            m.copy(
                name = if (named) m.name else m.detail?.let(::nameOf) ?: s?.name ?: nameOf(m.id) ?: m.name,
                detail = m.detail ?: s?.full,
            )
        }
        val have = fixed.map { it.id }.toSet()
        val head = if ("default" in have) fixed else listOf(DEFAULT) + fixed
        return head + STATIC.filter { it.id !in have }.map { ModelInfo(it.id, it.name, "anthropic", variants = it.efforts, detail = it.full) }
    }
}
