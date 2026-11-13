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
    val forks: IntArray
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
        if (c.parents.isEmpty()) {
            waiting[col] = null
        } else {
            waiting[col] = c.parents[0]
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
        val after = IntArray(waiting.size) { if (waiting[it] == null) -1 else colors[it] }
        prev = after
        val row = GraphRow(c, col, nodeColor, before, after, joins, forks)
        if (row.laneCount > maxLanes) maxLanes = row.laneCount
        return row
    }
}
