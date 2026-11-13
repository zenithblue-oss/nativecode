package com.zenithblue.nativecode.terminal

import android.content.Context
import android.util.Log
import com.zenithblue.nativecode.RootShell
import com.zenithblue.nativecode.entitlements.GuestGate
import java.io.File
import java.util.concurrent.TimeUnit

/** Builds shell arguments and environment map for chroot Debian sessions (SSOT helper). */
object ChrootCommandBuilder {

    private const val TAG = "ChrootCommandBuilder"

    const val CHROOT_PATH = "/data/local/tmp/chrootAlpine"

    /** On-device SSOT helper (assets/scripts/chroot/nativecode_chroot.sh). */
    const val CHROOT_HELPER = "/data/local/tmp/nativecode_chroot.sh"
    const val CHROOT_HELPER_ASSET = "scripts/chroot/nativecode_chroot.sh"
    /** Must match first `# nativecode-chroot vN` line in the asset. */
    const val CHROOT_HELPER_VERSION = "nativecode-chroot v2.3"

    /** Compat wrappers (thin → helper); setup still installs for ADB users. */
    const val RUNNER_ROOT = "/data/local/tmp/run_debian13_root.sh"
    const val ENTER_FLUX = "/data/local/tmp/enter_debian13.sh"
    const val ENTER_ROOT = "/data/local/tmp/enter_debian13_root.sh"

    /** Session executable — must be a system binary (SELinux blocks exec of app-data scripts). */
    const val SESSION_EXEC = "/system/bin/sh"

    const val LAUNCH_TOOL_VERSION = "nativecode-launch-tool v6"
    private const val TERM_WRAPPER_VERSION = "nativecode-chroot-term-wrapper v3"
    private val launcherPrepLock = Any()
    private var launcherPrepGeneration = 0L

    fun build(
        ctx: Context,
        shellCmd: String,
        user: String = "flux"
    ): Pair<Array<String>, HashMap<String, String>> {
        ensureLauncherScript(ctx)
        ensureHelperScript(ctx)

        val u = if (user == "root") "root" else "flux"
        val toolExec = parseToolExec(shellCmd)

        // Always `sh $HELPER` (not bare exec of script) — SELinux often blocks exec of
        // /data/local/tmp/*.sh; /system/bin/sh interpreting the script is reliable.
        val rootInner: String = when {
            toolExec != null -> {
                val (dir, argv) = toolExec
                if (dir != null) {
                    // cd then exec launch_tool — b64 keeps TTY (v2.2); argv may have spaces
                    val payload = "mkdir -p ${shellSingleQuote(dir)} && cd ${shellSingleQuote(dir)} && exec $argv"
                    val b64 = android.util.Base64.encodeToString(
                        payload.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    "exec sh $CHROOT_HELPER b64 --user $u -- $b64"
                } else {
                    "exec sh $CHROOT_HELPER exec --user $u -- $argv"
                }
            }
            isSimpleGuestCmd(shellCmd) -> {
                val esc = shellCmd.replace("'", "'\\''")
                "exec sh $CHROOT_HELPER sh --user $u -- '$esc'"
            }
            else -> {
                val b64 = android.util.Base64.encodeToString(
                    shellCmd.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                "exec sh $CHROOT_HELPER b64 --user $u -- $b64"
            }
        }

        val cmd = RootShell.shellRootCommand(rootInner)

        // Inline WINCH trap on outer sh (mShellPid). Keep this shell as parent (no leading exec).
        val winchCmd =
            "trap 'kill -WINCH -\$\$ 2>/dev/null; kill -WINCH 0 2>/dev/null' WINCH; $cmd"

        val envMap = HashMap(System.getenv())
        envMap["PATH"] = "/system/bin:/system/xbin:/sbin:" + (envMap["PATH"] ?: "")
        envMap["TERM"] = "xterm-256color"
        envMap["HOME"] = if (u == "root") "/root" else "/home/flux"
        envMap["LANG"] = "en_US.UTF-8"
        envMap["LC_ALL"] = "en_US.UTF-8"
        envMap["XDG_RUNTIME_DIR"] = "/tmp"
        envMap["TMPDIR"] = "/tmp"

        return arrayOf(SESSION_EXEC, "-c", winchCmd) to envMap
    }

    fun buildInteractive(
        ctx: Context,
        user: String = "flux",
        workDir: String? = null
    ): Pair<Array<String>, HashMap<String, String>> {
        ensureHelperScript(ctx)
        val u = if (user == "root") "root" else "flux"
        val shell = if (u == "root") "bash" else "auto"
        val wd = workDir?.takeIf { it.isNotBlank() }
            ?.let { " --workdir ${shellSingleQuote(it)}" }
            ?: ""
        val rootInner = "exec sh $CHROOT_HELPER login --user $u --shell $shell$wd"
        val cmd = RootShell.shellRootCommand(rootInner)
        val winchCmd =
            "trap 'kill -WINCH -\$\$ 2>/dev/null; kill -WINCH 0 2>/dev/null' WINCH; $cmd"
        val envMap = HashMap(System.getenv())
        envMap["PATH"] = "/system/bin:/system/xbin:/sbin:" + (envMap["PATH"] ?: "")
        envMap["TERM"] = "xterm-256color"
        envMap["HOME"] = if (u == "root") "/root" else "/home/flux"
        envMap["LANG"] = "en_US.UTF-8"
        envMap["LC_ALL"] = "en_US.UTF-8"
        envMap["XDG_RUNTIME_DIR"] = "/tmp"
        envMap["TMPDIR"] = "/tmp"
        return arrayOf(SESSION_EXEC, "-c", winchCmd) to envMap
    }

    /**
     * Stage [CHROOT_HELPER] from assets when missing or version stamp mismatches.
     * Uses [RootShell] su discovery (not hard-coded `/system/bin/su`).
     * Safe to call from a background thread; may briefly invoke su.
     */
    fun ensureHelperScript(ctx: Context): Boolean {
        return try {
            val existing = RootShell.capture(
                "head -n 2 $CHROOT_HELPER 2>/dev/null || true",
                timeoutMs = 4_000L
            )
            if (existing.contains(CHROOT_HELPER_VERSION)) {
                return true
            }

            val staged = RootShell.stageAsset(ctx, CHROOT_HELPER_ASSET)
                ?: run {
                    val tmp = File(ctx.cacheDir, "nativecode_chroot.sh")
                    ctx.assets.open(CHROOT_HELPER_ASSET).use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    tmp.absolutePath
                }

            var code = RootShell.executeSync(
                "cp -f '$staged' $CHROOT_HELPER && chmod 755 $CHROOT_HELPER && " +
                    "grep -q '$CHROOT_HELPER_VERSION' $CHROOT_HELPER"
            )
            if (code != 0) {
                // Last resort: base64 stream (app cannot write /data/local/tmp)
                val bytes = ctx.assets.open(CHROOT_HELPER_ASSET).use { it.readBytes() }
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                code = RootShell.executeSync(
                    "echo $b64 | base64 -d > $CHROOT_HELPER && chmod 755 $CHROOT_HELPER"
                )
            }
            if (code != 0) {
                Log.w(TAG, "ensureHelperScript root stage failed exit=$code")
                return false
            }
            Log.i(TAG, "ensureHelperScript staged $CHROOT_HELPER")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ensureHelperScript failed: ${e.message}")
            false
        }
    }

    /**
     * Guest payload safe to embed in `sh --user U -- '…'`.
     * Reject `$` / backticks / quotes / newlines / backslash — those need b64.
     * Single-quote must force b64: nested `su -c '…'` + `sh -- '…'` breaks git paths.
     */
    private fun isSimpleGuestCmd(shellCmd: String): Boolean {
        if (shellCmd.isEmpty()) return false
        for (c in shellCmd) {
            when (c) {
                '$', '`', '"', '\'', '\n', '\r', '\\' -> return false
            }
        }
        return true
    }

    private fun shellSingleQuote(s: String): String =
        ShellQuoting.singleQuote(s)

    /**
     * AI tool launch: `/tmp/launch_tool.sh TOOL` or `mkdir -p DIR && cd DIR && /tmp/launch_tool.sh TOOL`.
     * Returns (workdir or null, argv string for helper exec/b64).
     */
    private fun parseToolExec(shellCmd: String): Pair<String?, String>? {
        val t = shellCmd.trim()
        val withDir = Regex(
            """^mkdir -p (.+) && cd \1 && (/tmp/launch_tool\.sh\s+\S+)$"""
        ).matchEntire(t)
        if (withDir != null) {
            val dir = withDir.groupValues[1].trim()
            if (dir.contains('\'')) return null
            return dir to withDir.groupValues[2].trim()
        }
        val bare = Regex("""^(/tmp/launch_tool\.sh\s+\S+)$""").matchEntire(t)
        if (bare != null) {
            return null to bare.groupValues[1].trim()
        }
        return null
    }

    /**
     * Best-effort ADB mirror of WINCH wrapper under /data/local/tmp (via su).
     * Not used as sessionExec — SELinux/path; session uses SESSION_EXEC + inline trap.
     */
    fun ensureTermWrapper(): Boolean {
        val scriptPath = "/data/local/tmp/chroot_term_wrapper.sh"
        val script = """#!/system/bin/sh
# $TERM_WRAPPER_VERSION
# ADB/debug mirror only — app sessions use inline trap on /system/bin/sh
trap 'kill -WINCH -${'$'}${'$'} 2>/dev/null; kill -WINCH 0 2>/dev/null' WINCH
"${'$'}@"
exit ${'$'}?
""".trimIndent()
        return try {
            val existing = try {
                val p = Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c", "cat $scriptPath 2>/dev/null"))
                p.inputStream.bufferedReader().readText().also {
                    p.waitFor(3, TimeUnit.SECONDS)
                }
            } catch (_: Exception) {
                ""
            }
            if (existing == script) return true
            val b64 = android.util.Base64.encodeToString(script.toByteArray(), android.util.Base64.NO_WRAP)
            val p = Runtime.getRuntime().exec(
                arrayOf(
                    "/system/bin/su", "-c",
                    "echo $b64 | base64 -d > $scriptPath && chmod 755 $scriptPath"
                )
            )
            p.waitFor(5, TimeUnit.SECONDS)
            p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * AI tool launcher written to app usr/tmp; helper copies into chroot /tmp on mount.
     * Always rewrites when content differs (stale short-PATH scripts fixed).
     */
    fun ensureLauncherScript(ctx: Context): Boolean {
        val scriptPath = "${ctx.filesDir}/usr/tmp/launch_tool.sh"
        val script = """#!/bin/bash
# $LAUNCH_TOOL_VERSION
export HOME=/home/flux
export TERM="${'$'}{TERM:-xterm-256color}"
export LANG=en_US.UTF-8
export LC_ALL=en_US.UTF-8
export XDG_RUNTIME_DIR=/tmp
export TMPDIR=/tmp
export NVM_DIR="${'$'}{NVM_DIR:-${'$'}HOME/.nvm}"
export GIT_CONFIG_COUNT=1
export GIT_CONFIG_KEY_0=safe.directory
export GIT_CONFIG_VALUE_0="${'$'}PWD"

export PATH="${'$'}HOME/.local/bin:${'$'}HOME/bin:${'$'}HOME/.cargo/bin:/opt/nodejs/bin:/usr/local/bin:/usr/bin:/bin:/sbin"

# SSOT: same env as interactive setup_cli_tools
_cli_env_loaded=0
if [ -f "${'$'}HOME/.config/fluxlinux/cli-tools.env" ]; then
  . "${'$'}HOME/.config/fluxlinux/cli-tools.env"
  _cli_env_loaded=1
fi

# Keep configured node. Discover only when it is missing or will not run.
if [ -n "${'$'}{FLUX_NODE_BIN:-}" ] && [ -x "${'$'}FLUX_NODE_BIN" ] && "${'$'}FLUX_NODE_BIN" -v >/dev/null 2>&1; then
  export PATH="${'$'}{FLUX_NODE_BIN%/*}:${'$'}PATH"
else
  _n=""
  if [ -d "${'$'}NVM_DIR/versions/node" ]; then
    _n=$(ls -1d "${'$'}NVM_DIR/versions/node"/v* 2>/dev/null | sort -V | tail -1)
  fi
  if [ -n "${'$'}_n" ] && [ -x "${'$'}_n/bin/node" ] && "${'$'}_n/bin/node" -v >/dev/null 2>&1; then
    export FLUX_NODE_BIN="${'$'}_n/bin/node"
    export PATH="${'$'}_n/bin:${'$'}PATH"
  elif [ -x /opt/nodejs/bin/node ] && /opt/nodejs/bin/node -v >/dev/null 2>&1; then
    export FLUX_NODE_BIN=/opt/nodejs/bin/node
    export PATH="/opt/nodejs/bin:${'$'}PATH"
  fi
  unset _n
fi

# Free/Pro gate shims (entitlements/GuestGate.kt) must win over the real CLIs, also for nested shell tools.
export PATH="${GuestGate.GATE_BIN}:${'$'}PATH"

if [ ${'$'}# -lt 1 ]; then
  echo "launch_tool: missing command" >&2
  exit 127
fi
if ! command -v "${'$'}1" >/dev/null 2>&1; then
  echo "launch_tool: command not found: ${'$'}1 (PATH=${'$'}PATH)" >&2
  exit 127
fi
cmd="${'$'}1"
shift
case "${'$'}cmd" in
  opencode)
    exec opencode --auto "${'$'}@"
    ;;
  codex)
    # codex 0.168+ spawns a pid-managed app-server daemon and dies in PRoot (cannot read its start time).
    # --no-daemon is a TUI-only flag: tab launches (no args) get it, subcommands (login, app-server...) do not.
    if [ ${'$'}# -eq 0 ]; then exec codex --no-daemon; fi
    exec codex "${'$'}@"
    ;;
  *)
    exec "${'$'}cmd" "${'$'}@"
    ;;
esac
""".trimIndent()
        return synchronized(launcherPrepLock) {
            val generation = ++launcherPrepGeneration
            var tmp: File? = null
            try {
                val f = File(scriptPath)
                val parent = f.parentFile ?: return@synchronized false
                parent.mkdirs()
                val stamp = File(parent, ".launch_tool.gen")
                val gen = "$LAUNCH_TOOL_VERSION:${ctx.filesDir}"
                if (f.isFile && f.canExecute() && stamp.isFile && stamp.readText() == gen) {
                    return@synchronized true
                }
                val staged = File(parent, ".launch_tool.sh.$generation.tmp")
                tmp = staged
                staged.writeText(script)
                if (!staged.setExecutable(true, false)) return@synchronized false
                if (generation != launcherPrepGeneration) return@synchronized false
                if (!staged.renameTo(f) || !f.isFile || !f.canExecute()) return@synchronized false
                tmp = null
                stamp.writeText(gen)
                true
            } catch (_: Exception) {
                false
            } finally {
                tmp?.delete()
            }
        }
    }

    /** Bash-safe fallback environment for legacy callers; launch_tool.sh is preferred. */
    fun toolEnvInit(): String =
        "if [ -f \$HOME/.config/fluxlinux/cli-tools.env ]; then . \$HOME/.config/fluxlinux/cli-tools.env; fi; " +
            "export PATH=\$HOME/.local/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH; " +
            "export NVM_DIR=\${NVM_DIR:-\$HOME/.nvm}; " +
            "export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=safe.directory GIT_CONFIG_VALUE_0=\"\$PWD\""

    fun toolBinaryName(type: String): String = when (type) {
        "opencode" -> "opencode"
        "codex" -> "codex"
        "agy" -> "agy"
        "claude-code" -> "claude"
        "qwen-code" -> "qwen"
        "grok" -> "grok"
        "kiro" -> "kiro-cli"
        else -> error("Unknown Linux tool type: $type")
    }

    /**
     * Shell command for tool/shell session.
     * Both chroot and proot use /tmp/launch_tool.sh (proot via --shared-tmp → PREFIX/tmp).
     * @param workDir guest cwd; null = user home (/home/flux or /root for shell-root)
     */
    fun buildToolShellCommand(ctx: Context, type: String, workDir: String?): String {
        require(type != "shell" && type != "shell-root") {
            "Interactive shell types must use LinuxCommandBuilder.buildInteractive"
        }
        val dir = workDir?.takeIf { it.isNotBlank() } ?: "/home/flux"
        val tool = toolBinaryName(type)
        val dirQ = "'${dir.replace("'", "'\\''")}'"
        if (!ensureLauncherScript(ctx)) {
            return "echo 'launch_tool: failed to write launcher' >&2; exit 127"
        }
        return if (workDir.isNullOrBlank()) {
            "/tmp/launch_tool.sh $tool"
        } else {
            "mkdir -p $dirQ && cd $dirQ && /tmp/launch_tool.sh $tool"
        }
    }
}
