package com.zenithblue.nativecode.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitLogParseTest {
    private val h = "a".repeat(40)
    private val p1 = "b".repeat(40)
    private val p2 = "c".repeat(40)

    @Test fun parsesLineWithRefs() {
        val line = listOf(
            h, "$p1 $p2",
            "HEAD -> refs/heads/main, refs/remotes/origin/main, refs/remotes/origin/HEAD, tag: refs/tags/v1.0, refs/heads/feature/x",
            "Ann", "1700000000", "Fix: a\tb | c"
        ).joinToString("\u0000")
        val c = GitLogParse.parseLine(line)!!
        assertEquals(listOf(p1, p2), c.parents)
        assertTrue(c.isHead)
        assertEquals(
            listOf(
                GitRef("main", GitRefKind.LOCAL), GitRef("origin/main", GitRefKind.REMOTE),
                GitRef("v1.0", GitRefKind.TAG), GitRef("feature/x", GitRefKind.LOCAL)
            ),
            c.refs
        )
        assertEquals("Fix: a\tb | c", c.subject)
        assertEquals(1700000000L, c.time)
    }

    @Test fun rootAndDetachedHead() {
        val c = GitLogParse.parseLine("$h\u0000\u0000HEAD\u0000Bob\u00001\u0000init")!!
        assertTrue(c.parents.isEmpty()); assertTrue(c.isHead); assertTrue(c.refs.isEmpty())
        assertFalse(GitLogParse.parseLine("$h\u0000\u0000\u0000Bob\u00001\u0000x")!!.isHead)
    }

    @Test fun rejectsGarbage() {
        assertNull(GitLogParse.parseLine("not a record"))
        assertNull(GitLogParse.parseLine(""))
    }

    @Test fun parsesDetail() {
        val out = "Ann <a@x>\u00001700000000\u0000$p1\u0000Subject\n\nbody line\n\n" +
            // -z output: unicode / tab-free paths arrive raw, never C-quoted
            "\n__FILES__\nM\u0000src/a.kt\u0000A\u0000b.txt\u0000M\u0000docs/日本語 é.md\u0000" +
            "\n__NUM__\n3\t1\tsrc/a.kt\u0000-\t-\tb.txt\u00002\t0\tdocs/日本語 é.md\u0000"
        val d = GitLogParse.parseDetail(out)
        assertNotNull(d)
        assertEquals("Ann <a@x>", d!!.author)
        assertEquals(listOf(p1), d.parents)
        assertEquals("Subject\n\nbody line", d.message)
        assertEquals(GitFileChange('M', "src/a.kt", 3, 1), d.files[0])
        assertEquals(GitFileChange('A', "b.txt", 0, 0), d.files[1])
        assertEquals(GitFileChange('M', "docs/日本語 é.md", 2, 0), d.files[2])
        assertEquals(3, d.files.size)
    }

    @Test fun relativeTimes() {
        assertEquals("now", GitLogParse.relativeTime(100, 90))
        assertEquals("5m", GitLogParse.relativeTime(1000, 700))
        assertEquals("2h", GitLogParse.relativeTime(10000, 2000))
        assertEquals("3d", GitLogParse.relativeTime(86400 * 3 + 5, 0))
        assertEquals("2y", GitLogParse.relativeTime(86400L * 365 * 2 + 5, 0))
    }
}
