package com.zenithblue.nativecode.agent.chat.core

/**
 * One classifier for every runtime (opencode, claude, codex, ACP agy/grok). A runtime reports a [Failure] in its own words
 * (claude `Session ended unexpectedly`, codex `-32601`, opencode `HTTP 404`, an ACP initialize error, the stderr/stdout
 * tail of an exited process); [refine] maps it to the failure class the error card shows. Pure, no Android.
 *
 * Version-related failures (unknown flag, missing app-server method, protocol mismatch, missing opencode endpoint) become
 * [FailureClass.PROTOCOL_ERROR] with [versionRelated] true; only then does the UI run `<cli> --version` and compare it with
 * the per-CLI minimum (see [versionTooOld]). Nothing here runs when the chat works.
 */
object ChatFailures {
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val GATE = rx("""NativeCode:""")
    private val MISSING = rx("""command not found|executable file not found|not installed|spawn \S+ ENOENT|exec: .{0,60}not found|: not found\b""")
    private val SIGNED_OUT = rx(
        """not logged in|not signed in|please run /login|please (log|sign) ?in|login required|authentication[_ ]failed|""" +
            """auth(entication)? required|invalid api key|no credentials|\b(http|status|code)[ :=]*401\b|unauthorized""",
    )
    private val LIMIT = rx("""usage limit|session limit|rate[ _-]?limit|quota|limit reached|too many requests|\b(http|status|code)[ :=]*429\b""")
    private val VERSION = rx(
        """unknown (option|flag|argument|command)|unrecognized (option|argument|arguments|flag)|unexpected argument|invalid (option|flag)|""" +
            """method not found|-32601|unknown variant|missing field|protocol ?version|unsupported (protocol|version)|""" +
            """\bneeds \d+(\.\d+)+|requires (version )?\d+(\.\d+)+|\b(http|status)[ :=]*404\b|404 not found""",
    )
    private val PROTOCOL = rx("""-32\d{3}|invalid request|invalid params|parse error|json-?rpc|protocol error|malformed|unexpected (frame|response|token)|serde|\b(http|status)[ :=]*4\d\d\b""")
    private val NETWORK = rx(
        """network|ECONNREFUSED|ECONNRESET|ETIMEDOUT|ENOTFOUND|EAI_AGAIN|getaddrinfo|timed out|connection (refused|reset|closed|lost)|""" +
            """unreachable|fetch failed|socket hang up|offline|lost the connection|\b(http|status)[ :=]*5\d\d\b|overloaded""",
    )
    private val CRASH = rx(
        """session ended unexpectedly|\bexited\b|did not start|did not answer|failed to start|not running|segmentation|sigsegv|sigkill|""" +
            """\bkilled\b|core dumped|panic|fatal error|uncaught""",
    )

    /** Classes that already say exactly what went wrong; the text rules never override them. */
    private val SPECIFIC = setOf(FailureClass.GATE_DENIED, FailureClass.USAGE_LIMIT, FailureClass.PERMISSION_ERROR, FailureClass.VERSION_OLD, FailureClass.CLI_MISSING)

    /**
     * [f] with a class from the card list: not installed, not signed in, version, Pro required, session limit, crashed,
     * protocol, network. [raw] = extra text (the process's stderr/stdout tail, the session start error). Message and details are kept.
     */
    fun refine(f: Failure, raw: String? = null): Failure {
        if (f.cls in SPECIFIC) return f
        val text = listOfNotNull(f.message, f.details, raw).joinToString("\n")
        val cls = when {
            GATE.containsMatchIn(text) -> FailureClass.GATE_DENIED
            MISSING.containsMatchIn(text) -> FailureClass.CLI_MISSING
            SIGNED_OUT.containsMatchIn(text) -> FailureClass.PERMISSION_ERROR
            LIMIT.containsMatchIn(text) -> FailureClass.USAGE_LIMIT
            VERSION.containsMatchIn(text) || PROTOCOL.containsMatchIn(text) -> FailureClass.PROTOCOL_ERROR
            NETWORK.containsMatchIn(text) -> FailureClass.NETWORK_ERROR
            CRASH.containsMatchIn(text) -> FailureClass.CRASHED
            else -> f.cls
        }
        // The raw tail is the only place the reason may live: keep it as the details when there were none.
        val details = f.details ?: raw?.takeLast(4096)?.takeIf { it.isNotBlank() && cls != f.cls }
        return if (cls == f.cls && details == f.details) f else f.copy(cls = cls, details = details)
    }

    /** True when the text points at a CLI that is too old (or too new) for the wire protocol; the UI then checks `--version`. */
    fun versionRelated(f: Failure): Boolean =
        f.cls == FailureClass.VERSION_OLD ||
            (f.cls == FailureClass.PROTOCOL_ERROR && VERSION.containsMatchIn(listOfNotNull(f.message, f.details).joinToString("\n")))

    /** First line of [message], at most [max] chars, for the card body. */
    fun reason(message: String, max: Int = 160): String {
        val line = message.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return if (line.length <= max) line else line.take(max - 1).trimEnd() + "…"
    }

    // ---------------- versions ----------------

    /** First `1.2.3` / `v1.2` / `0.167.1-beta` number in `<cli> --version` output (`2.1.323 (Claude Code)`, `codex-cli 0.167.1`). */
    fun parseVersion(output: String): String? = Regex("""\bv?(\d+(?:\.\d+){1,3})""").find(output)?.groupValues?.get(1)

    /** Numeric compare of dotted versions; missing parts count as 0 (`2.1` == `2.1.0`). */
    fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** [installed] below [min]. Unknown installed version = not too old (never block on a guess). */
    fun versionTooOld(installed: String?, min: String?): Boolean =
        installed != null && min != null && compareVersions(installed, min) < 0
}
