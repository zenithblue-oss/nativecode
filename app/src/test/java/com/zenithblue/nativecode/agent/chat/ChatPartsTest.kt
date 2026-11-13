package com.zenithblue.nativecode.agent.chat

import com.zenithblue.nativecode.agent.chat.core.DeltaCoalescer
import com.zenithblue.nativecode.agent.chat.core.DiffStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPartsTest {

    @Test fun coalescerEmitsLatestSnapshotPerKeyOncePerWindow() {
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val out = mutableListOf<String>()
        val c = DeltaCoalescer<String>(50, { ms, b -> timers += ms to b }, { out += it })
        c.offer("a", "H"); c.offer("a", "He"); c.offer("b", "x"); c.offer("a", "Hel")
        assertEquals(1, timers.size)
        assertEquals(50L, timers[0].first)
        assertTrue(out.isEmpty())
        timers.removeAt(0).second()
        assertEquals(listOf("Hel", "x"), out)
        c.offer("a", "Hello")
        c.flush() // another event arrived: flush first, keeps order
        assertEquals(listOf("Hel", "x", "Hello"), out)
        timers.removeAt(0).second() // stale timer: nothing pending
        assertEquals(3, out.size)
    }

    @Test fun checkpointScripts() {
        val ref = TurnCheckpoints.ref("t-1", 3, "base")
        assertEquals("refs/nativecode/chk/t-1/3/base", ref)
        val s = TurnCheckpoints.captureScript("/home/flux/my proj", ref, "nc-chk-x")
        assertTrue(s.startsWith("cd '/home/flux/my proj' || exit 3;"))
        assertTrue(s.contains("export GIT_INDEX_FILE=\"\$gd/nc-chk-x\""))
        val order = listOf("git read-tree HEAD", "git add -A -- .", "git write-tree", "git commit-tree \"\$t\" -p HEAD", "git update-ref '$ref' \"\$c\"")
        assertEquals(order.map { s.indexOf(it) }.sorted(), order.map { s.indexOf(it) })
        assertTrue(order.all { s.contains(it) })
        assertTrue(s.contains("|| exit 4")) // no HEAD = no diff
        assertTrue(TurnCheckpoints.pruneScript("/p", "t-1").contains("'refs/nativecode/chk/t-1/'"))
        assertTrue(TurnCheckpoints.patchScript("/p", "a", null, 10).contains("git diff --no-color 'a' | head -c 11"))
    }

    @Test fun numstatAndPatchParsing() {
        val out = "proot warning\nNCOUT\u0000M\u0000src/a.kt\u0000A\u0000new file.txt\u0000D\u0000gone\u0000NCSEP\u0000" +
            "12\t3\tsrc/a.kt\u000030\t0\tnew file.txt\u0000-\t-\tgone\u0000"
        assertEquals(listOf(DiffStat("src/a.kt", "M", 12, 3), DiffStat("new file.txt", "A", 30, 0), DiffStat("gone", "D", 0, 0)),
            TurnCheckpoints.parseNumstat(out))
        assertTrue(TurnCheckpoints.parseNumstat("garbage").isEmpty())
        assertEquals("diff", TurnCheckpoints.capPatch("noise\nNCOUT\ndiff", 10))
        assertEquals("0123456789\n[truncated]", TurnCheckpoints.capPatch("NCOUT\n0123456789X", 10))
    }

    @Test fun attachmentNamesAndLimits() {
        assertEquals("attach_5_my_photo__1_.png", ChatAttachments.safeName("DCIM/my photo (1).png", "image/png", true, 5))
        assertEquals("attach_5_image.webp", ChatAttachments.safeName("..", "image/webp", true, 5))
        assertEquals("attach_5_file", ChatAttachments.safeName(null, null, false, 5))
        assertEquals(96, ChatAttachments.safeName("x".repeat(300), null, false, 5).removePrefix("attach_5_").length)
        assertEquals(ChatAttachments.MAX_IMAGE_BYTES, ChatAttachments.limitFor("image/jpeg"))
        assertEquals(ChatAttachments.MAX_FILE_BYTES, ChatAttachments.limitFor("image/heic"))
        assertEquals(ChatAttachments.MAX_FILE_BYTES, ChatAttachments.limitFor("application/pdf"))
        assertNull(ChatAttachments.checkMessage("x".repeat(120_000), 100))
        assertTrue(ChatAttachments.checkMessage("x".repeat(120_001), 0)!!.contains("too long"))
        assertTrue(ChatAttachments.checkMessage("", 101)!!.contains("max 100"))
        assertEquals("/home/flux/a", ChatAttachments.guestPath(null, "a"))
        assertEquals("/home/flux/p/a", ChatAttachments.guestPath("/home/flux/p/", "a"))
    }

    @Test fun turnWakeLockEdges() {
        val s = TurnSet()
        assertEquals(TurnSet.Edge.FIRST, s.set("a", true))
        assertEquals(TurnSet.Edge.NONE, s.set("a", true)) // not ref-counted
        assertEquals(TurnSet.Edge.NONE, s.set("b", true))
        assertEquals(TurnSet.Edge.NONE, s.set("a", false))
        assertEquals(TurnSet.Edge.LAST, s.set("b", false))
        assertEquals(TurnSet.Edge.NONE, s.set("b", false))
        s.set("c", true)
        assertTrue(s.clear())
        assertEquals(0, s.count())
        assertEquals(TurnSet.Edge.FIRST, s.set("c", true))
    }

    @Test fun titleFromPrompt() {
        assertEquals("Fix it", ChatReducer.titleOf("\n  Fix it  \nmore"))
        assertEquals(60, ChatReducer.titleOf("y".repeat(99)).length)
        assertEquals(ChatReducer.DEFAULT_TITLE, ChatReducer.titleOf("  "))
    }
}
