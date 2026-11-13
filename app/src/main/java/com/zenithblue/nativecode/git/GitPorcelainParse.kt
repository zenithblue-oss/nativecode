package com.zenithblue.nativecode.git

/** Pure parse helpers for guest git bundle output. */
object GitPorcelainParse {

    fun parseBranchesBundle(raw: String): BranchInfo {
        val lines = raw.lines().map { it.trimEnd() }
        if (lines.any { it.trim() == "__NOGIT__" }) {
            return BranchInfo(isRepo = false, current = null, localBranches = emptyList())
        }
        var section = ""
        var current: String? = null
        val local = ArrayList<String>()
        val remote = ArrayList<String>()
        for (line in lines) {
            val t = line.trim()
            when (t) {
                "__HEAD__" -> { section = "head"; continue }
                "__LOCAL__", "__BRANCHES__" -> { section = "local"; continue }
                "__REMOTE__" -> { section = "remote"; continue }
                "__END__" -> { section = ""; continue }
                "__NOGIT__" -> return BranchInfo(false, null, emptyList())
            }
            // strip noise from proot banners
            if (t.startsWith("__") && t.endsWith("__")) continue
            when (section) {
                "head" -> {
                    if (t.isNotEmpty() && current == null && !t.startsWith("fatal:")) {
                        current = t
                    }
                }
                "local" -> {
                    if (t.isNotEmpty() && !t.startsWith("fatal:") && t !in local) local.add(t)
                }
                "remote" -> {
                    // drop "origin/HEAD -> origin/main" style
                    val name = t.substringBefore(" -> ").trim()
                    if (name.isNotEmpty() && !name.endsWith("/HEAD") &&
                        !name.startsWith("fatal:") && name !in remote
                    ) {
                        remote.add(name)
                    }
                }
            }
        }
        // Ensure current appears in local list when present
        val cur = current?.takeIf { it.isNotEmpty() && it != "HEAD" }
        if (cur != null && cur !in local && !cur.contains("/")) local.add(0, cur)
        val detached = cur == null || current == "HEAD"
        return BranchInfo(
            isRepo = true,
            current = cur,
            localBranches = local,
            remoteBranches = remote,
            detached = detached
        )
    }

    // -- CHANGES page: output of GitGuestCommands.changesBundle ---------------------------------

    const val M_STATUS = "__ST__"
    const val M_UNSTAGED = "__UN__"
    const val M_STAGED = "__CA__"
    const val M_STATE = "__GD__"
    const val M_FAIL = "__FAIL__"
    private val MARKERS = listOf(M_STATUS, M_UNSTAGED, M_STAGED, M_STATE, M_FAIL)

    private fun marker(m: String) = "\u0000$m\u0000"

    /** Text between marker [m] and the next marker (or end). Null if [m] is absent. */
    private fun section(raw: String, m: String): String? {
        val i = raw.indexOf(marker(m))
        if (i < 0) return null
        val s = i + m.length + 2
        // Exact markers only: a file name may itself start with "__".
        val e = MARKERS.map { raw.indexOf(marker(it), s) }.filter { it >= 0 }.minOrNull() ?: raw.length
        return raw.substring(s, e)
    }

    fun parseChangesBundle(raw: String): RepoStatusResult {
        section(raw, M_FAIL)?.let { msg ->
            val m = msg.trim()
            return if (m.contains("not a git repository", true) || m.isEmpty()) RepoStatusResult.NotGit()
            else RepoStatusResult.Error(m.take(300))
        }
        val st = section(raw, M_STATUS) ?: return RepoStatusResult.Error(raw.trim().take(300).ifEmpty { "git status failed" })
        val status = parseStatusV2(st)
        val un = parseNumstatZ(section(raw, M_UNSTAGED).orEmpty())
        val ca = parseNumstatZ(section(raw, M_STAGED).orEmpty())
        val files = status.files.map { f ->
            val n = when (f.group) {
                ChangeGroup.STAGED -> ca[f.path]
                ChangeGroup.UNSTAGED, ChangeGroup.CONFLICT -> un[f.path]
                ChangeGroup.UNTRACKED -> null
            }
            if (n == null) f else f.copy(added = n.first, deleted = n.second)
        }
        val state = section(raw, M_STATE)?.trim()?.takeIf { it.isNotEmpty() }
        return RepoStatusResult.Ok(status.copy(files = files, state = state))
    }

    /**
     * `git status --porcelain=v2 --branch -z --show-stash`. One tracked entry can yield a STAGED
     * and an UNSTAGED row (X and Y). Paths are relative to the repo root.
     */
    fun parseStatusV2(raw: String): RepoStatus {
        var branch: String? = null
        var oid: String? = null
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        var stash = 0
        val files = ArrayList<ChangeFile>()
        val f = raw.split('\u0000')
        var i = 0
        while (i < f.size) {
            val e = f[i++]
            if (e.length < 2) continue
            when (e[0]) {
                '#' -> {
                    val kv = e.removePrefix("# ")
                    val k = kv.substringBefore(' ')
                    val v = kv.substringAfter(' ', "")
                    when (k) {
                        "branch.oid" -> oid = v.takeIf { it != "(initial)" }
                        "branch.head" -> branch = v.takeIf { it != "(detached)" }
                        "branch.upstream" -> upstream = v
                        "branch.ab" -> {
                            val p = v.split(' ')
                            ahead = p.getOrNull(0)?.removePrefix("+")?.toIntOrNull() ?: 0
                            behind = p.getOrNull(1)?.removePrefix("-")?.toIntOrNull() ?: 0
                        }
                        "stash" -> stash = v.toIntOrNull() ?: 0
                    }
                }
                '1', '2' -> {
                    val renamed = e[0] == '2'
                    val p = e.split(' ', limit = if (renamed) 10 else 9)
                    if (p.size < (if (renamed) 10 else 9)) continue
                    val xy = p[1]
                    val path = p.last()
                    val orig = if (renamed) f.getOrNull(i++) else null
                    val x = xy.getOrElse(0) { '.' }
                    val y = xy.getOrElse(1) { '.' }
                    if (x != '.') files += ChangeFile(ChangeGroup.STAGED, x, path, orig.takeIf { x == 'R' || x == 'C' })
                    if (y != '.') files += ChangeFile(ChangeGroup.UNSTAGED, y, path, orig.takeIf { y == 'R' || y == 'C' })
                }
                'u' -> {
                    val p = e.split(' ', limit = 11)
                    if (p.size == 11) files += ChangeFile(ChangeGroup.CONFLICT, '!', p[10])
                }
                '?' -> files += ChangeFile(ChangeGroup.UNTRACKED, 'U', e.substring(2))
            }
        }
        return RepoStatus(branch, oid, upstream, ahead, behind, stash, files, null)
    }

    /**
     * `git diff --numstat -z`: `add TAB del TAB path NUL`, renames `add TAB del TAB NUL src NUL dst NUL`.
     * Binary files (`-`) map to -1. Keyed by the destination path.
     */
    fun parseNumstatZ(raw: String): Map<String, Pair<Int, Int>> {
        val out = HashMap<String, Pair<Int, Int>>()
        val f = raw.split('\u0000')
        var i = 0
        while (i < f.size) {
            val e = f[i++].trimStart('\n')
            val p = e.split('\t', limit = 3)
            if (p.size < 3) continue
            var path = p[2]
            if (path.isEmpty()) { i++; path = f.getOrNull(i++) ?: continue }
            out[path] = (p[0].toIntOrNull() ?: -1) to (p[1].toIntOrNull() ?: -1)
        }
        return out
    }
}
