package com.zenithblue.nativecode.agent.chat

import android.content.Context
import com.zenithblue.nativecode.agent.chat.core.DiffStat
import com.zenithblue.nativecode.agent.chat.core.Run
import com.zenithblue.nativecode.git.GitGuestCommands
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Per-turn hidden-ref checkpoints (plan 9.2.6 G, t3code `GitVcsDriver.ts:969-1370`): a throwaway index snapshots
 * the work tree into `refs/nativecode/chk/<thread>/<ordinal>/{base,end}`. The user's index, stash and branches
 * are never touched. Guest git in PRoot (the chat's own isolation). Blocking: call off the main thread.
 * Non-git cwd or no HEAD: null (no diff).
 */
object TurnCheckpoints {
    const val BUDGET_MS = 5_000L
    const val PATCH_CAP = 512 * 1024
    private const val OUT = "NCOUT"
    private val q = GitGuestCommands::shellQuote

    fun ref(threadId: String, ordinal: Int, which: String) = "refs/nativecode/chk/$threadId/$ordinal/$which"

    /** Snapshot script; prints `NCOUT <sha>` on success. Exit 3 = not a repo, 4 = no HEAD. */
    fun captureScript(cwd: String, ref: String, tmpName: String = "nc-chk-${UUID.randomUUID()}"): String =
        "cd ${q(cwd)} || exit 3; gd=\$(git rev-parse --absolute-git-dir 2>/dev/null) || exit 3; " +
            "git rev-parse -q --verify HEAD >/dev/null || exit 4; " +
            "export GIT_INDEX_FILE=\"\$gd/$tmpName\"; trap 'rm -f \"\$GIT_INDEX_FILE\"' EXIT; " +
            "export GIT_AUTHOR_NAME=NativeCode GIT_AUTHOR_EMAIL=checkpoint@nativecode GIT_COMMITTER_NAME=NativeCode GIT_COMMITTER_EMAIL=checkpoint@nativecode; " +
            "git read-tree HEAD && git add -A -- . && t=\$(git write-tree) && c=\$(git commit-tree \"\$t\" -p HEAD -m nativecode-checkpoint) && " +
            "git update-ref ${q(ref)} \"\$c\" && echo \"$OUT \$c\""

    // ponytail: --no-renames, so kind is M/A/D/T only; add rename pairing when the diff page needs R
    fun numstatScript(cwd: String, from: String, to: String): String =
        "cd ${q(cwd)} || exit 3; printf '$OUT\\0'; git diff --no-renames -z --name-status ${q(from)} ${q(to)} && printf 'NCSEP\\0' && " +
            "git diff --no-renames -z --numstat ${q(from)} ${q(to)}"

    /** [to] null = against the work tree. Capped in the guest; one extra byte tells us it was cut. */
    fun patchScript(cwd: String, from: String, to: String?, cap: Int = PATCH_CAP): String =
        "cd ${q(cwd)} || exit 3; printf '$OUT\\n'; git diff --no-color ${q(from)}${to?.let { " " + q(it) } ?: ""} | head -c ${cap + 1}"

    fun pruneScript(cwd: String, threadId: String): String =
        "cd ${q(cwd)} || exit 3; git for-each-ref --format='%(refname)' ${q("refs/nativecode/chk/$threadId/")} | " +
            "while read -r r; do git update-ref -d \"\$r\"; done"

    /** `-z --name-status` + `NCSEP` + `-z --numstat` → stats in path order. Binary files count 0/0. */
    fun parseNumstat(out: String): List<DiffStat> {
        val body = out.substringAfter("$OUT\u0000", "")
        val (ns, num) = body.split("NCSEP\u0000", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val kinds = LinkedHashMap<String, String>()
        ns.split('\u0000').filter { it.isNotEmpty() }.chunked(2).forEach { if (it.size == 2) kinds[it[1]] = it[0].take(1) }
        val counts = HashMap<String, Pair<Int, Int>>()
        num.split('\u0000').filter { it.isNotEmpty() }.forEach { rec ->
            val p = rec.split('\t', limit = 3)
            if (p.size == 3) counts[p[2]] = (p[0].toIntOrNull() ?: 0) to (p[1].toIntOrNull() ?: 0)
        }
        return kinds.map { (path, k) -> counts[path].let { DiffStat(path, k, it?.first ?: 0, it?.second ?: 0) } }
    }

    fun capPatch(out: String, cap: Int = PATCH_CAP): String {
        val body = out.substringAfter("$OUT\n", "")
        val bytes = body.toByteArray(Charsets.UTF_8)
        return if (bytes.size <= cap) body else String(bytes, 0, cap, Charsets.UTF_8) + "\n[truncated]"
    }

    // ---------------- guest calls ----------------

    fun capture(ctx: Context, cwd: String, threadId: String, ordinal: Int, which: String): String? =
        guest(ctx, cwd, captureScript(cwd, ref(threadId, ordinal, which)))?.lineSequence()
            ?.firstOrNull { it.startsWith("$OUT ") }?.removePrefix("$OUT ")?.trim()?.takeIf { it.length >= 40 }

    fun diffStat(ctx: Context, cwd: String, base: String, end: String): List<DiffStat>? =
        guest(ctx, cwd, numstatScript(cwd, base, end))?.let(::parseNumstat)

    fun turnPatch(ctx: Context, cwd: String, run: Run): String? {
        val base = run.chkBase ?: return null
        return patch(ctx, cwd, base, run.chkEnd ?: return null)
    }

    /** First base → last end of the thread's runs. */
    fun threadPatch(ctx: Context, cwd: String, runs: List<Run>): String? {
        val base = runs.sortedBy { it.ordinal }.firstOrNull { it.chkBase != null }?.chkBase ?: return null
        val end = runs.sortedBy { it.ordinal }.lastOrNull { it.chkEnd != null }?.chkEnd ?: return null
        return patch(ctx, cwd, base, end)
    }

    fun uncommittedPatch(ctx: Context, cwd: String): String? = patch(ctx, cwd, "HEAD", null)

    fun prune(ctx: Context, cwd: String, threadId: String) { guest(ctx, cwd, pruneScript(cwd, threadId), 30_000) }

    private fun patch(ctx: Context, cwd: String, from: String, to: String?): String? =
        guest(ctx, cwd, patchScript(cwd, from, to), 15_000)?.let { capPatch(it) }

    /** Guest bash with the git env; stdout only (stderr dropped, so PRoot noise never mixes in). Null on failure or over [budgetMs]. */
    private fun guest(ctx: Context, cwd: String, script: String, budgetMs: Long = BUDGET_MS): String? = runCatching {
        val (args, env) = LinuxCommandBuilder.buildGuestBash(ctx, script, method = "proot")
        val p = ShellCommandRunner.startStdout(ctx, args, HashMap(env).apply { putAll(GitGuestCommands.gitEnv(cwd)) })
        val buf = ByteArrayOutputStream()
        val reader = Thread { runCatching { p.inputStream.use { it.copyTo(buf) } } }.apply { isDaemon = true; start() }
        if (!p.waitFor(budgetMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return null }
        reader.join(1_000)
        if (p.exitValue() != 0) null else buf.toString(Charsets.UTF_8.name())
    }.getOrNull()
}
