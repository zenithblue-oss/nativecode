package com.zenithblue.nativecode.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryTest {
    @Test
    fun freeFormTextNeverPassesAsAParameter() {
        assertEquals("claude-code", Telemetry.token("claude-code"))
        assertEquals("nc_pro_monthly", Telemetry.token("nc_pro_monthly"))
        for (leak in listOf(
            "/data/data/com.zenithblue.nativecode/files/home/my-project",
            "my secret project",
            "sk-ant-api03-" + "x".repeat(40),
            "user@example.com",
            "fix the login bug",
            "",
            null,
        )) assertEquals("other", Telemetry.token(leak))
    }

    @Test
    fun setupDurationIsBucketed() {
        assertEquals("unknown", Telemetry.durationBucket(0))
        assertEquals("lt5m", Telemetry.durationBucket(60_000))
        assertEquals("5to15m", Telemetry.durationBucket(10 * 60_000L))
        assertEquals("15to30m", Telemetry.durationBucket(20 * 60_000L))
        assertEquals("gt30m", Telemetry.durationBucket(90 * 60_000L))
    }

    @Test
    fun parameterKeysAreAFixedSnakeCaseSet() {
        Telemetry.Param.entries.forEach { assert(Regex("[a-z_]{1,40}").matches(it.key)) { it.key } }
        Telemetry.Event.entries.forEach { assert(Regex("[a-z_]{1,40}").matches(it.id)) { it.id } }
    }
}
