package com.zenithblue.nativecode.terminal

import android.content.Context
import com.zenithblue.nativecode.BuildConfig
import java.io.File

/** Handles project creation: folder setup and git clone, unified for proot/chroot. */
object ProjectManager {

    /** Repo folder name from a git URL (strip trailing .git). */
    fun repoNameFromUrl(gitUrl: String): String =
        gitUrl.trim().trimEnd('/').substringAfterLast("/").removeSuffix(".git")
            .ifEmpty { "repo" }

    /** Clones a git repository into the isolation [method] rootfs.
     *  Calls [onProgress] for each output line, then [onDone] with exit code. */
    fun cloneRepo(
        ctx: Context,
        gitUrl: String,
        destPath: String,
        method: String = LinuxCommandBuilder.currentMethod,
        onProgress: (line: String) -> Unit,
        onDone: (exitCode: Int) -> Unit
    ) {
        val urlQ = shellQuote(gitUrl.trim())
        val destQ = shellQuote(destPath)
        val parentQ = shellQuote(destPath.substringBeforeLast('/').ifEmpty { "/" })
        // Absolute dest; rmdir empty shell if present.
        // Progress uses CR — convert to NL so runStreamed (readLine) updates UI.
        // GIT_TERMINAL_PROMPT=0 avoids hung credential prompts (no TTY).
        // Leading `export` injects `$` so chroot stages script (safe quoting + noprofile).
        val gitCmd =
            "export HOME=/home/flux GIT_TERMINAL_PROMPT=0 GIT_PAGER=cat; " +
                "mkdir -p $parentQ && " +
                "if [ -d $destQ ] && [ ! -d $destQ/.git ]; then rmdir $destQ 2>/dev/null || true; fi && " +
                "git clone --progress $urlQ $destQ 2>&1 | tr '\\r' '\\n'"
        val (args, envMap) = LinuxCommandBuilder.build(ctx, gitCmd, method = method)
        val env = HashMap(envMap).apply {
            put("HOME", "/home/flux")
            put("GIT_TERMINAL_PROMPT", "0")
            put("GIT_PAGER", "cat")
        }
        ShellCommandRunner.runStreamed(ctx, args, env, onLine = onProgress, onDone = onDone)
    }

    /** Creates a directory inside the Debian environment for [method].
     *  Fire-and-forget: swallows errors. */
    fun ensureDir(
        ctx: Context,
        path: String,
        method: String = LinuxCommandBuilder.currentMethod
    ) {
        val pathQ = shellQuote(path)
        val (args, envMap) = LinuxCommandBuilder.build(ctx, "mkdir -p $pathQ", method = method)
        try {
            ShellCommandRunner.run(ctx, args, envMap)
        } catch (_: Exception) {
            // non-fatal
        }
    }

    /** True if host path under [method] has a `.git` directory. */
    fun hasGitCheckout(ctx: Context, projectPath: String, method: String): Boolean {
        val host = ProjectPathResolver.resolve(ctx, projectPath, method)
        return File(host, ".git").isDirectory
    }

    /** Opposite isolation method. */
    fun oppositeMethod(method: String): String =
        if (BuildConfig.PLAY_STORE_BUILD || method == "chroot") "proot" else "chroot"

    /**
     * If [method] host tree lacks `.git` but the opposite rootfs has it,
     * return the method where data lives; else null.
     */
    fun detectRepoMethod(
        ctx: Context,
        projectPath: String,
        preferredMethod: String
    ): String? {
        if (BuildConfig.PLAY_STORE_BUILD) {
            return "proot".takeIf { hasGitCheckout(ctx, projectPath, it) }
        }
        if (hasGitCheckout(ctx, projectPath, preferredMethod)) return preferredMethod
        val other = oppositeMethod(preferredMethod)
        if (hasGitCheckout(ctx, projectPath, other)) return other
        return null
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
