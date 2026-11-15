package com.zenithblue.nativecode.git

import android.content.Context
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.terminal.ProjectManager
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/**
 * Project-scoped git queries via guest shell (proot | chroot).
 * Always pass explicit [method] = project isolation, not only global currentMethod.
 */
object GitRepoService {

    fun isGitRepo(ctx: Context, projectPath: String, method: String): Boolean {
        if (projectPath.isBlank()) return false
        if (ProjectManager.hasGitCheckout(ctx, projectPath, method)) return true
        return try {
            val cmd = GitGuestCommands.isInsideWorkTree(projectPath)
            val (args, env) = LinuxCommandBuilder.build(ctx, cmd, method = method)
            val fullEnv = HashMap(env).apply { putAll(GitGuestCommands.gitEnv(projectPath)) }
            val (exit, out) = ShellCommandRunner.runCaptureExit(ctx, args, fullEnv)
            exit == 0 && out.contains("true")
        } catch (_: Exception) {
            false
        }
    }

    /** Blocking — call from bg thread. */
    fun listBranches(ctx: Context, projectPath: String, method: String): BranchInfo {
        if (projectPath.isBlank()) {
            return BranchInfo(isRepo = false, current = null, localBranches = emptyList())
        }
        return try {
            val script = GitGuestCommands.branchesBundle(projectPath)
            val (args, env) = LinuxCommandBuilder.build(ctx, script, method = method)
            val fullEnv = HashMap(env).apply { putAll(GitGuestCommands.gitEnv(projectPath)) }
            val (exit, out) = ShellCommandRunner.runCaptureExit(ctx, args, fullEnv)
            if (exit == 2) {
                return BranchInfo(isRepo = false, current = null, localBranches = emptyList())
            }
            GitPorcelainParse.parseBranchesBundle(out)
        } catch (e: Exception) {
            BranchInfo(isRepo = false, current = null, localBranches = emptyList())
        }
    }

    /** Blocking: one guest script in the project's isolation with the git env. */
    fun run(ctx: Context, projectPath: String, method: String, script: String): Pair<Int, String> {
        val (args, env) = LinuxCommandBuilder.build(ctx, script, method = method)
        return ShellCommandRunner.runCaptureExit(ctx, args, HashMap(env).apply { putAll(GitGuestCommands.gitEnv(projectPath)) })
    }

    /** Blocking — call from bg thread. CHANGES page status (3 git calls). */
    fun loadChanges(ctx: Context, projectPath: String, method: String): RepoStatusResult {
        if (projectPath.isBlank()) return RepoStatusResult.NotGit(tr(R.string.git_no_active_project_2))
        return try {
            val (exit, out) = run(ctx, projectPath, method, GitGuestCommands.changesBundle(projectPath))
            if (exit == 2) RepoStatusResult.NotGit("Project path not found")
            else GitPorcelainParse.parseChangesBundle(out)
        } catch (e: Exception) {
            RepoStatusResult.Error(e.message ?: "git status error")
        }
    }
}
