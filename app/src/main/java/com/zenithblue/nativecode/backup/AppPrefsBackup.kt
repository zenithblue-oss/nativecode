package com.zenithblue.nativecode.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure part of the settings/projects backup: which `nativecode_prefs` keys travel, their JSON form, and the restore merge.
 * ALLOWLIST, not a denylist: the prefs file also holds consent flags, Pro/billing caches, the gate key, onboarding and
 * rootfs-state flags, battery calibration and device paths, and a new key added later must not leak into an archive by
 * default. A new user setting that should travel is one line in [KEYS].
 */
object AppPrefsBackup {
    const val FILE = "prefs.json"
    const val PROJECTS = "projects_json"

    val KEYS: Set<String> = setOf(
        PROJECTS,
        "pref_topbar_stats", "pref_terminal_zoom", "pref_show_extra_keys", "pref_keep_awake", "pref_extra_keys_preset",
        "pref_chat_view_beta", "pref_chat_show_thinking", "pref_terminal_mode_app", "pref_terminal_mode_ws",
        "editor_font_sp", "editor_wrap", "dir_show_hidden", "dir_sort",
    )

    private fun typeOf(v: Any?): String? = when (v) {
        is String -> "s"; is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> null
    }

    /** [all] (a SharedPreferences map) reduced to the allowlisted, supported-type entries. */
    fun filter(all: Map<String, *>): Map<String, Any> =
        all.entries.filter { it.key in KEYS && typeOf(it.value) != null }.associate { it.key to it.value!! }

    fun encode(all: Map<String, *>): String = JSONObject().apply {
        for ((k, v) in filter(all)) put(k, JSONObject().put("t", typeOf(v)).put("v", v))
    }.toString()

    /** Inverse of [encode]; unknown keys and malformed entries are dropped, so a hand-edited archive can not write other prefs. */
    fun decode(json: String): Map<String, Any> = try {
        val o = JSONObject(json)
        val out = LinkedHashMap<String, Any>()
        for (k in o.keys()) {
            if (k !in KEYS) continue
            val e = o.optJSONObject(k) ?: continue
            val v: Any = when (e.optString("t")) {
                "s" -> e.getString("v")
                "b" -> e.getBoolean("v")
                "i" -> e.getInt("v")
                "l" -> e.getLong("v")
                "f" -> e.getDouble("v").toFloat()
                else -> continue
            }
            out[k] = v
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }

    /** Projects union by path: every local entry stays untouched, backup entries with a new path are appended. */
    fun mergeProjects(local: String?, backup: String?): String {
        val have = HashSet<String>()
        val out = JSONArray()
        runCatching {
            val a = JSONArray(local ?: "[]")
            for (i in 0 until a.length()) { have += a.getJSONObject(i).getString("path"); out.put(a.getJSONObject(i)) }
        }
        runCatching {
            val b = JSONArray(backup ?: "[]")
            for (i in 0 until b.length()) {
                val p = b.getJSONObject(i)
                if (p.getString("path") in have) continue
                if (!p.has("name") || !p.has("icon")) continue // the app's own reader needs both
                have += p.getString("path"); out.put(p)
            }
        }
        return out.toString()
    }

    /**
     * What a restore writes into the local prefs. Settings are overwritten by the backup's value: the allowlist holds only
     * user preferences and a restore is an explicit "bring my setup back" (fill-if-absent would be a no-op for any key the
     * fresh install already touched). Projects are the exception and are merged, never replaced.
     */
    fun toWrite(local: Map<String, *>, backup: Map<String, Any>): Map<String, Any> {
        val w = LinkedHashMap(backup)
        (backup[PROJECTS] as? String)?.let { w[PROJECTS] = mergeProjects(local[PROJECTS] as? String, it) }
        return w
    }
}
