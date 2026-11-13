package com.zenithblue.nativecode.entitlements

import android.content.Context
import android.util.Log
import com.zenithblue.nativecode.BuildConfig
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Free/Pro gate inside the guest (item 9). The app cannot see what a CLI's shell tool runs, so the AI CLI
 * names resolve to shims ([ASSET] copied to /opt/nativecode/gate/bin, first on PATH) that read
 * /etc/nativecode/entitlement, a state file the app rewrites on every entitlement change. Kotlin file I/O
 * only; the app never runs a guest command. Chat sessions (item 12) start through the same launcher, so
 * they pass the same shim and the same session count.
 *
 * Limits: the guest user owns every file, so this stops casual use (an agent running `codex` from
 * opencode, a pasted `echo pro=1`), not a user who edits the shim or execs the real binary by path.
 * The MAC is `sha256(body + key)`, key per install; it is tamper-evident only because the key has to
 * be readable in the guest too. The hard guarantee is not shipping Pro binaries to Free users (plan 3.4).
 */
object GuestGate {
    private const val TAG = "GuestGate"
    private const val ASSET = "gate/nc-gate.sh"
    private const val KEY_PLACEHOLDER = "__NC_GATE_KEY__"
    private const val PREFS = "nativecode_prefs"
    private const val PREF_KEY = "gate_key"
    const val GATE_BIN_REL = "opt/nativecode/gate/bin"
    const val GATE_BIN = "/$GATE_BIN_REL"
    const val ENTITLEMENT_REL = "etc/nativecode/entitlement"

    /** Every guest command name that starts an AI session. */
    val GATED_TOOLS = listOf("opencode", "codex", "claude", "qwen", "agy", "grok", "kiro", "kiro-cli", "hermes")

    /** Rewrites shims and state when they differ. Cheap enough for cold start, resume and every tab open. */
    fun sync(ctx: Context) {
        runCatching {
            val rootfs = ProjectPathResolver.prootRootfsDir(ctx)
            if (!File(rootfs, "etc").isDirectory) return
            val key = key(ctx)
            install(rootfs, key, ctx.assets.open(ASSET).use { String(it.readBytes()) })
            writeEntitlement(rootfs, render(BuildConfig.MONETISATION_ENFORCE, EntitlementStore.proValidUntilMs(ctx) / 1000, key))
        }.onFailure { Log.w(TAG, "guest gate sync failed: ${it.message}") }
    }

    internal fun install(rootfs: File, key: String, template: String) {
        val script = template.replace(KEY_PLACEHOLDER, key)
        val dir = File(rootfs, GATE_BIN_REL)
        dir.mkdirs()
        // Each parent must be traversable by the proot guest user.
        generateSequence(dir) { it.parentFile }.takeWhile { it != rootfs }.forEach { it.setExecutable(true, false); it.setReadable(true, false) }
        for (tool in GATED_TOOLS) {
            val f = File(dir, tool)
            if (f.isFile && f.readText() == script && f.canExecute()) continue
            writeTextAtomic(f, script, executable = true)
        }
    }

    internal fun writeEntitlement(rootfs: File, content: String) {
        val f = File(rootfs, ENTITLEMENT_REL)
        if (f.isFile && f.readText() == content) return
        f.parentFile?.mkdirs()
        f.parentFile?.setExecutable(true, false)
        writeTextAtomic(f, content)
    }

    internal fun render(enforce: Boolean, proValidUntilSec: Long, key: String): String {
        val body = "v=1\nenforce=${if (enforce) 1 else 0}\npro=${if (proValidUntilSec > 0) 1 else 0}\nexp=$proValidUntilSec\n" +
            "free_tool=${FeatureLimits.FREE_AI_TOOL_ID}\nmax_free=${FeatureLimits.FREE_MAX_AI_SESSIONS}\nmax_pro=${FeatureLimits.PRO_MAX_AI_SESSIONS}\n"
        return body + "mac=${mac(body, key)}\n"
    }

    internal fun mac(body: String, key: String): String =
        MessageDigest.getInstance("SHA-256").digest((body + key).toByteArray()).joinToString("") { "%02x".format(it) }

    private fun key(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString(PREF_KEY, null) ?: ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }.also { p.edit().putString(PREF_KEY, it).apply() }
    }
}

/**
 * Guest file write that a reader (shim, shell) never sees half-done: temp file in the same dir, then rename.
 * A symlink at [file] is replaced by the regular file, so callers skip user-owned symlinked files.
 */
internal fun writeTextAtomic(file: File, text: String, executable: Boolean = false) {
    val tmp = File(file.parentFile, ".${file.name}.nc-tmp")
    tmp.writeText(text)
    tmp.setReadable(true, false)
    if (executable) tmp.setExecutable(true, false)
    if (!tmp.renameTo(file)) {
        tmp.delete()
        error("could not replace ${file.path}")
    }
}
