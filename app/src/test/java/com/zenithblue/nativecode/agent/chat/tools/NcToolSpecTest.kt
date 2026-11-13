package com.zenithblue.nativecode.agent.chat.tools

import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.tools.NcToolSpec.Failure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** M7 tool schemas, input checks, mode clamping (t3 delegatedTaskModes), depth + plan gate rules. */
class NcToolSpecTest {
    private val t3Ported = listOf(
        "nc_orchestrator_capabilities", "nc_delegate_task", "nc_task_status", "nc_task_cancel", "nc_thread_list", "nc_thread_read",
        "nc_thread_send", "nc_thread_wait", "nc_thread_interrupt", "nc_thread_launch", "nc_thread_transfer", "nc_pending_request", "nc_queue",
    )

    @Test fun every_tool_has_a_valid_object_schema() {
        val names = NcToolSpec.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.containsAll(t3Ported))
        for (t in NcToolSpec.ALL) {
            assertTrue(t.name, t.name.startsWith("nc_") && t.name.length <= 64 && t.name.matches(Regex("[a-z0-9_]+")))
            val s = t.json().getJSONObject("inputSchema")
            assertEquals("object", s.getString("type"))
            val props = s.getJSONObject("properties")
            val req = s.getJSONArray("required")
            for (i in 0 until req.length()) assertTrue("${t.name}.${req.getString(i)}", props.has(req.getString(i)))
            for (k in props.keys()) assertTrue("${t.name}.$k", props.getJSONObject(k).getString("type") in setOf("string", "integer", "number", "boolean", "object", "array"))
            assertTrue(t.description.isNotBlank())
        }
        // t3 schema fields kept
        val d = NcToolSpec.tool("nc_delegate_task")!!.schema.getJSONObject("properties")
        for (k in listOf("task", "target", "title", "role", "isolation", "mode", "timeoutMs", "clientRequestId", "runtimeMode", "interactionMode")) assertTrue(k, d.has(k))
        assertEquals(listOf("inherit", "approval-required", "auto-accept-edits", "auto", "full-access"),
            d.getJSONObject("runtimeMode").getJSONArray("enum").let { e -> (0 until e.length()).map { e.getString(it) } })
    }

    @Test fun depth_limit_hides_writer_tools_from_children() {
        val child = NcToolSpec.visible(isChild = true).map { it.name }.toSet()
        for (w in listOf("nc_delegate_task", "nc_thread_send", "nc_thread_launch", "nc_thread_interrupt", "nc_thread_transfer")) assertFalse(w, w in child)
        assertTrue("nc_thread_read" in child && "nc_task_status" in child && "nc_orchestrator_capabilities" in child)
        assertEquals(NcToolSpec.ALL.size, NcToolSpec.visible(isChild = false).size)
    }

    @Test fun delegate_rules_depth_then_plan() {
        val free: (String) -> Boolean = { it == "opencode" }
        val pro: (String) -> Boolean = { true }
        assertEquals("capability_denied", NcToolSpec.delegateDenied(true, "opencode", pro)!!.code) // child never delegates
        assertTrue(NcToolSpec.delegateDenied(true, "opencode", pro)!!.message.contains("depth"))
        val f = NcToolSpec.delegateDenied(false, "claude-code", free)!!
        assertEquals("capability_denied", f.code)
        assertTrue(f.message.contains("Pro") && f.message.contains("opencode only"))
        assertNull(NcToolSpec.delegateDenied(false, "opencode", free))
        assertNull(NcToolSpec.delegateDenied(false, "grok", pro))
    }

    @Test fun runtime_mode_never_broader_than_parent() {
        val all = RuntimeMode.entries.toList()
        assertEquals(RuntimeMode.AUTO, NcToolSpec.resolveRuntimeMode(RuntimeMode.AUTO, null, all))
        assertEquals(RuntimeMode.AUTO, NcToolSpec.resolveRuntimeMode(RuntimeMode.AUTO, "inherit", all))
        assertEquals(RuntimeMode.APPROVAL_REQUIRED, NcToolSpec.resolveRuntimeMode(RuntimeMode.AUTO, "approval-required", all))
        val e = assertThrows(Failure::class.java) { NcToolSpec.resolveRuntimeMode(RuntimeMode.APPROVAL_REQUIRED, "full-access", all) }
        assertEquals("runtime_mode_escalation_denied", e.code)
        assertThrows(Failure::class.java) { NcToolSpec.resolveRuntimeMode(RuntimeMode.AUTO_ACCEPT_EDITS, "auto", all) }
        // inherited mode the target lacks steps down to its broadest narrower one; an explicit one is refused
        val agy = listOf(RuntimeMode.APPROVAL_REQUIRED, RuntimeMode.AUTO_ACCEPT_EDITS, RuntimeMode.FULL_ACCESS)
        assertEquals(RuntimeMode.AUTO_ACCEPT_EDITS, NcToolSpec.resolveRuntimeMode(RuntimeMode.AUTO, null, agy))
        assertEquals("invalid_request", assertThrows(Failure::class.java) { NcToolSpec.resolveRuntimeMode(RuntimeMode.FULL_ACCESS, "auto", agy) }.code)
        assertEquals(RuntimeMode.APPROVAL_REQUIRED, NcToolSpec.resolveRuntimeMode(RuntimeMode.AUTO, null, listOf(RuntimeMode.APPROVAL_REQUIRED)))
        assertEquals(RuntimeMode.FULL_ACCESS, NcToolSpec.resolveRuntimeMode(RuntimeMode.FULL_ACCESS, null, null))
    }

    @Test fun interaction_mode_and_drive_rules() {
        assertEquals(InteractionMode.PLAN, NcToolSpec.resolveInteractionMode(InteractionMode.PLAN, null))
        assertEquals(InteractionMode.PLAN, NcToolSpec.resolveInteractionMode(InteractionMode.DEFAULT, "plan"))
        assertEquals("interaction_mode_escalation_denied",
            assertThrows(Failure::class.java) { NcToolSpec.resolveInteractionMode(InteractionMode.PLAN, "default") }.code)
        assertTrue(NcToolSpec.canDrive(RuntimeMode.AUTO, InteractionMode.DEFAULT, RuntimeMode.APPROVAL_REQUIRED, InteractionMode.PLAN))
        assertFalse(NcToolSpec.canDrive(RuntimeMode.APPROVAL_REQUIRED, InteractionMode.DEFAULT, RuntimeMode.FULL_ACCESS, InteractionMode.DEFAULT))
        assertFalse(NcToolSpec.canDrive(RuntimeMode.FULL_ACCESS, InteractionMode.PLAN, RuntimeMode.FULL_ACCESS, InteractionMode.DEFAULT))
    }

    @Test fun validate_checks_and_normalises() {
        val s = NcToolSpec.tool("nc_delegate_task")!!.schema
        assertEquals("invalid_request", assertThrows(Failure::class.java) { NcToolSpec.validate(s, JSONObject()) }.code)
        assertThrows(Failure::class.java) { NcToolSpec.validate(s, JSONObject().put("task", 5)) }
        assertThrows(Failure::class.java) { NcToolSpec.validate(s, JSONObject().put("task", "x").put("mode", "later")) }
        assertThrows(Failure::class.java) { NcToolSpec.validate(s, JSONObject().put("task", "x".repeat(110_001))) }
        // opencode sends nested objects as JSON text; numbers may come as strings
        val a = JSONObject().put("task", "hi").put("target", """{"providerInstanceId":"claude-code","model":"sonnet"}""").put("timeoutMs", "5000").put("title", JSONObject.NULL)
        NcToolSpec.validate(s, a)
        assertEquals("claude-code", a.getJSONObject("target").getString("providerInstanceId"))
        assertEquals(5000.0, a.getDouble("timeoutMs"), 0.0)
        assertFalse(a.has("title"))
        assertThrows(Failure::class.java) { NcToolSpec.validate(s, JSONObject().put("task", "x").put("target", JSONObject().put("model", 3))) }
        val r = JSONObject().put("threadId", "t").put("limit", 500)
        NcToolSpec.validate(NcToolSpec.tool("nc_thread_read")!!.schema, r)
        assertEquals(100, NcToolSpec.clamp(r, "limit", 20, 100)) // clamped, not refused (t3: Codex hides maximum)
        assertThrows(Failure::class.java) { NcToolSpec.validate(NcToolSpec.tool("nc_thread_read")!!.schema, JSONObject().put("threadId", "t").put("limit", 1.5)) }
    }

    @Test fun providers_prompts_and_options() {
        val known = listOf("opencode", "claude-code", "codex", "agy", "grok")
        assertEquals("claude-code", NcToolSpec.providerId("Claude", known))
        assertEquals("agy", NcToolSpec.providerId("antigravity", known))
        assertNull(NcToolSpec.providerId("kiro", known))
        assertEquals("high", NcToolSpec.effortOf(JSONObject().put("effort", "high")))
        assertEquals("max", NcToolSpec.effortOf(JSONArray().put(JSONObject().put("id", "effort").put("value", "max"))))
        assertTrue(NcToolSpec.taskPrompt("do x", "review").startsWith("Act as the review sub-agent for this task.\n\ndo x"))
        assertTrue(NcToolSpec.taskPrompt("do x", null).contains("cannot delegate"))
    }

    @Test fun cross_thread_messages_round_trip() {
        val m = NcToolSpec.parseIncoming(NcToolSpec.threadMessage("abc", "My \"thread\"", "hello\nworld"))!!
        assertEquals("message", m.kind); assertEquals("abc", m.fromThread); assertEquals("hello\nworld", m.body)
        val r = NcToolSpec.parseIncoming(NcToolSpec.taskResult("task_1", "child", "claude-code", "completed", "**done**"))!!
        assertEquals("task", r.kind); assertEquals("child", r.fromThread); assertEquals("claude-code", r.label); assertEquals("completed", r.status)
        assertEquals("**done**", r.body)
        assertNull(NcToolSpec.parseIncoming("plain <nc_thread_message> text"))
        assertNotNull(NcToolSpec.parseIncoming(NcToolSpec.taskResult("t", "c", "opencode", "failed", null)))
    }

    @Test fun results_and_task_codec() {
        val err = NcToolSpec.fail(Failure("task_not_found", "nope"))
        assertTrue(err.getBoolean("isError"))
        assertEquals("task_not_found", JSONObject(err.getJSONArray("content").getJSONObject(0).getString("text")).getJSONObject("error").getString("code"))
        val t = NcTasks.Task("task_1", "p", "c", "claude-code", null, "Title", 1L, 2, "completed", "sum", null, true, false, true, "rq", 9L)
        assertEquals(t, NcTasks.decode(NcTasks.encode(t)))
    }
}
