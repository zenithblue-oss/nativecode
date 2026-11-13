package com.zenithblue.nativecode.git

/** A branch / remote branch / tag and the commit it points at (tags peeled). */
data class RefTarget(val name: String, val kind: GitRefKind, val hash: String)

/** Pure matching for the history search. No Android. */
object GitSearch {

    /**
     * Higher is better, -1 = no match. Substring beats fuzzy (in-order subsequence);
     * a match at the start of the name or of its last path segment beats one in the middle;
     * shorter names win ties. Case-insensitive. Empty query matches everything with 0.
     */
    fun score(query: String, name: String): Int {
        if (query.isEmpty()) return 0
        val q = query.lowercase()
        val n = name.lowercase()
        val at = n.indexOf(q)
        if (at >= 0) {
            val seg = n.lastIndexOf('/') + 1
            val bonus = when {
                at == 0 -> 300
                at == seg -> 250
                n[at - 1] in "/-_." -> 150
                else -> 0
            }
            return 1000 + bonus - at - (n.length - q.length)
        }
        // fuzzy: every query char in order; penalise gaps
        var j = 0
        var gaps = 0
        var last = -1
        for (i in n.indices) {
            if (j < q.length && n[i] == q[j]) {
                if (last >= 0) gaps += i - last - 1
                last = i; j++
            }
        }
        return if (j == q.length) maxOf(1, 500 - gaps * 10 - n.length) else -1
    }

    /** Refs matching [query], best first. */
    fun rankRefs(query: String, refs: List<RefTarget>, limit: Int = 200): List<RefTarget> =
        refs.asSequence()
            .map { it to score(query.trim(), it.name) }
            .filter { it.second >= 0 }
            .sortedWith(compareByDescending<Pair<RefTarget, Int>> { it.second }.thenBy { it.first.kind.ordinal }.thenBy { it.first.name })
            .take(limit).map { it.first }.toList()

    /** Hash prefix, or substring of subject or author (case-insensitive). */
    fun commitMatches(query: String, c: GitCommit): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        if (q.length >= 4 && c.hash.startsWith(q.lowercase())) return true
        return c.subject.contains(q, ignoreCase = true) || c.author.contains(q, ignoreCase = true)
    }

    /** Output of [GitGuestCommands.refList]: `refname NUL object NUL peeled` per line. */
    fun parseRefList(out: String): List<RefTarget> = out.lineSequence().mapNotNull { l ->
        val f = l.split('\u0000')
        if (f.size < 2) return@mapNotNull null
        val hash = f.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: f[1]
        val r = f[0]
        when {
            r.startsWith("refs/heads/") -> RefTarget(r.removePrefix("refs/heads/"), GitRefKind.LOCAL, hash)
            r.startsWith("refs/tags/") -> RefTarget(r.removePrefix("refs/tags/"), GitRefKind.TAG, hash)
            r.startsWith("refs/remotes/") && !r.endsWith("/HEAD") ->
                RefTarget(r.removePrefix("refs/remotes/"), GitRefKind.REMOTE, hash)
            else -> null
        }
    }.toList()
}
