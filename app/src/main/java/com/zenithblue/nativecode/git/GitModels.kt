package com.zenithblue.nativecode.git

/** Branch list for project settings (local + remote, display-only). */
data class BranchInfo(
    val isRepo: Boolean,
    val current: String?,
    /** Local branch short names. */
    val localBranches: List<String>,
    /** Remote-tracking names (e.g. origin/main). */
    val remoteBranches: List<String> = emptyList(),
    val detached: Boolean = false
) {
    /** Combined list for dropdown: locals first, then remotes not already listed. */
    val allBranches: List<String>
        get() {
            val out = ArrayList<String>(localBranches.size + remoteBranches.size)
            out.addAll(localBranches)
            for (r in remoteBranches) {
                val short = r.removePrefix("origin/").removePrefix("upstream/")
                if (r !in out && short !in out) out.add(r)
            }
            return out
        }

    /** Backward-compatible alias used by older call sites. */
    val branches: List<String> get() = allBranches
}

enum class ChangeGroup { CONFLICT, STAGED, UNSTAGED, UNTRACKED }

/**
 * One file row on the CHANGES page. [status] uses VS Code letters:
 * M A D R C T, U = untracked, ! = merge conflict. [added]/[deleted] = -1 when unknown or binary.
 */
data class ChangeFile(
    val group: ChangeGroup,
    val status: Char,
    val path: String,
    val origPath: String? = null,
    val added: Int = -1,
    val deleted: Int = -1
)

/** `git status --porcelain=v2 --branch -z --show-stash` + numstat, parsed. */
data class RepoStatus(
    /** null = detached HEAD. */
    val branch: String?,
    /** null = no commits yet. */
    val oid: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val stash: Int,
    val files: List<ChangeFile>,
    /** MERGING, REBASING, CHERRY-PICKING, REVERTING, BISECTING or null. */
    val state: String?
)

sealed class RepoStatusResult {
    data class Ok(val status: RepoStatus) : RepoStatusResult()
    data class NotGit(val message: String = "Project is not a git repository") : RepoStatusResult()
    data class Error(val message: String) : RepoStatusResult()
}
