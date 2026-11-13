package com.zenithblue.nativecode.agent.chat.acp

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.AgentEvent.ItemUpsert
import com.zenithblue.nativecode.agent.chat.core.AgentEvent.RequestUpsert
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays ACP sessions recorded on the phone from the real CLIs (`app/src/test/resources/agent/agy-1.5.8/`,
 * `grok-1.0.77/`): 4 turns each (plain reply; shell + file edit with one approval; cancel; a question), then a
 * model/effort switch and a resume in a new process. Agent lines go through [AcpRpc] into [AcpMapper].
 */
class AcpFixtureTest {
    private fun replay(file: String, profile: AcpProfile): List<AgentEvent> {
        val ev = ArrayList<AgentEvent>()
        val m = AcpMapper(profile, "n", { ev += it })
        val promptIds = HashSet<Long>()
        val rpc = AcpRpc({ true }, { method, p ->
            val name = method.removePrefix("_")
            when {
                name == "session/update" -> m.update(p)
                name == "x.ai/session_notification" && p.optJSONObject("update")?.optString("sessionUpdate") == "turn_completed" ->
                    p.optJSONObject("update")?.optJSONObject("usage")?.let { m.addUsage(AcpWire.usageOf(it, m.usage)) }
            }
        }, { id, method, p ->
            when (method.removePrefix("_")) {
                "session/request_permission" -> m.permission(id, p)
                "x.ai/ask_user_question" -> m.xaiQuestion(id, p)
            }
            null
        })
        for (line in javaClass.getResource(file)!!.readText().lineSequence()) {
            if (line.startsWith("# ---")) break // resume: covered by the protocol, not the mapper
            if (line.length < 3) continue
            val o = JSONObject(line.substring(2))
            if (line[0] == '>') {
                if (o.optString("method") == "session/prompt") { promptIds += o.getLong("id"); m.beginTurn() }
                if (o.optString("method") == "session/cancel") m.interrupting = true
                continue
            }
            if (o.has("id") && !o.has("method") && o.optLong("id", -1) in promptIds) {
                val res = o.getJSONObject("result")
                m.addUsage(AcpWire.promptUsage(res, m.usage))
                val (status, failure) = AcpWire.stop(res.optString("stopReason"), m.interrupting, profile.refusalIsQuota)
                m.endTurn(status, failure)
                continue
            }
            rpc.onLine(line.substring(2))
        }
        return ev
    }

    private fun List<AgentEvent>.terminals() = filterIsInstance<AgentEvent.TurnTerminal>().map { it.status }
    private fun List<AgentEvent>.last(ref: String) = filterIsInstance<ItemUpsert>().last { it.nativeRef == ref }
    private fun List<AgentEvent>.texts() = filterIsInstance<ItemUpsert>().filter { it.status == ItemStatus.COMPLETED }
        .mapNotNull { (it.payload as? ItemPayload.AssistantMessage)?.text }

    @Test fun agy() {
        val ev = replay("/agent/agy-1.5.8/session.ndjson", AcpProfile.AGY)
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.COMPLETED, RunStatus.INTERRUPTED, RunStatus.COMPLETED), ev.terminals())
        assertTrue(ev.none { it is AgentEvent.Unknown })
        assertEquals("hi", ev.texts().first())
        assertTrue("done" in ev.texts())

        val cmd = ev.last("n:tool:call_17805403220268323584")
        assertEquals(ItemStatus.COMPLETED, cmd.status)
        val c = cmd.payload as ItemPayload.CommandExecution
        assertEquals("echo fixture-ok", c.command)
        assertEquals("fixture-ok\r\n", c.outputTail) // agy's "Created At… Command output:" header stripped
        assertEquals(0, c.exitCode)

        val write = ev.last("n:tool:call_17116027255099099935").payload as ItemPayload.FileChange
        assertEquals("/tmp/ncm4w-agy/note.txt", write.path)
        assertEquals(1, write.add)
        assertTrue(write.diff!!.startsWith("--- /dev/null"))

        // run_command asked: agent's exact options mapped to t3code labels + Cancel
        val ask = ev.filterIsInstance<RequestUpsert>()
        assertEquals(RequestKind.COMMAND, ask[0].kind)
        assertEquals("echo fixture-ok", ask[0].detail.command)
        assertEquals(listOf(AcpWire.CANCEL to "Cancel", "deny" to "Deny", "allow" to "Allow once"), ask[0].options.map { it.id to it.label })

        // ask_question (headless: printed as text) is a question row, not a raw tool name
        val q = ev.last("n:tool:call_3722744437218733929").payload as ItemPayload.DynamicTool
        assertEquals("Question: Which color do you prefer?", q.name)

        // context ring from usage_update; tokens from the prompt result (thought tokens kept apart)
        val usage = ev.filterIsInstance<AgentEvent.UsageUpdate>().map { it.usage }
        assertTrue(usage.any { it.contextSize == 1_048_576L && it.reasoningTokens == 280L && it.cacheReadTokens == 9_467L })
        assertEquals(250_000L, usage.last().contextSize) // after the switch to Claude Sonnet
        assertEquals(listOf("goal", "compact", "schedule", "browser", "plan"), m(ev).take(5))
    }

    /** Slash commands as the mapper saw them (available_commands_update). */
    private fun m(ev: List<AgentEvent>): List<String> {
        val raw = javaClass.getResource("/agent/agy-1.5.8/session.ndjson")!!.readText().lineSequence()
            .first { it.contains("available_commands_update") }.substring(2)
        return AcpWire.commands(JSONObject(raw).getJSONObject("params").getJSONObject("update").optJSONArray("availableCommands")).map { it.name }
    }

    @Test fun agySession() {
        val lines = javaClass.getResource("/agent/agy-1.5.8/session.ndjson")!!.readText().lines()
        val session = JSONObject(lines.first { it.startsWith("<") && it.contains("\"sessionId\"") && it.contains("\"result\"") }.substring(2)).getJSONObject("result")
        val (models, current) = AcpWire.models(session)
        assertEquals("gemini-3.8-flash", current)
        assertEquals(listOf("gemini-3.8-flash", "claude-sonnet-4-6", "claude-opus-4-6-thinking", "gpt-oss-120b-medium"), models.map { it.id })
        val effort = AcpWire.effortOption(session.getJSONArray("configOptions"))!!
        assertEquals(listOf("low", "medium", "high"), AcpWire.sortEfforts(AcpWire.selectChoices(effort).map { it.first }))
        val modes = AcpWire.objects(session.getJSONObject("modes").getJSONArray("availableModes")).map { it.getString("id") }
        // the native ids the agy profile maps Supervised / Auto-accept edits / Auto / plan onto
        assertTrue(modes.containsAll(AcpProfile.AGY.nativeModes.values + AcpProfile.AGY.planMode!!))
    }

    @Test fun grok() {
        val ev = replay("/agent/grok-1.0.77/session.ndjson", AcpProfile.GROK)
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.COMPLETED, RunStatus.INTERRUPTED, RunStatus.COMPLETED), ev.terminals())
        assertTrue(ev.none { it is AgentEvent.Unknown })
        assertEquals("hi", ev.texts().first())

        val shell = ev.last("n:tool:call-005107ac-08e8-4dd9-b1ff-0ef03fd33dc7-0")
        assertEquals(ItemStatus.COMPLETED, shell.status)
        assertEquals("fixture-ok\n", (shell.payload as ItemPayload.CommandExecution).outputTail)
        assertEquals(0, (shell.payload as ItemPayload.CommandExecution).exitCode)
        val edit = ev.last("n:tool:call-005107ac-08e8-4dd9-b1ff-0ef03fd33dc7-1").payload as ItemPayload.FileChange
        assertEquals("/tmp/ncm4w-grok/note.txt", edit.path)

        // approval: project-wide grants hidden, only the session edit grant (acpSupport.ts:104)
        val ask = ev.filterIsInstance<RequestUpsert>().first { it.kind == RequestKind.FILE_CHANGE }
        assertEquals(listOf("cancel", "reject", "allow_session", "allow_once"), ask.options.map { it.kind })
        assertEquals("allow-edits-session", ask.options[2].id)
        assertTrue(ask.detail.diff!!.contains("+hello"))

        // x.ai/ask_user_question → question card; its tool row stays hidden
        val q = ev.filterIsInstance<RequestUpsert>().first { it.kind == RequestKind.USER_INPUT }
        assertEquals("Which color do you want me to use?", q.detail.questions.single().text)
        assertEquals(listOf("red", "blue"), q.detail.questions.single().options.map { it.label })
        assertTrue(ev.filterIsInstance<ItemUpsert>().none { it.nativeRef == "n:tool:call-25c2de31-d8d3-44af-8d1d-d14b9329d643-2" })
        // unanswered in the replay: expired at turn end
        assertEquals(RequestStatus.EXPIRED, ev.filterIsInstance<RequestUpsert>().last { it.nativeId == q.nativeId }.status)

        // usage: cost from ticks, input excludes cache
        val u = ev.filterIsInstance<AgentEvent.UsageUpdate>().map { it.usage }.first { it.costUsd != null }
        assertEquals(0.01336064, u.costUsd!!, 1e-9)
        assertEquals(19900L - 2176L, u.inputTokens)
    }

    @Test fun grokModels() {
        val init = JSONObject(javaClass.getResource("/agent/grok-1.0.77/session.ndjson")!!.readText().lines()[1].substring(2)).getJSONObject("result")
        val state = init.getJSONObject("_meta").getJSONObject("modelState")
        val (models, current) = AcpWire.xaiModels(state)
        assertEquals("grok-4.7", current)
        assertEquals(listOf("low", "medium", "high", "xhigh"), models.first().variants)
        assertEquals(256_000L, models.first().contextLimit)
        assertEquals("high", AcpWire.xaiDefaultEffort(state, current))
        assertNotNull(AcpWire.objects(init.getJSONArray("authMethods")).firstOrNull { it.getString("id") == "cached_token" })
        val cmds = AcpWire.commands(init.getJSONObject("_meta").getJSONArray("availableCommands"), AcpProfile.GROK.dropCommands).map { it.name }
        assertTrue("compact" in cmds && "always-approve" !in cmds && "context" !in cmds)
    }
}
