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
     * Single spawn: is-repo + porcelain status + numstat vs HEAD.
     * Untracked LOC omitted (count-only in parse).
     */
    fun statusSummaryBundle(projectPath: String): String {
        val p = shellQuote(projectPath)
        val git = gitCommand(projectPath, "")
        return "cd $p || exit 2; " +
            "if ! $git rev-parse --is-inside-work-tree >/dev/null 2>&1; then " +
            "echo '__NOGIT__'; exit 0; fi; " +
            "echo '__STATUS__'; " +
            "$git status --porcelain -uall 2>/dev/null; " +
            "echo '__NUMSTAT__'; " +
            "$git diff --numstat HEAD 2>/dev/null"
    }

    fun isInsideWorkTree(projectPath: String): String =
        "cd ${shellQuote(projectPath)} && ${gitCommand(projectPath, "rev-parse --is-inside-work-tree")} 2>/dev/null"

    fun diffFile(projectPath: String, filePath: String): String =
        "cd ${shellQuote(projectPath)} && ${gitCommand(projectPath, "diff HEAD -- ${shellQuote(filePath)}")}"

    fun diffUntracked(projectPath: String, filePath: String): String =
        "cd ${shellQuote(projectPath)} && [ -f ${shellQuote(filePath)} ] && " +
            gitCommand(projectPath, "diff --no-index /dev/null ${shellQuote(filePath)}")
}
