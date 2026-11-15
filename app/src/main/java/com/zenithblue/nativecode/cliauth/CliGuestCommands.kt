package com.zenithblue.nativecode.cliauth

import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/**
 * Pure guest shell snippets for AI CLI auth. No Android UI.
 * Always run as flux with NVM/PATH bootstrap matching setup_cli_tools.sh.
 * Login state is NOT read here: [CliCredentialProbe] reads the auth files from the host.
 */
object CliGuestCommands {

    const val AUTH_TIMEOUT_MS = 12 * 60 * 1000L
    const val AUTH_ENV_REL = ".config/fluxlinux/cli-auth.env"

    /** Non-interactive env hints (merged into ProcessBuilder). */
    fun cliEnv(): Map<String, String> = mapOf(
        "NO_COLOR" to "1",
        "CI" to "1",
        "TERM" to "dumb",
        tr(R.string.cliauth_browser) to "echo",
        "GH_BROWSER" to "echo"
    )

    /**
     * PATH + nvm + managed env so npm-global / curl bins resolve.
     *
     * Snippets execute under NativeCode's non-interactive Bash contract.
     * Host builders quote the payload so guest variables are not expanded early.
     * Absolute /home/flux paths (flux is fixed guest user).
     */
    fun pathBootstrap(): String = listOf(
        "export HOME=/home/flux",
        "export USER=flux",
        "export PATH=/home/flux/.local/bin:/home/flux/bin:/home/flux/.cargo/bin:/opt/nodejs/bin:/usr/local/bin:/usr/bin:/bin:/sbin",
        "export NVM_DIR=/home/flux/.nvm",
        // find: no v* glob. No double-quotes: chroot nests su -c "… -c \"cmd\""
        "if [ -d /home/flux/.nvm/versions/node ]; then " +
            "_n=\$(find /home/flux/.nvm/versions/node -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -1); " +
            "case \$_n in /home/flux/.nvm/*) PATH=\$_n/bin:\$PATH;; esac; unset _n; fi",
        "[ -f /home/flux/.config/fluxlinux/cli-tools.env ] && . /home/flux/.config/fluxlinux/cli-tools.env 2>/dev/null || true",
        "[ -f /home/flux/.config/fluxlinux/cli-auth.env ] && . /home/flux/.config/fluxlinux/cli-auth.env 2>/dev/null || true"
    ).joinToString("; ")

    fun wrap(body: String): String = "${pathBootstrap()}; $body"

    /**
     * Detect bin via command -v + absolute path probes (curl installers land in ~/.local/bin).
     */
    fun detectBin(bin: String, altBins: List<String> = emptyList()): String {
        val all = (listOf(bin) + altBins).joinToString(" ")
        return wrap(
            """
            for c in $all; do
              if command -v ${'$'}c >/dev/null 2>&1; then
                echo BIN_OK ${'$'}c
                ${'$'}c --version 2>/dev/null | head -1 || ${'$'}c -v 2>/dev/null | head -1 || echo ok
                exit 0
              fi
              for d in /home/flux/.local/bin /home/flux/bin /usr/local/bin /usr/bin; do
                if [ -x ${'$'}d/${'$'}c ]; then
                  echo BIN_OK ${'$'}d/${'$'}c
                  ${'$'}d/${'$'}c --version 2>/dev/null | head -1 || echo ok
                  exit 0
                fi
              done
            done
            echo BIN_MISSING
            """.trimIndent().replace('\n', ' ')
        )
    }

    // ── login commands (the CLI's own flow; BROWSER=echo prints the URL for the app to open) ──

    fun loginCodexDevice(): String = wrap(
        "export BROWSER=echo; codex login --device-auth 2>&1"
    )

    /** URL is printed; localhost callback works on-device, a pasted code goes to stdin. */
    fun loginClaude(): String = wrap(
        "export BROWSER=echo; claude auth login 2>&1"
    )

    /**
     * Prints a Google URL (redirect antigravity.google/oauth-callback shows the code), then reads
     * the code or landing URL from stdin. No D-Bus in the guest, so the token goes to
     * ~/.gemini/antigravity-cli/antigravity-oauth-token.
     */
    fun loginAgy(): String = wrap("agy acp --login 2>&1")

    fun loginKiro(): String = wrap(
        "export BROWSER=echo; if command -v kiro-cli >/dev/null 2>&1; then " +
            "kiro-cli login --use-device-flow 2>&1; else kiro login --use-device-flow 2>&1; fi"
    )

    fun loginGrok(): String = wrap(
        "export BROWSER=echo; grok login 2>&1"
    )

    // ── logout (only CLIs whose own logout is non-interactive; others: file removal) ──

    fun logoutCodex(): String = wrap("codex logout 2>&1 || true")

    fun logoutKiro(): String = wrap(
        "kiro-cli logout 2>&1 || kiro logout 2>&1 || true"
    )
}
