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
            // a node with parents either continues its lane or bends into another lane; roots end
            val continues = r.col < r.after.size && r.after[r.col] >= 0
            if (r.commit.parents.isEmpty()) assertTrue(!continues && r.forks.isEmpty())
            else assertTrue(continues || r.forks.isNotEmpty())
            // one lane per hash: lanes never pile up waiting for the same parent
            assertEquals("no deferred joins", 0, r.joins.size)
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
        assertEquals(listOf(1), rows[2].merges.toList()) // side lane bends into main below p1 (git: |/)
        assertEquals(0, rows[3].col)
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
        // side lanes fold leftwards one by one as they meet the shared root
        assertEquals(listOf(3, 2, 1), rows.subList(2, 5).map { it.merges.single() })
        assertEquals(0, rows.last().after.size)
    }

    @Test fun manyParallelBranchesShareOneLane() {
        // 40 tips on one base: git draws `| * h / |/` per tip, 2 lanes, not 40
        val n = 40
        val heads = (0 until n).map { c("h$it", "base") }
        val rows = layout(*heads.toTypedArray(), c("base"))
        checkInvariants(rows)
        assertEquals(listOf(0) + List(n - 1) { 1 }, rows.take(n).map { it.col })
        assertTrue(rows.drop(1).take(n - 1).all { it.forks.toList() == listOf(0) })
        assertEquals(2, rows.maxOf { it.laneCount })
    }

    @Test fun snapshotsAreCappedButRoutingIsNot() {
        // 150 tips with distinct parents: 150 live lanes at once
        val n = 150
        val heads = (0 until n).map { c("h$it", "b$it") }
        val mids = (0 until n).map { c("b$it", "base") }
        val rows = layout(*(heads + mids).toTypedArray(), c("base"))
        assertTrue(rows.all { it.after.size <= GitGraphLayout.MAX_SNAPSHOT })
        assertEquals(n - 1, rows[n - 1].col) // lane index itself is not capped
        assertEquals(n - 1, rows[2 * n - 1].col) // b149 still found in its lane
        val l = GitGraphLayout(); heads.forEach { l.add(it) }
        assertEquals(n, l.maxLanes)
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
        assertEquals(listOf(0), rows[3].forks.toList()) // y bends into p's lane (q already waited there)
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
