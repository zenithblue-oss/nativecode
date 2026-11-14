package com.zenithblue.nativecode.agent.chat.core

/**
 * NativeCode skills attached in the composer: each one rides at the head of the prompt as a context block
 * `<nc_skill name="…">md</nc_skill>`, so every CLI gets the full md and the stored user message keeps it
 * (the timeline splits it back into chips).
 */
object SkillBlocks {
    data class Block(val name: String, val body: String)

    private val HEAD = Regex("""\A<nc_skill name="([^"\n]*)">\n([\s\S]*?)\n</nc_skill>\n+""")

    fun wrap(skills: List<Block>, prompt: String): String =
        skills.joinToString("") { "<nc_skill name=\"${it.name.replace('"', '\'').replace('\n', ' ')}\">\n${it.body.trimEnd()}\n</nc_skill>\n\n" } + prompt

    /** Leading skill blocks and the prompt after them. */
    fun split(text: String): Pair<List<Block>, String> {
        val out = ArrayList<Block>()
        var rest = text
        while (true) {
            val m = HEAD.find(rest) ?: break
            out += Block(m.groupValues[1], m.groupValues[2])
            rest = rest.substring(m.range.last + 1)
        }
        return out to rest
    }

    /** The user's own words, else "Skill: <names>" (titles, previews). */
    fun plain(text: String): String = split(text).let { (b, rest) -> rest.ifBlank { if (b.isEmpty()) rest else "Skill: " + b.joinToString { it.name } } }
}
