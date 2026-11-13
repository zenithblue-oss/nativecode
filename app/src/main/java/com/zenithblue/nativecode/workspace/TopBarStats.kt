package com.zenithblue.nativecode.workspace

/** Phone stats the top bar can show. Order in the user's list = priority (last dropped first). */
enum class Stat(val id: String, val title: String) {
    BATTERY("bat", "Battery"),
    CPU("cpu", "CPU load"),
    RAM("ram", "RAM used"),
    TEMP("temp", "Battery temperature"),
    GPU("gpu", "GPU load"),
    NET("net", "Network rate"),
    DISK("disk", "Storage free");

    companion object {
        fun byId(id: String): Stat? = values().firstOrNull { it.id == id }
    }
}

/** Pure top-bar stat list + fit math (unit-tested). Stored as one comma-separated preference. */
object TopBarStats {
    const val PREF = "pref_topbar_stats"
    val DEFAULT = listOf(Stat.BATTERY, Stat.CPU, Stat.RAM, Stat.TEMP)

    /** null = never set → default; "" = user removed all. Unknown ids and duplicates dropped. */
    fun parse(pref: String?): List<Stat> =
        if (pref == null) DEFAULT
        else pref.split(',').mapNotNull { Stat.byId(it.trim()) }.distinct()

    fun serialize(stats: List<Stat>): String = stats.joinToString(",") { it.id }

    /** Enabled stats first in user order, then the rest in declaration order (customise list). */
    fun allOrdered(enabled: List<Stat>): List<Stat> = enabled + Stat.values().filter { it !in enabled }

    fun toggle(enabled: List<Stat>, stat: Stat): List<Stat> =
        if (stat in enabled) enabled - stat else enabled + stat

    /** Move an enabled stat up (-1) or down (+1); out-of-range is a no-op. */
    fun move(enabled: List<Stat>, stat: Stat, delta: Int): List<Stat> {
        val i = enabled.indexOf(stat)
        val j = i + delta
        if (i < 0 || j !in enabled.indices) return enabled
        return enabled.toMutableList().apply { set(i, enabled[j]); set(j, stat) }
    }

    /**
     * How many leading chips fit in [available] px. The first that does not fit and all after it
     * are dropped (no scroll, no wrap).
     */
    fun fitCount(widths: List<Int>, gap: Int, available: Int): Int {
        var used = 0
        widths.forEachIndexed { i, w ->
            used += w + if (i > 0) gap else 0
            if (used > available) return i
        }
        return widths.size
    }

    /** One row only when title, every enabled stat and the rail all fit; else title owns row 1. */
    fun singleRow(titleW: Int, statsW: Int, railW: Int, fixedW: Int, totalW: Int): Boolean =
        titleW + statsW + railW + fixedW <= totalW

    fun rate(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", bytesPerSec / 1_000_000.0)
        bytesPerSec >= 1_000 -> "${bytesPerSec / 1_000}K"
        else -> "0K"
    }
}
