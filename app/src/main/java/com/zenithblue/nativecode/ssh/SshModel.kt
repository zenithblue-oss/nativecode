package com.zenithblue.nativecode.ssh

import com.zenithblue.nativecode.terminal.ShellQuoting
import java.security.MessageDigest
import java.util.Base64

/** A validated OpenSSH public key line (no options): `type base64 [comment]`. */
data class PubKey(val type: String, val blob: String, val comment: String) {
    /** `SHA256:<base64 without padding>` as printed by `ssh-keygen -l`. */
    val fingerprint: String get() = fingerprintOf(blob)
    val line: String get() = if (comment.isEmpty()) "$type $blob" else "$type $blob $comment"

    companion object {
        fun fingerprintOf(blob: String): String {
            val raw = Base64.getDecoder().decode(blob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(raw))
        }
    }
}

/** Pure logic for the SSH page: public-key parsing, sshd_config, address choice, command lines (plan 17). */
object SshModel {
    const val PORT = 8022
    const val USER = "flux"
    const val GUEST_HOME = "/home/flux"
    const val CONFIG_DIR = "$GUEST_HOME/.config/nativecode"
    const val HOST_KEY_DIR = "$CONFIG_DIR/ssh"
    const val HOST_KEY = "$HOST_KEY_DIR/ssh_host_ed25519_key"
    const val SSHD_CONFIG = "$CONFIG_DIR/sshd_config"
    const val USER_SSH_DIR = "$GUEST_HOME/.ssh"
    const val AUTHORIZED_KEYS = "$USER_SSH_DIR/authorized_keys"
    const val MAX_KEY_LINE = 8192

    private val KEY_TYPES = setOf(
        "ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "ssh-rsa",
        "sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com"
    )

    /**
     * Parses exactly one public key. Rejects authorized_keys option prefixes (`command=`, `from=`...), several lines,
     * private keys, bad base64 and a blob whose embedded type differs from the declared one.
     */
    fun parsePublicKey(input: String): PubKey? {
        val text = input.trim()
        if (text.isEmpty() || text.length > MAX_KEY_LINE || text.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        val parts = text.split(Regex("[ \t]+"), limit = 3)
        if (parts.size < 2 || parts[0] !in KEY_TYPES) return null
        val blob = parts[1]
        val raw = try { Base64.getDecoder().decode(blob) } catch (_: IllegalArgumentException) { return null }
        if (raw.size < 8) return null
        val n = ((raw[0].toInt() and 255) shl 24) or ((raw[1].toInt() and 255) shl 16) or ((raw[2].toInt() and 255) shl 8) or (raw[3].toInt() and 255)
        if (n != parts[0].length || raw.size < 4 + n || String(raw, 4, n, Charsets.US_ASCII) != parts[0]) return null
        val comment = parts.getOrNull(2)?.trim().orEmpty().filter { it >= ' ' }.take(120)
        return PubKey(parts[0], blob, comment)
    }

    /** Valid key lines of an authorized_keys file; comments, blanks and option-prefixed lines are skipped. */
    fun parseAuthorizedKeys(file: String): List<PubKey> = file.lineSequence().mapNotNull { parsePublicKey(it) }.toList()

    /** File text after adding [key] (unchanged when its blob is already there). */
    fun addAuthorizedKey(file: String, key: PubKey): String {
        if (parseAuthorizedKeys(file).any { it.blob == key.blob }) return file
        val base = if (file.isEmpty() || file.endsWith("\n")) file else file + "\n"
        return base + key.line + "\n"
    }

    /** File text with every line holding [fingerprint] removed; other lines are kept byte for byte. */
    fun removeAuthorizedKey(file: String, fingerprint: String): String =
        file.lineSequence().filter { l -> parsePublicKey(l)?.fingerprint != fingerprint }.joinToString("\n")
            .let { if (it.isNotEmpty() && !it.endsWith("\n")) it + "\n" else it }

    /** sshd_config: key auth only, one user, the LAN addresses (IPv4 and IPv6), no forwarding extras. */
    fun sshdConfig(listenAddress: String, port: Int = PORT): String = sshdConfig(listOf(listenAddress), port)

    fun sshdConfig(listenAddresses: List<String>, port: Int = PORT): String = """
        # NativeCode SSH server. Rewritten on every start.
        Port $port
        ${listenAddresses.joinToString("\n        ") { "ListenAddress $it" }}
        HostKey $HOST_KEY
        PidFile $HOST_KEY_DIR/sshd.pid
        AuthorizedKeysFile .ssh/authorized_keys
        AllowUsers $USER
        PermitRootLogin no
        PubkeyAuthentication yes
        PasswordAuthentication no
        KbdInteractiveAuthentication no
        PermitEmptyPasswords no
        X11Forwarding no
        AllowAgentForwarding no
        PrintMotd no
        LogLevel INFO
        Subsystem sftp internal-sftp
    """.trimIndent() + "\n"

    /**
     * sshd allocates a pty for interactive logins and chowns it to the `tty` group, which the PRoot guest user may not do
     * (`chown(/dev/pts/N, uid, 5) failed`, the login then dies). Without a `tty` group sshd keeps the pty's own group and
     * skips the chown. Returns the group file with the `tty` entry renamed to `tty_` (same gid), or null when there is none.
     */
    fun renameTtyGroup(group: String): String? {
        if (!group.lineSequence().any { it.startsWith("tty:") }) return null
        return group.lineSequence().joinToString("\n") { if (it.startsWith("tty:")) "tty_:" + it.removePrefix("tty:") else it }
    }

    fun sshdCommand(): String = "exec /usr/sbin/sshd -D -e -f ${ShellQuoting.singleQuote(SSHD_CONFIG)}"

    /** IPv6 literals are bracketed so the host part reads unambiguously next to the port. */
    fun connectCommand(ip: String, port: Int = PORT): String = "ssh -p $port $USER@${if (ip.contains(':')) "[$ip]" else ip}"

    /** Valid IPv4 literal only (the ListenAddress is written into a config file). */
    fun isIpv4(s: String): Boolean = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(s)
        ?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true

    /** Valid IPv6 literal only (it is written into a config file); zone ids and the like are rejected. */
    fun isIpv6(s: String): Boolean = s.count { it == ':' } >= 2 && Regex("""[0-9a-fA-F:]+""").matches(s)

    /** Link-local, loopback, unspecified and multicast IPv6 addresses are never offered. */
    private fun usableIpv6(ip: String): Boolean {
        val l = ip.lowercase()
        return isIpv6(l) && l != "::" && l != "::1" && !l.startsWith("fe8") && !l.startsWith("fe9") &&
            !l.startsWith("fea") && !l.startsWith("feb") && !l.startsWith("ff")
    }

    private fun usable(ip: String): Boolean =
        if (ip.contains(':')) usableIpv6(ip) else isIpv4(ip) && !ip.startsWith("127.") && !ip.startsWith("169.254.")

    /** Wi-Fi first, then hotspot, then ethernet; 9 = never (cellular, VPN, loopback). `v4-wlan0` is the 464XLAT (clat) side of wlan0. */
    private fun rank(rawName: String): Int {
        val name = rawName.removePrefix("v4-")
        return when {
            name.startsWith("wlan") -> 0
            name.startsWith("swlan") || name.startsWith("ap") -> 1
            name.startsWith("eth") || name.startsWith("usb") -> 2
            else -> 9
        }
    }

    /**
     * Every address sshd should listen on, from (interface name, address) pairs: all usable IPv4 and IPv6 addresses of the
     * best interface class present (Wi-Fi, else hotspot, else ethernet). Reachable IPv4 first, then IPv6, then the clat IPv4. Cellular, VPN, loopback and
     * link-local addresses are never offered, so an IPv6-only Wi-Fi (clat `v4-wlan0` plus global IPv6) works too.
     */
    fun pickAddresses(candidates: List<Pair<String, String>>): List<String> {
        val ok = candidates.filter { (n, ip) -> rank(n) < 9 && usable(ip) }
        val best = ok.minOfOrNull { rank(it.first) } ?: return emptyList()
        return ok.filter { rank(it.first) == best }.map { it.second }.distinct()
            // 192.0.0.0/29 is the clat (464XLAT) address: local to the phone, not reachable from other devices, so it goes last.
            .sortedBy { if (it.contains(':')) 1 else if (it.startsWith("192.0.0.")) 2 else 0 }
    }

    /** The one address shown to the user: the first of [pickAddresses] (a reachable IPv4, else IPv6, else the clat IPv4). */
    fun pickAddress(candidates: List<Pair<String, String>>): String? = pickAddresses(candidates).firstOrNull()

    /** Parent pid from a /proc/<pid>/stat line (the command is in parentheses and may hold spaces). */
    fun parentOf(stat: String): Int? {
        val i = stat.lastIndexOf(')')
        if (i < 0) return null
        return stat.substring(i + 1).trim().split(' ').getOrNull(1)?.toIntOrNull()
    }

    /** Every descendant of [root], parents before children. */
    fun descendants(root: Int, parents: Map<Int, Int>): List<Int> {
        val out = ArrayList<Int>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for ((pid, ppid) in parents) if (ppid == cur && pid != root && pid !in out) { out.add(pid); queue.addLast(pid) }
        }
        return out
    }

    /** Last non-empty lines of sshd output kept for the page; a log line never contains key material. */
    fun tail(lines: List<String>, n: Int = 40): List<String> = lines.filter { it.isNotBlank() }.takeLast(n)

    /** `Server listening on <addr> port <port>.` printed by sshd -e once it is bound. */
    fun isListeningLine(line: String): Boolean = line.contains("Server listening on")
}
