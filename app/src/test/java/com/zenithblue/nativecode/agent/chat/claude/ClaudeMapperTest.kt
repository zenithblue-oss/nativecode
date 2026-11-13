package com.zenithblue.nativecode.agent.chat.claude

import com.zenithblue.nativecode.agent.chat.core.AgentEvent
import com.zenithblue.nativecode.agent.chat.core.AgentEvent.ItemUpsert
import com.zenithblue.nativecode.agent.chat.core.AgentEvent.RequestUpsert
import com.zenithblue.nativecode.agent.chat.core.ChatAttachment
import com.zenithblue.nativecode.agent.chat.core.FailureClass
import com.zenithblue.nativecode.agent.chat.core.InteractionMode
import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.core.ItemStatus
import com.zenithblue.nativecode.agent.chat.core.RequestAnswer
import com.zenithblue.nativecode.agent.chat.core.RequestKind
import com.zenithblue.nativecode.agent.chat.core.RequestStatus
import com.zenithblue.nativecode.agent.chat.core.RunStatus
import com.zenithblue.nativecode.agent.chat.core.RuntimeMode
import com.zenithblue.nativecode.agent.chat.core.TurnInput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays NDJSON recorded from the real claude 2.1.323 on the phone (`app/src/test/resources/agent/claude-2.1.323/`,
 * `<` = CLI stdout, `>` = what the recorder wrote). Our `user` lines start turns / steer, as the runtime does.
 */
class ClaudeMapperTest {
    private class Replay(file: String) {
        val events = ArrayList<AgentEvent>()
        val replies = ArrayList<JSONObject>()
        val mapper = ClaudeMapper({ events += it }, { replies += it })
        val lines: List<String> = javaClass.getResource("/agent/claude-2.1.323/$file")!!.readText().lines().filter { it.length > 2 }

        init {
            for (l in lines) {
                val o = runCatching { JSONObject(l.substring(2)) }.getOrNull() ?: continue
                when {
                    l.startsWith("> ") && o.optString("type") == "user" -> if (o.optString("priority") == "now") mapper.steer() else mapper.beginTurn()
                    l.startsWith("< ") && o.optString("type") == "control_response" -> mapper.ready = true // handshake done
                    l.startsWith("< ") -> mapper.handle(o)
                }
            }
        }

        fun items(ref: String) = events.filterIsInstance<ItemUpsert>().filter { it.nativeRef == ref }
        fun terminals() = events.filterIsInstance<AgentEvent.TurnTerminal>()
        fun requests() = events.filterIsInstance<RequestUpsert>()
        fun payloads() = events.filterIsInstance<ItemUpsert>().map { it.payload }
    }

    @Test fun basicTurnsApprovalsAndUsage() {
        val r = Replay("basic.ndjson")
        assertEquals(5, r.events.count { it is AgentEvent.TurnStarted })
        assertEquals(List(5) { RunStatus.COMPLETED }, r.terminals().map { it.status })
        assertTrue(r.events.none { it is AgentEvent.Unknown })
        assertTrue(r.payloads().any { it is ItemPayload.AssistantMessage && it.text == "hi" })

        val bash = r.requests().first { it.nativeId == "b1b2751e-4535-447f-a634-57e44bb1ca87" }
        assertEquals(RequestKind.COMMAND, bash.kind)
        assertEquals("touch fx-a.txt", bash.detail.command)
        assertEquals(listOf("cancel", "reject", "session", "once"), bash.options.map { it.id })
        assertEquals("Allow `touch fx-a.txt` this session", bash.options[2].label)

        val write = r.requests().first { it.nativeId == "db674986-a60f-457d-a319-d141ff6dd906" }
        assertEquals(RequestKind.FILE_CHANGE, write.kind)
        assertEquals("Write blocked.txt", write.detail.title)
        assertTrue(write.detail.diff!!.contains("+x"))
        assertEquals("Allow Write this session", write.options[2].label)

        val cmd = r.items("toolu_016GDCN2hLNwmC9RZXmFYjGz").last()
        assertEquals(ItemStatus.COMPLETED, cmd.status)
        assertEquals("touch fx-a.txt", (cmd.payload as ItemPayload.CommandExecution).command)
        assertEquals(ItemStatus.FAILED, r.items("toolu_01K93dqhVyxvNydpYm7GBi7L").last().status) // declined Write

        // mode chip: system/init once, then the live set_permission_mode echoes (system/status), on change only
        val modes = r.events.filterIsInstance<AgentEvent.SessionState>().mapNotNull { it.runtimeMode }
        assertEquals(listOf(RuntimeMode.APPROVAL_REQUIRED, RuntimeMode.AUTO_ACCEPT_EDITS, RuntimeMode.FULL_ACCESS, RuntimeMode.APPROVAL_REQUIRED), modes)

        // usage per run (total_cost_usd is cumulative per process: the run gets the delta) and the 1M window from modelUsage
        val u = r.events.filterIsInstance<AgentEvent.UsageUpdate>().last().usage
        assertEquals(0.005997 - 0.005383, u.costUsd!!, 0.000002)
        assertEquals(1_000_000L, u.contextSize)
        assertTrue((u.contextUsed ?: 0) > 20_000)
    }

    @Test fun allowForSessionSendsOnlyTheScopedRule() {
        val r = Replay("basic.ndjson")
        val id = "bb3796de-3ce8-4cbb-817d-b922ebf0b47d"
        r.replies.clear()
        r.mapper.respond(id, RequestAnswer(optionId = ClaudeMapper.SESSION))
        val resp = r.replies.single().getJSONObject("response")
        assertEquals(id, resp.getString("request_id"))
        val body = resp.getJSONObject("response")
        assertEquals("allow", body.getString("behavior"))
        assertEquals("user_permanent", body.getString("decisionClassification"))
        assertEquals("toolu_01Nrfe5quhKvddXrXRnpwA7P", body.getString("toolUseID"))
        val perms = body.getJSONArray("updatedPermissions")
        assertEquals(1, perms.length()) // not addDirectories, never setMode (it flipped the session to acceptEdits)
        assertEquals("addRules", perms.getJSONObject(0).getString("type"))
        assertEquals("session", perms.getJSONObject(0).getString("destination"))
        assertEquals("touch fx-b.txt", perms.getJSONObject(0).getJSONArray("rules").getJSONObject(0).getString("ruleContent"))
        assertEquals(RequestStatus.RESOLVED, r.requests().last().status)

        // once / reject with feedback / cancel
        val r2 = Replay("basic.ndjson")
        r2.mapper.respond("b1b2751e-4535-447f-a634-57e44bb1ca87", RequestAnswer(optionId = ClaudeMapper.ONCE))
        assertEquals("user_temporary", r2.replies.last().getJSONObject("response").getJSONObject("response").getString("decisionClassification"))
        r2.mapper.respond("db674986-a60f-457d-a319-d141ff6dd906", RequestAnswer(optionId = ClaudeMapper.REJECT, feedback = "Not now"))
        val deny = r2.replies.last().getJSONObject("response").getJSONObject("response")
        assertEquals("deny", deny.getString("behavior"))
        assertEquals("Not now", deny.getString("message"))
        val r3 = Replay("basic.ndjson")
        r3.mapper.respond("b1b2751e-4535-447f-a634-57e44bb1ca87", RequestAnswer(cancel = true))
        assertTrue(r3.replies.last().getJSONObject("response").getJSONObject("response").getBoolean("interrupt"))
        assertEquals(RequestStatus.CANCELLED, r3.requests().last().status)
    }

    @Test fun askUserQuestionIsAQuestionCard() {
        val r = Replay("question.ndjson")
        val q = r.requests().single()
        assertEquals(RequestKind.USER_INPUT, q.kind)
        assertEquals("Color", q.detail.title)
        assertEquals("Which color do you prefer?", q.detail.questions.single().text)
        assertEquals(listOf("Red", "Blue"), q.detail.questions.single().options.map { it.label })
        assertTrue(r.payloads().none { it is ItemPayload.DynamicTool }) // no tool card for AskUserQuestion
        assertEquals(listOf(RunStatus.COMPLETED), r.terminals().map { it.status })

        r.mapper.respond(q.nativeId, RequestAnswer(answers = mapOf("Which color do you prefer?" to listOf("Blue"))))
        val body = r.replies.last().getJSONObject("response").getJSONObject("response")
        assertEquals("allow", body.getString("behavior"))
        assertEquals("Blue", body.getJSONObject("updatedInput").getJSONObject("answers").getString("Which color do you prefer?"))
        assertEquals(1, body.getJSONObject("updatedInput").getJSONArray("questions").length())
    }

    @Test fun exitPlanModeBecomesAPlanCardAndIsDenied() {
        val r = Replay("plan.ndjson")
        val plan = r.payloads().filterIsInstance<ItemPayload.ProposedPlan>().single()
        assertTrue(plan.markdown.contains("hello.txt"))
        val reply = r.replies.single().getJSONObject("response")
        assertEquals("307a7ded-88b4-4bdf-bcb9-a20fc3b98160", reply.getString("request_id"))
        assertEquals("deny", reply.getJSONObject("response").getString("behavior"))
        assertEquals(ClaudeWire.PLAN_CAPTURED, reply.getJSONObject("response").getString("message"))
        assertTrue(r.requests().isEmpty())
        assertTrue(r.items("toolu_01TUcaKaBrhqRCyHWVEJBhKw").isEmpty()) // ToolSearch has no card
        assertEquals(InteractionMode.PLAN, r.events.filterIsInstance<AgentEvent.SessionState>().first { it.interactionMode != null }.interactionMode)
        assertEquals(listOf(RunStatus.COMPLETED), r.terminals().map { it.status })
    }

    @Test fun steerSwallowsTheExtraResultAndInterruptEndsInterrupted() {
        val r = Replay("steer.ndjson")
        assertEquals(2, r.events.count { it is AgentEvent.TurnStarted }) // steer stays in the run
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.INTERRUPTED), r.terminals().map { it.status })
        assertTrue(r.payloads().any { it is ItemPayload.AssistantMessage && it.text == "steered" })
    }

    @Test fun toolsSubagentAndAutoTurn() {
        val r = Replay("tools.ndjson")
        val edit = r.items("toolu_012pYkLXEP9CqjkpA3xSxL9x").last()
        val fc = edit.payload as ItemPayload.FileChange
        assertEquals("/home/flux/nc-m2-fixture/notes.txt", fc.path)
        assertEquals(1, fc.add); assertEquals(1, fc.del)
        assertTrue(fc.diff!!.contains("+gamma"))
        val read = r.items("toolu_018CXFYgmanj1xrEUTUAbCP1").last().payload as ItemPayload.DynamicTool
        assertEquals("Read notes.txt", read.name)

        val agent = r.items("toolu_015xuHa34CV2yDJUsFRBqR7X")
        val sub = agent.last().payload as ItemPayload.Subagent
        assertEquals("List directory names", sub.title)
        assertEquals(1, sub.childCount)
        assertTrue(sub.result!!.contains("notes.txt"))
        assertEquals(ItemStatus.COMPLETED, agent.last().status)
        assertEquals("toolu_015xuHa34CV2yDJUsFRBqR7X", r.items("toolu_016Csjpp1ZiCUjrjkhHEdU66").last().parentRef)

        // the background agent's completion makes claude run a turn nobody sent: it still gets a run
        assertEquals(2, r.events.count { it is AgentEvent.TurnStarted })
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.COMPLETED), r.terminals().map { it.status })
    }

    @Test fun signedOutIsAFailureAndTheSignInBanner() {
        val r = Replay("signedout.ndjson")
        val t = r.terminals().single()
        assertEquals(RunStatus.FAILED, t.status)
        assertEquals(FailureClass.PERMISSION_ERROR, t.failure!!.cls)
        assertEquals(ClaudeWire.SIGNED_OUT, t.failure!!.message)
        assertTrue(r.payloads().none { it is ItemPayload.AssistantMessage }) // not a bubble
        assertEquals(emptyList<Any>(), r.events.filterIsInstance<AgentEvent.SessionState>().last().models)
        assertTrue(r.mapper.signedOut)
    }

    @Test fun missingResumeResultIsIgnored() {
        val r = Replay("resume-missing.ndjson")
        assertTrue(r.terminals().isEmpty())
        val r2 = Replay("resume.ndjson")
        assertEquals(listOf(RunStatus.COMPLETED), r2.terminals().map { it.status })
        assertTrue(r2.payloads().any { it is ItemPayload.AssistantMessage && it.text == "hi" })
    }

    @Test fun wireArgvModesAndAttachments() {
        val argv = ClaudeWire.argv("haiku", "low", "default", null, "sid-1")
        assertEquals("claude", argv[0])
        assertTrue(argv.containsAll(listOf("--permission-prompt-tool", "stdio", "--input-format", "stream-json", "--verbose")))
        assertEquals("sid-1", argv[argv.indexOf("--session-id") + 1])
        assertEquals("haiku", argv[argv.indexOf("--model") + 1])
        assertFalse(ClaudeWire.argv("default", null, "plan", "r1", null).contains("--model"))
        assertEquals("r1", ClaudeWire.argv(null, null, "plan", "r1", null).let { it[it.indexOf("--resume") + 1] })
        assertEquals("plan", ClaudeWire.permissionMode(RuntimeMode.FULL_ACCESS, InteractionMode.PLAN))
        assertEquals("bypassPermissions", ClaudeWire.permissionMode(RuntimeMode.FULL_ACCESS, InteractionMode.DEFAULT))
        assertEquals("acceptEdits", ClaudeWire.permissionMode(RuntimeMode.AUTO_ACCEPT_EDITS, InteractionMode.DEFAULT))
        assertNull(ClaudeWire.modesOf("dontAsk"))

        // M7: the token never reaches argv, only the env the `${NC_MCP_H0}` reference expands from
        val mcp = com.zenithblue.nativecode.agent.chat.core.McpEndpoint("nc", "http://127.0.0.1:9/mcp", mapOf("Authorization" to "Bearer secret"), "use nc tools")
        val withMcp = ClaudeWire.argv(null, null, "default", null, "s", mcp)
        assertFalse(withMcp.any { "secret" in it })
        val server = JSONObject(withMcp[withMcp.indexOf("--mcp-config") + 1]).getJSONObject("mcpServers").getJSONObject("nc")
        assertEquals("\${NC_MCP_H0}", server.getJSONObject("headers").getString("Authorization"))
        assertEquals("mcp__nc", withMcp[withMcp.indexOf("--allowedTools") + 1])
        assertEquals("use nc tools", withMcp[withMcp.indexOf("--append-system-prompt") + 1])
        assertEquals("Bearer secret", ClaudeWire.mcpEnv(mcp)["NC_MCP_H0"])
        assertFalse(ClaudeWire.argv(null, null, "default", null, "s").contains("--mcp-config"))

        // slash commands: argumentHint kept, /compact always offered
        val cmds = ClaudeWire.parseCommands(JSONArray("""[{"name":"review","description":"Review a PR","argumentHint":"<pr>"},{"name":"init","argumentHint":""}]"""))
        assertEquals(listOf("compact", "review", "init"), cmds.map { it.name })
        assertEquals("<pr>", cmds[1].argumentHint)
        assertNull(cmds[2].argumentHint)

        val turn = TurnInput("c1", "look", listOf(
            ChatAttachment("a.png", "image/png", 3, "/home/flux/a.png", "/host/a.png"),
            ChatAttachment("b.pdf", "application/pdf", 3, "/home/flux/b.pdf", "/host/b.pdf"),
            ChatAttachment("c.zip", "application/zip", 3, "/home/flux/c.zip", "/host/c.zip"),
        ))
        val msg = ClaudeWire.userMessage(turn, steer = true) { byteArrayOf(1, 2, 3) }
        assertEquals("now", msg.getString("priority"))
        val content = msg.getJSONObject("message").getJSONArray("content")
        assertEquals(listOf("text", "image", "document"), (0 until content.length()).map { content.getJSONObject(it).getString("type") })
        assertTrue(content.getJSONObject(0).getString("text").contains("[Attached file \"c.zip\" is saved at: /home/flux/c.zip]"))
        assertEquals("AQID", content.getJSONObject(1).getJSONObject("source").getString("data"))
        assertEquals("application/pdf", content.getJSONObject(2).getJSONObject("source").getString("media_type"))

        assertTrue(ClaudeWire.versionOk("2.1.323")); assertFalse(ClaudeWire.versionOk("2.0.9")); assertTrue(ClaudeWire.versionOk("3.0.0"))
        val init = JSONObject(Replay("basic.ndjson").lines.first { it.contains("\"models\"") }.substring(2)).getJSONObject("response").getJSONObject("response")
        val models = ClaudeWire.parseModels(init.getJSONArray("models"))
        assertEquals("default", models.first().id)
        assertTrue(models.first().variants.contains("high"))
        assertTrue(ClaudeWire.parseCommands(init.getJSONArray("commands")).any { it.name == "compact" })
    }
}
