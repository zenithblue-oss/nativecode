package com.zenithblue.nativecode.ssh

import android.content.Context
import android.system.Os
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import java.io.File
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList

enum class SshState { STOPPED, STARTING, RUNNING, ERROR }

data class SshStatus(val state: SshState, val address: String? = null, val message: String? = null)

/**
 * Runs `sshd` as the guest user `flux` inside PRoot (plan 17): key auth only, the Wi-Fi/hotspot/ethernet addresses (IPv4 and IPv6), port 8022.
 * OpenSSH itself is bundled in the Linux runtime update pack (RuntimeUpdatePack); nothing is installed here.
 * Files are written straight into the guest home (Kotlin I/O, no guest scripts); only `ssh-keygen`
 * and `sshd` itself run in the guest. Private keys are never read, logged or copied by this class.
 * [start], [stop] and the install/keygen calls block or spawn threads: call them off the main thread.
 */
object SshServerController {
    private const val METHOD = "proot"
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val logLines = ArrayList<String>()

    @Volatile var status: SshStatus = SshStatus(SshState.STOPPED)
        private set
    @Volatile private var proc: Process? = null
    @Volatile private var stopping = false

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private fun set(s: SshStatus) { status = s; listeners.forEach { runCatching { it() } } }

    fun log(): List<String> = synchronized(logLines) { SshModel.tail(logLines) }

    fun keepRunning(ctx: Context): Boolean = prefs(ctx).getBoolean("ssh_keep_running", false)
    fun setKeepRunning(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("ssh_keep_running", on).apply() }
    fun warned(ctx: Context): Boolean = prefs(ctx).getBoolean("ssh_warned", false)
    fun setWarned(ctx: Context) { prefs(ctx).edit().putBoolean("ssh_warned", true).apply() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nativecode_prefs", Context.MODE_PRIVATE)

    private fun rootfs(ctx: Context) = ProjectPathResolver.prootRootfsDir(ctx)
    private fun home(ctx: Context) = ProjectPathResolver.guestHomeDir(ctx, METHOD)

    /** Guest path of [name] when present in the rootfs, else null. */
    fun findBinary(ctx: Context, name: String): String? =
        listOf("/usr/sbin", "/usr/bin", "/sbin", "/bin", "/usr/local/bin", "/usr/local/sbin")
            .map { "$it/$name" }.firstOrNull { File(rootfs(ctx), it.removePrefix("/")).exists() }

    fun isInstalled(ctx: Context): Boolean =
        findBinary(ctx, "sshd") != null && findBinary(ctx, "ssh-keygen") != null && findBinary(ctx, "ssh") != null

    /** LAN addresses (IPv4 and IPv6) of this device: Wi-Fi, else hotspot, else ethernet. Empty when there are none. */
    fun lanAddresses(): List<String> = try {
        SshModel.pickAddresses(NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
            .flatMap { ni -> ni.inetAddresses.toList().map { ni.name to (it.hostAddress ?: "").substringBefore('%') } })
    } catch (_: Exception) { emptyList() }

    /** The address to show: the first of [lanAddresses], or null. */
    fun lanAddress(): String? = lanAddresses().firstOrNull()

    private fun guest(ctx: Context, cmd: String, user: String = "flux"): Pair<Int, String> {
        val (args, env) = LinuxCommandBuilder.build(ctx, cmd, user, method = METHOD)
        return ShellCommandRunner.runCaptureExit(ctx, args, env)
    }

    private fun mkdir700(f: File) { f.mkdirs(); Os.chmod(f.absolutePath, 448) }
    private fun chmod600(f: File) { Os.chmod(f.absolutePath, 384) }

    private fun hostKeyFile(ctx: Context) = File(home(ctx), SshModel.HOST_KEY.removePrefix(SshModel.GUEST_HOME + "/"))

    /** SHA256 fingerprint of the host key, or null before the first start. */
    fun hostFingerprint(ctx: Context): String? =
        runCatching { SshModel.parsePublicKey(File(hostKeyFile(ctx).path + ".pub").readText())?.fingerprint }.getOrNull()

    fun authorizedKeysFile(ctx: Context) = File(home(ctx), ".ssh/authorized_keys")

    fun authorizedKeys(ctx: Context): List<PubKey> =
        runCatching { SshModel.parseAuthorizedKeys(authorizedKeysFile(ctx).readText()) }.getOrDefault(emptyList())

    /** Adds a pasted or imported public key. Returns null on success, else why it was refused. */
    fun addAuthorizedKey(ctx: Context, text: String): String? {
        val key = SshModel.parsePublicKey(text)
            ?: return "That is not one OpenSSH public key line (ssh-ed25519 AAAA... or similar). Private keys are refused."
        val f = authorizedKeysFile(ctx)
        return try {
            mkdir700(f.parentFile!!)
            val next = SshModel.addAuthorizedKey(if (f.exists()) f.readText() else "", key)
            f.writeText(next)
            chmod600(f)
            null
        } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
    }

    fun removeAuthorizedKey(ctx: Context, fingerprint: String): Boolean = try {
        val f = authorizedKeysFile(ctx)
        if (f.exists()) { f.writeText(SshModel.removeAuthorizedKey(f.readText(), fingerprint)); chmod600(f) }
        true
    } catch (_: Exception) { false }

    /** Starts sshd on a worker thread; progress arrives through [addListener]. No-op while starting or running. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(this) {
            if (status.state == SshState.STARTING || status.state == SshState.RUNNING) return
            set(SshStatus(SshState.STARTING))
        }
        stopping = false
        synchronized(logLines) { logLines.clear() }
        Thread {
            val fail = { m: String -> set(SshStatus(SshState.ERROR, message = m)) }
            try {
                val ips = lanAddresses()
                val ip = ips.firstOrNull() ?: return@Thread fail("No Wi-Fi address. Connect to Wi-Fi or turn on the hotspot, then try again.")
                val sshd = findBinary(app, "sshd") ?: return@Thread fail("OpenSSH is not installed.")
                val keygen = findBinary(app, "ssh-keygen") ?: return@Thread fail("OpenSSH is not installed.")
                runCatching {
                    val g = File(rootfs(app), "etc/group")
                    SshModel.renameTtyGroup(g.readText())?.let { g.writeText(it) }
                }
                val h = home(app)
                mkdir700(File(h, ".config")); mkdir700(File(h, ".config/nativecode")); mkdir700(File(h, ".config/nativecode/ssh")); mkdir700(File(h, ".ssh"))
                val key = hostKeyFile(app)
                if (!key.exists()) {
                    val (code, out) = guest(app, "$keygen -q -t ed25519 -N '' -C nativecode-host -f '${SshModel.HOST_KEY}' 2>&1")
                    if (code != 0 || !key.exists()) return@Thread fail("Host key generation failed: " + out.lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" | "))
                }
                chmod600(key)
                val cfg = File(h, SshModel.SSHD_CONFIG.removePrefix(SshModel.GUEST_HOME + "/"))
                cfg.writeText(SshModel.sshdConfig(ips))
                chmod600(cfg)
                authorizedKeysFile(app).takeIf { it.exists() }?.let { chmod600(it) }

                val (args, env) = LinuxCommandBuilder.build(app, SshModel.sshdCommand().replace("/usr/sbin/sshd", sshd), "flux", method = METHOD)
                val p = ShellCommandRunner.startPiped(app, args, env, mergeStderr = true)
                proc = p
                p.outputStream.close()
                val reader = Thread {
                    try {
                        p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                            synchronized(logLines) { logLines.add(line.take(300)); if (logLines.size > 200) logLines.removeAt(0) }
                            if (SshModel.isListeningLine(line) && status.state == SshState.STARTING) set(SshStatus(SshState.RUNNING, ip))
                            else listeners.forEach { runCatching { it() } }
                        }
                    } catch (_: Exception) { }
                }.apply { isDaemon = true; start() }
                // Watch for the ready line; sshd that exits early is an error with its last lines.
                val deadline = System.currentTimeMillis() + 15_000
                while (status.state == SshState.STARTING && System.currentTimeMillis() < deadline && p.isAlive) Thread.sleep(150)
                if (status.state == SshState.STARTING) {
                    if (p.isAlive) { p.destroyForcibly(); fail("sshd did not report that it is listening.") }
                }
                val exit = p.waitFor()
                reader.join(1500)
                proc = null
                if (stopping) set(SshStatus(SshState.STOPPED))
                else if (status.state != SshState.ERROR) set(SshStatus(SshState.ERROR, message = "sshd exited (status $exit). " + log().takeLast(2).joinToString(" | ")))
            } catch (e: Exception) {
                proc = null
                set(SshStatus(SshState.ERROR, message = e.message ?: e.javaClass.simpleName))
            } finally {
                if (status.state != SshState.RUNNING && status.state != SshState.STARTING) SshService.stop(app)
            }
        }.apply { isDaemon = true; name = "sshd-launcher"; start() }
        SshService.start(app)
    }

    fun stop(ctx: Context) {
        stopping = true
        val p = proc
        killSshd(ctx)
        if (p == null) { set(SshStatus(SshState.STOPPED)); SshService.stop(ctx.applicationContext); return }
        p.destroy()
        Thread { Thread.sleep(2000); if (p.isAlive) p.destroyForcibly() }.apply { isDaemon = true; start() }
    }

    /**
     * Ends sshd and every login session under it. Killing the PRoot wrapper alone leaves sshd listening, so the
     * pid file sshd wrote (guest pids are host pids under PRoot) is used and its descendants are killed too.
     */
    private fun killSshd(ctx: Context) {
        val pid = runCatching { File(home(ctx), ".config/nativecode/ssh/sshd.pid").readText().trim().toInt() }.getOrNull() ?: return
        val isSshd = runCatching { File("/proc/$pid/cmdline").readText().contains("sshd") }.getOrDefault(false)
        if (!isSshd) return
        val victims = SshModel.descendants(pid, parentMap()).reversed() + pid
        for (v in victims) runCatching { Os.kill(v, android.system.OsConstants.SIGKILL) }
    }

    /** pid -> ppid of every process this app may read in /proc. */
    private fun parentMap(): Map<Int, Int> = File("/proc").list().orEmpty().mapNotNull { n ->
        val pid = n.toIntOrNull() ?: return@mapNotNull null
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return@mapNotNull null
        SshModel.parentOf(stat)?.let { pid to it }
    }.toMap()

    // region client keys (the guest ~/.ssh)

    /** Private key files under the guest ~/.ssh that have a `.pub` beside them. */
    fun clientKeys(ctx: Context): List<String> =
        File(home(ctx), ".ssh").listFiles()?.filter { it.isFile && it.name.endsWith(".pub") && File(it.parentFile, it.name.removeSuffix(".pub")).isFile }
            ?.map { it.name.removeSuffix(".pub") }?.sorted() ?: emptyList()

    fun publicKeyText(ctx: Context, keyFile: String): String? =
        if (!Regex("[A-Za-z0-9][A-Za-z0-9_.\\-]{0,63}").matches(keyFile)) null
        else runCatching { File(home(ctx), ".ssh/$keyFile.pub").readText().trim() }.getOrNull()

    /** Generates an ed25519 keypair `~/.ssh/<name>` (no passphrase, mode 600). Returns null on success. */
    fun generateClientKey(ctx: Context, name: String): String? {
        if (!Regex("[A-Za-z0-9][A-Za-z0-9_.\\-]{0,63}").matches(name) || name.endsWith(".pub")) return "Key name: letters, digits, . _ - only."
        val keygen = findBinary(ctx, "ssh-keygen") ?: return "OpenSSH is not installed."
        val dir = File(home(ctx), ".ssh")
        if (File(dir, name).exists()) return "A key called $name already exists."
        return try {
            mkdir700(dir)
            val (code, out) = guest(ctx, "$keygen -q -t ed25519 -N '' -C 'flux@nativecode' -f '${SshModel.USER_SSH_DIR}/$name' 2>&1")
            if (code == 0 && File(dir, name).exists()) { chmod600(File(dir, name)); null }
            else "ssh-keygen failed (exit $code): " + out.lines().filter { it.isNotBlank() }.takeLast(2).joinToString(" | ")
        } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
    }

    // endregion
}
