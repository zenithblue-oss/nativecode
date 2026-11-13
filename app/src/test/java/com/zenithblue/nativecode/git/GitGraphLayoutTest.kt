package com.zenithblue.nativecode.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitGraphLayoutTest {

    private fun c(h: String, vararg parents: String) =
        GitCommit(h, parents.toList(), emptyList(), "a", 0L, "s $h", false)

    private fun layout(vararg commits: GitCommit): List<GraphRow> {
        val l = GitGraphLayout()
        return commits.map { l.add(it) }
    }

    /** No lane waits for a hash twice after a row except transient converging lanes; node lane is always valid. */
    private fun checkInvariants(rows: List<GraphRow>) {
        rows.forEachIndexed { i, r ->
            assertTrue("col >= 0", r.col >= 0)
            assertTrue("joins never include col", r.col !in r.joins)
            assertTrue("forks in range", r.forks.all { it < r.after.size })
            // lane k alive after row i-1 == lane k alive before row i
            if (i > 0) assertTrue(rows[i - 1].after === r.before)
            // after the node, lane col continues iff there are parents
            assertEquals(r.commit.parents.isNotEmpty(), r.col < r.after.size && r.after[r.col] >= 0)
        }
    }

    @Test fun linear() {
        val rows = layout(c("c", "b"), c("b", "a"), c("a"))
        checkInvariants(rows)
        assertTrue(rows.all { it.col == 0 })
        assertEquals(1, rows.maxOf { it.laneCount })
        assertEquals(0, rows[2].after.size) // root closes the lane
    }

    @Test fun forkAndMerge() {
        // m merges x (side) into main; both descend from base
        val rows = layout(c("m", "p1", "x"), c("x", "base"), c("p1", "base"), c("base"))
        checkInvariants(rows)
        assertEquals(0, rows[0].col)
        assertEquals(listOf(1), rows[0].forks.toList()) // side lane opens at the merge
        assertEquals(1, rows[1].col)                    // x lives in lane 1
        assertEquals(0, rows[2].col)
        assertEquals(0, rows[3].col)
        assertEquals(listOf(1), rows[3].joins.toList()) // side lane rejoins at base
        assertEquals(2, rows.maxOf { it.laneCount })
    }

    @Test fun octopusMerge() {
        val rows = layout(
            c("o", "p", "q", "r", "s"),
            c("s", "root"), c("r", "root"), c("q", "root"), c("p", "root"), c("root")
        )
        checkInvariants(rows)
        assertEquals(listOf(1, 2, 3), rows[0].forks.toList())
        assertEquals(4, rows.maxOf { it.laneCount })
        // all three side lanes plus nothing else end up joining at root
        assertEquals(3, rows.last().joins.size)
        assertEquals(0, rows.last().after.size)
    }

    @Test fun manyParallelBranches() {
        val n = 40
        val heads = (0 until n).map { c("h$it", "base") }
        val rows = layout(*heads.toTypedArray(), c("base"))
        checkInvariants(rows)
        // every head sits in its own column, no column reused while live
        assertEquals((0 until n).toList(), rows.take(n).map { it.col })
        assertEquals(n, rows.maxOf { it.laneCount })
        assertEquals(n - 1, rows.last().joins.size)
    }

    @Test fun multipleRoots() {
        val rows = layout(c("a2", "a1"), c("b2", "b1"), c("a1"), c("b1"))
        checkInvariants(rows)
        assertEquals(listOf(0, 1, 0, 1), rows.map { it.col })
        assertEquals(0, rows.last().after.size)
    }

    @Test fun freedLaneIsReused() {
        // x branches off and dies at its own root; next independent head reuses lane 1
        val rows = layout(c("m", "p", "x"), c("x"), c("p", "q"), c("y", "q"), c("q"))
        checkInvariants(rows)
        assertEquals(1, rows[1].col)
        assertEquals(1, rows[3].col)
        assertEquals(listOf(1), rows[4].joins.toList())
    }

    @Test fun copyResumesIdentically() {
        val l = GitGraphLayout()
        l.add(c("m", "p", "x")); l.add(c("x", "base"))
        val snap = l.copy()
        val a = l.add(c("p", "base")); val a2 = l.add(c("base"))
        val b = snap.add(c("p", "base")); val b2 = snap.add(c("base"))
        assertEquals(a.col, b.col); assertEquals(a2.joins.toList(), b2.joins.toList())
        assertEquals(a.after.toList(), b.after.toList())
    }

    @Test fun largeLinearIsFast() {
        val l = GitGraphLayout()
        val t = System.nanoTime()
        for (i in 100_000 downTo 1) l.add(c("h$i", "h${i - 1}"))
        assertTrue("100k rows < 2s", (System.nanoTime() - t) < 2_000_000_000L)
    }
}
