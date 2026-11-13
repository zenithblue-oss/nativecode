package com.zenithblue.nativecode.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitSearchTest {

    private val refs = listOf(
        RefTarget("main", GitRefKind.LOCAL, "a"),
        RefTarget("origin/main", GitRefKind.REMOTE, "a"),
        RefTarget("origin/minor", GitRefKind.REMOTE, "b"),
        RefTarget("feat/domain-model", GitRefKind.LOCAL, "c"),
        RefTarget("v3.4.0", GitRefKind.TAG, "d"),
        RefTarget("v3.4.0-beta.1", GitRefKind.TAG, "e")
    )

    @Test fun substringBeatsFuzzyAndPrefixWins() {
        assertTrue(GitSearch.score("main", "main") > GitSearch.score("main", "origin/main"))
        assertTrue(GitSearch.score("main", "origin/main") > GitSearch.score("main", "feat/domain-model"))
        assertTrue(GitSearch.score("main", "feat/domain-model") > GitSearch.score("mn", "origin/minor").coerceAtMost(999))
        assertEquals(-1, GitSearch.score("xyz", "main"))
        assertEquals(0, GitSearch.score("", "anything"))
    }

    @Test fun fuzzySubsequence() {
        assertTrue(GitSearch.score("ormr", "origin/minor") > 0)
        assertEquals(-1, GitSearch.score("rmo", "origin/minor".take(3)))
        assertTrue(GitSearch.score("MAIN", "Main") > 0) // case-insensitive
    }

    @Test fun ranking() {
        assertEquals(listOf("main", "origin/main", "feat/domain-model"), GitSearch.rankRefs("main", refs).map { it.name })
        assertEquals(listOf("v3.4.0", "v3.4.0-beta.1"), GitSearch.rankRefs("v3.4", refs).map { it.name })
        assertEquals(refs.size, GitSearch.rankRefs("", refs).size)
        assertEquals(2, GitSearch.rankRefs("", refs, limit = 2).size)
    }

    @Test fun commitMatching() {
        val c = GitCommit("deadbeef00", emptyList(), emptyList(), "Evan You", 0, "fix(runtime): handle null props", false)
        assertTrue(GitSearch.commitMatches("deadb", c))
        assertFalse(GitSearch.commitMatches("dea", c)) // hash needs 4+ chars, and "dea" is not in subject/author
        assertTrue(GitSearch.commitMatches("evan", c))
        assertTrue(GitSearch.commitMatches("NULL PROPS", c))
        assertFalse(GitSearch.commitMatches("  ", c))
    }

    @Test fun refListParse() {
        val out = "refs/heads/main\u0000aaa\u0000\nrefs/remotes/origin/HEAD\u0000aaa\u0000\n" +
            "refs/remotes/origin/dev\u0000bbb\u0000\nrefs/tags/v1\u0000tagobj\u0000ccc\nrefs/stash\u0000s\u0000\n"
        assertEquals(
            listOf(
                RefTarget("main", GitRefKind.LOCAL, "aaa"),
                RefTarget("origin/dev", GitRefKind.REMOTE, "bbb"),
                RefTarget("v1", GitRefKind.TAG, "ccc")
            ),
            GitSearch.parseRefList(out)
        )
    }
}
