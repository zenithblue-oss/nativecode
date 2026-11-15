package com.zenithblue.nativecode.ssh

import com.zenithblue.nativecode.entitlements.FeatureGate
import com.zenithblue.nativecode.entitlements.GateResult
import com.zenithblue.nativecode.entitlements.ProFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SshTest {
    /** A syntactically valid ssh-ed25519 blob: string "ssh-ed25519" + 32-byte string. */
    private fun ed25519Blob(fill: Int = 7): String {
        val type = "ssh-ed25519".toByteArray()
        val body = ByteArray(32) { fill.toByte() }
        val b = java.io.ByteArrayOutputStream()
        fun str(x: ByteArray) { b.write(byteArrayOf(0, 0, 0, x.size.toByte())); b.write(x) }
        str(type); str(body)
        return Base64.getEncoder().encodeToString(b.toByteArray())
    }
    private fun key(fill: Int = 7, comment: String = "me@pc") = "ssh-ed25519 ${ed25519Blob(fill)} $comment"

    // region public keys

    @Test fun parsesValidKey() {
        val k = SshModel.parsePublicKey(key())!!
        assertEquals("ssh-ed25519", k.type)
        assertEquals("me@pc", k.comment)
        assertTrue(k.fingerprint.startsWith("SHA256:"))
        assertFalse(k.fingerprint.endsWith("="))
    }

    @Test fun refusesOptionsPrivateKeysAndJunk() {
        assertNull(SshModel.parsePublicKey("command=\"rm -rf /\" " + key()))
        assertNull(SshModel.parsePublicKey("-----BEGIN OPENSSH PRIVATE KEY-----"))
        assertNull(SshModel.parsePublicKey(""))
        assertNull(SshModel.parsePublicKey("ssh-ed25519"))
        assertNull(SshModel.parsePublicKey("ssh-ed25519 !!!notbase64!!! x"))
        assertNull(SshModel.parsePublicKey(key() + "\n" + key(8)))               // two keys at once
        assertNull(SshModel.parsePublicKey("ssh-rsa " + ed25519Blob()))          // declared type differs from the blob
    }

    @Test fun addIsIdempotentAndKeepsOtherLines() {
        val k = SshModel.parsePublicKey(key(1))!!
        val file = "# mine\nfrom=\"10.0.0.1\" ssh-ed25519 AAAA other\n"
        val once = SshModel.addAuthorizedKey(file, k)
        assertTrue(once.startsWith(file))
        assertEquals(once, SshModel.addAuthorizedKey(once, k))
        assertEquals(1, SshModel.parseAuthorizedKeys(once).size)
    }

    @Test fun removeDropsOnlyThatKey() {
        val a = SshModel.parsePublicKey(key(1, "a"))!!
        val b = SshModel.parsePublicKey(key(2, "b"))!!
        val file = SshModel.addAuthorizedKey(SshModel.addAuthorizedKey("# note\n", a), b)
        val out = SshModel.removeAuthorizedKey(file, a.fingerprint)
        assertEquals(listOf(b.blob), SshModel.parseAuthorizedKeys(out).map { it.blob })
        assertTrue(out.contains("# note"))
        assertEquals("", SshModel.removeAuthorizedKey(a.line + "\n", a.fingerprint))
    }

    // endregion

    // region config and addresses

    @Test fun configIsKeyOnlyAndBoundToOneAddress() {
        val c = SshModel.sshdConfig("192.168.1.33")
        assertTrue(c.contains("Port 8022"))
        assertTrue(c.contains("ListenAddress 192.168.1.33"))
        assertTrue(c.contains("PasswordAuthentication no"))
        assertTrue(c.contains("KbdInteractiveAuthentication no"))
        assertTrue(c.contains("PubkeyAuthentication yes"))
        assertTrue(c.contains("AllowUsers flux"))
        assertTrue(c.contains("PermitRootLogin no"))
        assertFalse(c.contains("ListenAddress 0.0.0.0"))
    }

    @Test fun picksWifiOverEverythingElse() {
        val l = listOf("rmnet_data1" to "10.5.5.5", "lo" to "127.0.0.1", "wlan0" to "192.168.1.33", "ap0" to "192.168.43.1", "tun0" to "10.8.0.2")
        assertEquals("192.168.1.33", SshModel.pickAddress(l))
        assertEquals("192.168.43.1", SshModel.pickAddress(l.filter { it.first != "wlan0" }))
        assertNull(SshModel.pickAddress(listOf("rmnet0" to "10.1.1.1", "lo" to "127.0.0.1")))
        assertNull(SshModel.pickAddress(listOf("wlan0" to "169.254.3.3")))
        assertNull(SshModel.pickAddress(listOf("wlan0" to "999.1.1.1")))
    }

    @Test fun ttyGroupRenamedOnlyWhenPresent() {
        val g = "root:x:0:root\ntty:x:5:flux\nattty:x:9:\n"
        assertEquals("root:x:0:root\ntty_:x:5:flux\nattty:x:9:\n", SshModel.renameTtyGroup(g))
        assertNull(SshModel.renameTtyGroup("root:x:0:root\ntty_:x:5:\n"))
        assertFalse(SshModel.sshdConfig("1.2.3.4").contains("UsePAM"))
    }

    @Test fun processTreeForStop() {
        assertEquals(4, SshModel.parentOf("123 (sshd: flux@pts/0 x) S 4 123 0 0 -1")  )
        assertNull(SshModel.parentOf("garbage"))
        val parents = mapOf(10 to 1, 11 to 10, 12 to 11, 13 to 10, 99 to 2)
        assertEquals(listOf(11, 13, 12), SshModel.descendants(10, parents))
        assertEquals(emptyList<Int>(), SshModel.descendants(77, parents))
    }

    @Test fun commandsAndLogTail() {
        assertEquals("ssh -p 8022 flux@192.168.1.33", SshModel.connectCommand("192.168.1.33"))
        assertTrue(SshModel.sshdCommand().startsWith("exec /usr/sbin/sshd -D -e -f '"))
        assertTrue(SshModel.isListeningLine("Server listening on 192.168.1.33 port 8022."))
        assertEquals(listOf("b", "c"), SshModel.tail(listOf("a", "", "b", "c"), 2))
    }

    // endregion

    // region saved hosts

    private val ok = SshHost("id1", "Build box", "dev", "build.example.com", 22, "id_ed25519")

    @Test fun validatesHosts() {
        assertNull(RemoteHosts.validate(ok))
        assertNull(RemoteHosts.validate(ok.copy(host = "192.168.1.5", port = 8022, keyFile = "")))
        assertNull(RemoteHosts.validate(ok.copy(host = "fe80::1")))
        assertNotNull(RemoteHosts.validate(ok.copy(name = " ")))
        assertNotNull(RemoteHosts.validate(ok.copy(user = "-oProxyCommand=x")))
        assertNotNull(RemoteHosts.validate(ok.copy(host = "-oProxyCommand=x")))
        assertNotNull(RemoteHosts.validate(ok.copy(host = "a b")))
        assertNotNull(RemoteHosts.validate(ok.copy(host = "a;rm")))
        assertNotNull(RemoteHosts.validate(ok.copy(port = 0)))
        assertNotNull(RemoteHosts.validate(ok.copy(port = 70000)))
        assertNotNull(RemoteHosts.validate(ok.copy(keyFile = "../../etc/passwd")))
        assertNotNull(RemoteHosts.validate(ok.copy(keyFile = "id_ed25519.pub")))
    }

    @Test fun commandQuotesAndWaitsOnError() {
        val c = RemoteHosts.command(ok.copy(port = 2200))
        assertTrue(c.startsWith("ssh -p 2200 -i '/home/flux/.ssh/id_ed25519' -o IdentitiesOnly=yes 'dev@build.example.com';"))
        assertTrue(c.contains("read _x"))
        assertEquals("ssh -p 22 'dev@h'; rc=\$?; if [ \$rc -ne 0 ]; then echo; echo \"ssh exited with status \$rc. Press Enter to close.\"; read _x; fi",
            RemoteHosts.command(ok.copy(host = "h", keyFile = "")))
    }

    @Test fun roundTripSkipsCorruptLines() {
        val list = listOf(ok, ok.copy(id = "id2", name = "Pi", user = "pi", host = "10.0.0.2", port = 2222, keyFile = ""))
        assertEquals(list, RemoteHosts.decode(RemoteHosts.encode(list)))
        assertEquals(listOf(ok), RemoteHosts.decode(RemoteHosts.encode(listOf(ok)) + "\nbroken line\nx\ty\tz\tbad host\t22\t"))
        assertEquals(emptyList<SshHost>(), RemoteHosts.decode(""))
    }

    @Test fun upsertReplacesById() {
        val a = ok
        val b = ok.copy(id = "id2", name = "Two")
        assertEquals(listOf(a, b), RemoteHosts.upsert(listOf(a), b))
        assertEquals(listOf(a.copy(host = "x.example.com")), RemoteHosts.upsert(listOf(a), a.copy(host = "x.example.com")))
    }

    // endregion

    // region gating (client Free, server Pro)

    @Test fun serverIsProOnlyClientIsFree() {
        assertEquals(GateResult.Allow, FeatureGate.canUseSshServer(isPro = true, enforce = true))
        assertEquals(GateResult.Allow, FeatureGate.canUseSshServer(isPro = false, enforce = false))
        val deny = FeatureGate.canUseSshServer(isPro = false, enforce = true)
        assertTrue(deny is GateResult.Deny)
        assertEquals(ProFeature.SSH_SERVER, (deny as GateResult.Deny).upsell)
        // The client has no gate at all: saved hosts validate and build commands for a Free user.
        assertNull(RemoteHosts.validate(ok))
    }

    // endregion
}
