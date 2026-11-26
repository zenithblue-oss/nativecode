package com.zenithblue.nativecode.workspace

/** Phone stats the top bar can show. Order in the user's list = priority (last dropped first). */
enum class Stat(val id: String, val title: String) {
    BATTERY("bat", "Battery"),
    CPU("cpu", "CPU load"),
    RAM("ram", "RAM used"),
    TEMP("temp", "Battery temperature"),
    GPU("gpu", "GPU load"),
    NET("net", "Network rate"),
    DISK("disk", "Storage free"),
    WATT("watt", "Battery power");

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

    /** One row only when title, every enabled stat and the rail all fit; else title owns row 1. */
    fun singleRow(titleW: Int, statsW: Int, railW: Int, fixedW: Int, totalW: Int): Boolean =
        titleW + statsW + railW + fixedW <= totalW

    fun rate(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", bytesPerSec / 1_000_000.0)
        bytesPerSec >= 1_000 -> "${bytesPerSec / 1_000}K"
        else -> "0K"
    }
}

/**
 * Battery power in watts, signed (+ into the battery, - out of it). Approach copied from mkm: current x voltage,
 * direction from the plugged state until the kernel's own discharge sign has been learned, then from the raw sign.
 */
object Wattage {
    const val PREF_SCALE = "pref_wattage_scale"
    const val PREF_DISCHARGE_SIGN = "pref_wattage_discharge_sign"
    const val PREF_FLIP_SEEN = "pref_wattage_flip_seen"
    /** For devices that report mA instead of microamps (read too low) or the reverse (read too high). */
    val SCALES = listOf(0.001f, 1f, 1000f)
    /** Below 20 mA the raw sign is noise: do not learn from it. */
    const val LEARN_MIN_UA = 20_000L
    /** Above this (after scaling) the reading is a unit error, not a power draw. */
    private const val MAX_PLAUSIBLE_W = 100.0

    fun scaleLabel(scale: Float) = when { scale < 1f -> "\u00F71000"; scale > 1f -> "\u00D71000"; else -> "\u00D71" }

    /** True when current flows into the battery. */
    fun intoBattery(rawSign: Int, dischargeSign: Int, flipSeen: Boolean, plugged: Boolean): Boolean =
        if (dischargeSign != 0 && flipSeen && rawSign != 0) rawSign != dischargeSign else plugged

    /** New (dischargeSign, flipSeen) after one sample. */
    fun learn(rawSign: Int, magnitudeUa: Long, plugged: Boolean, dischargeSign: Int, flipSeen: Boolean): Pair<Int, Boolean> {
        if (magnitudeUa < LEARN_MIN_UA || rawSign == 0) return dischargeSign to flipSeen
        return when {
            !plugged && rawSign != dischargeSign -> rawSign to flipSeen
            plugged && dischargeSign != 0 && rawSign != dischargeSign -> dischargeSign to true
            else -> dischargeSign to flipSeen
        }
    }

    /** Signed watts, or null when the raw reading is implausible. [voltageMv] as in EXTRA_VOLTAGE. */
    fun watts(currentUa: Long, voltageMv: Int, scale: Float, into: Boolean): Double? {
        val scaled = Math.abs(currentUa.toDouble()) * voltageMv / 1e9 * scale
        if (scaled.isNaN() || scaled > MAX_PLAUSIBLE_W) return null
        return if (into) scaled else -scaled
    }

    fun format(w: Double): String =
        if (Math.abs(w) < 0.05) "0.0W" else String.format(java.util.Locale.US, "%+.1fW", w)
}
