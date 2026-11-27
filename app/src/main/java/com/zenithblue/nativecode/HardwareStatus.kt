package com.zenithblue.nativecode

/** Pure status mapping for the Hardware page (JVM-testable; the page does the probing). */
object HardwareStatus {
    enum class Driver { UP_TO_DATE, UPDATE, MISSING, NOT_APPLICABLE }

    /** [installed] = sha256 of the guest .so (null = no such file); [needsUpdate] = the Components update-card check. */
    fun driver(applicable: Boolean, installed: String?, needsUpdate: Boolean): Driver = when {
        !applicable -> Driver.NOT_APPLICABLE
        installed == null -> Driver.MISSING
        needsUpdate -> Driver.UPDATE
        else -> Driver.UP_TO_DATE
    }

    fun shortSha(sha: String?): String = sha?.take(8) ?: "none"

    enum class Rendezvous { OK, STALE, MISSING }

    /** Guest copy of nativecode_bridge.json against what this app would write now. */
    fun rendezvous(actual: String?, expected: String): Rendezvous = when {
        actual == null -> Rendezvous.MISSING
        actual == expected -> Rendezvous.OK
        else -> Rendezvous.STALE
    }

    enum class Host { RUNNING, STOPPED, MISSING, NOT_BUNDLED }

    /** [optional]: a server this build may legitimately not ship (Venus in F-Droid) shows NOT_BUNDLED, not an error. */
    fun host(binaryExists: Boolean, running: Boolean, optional: Boolean): Host = when {
        !binaryExists -> if (optional) Host.NOT_BUNDLED else Host.MISSING
        running -> Host.RUNNING
        else -> Host.STOPPED
    }

    /** android.os.PowerManager THERMAL_STATUS_* by value. */
    fun thermal(status: Int): String =
        listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN").getOrElse(status) { "UNKNOWN" }
}
