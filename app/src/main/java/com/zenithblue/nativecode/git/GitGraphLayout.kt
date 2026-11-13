package com.zenithblue.nativecode.git

/**
 * One drawn row. [before] / [after] are lane snapshots (color id per lane, -1 = empty)
 * entering the top / leaving the bottom of the row, so a row draws without its neighbours.
 * [before] is the previous row's [after] (shared array, never mutated).
 *
 * [joins]: extra lanes (besides [col]) that end at this node. [forks]: lanes that start at
 * this node (merge parents 2..n), either a new lane or an existing lane waiting for that parent.
 */
class GraphRow(
    val commit: GitCommit,
    val col: Int,
    val nodeColor: Int,
    val before: IntArray,
    val after: IntArray,
    val joins: IntArray,
    val forks: IntArray,
    /** Lanes that end in this row by bending into lane [col] below the node (shared first parent). */
    val merges: IntArray = IntArray(0)
) {
    val isMerge: Boolean get() = commit.parents.size > 1
    /** Highest lane index drawn in this row, + 1. */
    val laneCount: Int get() = maxOf(before.size, after.size, col + 1)
}

/**
 * Streaming lane assignment, O(commits x lanes). Input must be in `--topo-order`
 * (children before parents). State is tiny, so [copy] is a cheap resume point.
 */
class GitGraphLayout private constructor(
    private val waiting: ArrayList<String?>,
    private val colors: ArrayList<Int>,
    private var nextColor: Int,
    private var prev: IntArray,
    var maxLanes: Int
) {
    constructor() : this(ArrayList(), ArrayList(), 0, IntArray(0), 0)

    fun copy() = GitGraphLayout(ArrayList(waiting), ArrayList(colors), nextColor, prev, maxLanes)

    private fun newLane(hash: String?): Int {
        var i = waiting.indexOf(null)
        if (i < 0) { i = waiting.size; waiting.add(hash); colors.add(nextColor++) }
        else { waiting[i] = hash; colors[i] = nextColor++ }
        return i
    }

    fun add(c: GitCommit): GraphRow {
        val before = prev
        var col = -1
        var joins = IntArray(0)
        for (i in waiting.indices) {
            if (waiting[i] != c.hash) continue
            if (col < 0) col = i
            else { joins += i; waiting[i] = null }
        }
        if (col < 0) col = newLane(null)
        val nodeColor = colors[col]

        var forks = IntArray(0)
        var merges = IntArray(0)
        if (c.parents.isEmpty()) {
            waiting[col] = null
        } else {
            // First parent already has a lane (sibling branch drawn above): one lane per hash, the
            // leftmost wins, like `git log --graph`. Without this every fork kept a parallel lane
            // down to the shared parent and lane count exploded on branch-heavy repos.
            val p0 = c.parents[0]
            val j = waiting.indexOf(p0)
            when {
                j < 0 -> waiting[col] = p0
                j < col -> { waiting[col] = null; forks += j }          // node edge bends left into lane j
                else -> { waiting[col] = p0; waiting[j] = null; merges += j } // lane j bends left into col below the node
            }
            for (k in 1 until c.parents.size) {
                val p = c.parents[k]
                var j = waiting.indexOf(p)
                if (j < 0) j = newLane(p)
                if (j !in forks) forks += j
            }
        }
        while (waiting.isNotEmpty() && waiting.last() == null) {
            waiting.removeAt(waiting.size - 1); colors.removeAt(colors.size - 1)
        }
        // Per-row snapshot is capped: the cell never draws past a third of the width anyway, and
        // uncapped arrays on a branch-heavy repo cost rows x lanes x 4 bytes (50k x 200 = 40 MB).
        val after = IntArray(minOf(waiting.size, MAX_SNAPSHOT)) { if (waiting[it] == null) -1 else colors[it] }
        prev = after
        val row = GraphRow(c, col, nodeColor, before, after, joins, forks, merges)
        val lanes = maxOf(waiting.size, col + 1, row.laneCount)
        if (lanes > maxLanes) maxLanes = lanes
        return row
    }

    companion object {
        /** Lanes kept per row snapshot; wider lanes still route correctly, they just are not drawn. */
        const val MAX_SNAPSHOT = 64
    }
}
