package com.zenithblue.nativecode.agent.chat.runtime

import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.TurnInput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeWireTest {
    private fun JSONArray.triples() = OpenCodeWire.objects(this).map { "${it.getString("action")} ${it.getString("resource")} ${it.getString("effect")}" }

    @Test fun modeRulesTable() {
        val base = listOf("shell * ask", "edit * ask", "external_directory * ask")
        assertEquals(base, OpenCodeWire.rules(RuntimeMode.APPROVAL_REQUIRED, InteractionMode.DEFAULT).triples())
        assertEquals(base, OpenCodeWire.rules(RuntimeMode.AUTO, InteractionMode.DEFAULT).triples())
        assertEquals(listOf("shell * ask", "edit * allow", "external_directory * ask"), OpenCodeWire.rules(RuntimeMode.AUTO_ACCEPT_EDITS, InteractionMode.DEFAULT).triples())
        assertEquals(listOf("* * allow"), OpenCodeWire.rules(RuntimeMode.FULL_ACCESS, InteractionMode.DEFAULT).triples())
        // plan appends edit deny last (last match wins), after session grants
        val grant = OpenCodeWire.rule("shell", "allow", "echo *")
        assertEquals(listOf("* * allow", "shell echo * allow", "edit * deny"), OpenCodeWire.rules(RuntimeMode.FULL_ACCESS, InteractionMode.PLAN, listOf(grant)).triples())
    }

    @Test fun readIsNeverDenied() {
        for (mode in RuntimeMode.entries) for (im in InteractionMode.entries) {
            val denied = OpenCodeWire.objects(OpenCodeWire.rules(mode, im)).filter { it.getString("effect") == "deny" }.map { it.getString("action") }
            assertFalse("$mode/$im", "read" in denied || "*" in denied)
        }
    }

    @Test fun freeModelsFromRealCatalog() {
        val models = OpenCodeWire.parseModels(JSONObject(javaClass.getResource("/agent/opencode-2.3.0/models.json")!!.readText()).getJSONArray("data"))
        assertEquals(12, models.size)
        assertTrue(models.all { it.free })
        val pickle = models.first { it.id == "opencode/big-pickle" } // no -free suffix: cost 0 rule
        assertTrue(pickle.free)
        assertEquals(200_000L, pickle.contextLimit)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), models.first { it.id == "opencode/beam-free" }.variants)
    }

    @Test fun freeRule() {
        fun m(provider: String, id: String, input: Double, output: Double) = JSONObject().put("providerID", provider).put("id", id)
            .put("cost", JSONArray().put(JSONObject().put("input", input).put("output", output)))
        assertTrue(OpenCodeWire.isFree(m("opencode", "big-pickle", 0.0, 0.0)))
        assertTrue(OpenCodeWire.isFree(m("opencode-go", "x-free", 1.0, 2.0)))
        assertFalse(OpenCodeWire.isFree(m("opencode", "gpt-9", 1.0, 2.0)))
        assertFalse(OpenCodeWire.isFree(m("anthropic", "claude-free", 0.0, 0.0)))
        assertFalse(OpenCodeWire.isFree(JSONObject().put("providerID", "opencode").put("id", "x").put("cost", JSONArray())))
    }

    @Test fun modelRefKeepsOnlyListedVariant() {
        val models = OpenCodeWire.parseModels(JSONArray().put(JSONObject().put("providerID", "opencode").put("id", "m")
            .put("variants", JSONArray().put(JSONObject().put("id", "medium")))))
        assertEquals("medium", OpenCodeWire.modelRef("opencode/m", "medium", models)!!.getString("variant"))
        assertFalse(OpenCodeWire.modelRef("opencode/m", "max", models)!!.has("variant"))
        assertNull(OpenCodeWire.modelRef("nomodel", null, models))
        assertEquals("medium", OpenCodeWire.defaultEffort(models[0]))
    }

    @Test fun promptWithAttachments() {
        val turn = TurnInput(
            "c1", "look",
            listOf(
                ChatAttachment("shot.png", "image/png", 10, "/home/flux/proj/attach_1_shot.png"),
                ChatAttachment("spec.pdf", "application/pdf", 10, "/home/flux/proj/attach_2_spec.pdf"),
            ),
        )
        val body = OpenCodeWire.promptBody(OpenCodeWire.promptId("ses_1", turn.clientId), turn, steer = true)
        assertEquals("msg_nc_ses_1_c1", body.getString("id"))
        assertEquals(
            "look\n\n[Attached image \"shot.png\" is saved at: /home/flux/proj/attach_1_shot.png]\n\n" +
                "[Attached file \"spec.pdf\" is saved at: /home/flux/proj/attach_2_spec.pdf]",
            body.getString("text"),
        )
        val files = body.getJSONArray("files")
        assertEquals(1, files.length())
        assertEquals("file:///home/flux/proj/attach_1_shot.png", files.getJSONObject(0).getString("uri"))
        assertEquals("shot.png", files.getJSONObject(0).getString("name"))
        assertEquals("steer", body.getString("delivery"))
        assertFalse(OpenCodeWire.promptBody("msg_x", TurnInput("x", "hi"), steer = false).has("files"))
        assertEquals("file:///a%20b/c.png", OpenCodeWire.fileUri("/a b/c.png"))
    }

    /** write's metadata has no diff: a one-line file with a trailing newline is +1, not +2. */
    @Test fun writeWithoutDiffCountsLines() {
        fun add(content: String) = (OpenCodeWire.toolPayload("write", JSONObject().put("filePath", "/p/n.txt").put("content", content), "", JSONObject().put("exists", false))
            as com.zenithblue.nativecode.agent.chat.core.ItemPayload.FileChange).add
        assertEquals(1, add("hello chat\n"))
        assertEquals(1, add("hello chat"))
        assertEquals(3, add("a\nb\n\n"))
    }

    @Test fun argvSlashAndForms() {
        val argv = OpenCodeWire.serveArgv(40123)
        assertEquals("opencode", argv[0])
        assertTrue("serve" in argv && "--hostname=127.0.0.1" in argv && "--port=40123" in argv)
        assertTrue(OpenCodeHttpRuntime.freePort() != OpenCodeWire.MANAGED_SERVICE_PORT)
        assertEquals("review" to "main", OpenCodeWire.commandOf(" /review main "))
        assertEquals("init" to "", OpenCodeWire.commandOf("/init"))
        assertNull(OpenCodeWire.commandOf("not / a command"))
        assertEquals("msg_nc_ses_x_ab_c", OpenCodeWire.promptId("ses_x", "ab:c"))

        val fields = JSONArray()
            .put(JSONObject().put("key", "q0").put("type", "string").put("options", JSONArray().put(JSONObject().put("value", "y").put("label", "Yes"))))
            .put(JSONObject().put("key", "q1").put("type", "multiselect").put("options", JSONArray()))
        val answer = OpenCodeWire.formAnswer(fields, mapOf("q0" to listOf("Yes"), "q1" to listOf("a", " ", "b")))
        assertEquals("y", answer.getString("q0"))
        assertEquals(listOf("a", "b"), OpenCodeWire.strings(answer.getJSONArray("q1")))
    }
}
