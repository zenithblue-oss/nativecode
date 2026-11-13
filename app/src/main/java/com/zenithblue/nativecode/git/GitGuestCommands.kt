package com.zenithblue.nativecode.git

/**
 * Pure guest shell snippets for project git. No Android / UI.
 * Always run as flux via [com.zenithblue.nativecode.terminal.LinuxCommandBuilder].
 */
object GitGuestCommands {

    /**
     * Non-interactive git env (merge into ProcessBuilder / ShellCommandRunner env).
     *
     * Project trees live on an Android-owned bind mount while git runs as guest
     * flux, so Git's ownership guard must be scoped to this exact project path.
     * Never use the unsafe global `*` safe.directory value.
     */
    fun gitEnv(projectPath: String? = null): Map<String, String> = buildMap {
        put("GIT_PAGER", "cat")
        put("GIT_TERMINAL_PROMPT", "0")
        put("TERM", "dumb")
        put("NO_COLOR", "1")
        if (!projectPath.isNullOrBlank()) {
            put("GIT_CONFIG_COUNT", "1")
            put("GIT_CONFIG_KEY_0", "safe.directory")
            put("GIT_CONFIG_VALUE_0", projectPath)
        }
    }

    fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    /** Adds an exact, path-scoped safe.directory override to one git command. */
    fun gitCommand(projectPath: String, args: String): String =
        "git -c safe.directory=${shellQuote(projectPath)} $args"

    /** Single spawn: is-repo + current + local + remote-tracking branches. */
    fun branchesBundle(projectPath: String): String {
        val p = shellQuote(projectPath)
        val git = gitCommand(projectPath, "")
        // One line — safer inside proot/chroot -c quoting than multiline.
        return "cd $p || exit 2; " +
            "if ! $git rev-parse --is-inside-work-tree >/dev/null 2>&1; then echo '__NOGIT__'; exit 0; fi; " +
            "echo '__HEAD__'; " +
            "cur=\$($git branch --show-current 2>/dev/null); " +
            "[ -z \"\$cur\" ] && cur=\$($git rev-parse --abbrev-ref HEAD 2>/dev/null); " +
            "echo \"\$cur\"; " +
            "echo '__LOCAL__'; " +
            "$git branch --format='%(refname:short)' 2>/dev/null; " +
            "echo '__REMOTE__'; " +
            "$git branch -r --format='%(refname:short)' 2>/dev/null; " +
            "echo '__END__'"
    }

    /**
     * CHANGES page refresh, one guest start, 3 git calls: porcelain v2 status (branch, upstream,
     * ahead/behind, stash count), unstaged numstat, staged numstat. Repo state (merge, rebase...)
     * comes from plain file tests on the git dir, not git.
     * ponytail: state tests assume the project dir is the repo root (or a worktree with a .git file).
     */
    fun changesBundle(projectPath: String): String {
        val git = gitCommand(projectPath, "")
        fun m(name: String) = "printf '\\000$name\\000'; "
        val gd = "\"\$gd\""
        return "cd ${shellQuote(projectPath)} || exit 2; " +
            m(GitPorcelainParse.M_STATUS) +
            "$git status --porcelain=v2 --branch -z --show-stash -uall 2>/dev/null || { " +
            m(GitPorcelainParse.M_FAIL) + "$git rev-parse --git-dir 2>&1 | head -3; exit 0; }; " +
            m(GitPorcelainParse.M_UNSTAGED) + "$git diff --numstat -z 2>/dev/null; " +
            m(GitPorcelainParse.M_STAGED) + "$git diff --cached --numstat -z 2>/dev/null; " +
            m(GitPorcelainParse.M_STATE) +
            "gd=.git; [ -f .git ] && gd=\$(sed -n 's/^gitdir: //p' .git); " +
            "if [ -d $gd/rebase-merge ] || [ -d $gd/rebase-apply ]; then echo REBASING; " +
            "elif [ -f $gd/MERGE_HEAD ]; then echo MERGING; " +
            "elif [ -f $gd/CHERRY_PICK_HEAD ]; then echo CHERRY-PICKING; " +
            "elif [ -f $gd/REVERT_HEAD ]; then echo REVERTING; " +
            "elif [ -f $gd/BISECT_LOG ]; then echo BISECTING; fi"
    }

    /** `shortHash NUL unixTime NUL subject` of HEAD. Only run when HEAD's oid changed. */
    fun lastCommit(projectPath: String): String =
        "cd ${shellQuote(projectPath)} && " + gitCommand(projectPath, "log -1 --format=%h%x00%ct%x00%s")

    /** Every branch, remote branch and tag with the commit it points at (tags peeled). */
    fun refList(projectPath: String): String =
        "cd ${shellQuote(projectPath)} && " + gitCommand(
            projectPath,
            "for-each-ref --format='%(refname)%00%(objectname)%00%(*objectname)' refs/heads refs/remotes refs/tags"
        )

    fun stashList(projectPath: String): String =
        "cd ${shellQuote(projectPath)} && " + gitCommand(projectPath, "stash list --format='%gd: %s'")

    /** Root-relative literal pathspec (porcelain paths are relative to the repo root). */
    fun topPathspec(filePath: String): String = shellQuote(":(top,literal)$filePath")

    /** One git subcommand run in the project dir, e.g. `add -A -- <spec>`. */
    fun inProject(projectPath: String, args: String): String =
        "cd ${shellQuote(projectPath)} && " + gitCommand(projectPath, args)

    private val SHA = Regex("^[0-9a-f]{7,64}$")

    /**
     * One streaming git call for the whole graph: one commit per line,
     * `hash NUL parents NUL decorations(full) NUL author NUL unix-time NUL subject`.
     * [ref] null = all branches, tags, remotes and HEAD. [skip] only for a resume after the process was killed.
     */
    fun logStream(projectPath: String, ref: String?, skip: Int = 0): String {
        val scope = if (ref == null) "--branches --tags --remotes HEAD" else "--end-of-options ${shellQuote(ref)}"
        val skipArg = if (skip > 0) "--skip=$skip " else ""
        val args = "-c log.showSignature=false log --topo-order --decorate=full $skipArg" +
            "--format=%H%x00%P%x00%D%x00%an%x00%at%x00%s $scope"
        return "cd ${shellQuote(projectPath)} && ${gitCommand(projectPath, args)}"
    }

    /**
     * Message, author, date, parents, then name-status and numstat vs first parent (root commits vs empty).
     * File lists use -z so non-ASCII paths are not C-quoted ("\303\251") and match the diff call.
     */
    fun commitDetail(projectPath: String, hash: String): String {
        require(SHA.matches(hash))
        val dt = "diff-tree -z --root --no-commit-id -r -m --first-parent"
        return "cd ${shellQuote(projectPath)} && " +
            gitCommand(projectPath, "show -s --format='%an <%ae>%x00%at%x00%P%x00%B' $hash") +
            "; printf '\\n${GitLogParse.DETAIL_FILES}\\n'; " + gitCommand(projectPath, "$dt --name-status $hash") +
            "; printf '\\n${GitLogParse.DETAIL_NUM}\\n'; " + gitCommand(projectPath, "$dt --numstat $hash")
    }

    /** Diff of one file in one commit (against its first parent; root and merge commits work). */
    fun commitFileDiff(projectPath: String, hash: String, filePath: String): String {
        require(SHA.matches(hash))
        return "cd ${shellQuote(projectPath)} && " +
            gitCommand(projectPath, "show --first-parent --format= $hash -- ${literalPathspec(filePath)}")
    }

    /** One-time background `commit-graph write` so --topo-order can stream on big repos. */
    fun ensureCommitGraph(projectPath: String): String =
        "cd ${shellQuote(projectPath)} && ( [ -e .git/objects/info/commit-graph ] || [ -d .git/objects/info/commit-graphs ] || " +
            gitCommand(projectPath, "commit-graph write --reachable --split") + " ) >/dev/null 2>&1"

    fun isInsideWorkTree(projectPath: String): String =
        "cd ${shellQuote(projectPath)} && ${gitCommand(projectPath, "rev-parse --is-inside-work-tree")} 2>/dev/null"

    fun diffFile(projectPath: String, filePath: String): String =
        "cd ${shellQuote(projectPath)} && ${gitCommand(projectPath, "diff HEAD -- ${shellQuote(filePath)}")}"

    /** Pathspec with glob magic off. shellQuote does not stop `*`, `[`, `?`. */
    private fun literalPathspec(filePath: String): String =
        shellQuote(":(literal)$filePath")

    /**
     * One guest start. HEAD membership and index membership are separate
     * (`cat-file -e`, not `ls-files`, so a staged deletion still diffs against HEAD).
     * Unborn + staged: `git diff <empty-tree> -- path` (hash-object -t tree /dev/null).
     * Untracked: `--no-index` exit 1 means differences and is mapped to 0; exit >=2 stays.
     * Newlines in filenames are not safe in this one-line script.
     */
    fun diffFileAuto(projectPath: String, filePath: String): String {
        val p = shellQuote(projectPath)
        val spec = literalPathspec(filePath)
        val obj = shellQuote(filePath)
        val hasHead = gitCommand(projectPath, "rev-parse --verify HEAD")
        val inHead = gitCommand(projectPath, "cat-file -e HEAD:$obj")
        val inIndex = gitCommand(projectPath, "cat-file -e :$obj")
        val diffHead = gitCommand(projectPath, "diff HEAD -- $spec")
        val emptyTree = gitCommand(projectPath, "hash-object -t tree /dev/null")
        val diffEmpty = gitCommand(projectPath, "diff --no-ext-diff \$empty -- $spec")
        val untracked = gitCommand(projectPath, "diff --no-index -- /dev/null $obj")
        return "cd $p || exit 2; " +
            "if $hasHead >/dev/null 2>&1; then has_head=1; else has_head=0; fi; " +
            "if $inHead >/dev/null 2>&1; then in_head=1; else in_head=0; fi; " +
            "if $inIndex >/dev/null 2>&1; then in_index=1; else in_index=0; fi; " +
            "if [ \"\$has_head\" = 1 ] && { [ \"\$in_head\" = 1 ] || [ \"\$in_index\" = 1 ]; }; then " +
            "$diffHead; exit \$?; fi; " +
            "if [ \"\$has_head\" = 0 ] && [ \"\$in_index\" = 1 ]; then " +
            "empty=\$($emptyTree) && $diffEmpty; exit \$?; fi; " +
            "if [ -e $obj ] || [ -L $obj ]; then " +
            "$untracked; rc=\$?; if [ \$rc -eq 1 ]; then exit 0; fi; exit \$rc; fi; " +
            "echo 'not found' >&2; exit 2"
    }

    fun diffUntracked(projectPath: String, filePath: String): String =
        "cd ${shellQuote(projectPath)} && [ -f ${shellQuote(filePath)} ] && " +
            gitCommand(projectPath, "diff --no-index /dev/null ${shellQuote(filePath)}")
}
