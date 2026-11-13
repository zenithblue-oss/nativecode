package com.zenithblue.nativecode.agent.chat.codex

import com.zenithblue.nativecode.agent.chat.acp.AcpRpc
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
import com.zenithblue.nativecode.agent.chat.core.SessionConfig
import com.zenithblue.nativecode.agent.chat.core.TurnInput
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays `app/src/test/resources/agent/codex-0.168.0/turns.ndjson` (frames as codex 0.168.0 sends them: spike §2
 * shapes + the generated app-server schema) through [AcpRpc] (no `"jsonrpc"` dialect) into [CodexMapper], answering
 * server requests with the replies recorded in the fixture.
 */
class CodexMapperTest {
    private class Replay(val events: List<AgentEvent>, val replies: List<String>, val sent: List<String>)

    private fun replay(): Replay {
        val ev = ArrayList<AgentEvent>()
        val written = ArrayList<String>()
        val m = CodexMapper("n", { ev += it })
        val turnStarts = HashSet<Long>()
        val rpc = AcpRpc({ written += it; true }, { method, p -> m.notification(method, p) }, { id, method, p ->
            when (method) {
                "item/commandExecution/requestApproval" -> m.commandApproval(id, method, p)
                "item/fileChange/requestApproval" -> m.fileApproval(id, method, p)
                "item/tool/requestUserInput" -> m.userInput(id, p)
            }
            null
        }, tagged = false)
        val replies = ArrayList<String>()
        for (line in javaClass.getResource("/agent/codex-0.168.0/turns.ndjson")!!.readText().lineSequence()) {
            if (line.length < 3 || line.startsWith("#")) continue
            val o = JSONObject(line.substring(2))
            if (line[0] == '>') {
                when (o.optString("method")) {
                    "turn/start" -> { turnStarts += o.getLong("id"); m.beginTurn() }
                    "turn/interrupt" -> m.interrupting = true
                    "" -> { // our recorded reply to a server request: answer the card the same way
                        val p = m.pendingIds().map { m.pending(it)!! }.first { it.rpcId.toString() == o.get("id").toString() }
                        val res = o.getJSONObject("result")
                        val a = if (p.method == "item/tool/requestUserInput") RequestAnswer(answers = mapOf("color" to listOf("Blue")))
                        else RequestAnswer(optionId = res.getString("decision"))
                        val out = m.answer(p, a)!!
                        m.settle(p.event.nativeId, RequestStatus.RESOLVED)
                        replies += out.toString()
                        rpc.reply(p.rpcId, out)
                        assertEquals(res.toString(), out.toString())
                    }
                }
                continue
            }
            if (o.has("id") && !o.has("method") && o.getLong("id") in turnStarts) {
                m.onTurnId(o.getJSONObject("result").getJSONObject("turn").getString("id"))
                continue
            }
            rpc.onLine(line.substring(2))
        }
        return Replay(ev, replies, written)
    }

    private fun items(ev: List<AgentEvent>) = ev.filterIsInstance<ItemUpsert>()
    private fun last(ev: List<AgentEvent>, ref: String) = items(ev).last { it.nativeRef == ref }
    private fun terminals(ev: List<AgentEvent>) = ev.filterIsInstance<AgentEvent.TurnTerminal>()

    @Test fun textReasoningTodoUsageAndLimits() {
        val ev = replay().events
        val msg = last(ev, "turn_1:msg_1")
        assertEquals(ItemStatus.COMPLETED, msg.status)
        assertEquals(ItemPayload.AssistantMessage("Hello there!", "final_answer"), msg.payload)
        assertTrue(items(ev).any { it.nativeRef == "turn_1:msg_1" && it.status == ItemStatus.RUNNING && (it.payload as ItemPayload.AssistantMessage).text == "Hello there" })
        assertEquals("**Greeting** the user", (last(ev, "turn_1:rs_1").payload as ItemPayload.Reasoning).text)
        val todo = last(ev, "turn_1:todo").payload as ItemPayload.TodoList
        assertEquals(listOf("completed", "in_progress"), todo.steps.map { it.status })
        assertTrue(items(ev).none { it.nativeRef.endsWith(":u1") }) // the client owns user rows
        val u1 = ev.filterIsInstance<AgentEvent.UsageUpdate>().first().usage
        assertEquals(600L, u1.inputTokens); assertEquals(400L, u1.cacheReadTokens); assertEquals(200L, u1.outputTokens)
        assertEquals(1200L, u1.contextUsed); assertEquals(258400L, u1.contextSize)
        val rl = ev.filterIsInstance<AgentEvent.RateLimit>().single()
        assertEquals("allowed", rl.status)
        assertEquals("5h 42% · weekly 13% (plus)", rl.detail)
        assertEquals(listOf(RunStatus.COMPLETED, RunStatus.COMPLETED, RunStatus.INTERRUPTED, RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.FAILED),
            terminals(ev).map { it.status })
        assertEquals(listOf("turn_1", "turn_2", "turn_3", "turn_4", "turn_5", "turn_6"), ev.filterIsInstance<AgentEvent.TurnStarted>().mapNotNull { it.nativeTurnId })
    }

    @Test fun commandApprovalFromAvailableDecisionsAndOutput() {
        val r = replay()
        val req = r.events.filterIsInstance<RequestUpsert>().first { it.kind == RequestKind.COMMAND }
        assertEquals("n:0", req.nativeId)
        assertEquals(listOf("allow_once", "allow_always", "cancel"), req.options.map { it.kind })
        assertEquals("echo it's ok", req.detail.command)
        assertEquals(RequestStatus.RESOLVED, r.events.filterIsInstance<RequestUpsert>().last { it.nativeId == "n:0" }.status)
        val cmd = last(r.events, "turn_2:call_1").payload as ItemPayload.CommandExecution
        assertEquals("echo it's ok", cmd.command); assertEquals(0, cmd.exitCode); assertEquals("it's ok\n", cmd.outputTail)
        assertTrue(r.events.any { it is AgentEvent.CommandOutputDelta && it.nativeRef == "turn_2:call_1" })
        assertTrue(r.sent.map(::JSONObject).any { it.optInt("id", -1) == 0 && it.optJSONObject("result")?.optString("decision") == "accept" })
        assertFalse(r.sent.any { it.contains("jsonrpc") })
    }

    @Test fun patchApprovalShowsDiffAndDeclines() {
        val ev = replay().events
        val req = ev.filterIsInstance<RequestUpsert>().first { it.kind == RequestKind.FILE_CHANGE }
        assertEquals(listOf("/home/flux/proj/a.txt", "/home/flux/proj/b.txt"), req.detail.paths)
        assertTrue(req.detail.diff!!.contains("+one") && req.detail.diff!!.contains("-old"))
        val a = last(ev, "turn_2:call_2#0")
        assertEquals(ItemStatus.DECLINED, a.status)
        assertEquals(ItemPayload.FileChange("/home/flux/proj/a.txt", 2, 0, "+one\n+two"), a.payload)
        assertEquals(1, (last(ev, "turn_2:call_2#1").payload as ItemPayload.FileChange).del)
        // turn 2 usage = total delta over turn 1
        val u2 = ev.filterIsInstance<AgentEvent.UsageUpdate>().map { it.usage }.first { it.contextUsed == 2000L }
        assertEquals(1200L, u2.inputTokens); assertEquals(600L, u2.cacheReadTokens); assertEquals(200L, u2.outputTokens)
    }

    @Test fun interruptLeavesRunningItemsInterrupted() {
        val ev = replay().events
        assertEquals(ItemStatus.INTERRUPTED, last(ev, "turn_3:call_3").status)
    }

    @Test fun userInputWebSearchMcpCompaction() {
        val r = replay()
        val q = r.events.filterIsInstance<RequestUpsert>().first { it.kind == RequestKind.USER_INPUT }
        val question = q.detail.questions.single()
        assertEquals("color", question.id); assertTrue(question.custom); assertEquals(listOf("Red", "Blue"), question.options.map { it.label })
        assertTrue(r.replies.contains("""{"answers":{"color":{"answers":["Blue"]}}}"""))
        assertEquals("kotlin coroutines · kotlin", (last(r.events, "turn_4:ws_1").payload as ItemPayload.WebSearch).query)
        assertEquals(ItemPayload.DynamicTool("docs · lookup", """{"q":"x"}""", "found"), last(r.events, "turn_4:mcp_1").payload)
        assertEquals(ItemStatus.COMPLETED, last(r.events, "turn_4:cc_1").status)
        assertTrue(last(r.events, "turn_4:cc_1").payload is ItemPayload.Compaction)
    }

    @Test fun retriesThenSignInAndUsageLimit() {
        var authErrors = 0
        val ev = ArrayList<AgentEvent>()
        // re-run with an auth listener
        val m = CodexMapper("n") { ev += it }.apply { onAuthError = { authErrors++ } }
        m.beginTurn(); m.onTurnId("t")
        val err = """{"threadId":"x","turnId":"t","error":{"message":"Reconnecting... 1/5","codexErrorInfo":{"responseStreamDisconnected":{"httpStatusCode":401}}},"willRetry":true}"""
        m.notification("error", JSONObject(err))
        m.notification("error", JSONObject(err.replace("1/5", "2/5")))
        assertEquals(1, items(ev).map { it.nativeRef }.distinct().count { it.endsWith(":retry") })
        m.notification("error", JSONObject("""{"turnId":"t","error":{"message":"401","codexErrorInfo":{"httpConnectionFailed":{"httpStatusCode":401}}},"willRetry":false}"""))
        m.notification("turn/completed", JSONObject("""{"turn":{"id":"t","status":"failed","error":{"message":"401"}}}"""))
        val f = terminals(ev).single().failure!!
        assertEquals(FailureClass.PERMISSION_ERROR, f.cls); assertEquals(CodexWire.SIGN_IN, f.message)
        assertTrue(authErrors >= 1)

        val all = replay().events
        val limit = terminals(all).last().failure!!
        assertEquals(FailureClass.USAGE_LIMIT, limit.cls)
        assertEquals(1_800_500_000_000L, limit.retryAt)
    }

    @Test fun wireHelpers() {
        val res = JSONObject(javaClass.getResource("/agent/codex-0.168.0/turns.ndjson")!!.readText().lineSequence().first { it.contains("\"gpt-5.5-codex\",\"model\"") }.substring(2)).getJSONObject("result")
        val models = CodexWire.models(res)
        assertEquals(listOf("gpt-5.5-codex", "gpt-5.5"), models.map { it.first.id })
        assertEquals(listOf("low", "medium", "high"), models[0].first.variants)
        assertTrue(models[0].second)
        assertEquals(false to "ChatGPT Plus", CodexWire.account(JSONObject("""{"account":{"type":"chatgpt","email":"a@b","planType":"plus"},"requiresOpenaiAuth":true}""")))
        assertEquals(true to null, CodexWire.account(JSONObject("""{"account":null,"requiresOpenaiAuth":true}""")))
        val skills = CodexWire.skills(JSONObject("""{"data":[{"cwd":"/p","skills":[{"name":"review-pr","enabled":true},{"name":"compact","enabled":true},{"name":"off","enabled":false}]}]}"""), "/p")
        assertEquals(listOf("review-pr"), skills.map { it.name })
        assertEquals("\$review-pr 12", CodexWire.skillText("/review-pr 12", setOf("review-pr")))
        assertEquals("/other x", CodexWire.skillText("/other x", setOf("review-pr")))
        assertEquals("show" to null, CodexWire.goal("/goal"))
        assertEquals("pause" to null, CodexWire.goal("/goal pause"))
        assertEquals("set" to "ship it", CodexWire.goal("/goal ship it"))
        assertNull(CodexWire.goal("/goals"))
        assertEquals("Goal usage limited: x", CodexWire.describeGoal(JSONObject("""{"status":"usageLimited","objective":"x"}""")))
        assertEquals("ls -la", CodexWire.unwrapShell("/bin/bash -lc 'ls -la'"))
        assertEquals("rate_limited", CodexWire.rateLimit(JSONObject("""{"primary":{"usedPercent":100,"windowDurationMins":300,"resetsAt":10}}"""))!!.status)
        assertEquals(10_000L, CodexWire.rateLimit(JSONObject("""{"primary":{"usedPercent":100,"windowDurationMins":300,"resetsAt":10}}"""))!!.resetAt)
    }

    @Test fun policyPerModeAndTurnParams() {
        assertEquals("untrusted", CodexWire.approvalPolicy(RuntimeMode.APPROVAL_REQUIRED))
        assertEquals("untrusted", CodexWire.approvalPolicy(RuntimeMode.AUTO_ACCEPT_EDITS))
        assertEquals("on-request", CodexWire.approvalPolicy(RuntimeMode.AUTO))
        assertEquals("never", CodexWire.approvalPolicy(RuntimeMode.FULL_ACCESS))
        val cfg = SessionConfig("t", "/p", "/p", "gpt-5.5", "high", RuntimeMode.AUTO, InteractionMode.PLAN, null)
        val p = CodexWire.turnParams("thr", org.json.JSONArray(), cfg, "gpt-5.5", "high")
        assertEquals("dangerFullAccess", p.getJSONObject("sandboxPolicy").getString("type"))
        assertEquals("auto_review", p.getString("approvalsReviewer"))
        assertEquals("plan", p.getJSONObject("collaborationMode").getString("mode"))
        assertEquals("high", p.getJSONObject("collaborationMode").getJSONObject("settings").getString("reasoning_effort"))
        val t = CodexWire.threadParams(cfg, null, com.zenithblue.nativecode.agent.chat.core.McpEndpoint("nc", "http://127.0.0.1:1/mcp", mapOf("Authorization" to "Bearer x"), "use nc"))
        assertEquals("danger-full-access", t.getString("sandbox"))
        assertEquals("Bearer x", t.getJSONObject("config").getJSONObject("mcp_servers.nc").getJSONObject("http_headers").getString("Authorization"))
        assertEquals("use nc", t.getString("developerInstructions"))
        assertFalse(CodexWire.threadParams(cfg, null).getJSONObject("config").has("mcp_servers.nc"))
    }

    @Test fun inputInlinesImagesAndListsFiles() {
        val png = byteArrayOf(1, 2, 3)
        val turn = TurnInput("c", "look", listOf(
            ChatAttachment("a.png", "image/png", 3, "/p/a.png", "/host/a.png"),
            ChatAttachment("b.png", "image/png", 3, "/p/b.png", null),
            ChatAttachment("c.pdf", "application/pdf", 9, "/p/c.pdf", "/host/c.pdf"),
        ))
        val a = CodexWire.input(turn, turn.text) { if (it == "/host/a.png") png else null }
        assertEquals("text", a.getJSONObject(0).getString("type"))
        assertTrue(a.getJSONObject(0).getString("text").contains("[Attached file \"c.pdf\" is saved at: /p/c.pdf]"))
        assertEquals("data:image/png;base64,AQID", a.getJSONObject(1).getString("url"))
        assertEquals("localImage", a.getJSONObject(2).getString("type"))
    }

    @Test fun feedbackCardNeedsConfirmation() {
        val ev = ArrayList<AgentEvent>()
        val m = CodexMapper("n") { ev += it }
        m.beginTurn()
        val p = m.feedbackCard("crash on start")
        assertEquals(CodexMapper.FEEDBACK, p.method)
        assertEquals(listOf("upload", "cancel"), p.event.options.map { it.id })
        assertNotNull(m.pending(p.event.nativeId))
        assertEquals("crash on start", p.params.getString("reason"))
    }
}
