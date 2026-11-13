package com.zenithblue.nativecode.agent.chat.runtime

import com.zenithblue.nativecode.agent.chat.core.McpEndpoint
import com.zenithblue.nativecode.agent.chat.core.SlashCommand.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OpenCodeSlashTest {
    private fun data(name: String) = JSONObject(javaClass.getResource("/agent/opencode-2.3.0/$name")!!.readText()).getJSONArray("data")
    private val list = OpenCodeSlash.commands(data("command-list.json"), data("skill-list.json"))

    @Test fun commandsFromApiCommandAndSkills() {
        // compact injected first; duplicates and blanks dropped; skills after, sorted, named by id.
        assertEquals(listOf("compact", "init", "review", "release-notes", "android-build", "release-notes"), list.map { it.name })
        assertEquals(listOf(Kind.PROVIDER, Kind.PROVIDER, Kind.PROVIDER, Kind.PROVIDER, Kind.SKILL, Kind.SKILL), list.map { it.kind })
        assertEquals("create/update AGENTS.md", list[1].description)
        assertEquals("Build and install the APK", list[4].description)
        assertEquals(listOf("compact"), OpenCodeSlash.commands(null, null).map { it.name })
    }

    @Test fun skillMentions() {
        assertEquals(listOf("android-build", "release-notes"), OpenCodeSlash.skillMentions("\$android-build then \$release-notes and \$android-build"))
        assertEquals(emptyList<String>(), OpenCodeSlash.skillMentions("\$release-notes, then")) // t3: a mention ends at whitespace
        assertEquals(emptyList<String>(), OpenCodeSlash.skillMentions("costs \$5 or \$10k a\$b"))
        val skills = OpenCodeSlash.skillAttachments("use \$android-build and \$unknown", list)!!
        assertEquals(1, skills.length())
        assertEquals("android-build", skills.getJSONObject(0).getString("id"))
        assertNull(OpenCodeSlash.skillAttachments("use \$init", list)) // a command, not a skill
    }

    @Test fun mcpConfigBody() {
        val e = McpEndpoint("nc", "http://127.0.0.1:41234/mcp", mapOf("Authorization" to "Bearer tok"))
        val c = OpenCodeSlash.mcpBody(e).getJSONObject("config")
        assertEquals("remote", c.getString("type"))
        assertEquals("http://127.0.0.1:41234/mcp", c.getString("url"))
        assertEquals("Bearer tok", c.getJSONObject("headers").getString("Authorization"))
        assertFalse(c.getBoolean("oauth"))
    }

    @Test fun usageLimits() {
        assertNull(OpenCodeSlash.usageLimits(null))
        val ok = OpenCodeSlash.usageLimits(JSONObject("""{"workspace":{"id":"w","usageURL":"https://x"},"go":{
            "rolling":{"percent":42.4,"limited":false,"resetsAt":1000},"weekly":{"percent":13,"limited":false,"resetsAt":2000},
            "monthly":{"percent":5,"limited":false,"resetsAt":3000}},"budgets":[]}"""))!!
        assertEquals("allowed", ok.status)
        assertEquals("Go session 42% · weekly 13% · monthly 5%", ok.detail)
        assertNull(ok.resetAt)
        val hit = OpenCodeSlash.usageLimits(JSONObject("""{"workspace":{"id":"w","usageURL":"x"},"budgets":[
            {"id":"b","limit":5,"spent":5,"scope":"each","per":"set","coverage":"all","models":[],"shared":false,"resetsAt":9000}]}"""))!!
        assertEquals("rejected", hit.status)
        assertEquals(9000L, hit.resetAt)
        assertEquals("budget 5.00/5.00", hit.detail)
    }
}
