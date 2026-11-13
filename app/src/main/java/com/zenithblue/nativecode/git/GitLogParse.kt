package com.zenithblue.nativecode.git

enum class GitRefKind { LOCAL, REMOTE, TAG }

data class GitRef(val name: String, val kind: GitRefKind)

data class GitCommit(
    val hash: String,
    val parents: List<String>,
    val refs: List<GitRef>,
    val author: String,
    val time: Long,
    val subject: String,
    /** HEAD points at this commit (attached or detached). */
    val isHead: Boolean
)

data class GitFileChange(val status: Char, val path: String, val added: Int, val deleted: Int)

data class GitCommitDetail(
    val author: String,
    val time: Long,
    val parents: List<String>,
    val message: String,
    val files: List<GitFileChange>
)

/** Pure parsers for the git history page. No Android. */
object GitLogParse {

    /** `%H NUL %P NUL %D NUL %an NUL %at NUL %s` (one commit per line, --decorate=full). */
    fun parseLine(line: String): GitCommit? {
        val f = line.split('\u0000', limit = 6)
        if (f.size < 6 || f[0].length < 7) return null
        val (refs, head) = parseRefs(f[2])
        return GitCommit(
            hash = f[0],
            parents = if (f[1].isEmpty()) emptyList() else f[1].split(' '),
            refs = refs,
            author = f[3],
            time = f[4].toLongOrNull() ?: 0L,
            subject = f[5],
            isHead = head
        )
    }

    /** `%D` with --decorate=full: "HEAD -> refs/heads/main, refs/remotes/origin/main, tag: refs/tags/v1". */
    fun parseRefs(decor: String): Pair<List<GitRef>, Boolean> {
        if (decor.isEmpty()) return emptyList<GitRef>() to false
        var head = false
        val out = ArrayList<GitRef>(2)
        for (raw in decor.split(", ")) {
            var t = raw.trim()
            if (t == "HEAD") { head = true; continue }
            if (t.startsWith("HEAD -> ")) { head = true; t = t.removePrefix("HEAD -> ") }
            when {
                t.startsWith("tag: refs/tags/") -> out += GitRef(t.removePrefix("tag: refs/tags/"), GitRefKind.TAG)
                t.startsWith("refs/heads/") -> out += GitRef(t.removePrefix("refs/heads/"), GitRefKind.LOCAL)
                t.startsWith("refs/remotes/") -> {
                    val n = t.removePrefix("refs/remotes/")
                    if (!n.endsWith("/HEAD")) out += GitRef(n, GitRefKind.REMOTE)
                }
            }
        }
        return out to head
    }

    const val DETAIL_FILES = "__FILES__"
    const val DETAIL_NUM = "__NUM__"

    /** Output of [GitGuestCommands.commitDetail]. */
    fun parseDetail(out: String): GitCommitDetail? {
        val iFiles = out.indexOf("\n$DETAIL_FILES\n")
        val iNum = out.indexOf("\n$DETAIL_NUM\n")
        if (iFiles < 0 || iNum < iFiles) return null
        val head = out.substring(0, iFiles).split('\u0000', limit = 4)
        if (head.size < 4) return null
        val nums = HashMap<String, Pair<Int, Int>>()
        for (l in out.substring(iNum + DETAIL_NUM.length + 2).lines()) {
            val p = l.split('\t', limit = 3)
            if (p.size == 3) nums[p[2]] = (p[0].toIntOrNull() ?: 0) to (p[1].toIntOrNull() ?: 0)
        }
        val files = out.substring(iFiles + DETAIL_FILES.length + 2, iNum).lines().mapNotNull { l ->
            val p = l.split('\t', limit = 2)
            if (p.size < 2 || p[0].isEmpty()) null
            else GitFileChange(p[0][0], p[1], nums[p[1]]?.first ?: 0, nums[p[1]]?.second ?: 0)
        }
        return GitCommitDetail(
            author = head[0],
            time = head[1].toLongOrNull() ?: 0L,
            parents = head[2].split(' ').filter { it.isNotEmpty() },
            message = head[3].trim(),
            files = files
        )
    }

    fun relativeTime(nowSec: Long, thenSec: Long): String {
        val d = nowSec - thenSec
        return when {
            d < 60 -> "now"
            d < 3600 -> "${d / 60}m"
            d < 86400 -> "${d / 3600}h"
            d < 86400 * 30 -> "${d / 86400}d"
            d < 86400 * 365 -> "${d / (86400 * 30)}mo"
            else -> "${d / (86400 * 365)}y"
        }
    }
}
