package com.zenithblue.nativecode.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitChangesParseTest {

    private fun z(vararg f: String) = f.joinToString("\u0000", postfix = "\u0000")
    private fun m(name: String) = "\u0000$name\u0000"

    private val status = z(
        "# branch.oid 1234567890abcdef1234567890abcdef12345678",
        "# branch.head main",
        "# branch.upstream origin/main",
        "# branch.ab +2 -5",
        "# stash 3",
        "1 .M N... 100644 100644 100644 aaa bbb src/app/Main.kt",
        "1 MM N... 100644 100644 100644 aaa bbb both.txt",
        "1 A. N... 000000 100644 100644 000 bbb new file.txt",
        "1 D. N... 100644 000000 000000 aaa 000 gone.txt",
        "2 R. N... 100644 100644 100644 aaa aaa R100 lib/new.kt", "lib/old.kt",
        "u UU N... 100644 100644 100644 100644 a1 a2 a3 conflict.c",
        "? __init__.py",
        "? dir/untracked.md",
        "! ignored.log"
    )

    @Test fun statusV2Headers() {
        val s = GitPorcelainParse.parseStatusV2(status)
        assertEquals("main", s.branch)
        assertEquals("1234567890abcdef1234567890abcdef12345678", s.oid)
        assertEquals("origin/main", s.upstream)
        assertEquals(2, s.ahead); assertEquals(5, s.behind); assertEquals(3, s.stash)
    }

    @Test fun statusV2Entries() {
        val f = GitPorcelainParse.parseStatusV2(status).files
        fun of(g: ChangeGroup) = f.filter { it.group == g }.map { "${it.status} ${it.path}" }
        assertEquals(listOf("M both.txt", "A new file.txt", "D gone.txt", "R lib/new.kt"), of(ChangeGroup.STAGED))
        assertEquals(listOf("M src/app/Main.kt", "M both.txt"), of(ChangeGroup.UNSTAGED))
        assertEquals(listOf("! conflict.c"), of(ChangeGroup.CONFLICT))
        assertEquals(listOf("U __init__.py", "U dir/untracked.md"), of(ChangeGroup.UNTRACKED))
        assertEquals("lib/old.kt", f.first { it.status == 'R' }.origPath)
        assertNull(f.first { it.path == "both.txt" }.origPath)
    }

    @Test fun detachedAndInitial() {
        val s = GitPorcelainParse.parseStatusV2(z("# branch.oid (initial)", "# branch.head (detached)"))
        assertNull(s.branch); assertNull(s.oid); assertNull(s.upstream)
        assertEquals(0, s.ahead); assertTrue(s.files.isEmpty())
    }

    @Test fun numstatZ() {
        val n = GitPorcelainParse.parseNumstatZ(
            "3\t1\tsrc/a.kt\u0000-\t-\timg.png\u00000\t0\t\u0000old name.kt\u0000new name.kt\u0000"
        )
        assertEquals(3 to 1, n["src/a.kt"])
        assertEquals(-1 to -1, n["img.png"])
        assertEquals(0 to 0, n["new name.kt"])
        assertEquals(3, n.size)
    }

    @Test fun bundleMergesNumstatPerGroup() {
        val raw = "proot warning: noise\n" + m(GitPorcelainParse.M_STATUS) + status +
            m(GitPorcelainParse.M_UNSTAGED) + "4\t2\tboth.txt\u000010\t0\tsrc/app/Main.kt\u0000" +
            m(GitPorcelainParse.M_STAGED) + "1\t1\tboth.txt\u00005\t0\tnew file.txt\u00000\t0\t\u0000lib/old.kt\u0000lib/new.kt\u0000" +
            m(GitPorcelainParse.M_STATE) + "MERGING\n"
        val r = GitPorcelainParse.parseChangesBundle(raw) as RepoStatusResult.Ok
        val f = r.status.files
        assertEquals("MERGING", r.status.state)
        assertEquals(1 to 1, f.first { it.group == ChangeGroup.STAGED && it.path == "both.txt" }.let { it.added to it.deleted })
        assertEquals(4 to 2, f.first { it.group == ChangeGroup.UNSTAGED && it.path == "both.txt" }.let { it.added to it.deleted })
        assertEquals(0 to 0, f.first { it.status == 'R' }.let { it.added to it.deleted })
        assertEquals(-1, f.first { it.path == "__init__.py" }.added)
        assertEquals(9, f.size)
    }

    @Test fun bundleCleanAndFailures() {
        val clean = GitPorcelainParse.parseChangesBundle(
            m(GitPorcelainParse.M_STATUS) + z("# branch.oid abc", "# branch.head dev") +
                m(GitPorcelainParse.M_UNSTAGED) + m(GitPorcelainParse.M_STAGED) + m(GitPorcelainParse.M_STATE)
        ) as RepoStatusResult.Ok
        assertTrue(clean.status.files.isEmpty()); assertNull(clean.status.state)
        val notGit = GitPorcelainParse.parseChangesBundle(
            m(GitPorcelainParse.M_STATUS) + m(GitPorcelainParse.M_FAIL) + "fatal: not a git repository (or any parent)\n"
        )
        assertTrue(notGit is RepoStatusResult.NotGit)
        val err = GitPorcelainParse.parseChangesBundle(m(GitPorcelainParse.M_STATUS) + m(GitPorcelainParse.M_FAIL) + "fatal: bad index\n")
        assertTrue(err is RepoStatusResult.Error)
    }
}
