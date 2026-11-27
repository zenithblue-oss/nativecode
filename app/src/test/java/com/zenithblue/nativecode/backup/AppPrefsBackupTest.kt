package com.zenithblue.nativecode.backup

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPrefsBackupTest {
    private val proj = """[{"name":"a","icon":"x","path":"/home/flux/repos/a","lastOpened":5,"linuxMethod":"proot"}]"""

    @Test fun filterKeepsSettingsAndDropsEverythingElse() {
        val all = mapOf<String, Any?>(
            "projects_json" to proj, "pref_topbar_stats" to "battery,watts,net", "pref_terminal_zoom" to 44,
            "editor_font_sp" to 14f, "editor_wrap" to true,
            // never travel: consent, billing, install/gate ids, onboarding/rootfs state, device calibration, secrets-ish
            "consent_crash_reports" to true, "consent_usage_stats" to true, "consent_asked" to true,
            "pro_cached" to true, "force_pro" to true, "pro_checked_ms" to 1L, "gate_key" to "k",
            "onboarding_completed" to true, "privacy_accepted" to true, "linux_method" to "chroot",
            "chroot_installed" to true, "active_project_path" to "/x", "ssh_remote_hosts" to "[]",
            "pref_wattage_scale" to 2f, "pref_wattage_discharge_sign" to -1, "access_token" to "secret",
            "last_version_code" to 23, "pref_unknown_future" to "x", "null_value" to null,
        )
        assertEquals(
            setOf("projects_json", "pref_topbar_stats", "pref_terminal_zoom", "editor_font_sp", "editor_wrap"),
            AppPrefsBackup.filter(all).keys,
        )
        val json = AppPrefsBackup.encode(all)
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("consent"))
    }

    @Test fun roundTripKeepsTypes() {
        val src = mapOf<String, Any?>("pref_topbar_stats" to "battery,watts", "pref_terminal_zoom" to 44, "editor_font_sp" to 14.5f, "editor_wrap" to true)
        val back = AppPrefsBackup.decode(AppPrefsBackup.encode(src))
        assertEquals("battery,watts", back["pref_topbar_stats"])
        assertEquals(44, back["pref_terminal_zoom"])
        assertEquals(14.5f, back["editor_font_sp"])
        assertEquals(true, back["editor_wrap"])
    }

    @Test fun decodeDropsKeysOutsideAllowlistAndGarbage() {
        val j = """{"consent_asked":{"t":"b","v":true},"pref_keep_awake":{"t":"b","v":true},"editor_wrap":{"t":"q","v":1},"dir_sort":"bad"}"""
        assertEquals(mapOf<String, Any>("pref_keep_awake" to true), AppPrefsBackup.decode(j))
        assertTrue(AppPrefsBackup.decode("not json").isEmpty())
    }

    @Test fun projectsUnionByPathLocalWins() {
        val local = """[{"name":"mine","icon":"y","path":"/home/flux/repos/a","lastOpened":9},{"name":"b","icon":"y","path":"/home/flux/repos/b"}]"""
        val backup = """[{"name":"old","icon":"x","path":"/home/flux/repos/a"},{"name":"c","icon":"x","path":"/home/flux/repos/c","lastOpened":3},{"path":"/no/name"}]"""
        val m = JSONArray(AppPrefsBackup.mergeProjects(local, backup))
        assertEquals(listOf("/home/flux/repos/a", "/home/flux/repos/b", "/home/flux/repos/c"), (0 until m.length()).map { m.getJSONObject(it).getString("path") })
        assertEquals("mine", m.getJSONObject(0).getString("name"))
    }

    @Test fun projectsMergeIntoEmptyOrBrokenLocal() {
        assertEquals(1, JSONArray(AppPrefsBackup.mergeProjects(null, proj)).length())
        assertEquals(1, JSONArray(AppPrefsBackup.mergeProjects("garbage", proj)).length())
        assertEquals("[]", AppPrefsBackup.mergeProjects("[]", "garbage"))
    }

    @Test fun toWriteOverwritesSettingsAndMergesProjects() {
        val local = mapOf<String, Any?>("pref_topbar_stats" to "battery", "projects_json" to """[{"name":"b","icon":"y","path":"/b"}]""", "consent_asked" to true)
        val backup = AppPrefsBackup.decode(AppPrefsBackup.encode(mapOf("pref_topbar_stats" to "battery,watts,net", "projects_json" to proj)))
        val w = AppPrefsBackup.toWrite(local, backup)
        assertEquals("battery,watts,net", w["pref_topbar_stats"])
        assertEquals(2, JSONArray(w["projects_json"] as String).length())
        assertFalse("consent_asked" in w)
    }

    @Test fun oldArchiveWithoutPrefsIsANoOp() {
        assertTrue(AppPrefsBackup.toWrite(mapOf("pref_topbar_stats" to "x"), emptyMap()).isEmpty())
    }
}
