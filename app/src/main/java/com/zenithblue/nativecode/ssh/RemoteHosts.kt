package com.zenithblue.nativecode.ssh

import android.content.Context
import com.zenithblue.nativecode.terminal.ShellQuoting
import java.util.UUID

/** One saved remote. [keyFile] is a private key file name under the guest `~/.ssh` ("" = ssh defaults). No passwords are stored. */
data class SshHost(
    val id: String,
    val name: String,
    val user: String,
    val host: String,
    val port: Int = 22,
    val keyFile: String = ""
) {
    val target: String get() = "$user@$host" + if (port != 22) ":$port" else ""
}

/** Saved-hosts model, validation and the ssh command line (plan 17). Free for everyone. */
object RemoteHosts {
    private val USER_RE = Regex("""[A-Za-z_][A-Za-z0-9_.\-]{0,31}""")
    private val HOST_RE = Regex("""[A-Za-z0-9][A-Za-z0-9.\-_]{0,252}""")
    private val KEY_RE = Regex("""[A-Za-z0-9][A-Za-z0-9_.\-]{0,63}""")
    private const val PREF_KEY = "ssh_remote_hosts"

    /** Null when valid, else the message for the form. A host or user can never start with `-` (no option injection). */
    fun validate(h: SshHost): String? = when {
        h.name.isBlank() || h.name.length > 40 || h.name.any { it < ' ' } -> "Name is required (40 characters at most)."
        !USER_RE.matches(h.user) -> "User must be a Linux user name."
        !isHost(h.host) -> "Host must be a name or an IP address."
        h.port !in 1..65535 -> "Port must be 1-65535."
        h.keyFile.isNotEmpty() && (!KEY_RE.matches(h.keyFile) || h.keyFile.endsWith(".pub")) -> "Pick a private key file from ~/.ssh."
        else -> null
    }

    fun isHost(s: String): Boolean = HOST_RE.matches(s) || isIpv6(s)

    private fun isIpv6(s: String) = s.contains(':') && s.length <= 45 && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }

    /** `exec` line for the guest shell: runs ssh, and on a non-zero exit waits for Enter so the error stays on screen. */
    fun command(h: SshHost): String {
        val key = if (h.keyFile.isEmpty()) "" else " -i " + ShellQuoting.singleQuote("${SshModel.USER_SSH_DIR}/${h.keyFile}") + " -o IdentitiesOnly=yes"
        val dest = ShellQuoting.singleQuote("${h.user}@${h.host}")
        return "ssh -p ${h.port}$key $dest; rc=\$?; if [ \$rc -ne 0 ]; then echo; echo \"ssh exited with status \$rc. Press Enter to close.\"; read _x; fi"
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    /** One host per line, tab separated; validation keeps tabs and newlines out of every field. */
    fun encode(list: List<SshHost>): String = list.joinToString("\n") { listOf(it.id, it.name, it.user, it.host, it.port, it.keyFile).joinToString("\t") }

    fun decode(text: String): List<SshHost> = text.lineSequence().mapNotNull { l ->
        val f = l.split('\t')
        if (f.size < 6) return@mapNotNull null
        val h = SshHost(f[0], f[1], f[2], f[3], f[4].toIntOrNull() ?: return@mapNotNull null, f[5])
        h.takeIf { validate(it) == null && it.id.isNotBlank() }
    }.toList()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nativecode_prefs", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<SshHost> = decode(prefs(ctx).getString(PREF_KEY, "") ?: "")

    fun save(ctx: Context, list: List<SshHost>) { prefs(ctx).edit().putString(PREF_KEY, encode(list)).apply() }

    /** Inserts or replaces by id. */
    fun upsert(list: List<SshHost>, h: SshHost): List<SshHost> =
        if (list.any { it.id == h.id }) list.map { if (it.id == h.id) h else it } else list + h
}
