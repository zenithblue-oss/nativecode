package com.zenithblue.nativecode.agent.chat

import com.zenithblue.nativecode.agent.chat.core.ItemPayload
import com.zenithblue.nativecode.agent.chat.store.ChatCodec
import com.zenithblue.nativecode.agent.chat.ui.openableUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBridgeTest {
    private fun env(vararg kv: String) = kv.joinToString("\u0000", postfix = "\u0000").toByteArray()

    @Test fun parsesEnvAndStat() {
        assertEquals("t-1", ChatBridge.parseEnv(env("PATH=/bin", "NC_CHAT_THREAD=t-1", "HOME=/root")))
        assertNull(ChatBridge.parseEnv(env("PATH=/bin", "NC_CHAT_THREAD=")))
        assertNull(ChatBridge.parseEnv(env("XNC_CHAT_THREAD=t")))
        assertEquals(812, ChatBridge.parsePpid("4242 (python3 -m x) S 812 4242 0 0"))
    }

    @Test fun routesByOwnEnvThenAncestors() {
        // python(30) ← bash(20) ← proot(10, spawned by the chat with NC_CHAT_THREAD) ← app(5)
        val envs = mapOf(30 to env("A=1"), 20 to env("B=2"), 10 to env("NC_CHAT_THREAD=thread-9"), 5 to env())
        val parents = mapOf(30 to 20, 20 to 10, 10 to 5, 5 to 1)
        assertEquals("thread-9", ChatBridge.findThread(30, { envs[it] }, { parents[it] }))
        // own env wins (CLI re-exported a different thread)
        assertEquals("own", ChatBridge.findThread(30, { if (it == 30) env("NC_CHAT_THREAD=own") else envs[it] }, { parents[it] }))
        // terminal session: no ancestor carries it → popup path
        assertNull(ChatBridge.findThread(30, { env("A=1") }, { parents[it] }))
        // unreadable environ (other uid) still walks up; a parent loop stops at the depth cap
        assertEquals("thread-9", ChatBridge.findThread(30, { if (it == 30) null else envs[it] }, { parents[it] }))
        assertNull(ChatBridge.findThread(30, { null }, { 30 }))
    }

    @Test fun bridgePayloadRoundTrips() {
        val p = ItemPayload.Bridge("install_apk", "Install APK", "built", "/data/x/app.apk", "App · com.x 1.0 · 12345", ItemPayload.Bridge.DISMISSED, null, 99L)
        assertEquals(p, ChatCodec.payload("bridge_event", ChatCodec.payload(p)))
        val s = ItemPayload.Bridge("status", "Task status", "build", detail = "40", key = "k")
        assertEquals(s, ChatCodec.payload(s.type, ChatCodec.payload(s)))
    }

    @Test fun onlyHttpLinksOpen() {
        assertTrue(openableUrl("http://localhost:3000"))
        assertTrue(openableUrl("https://example.com/a?b=c"))
        assertFalse(openableUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(openableUrl("file:///data/data/x"))
        assertFalse(openableUrl("javascript:alert(1)"))
        assertFalse(openableUrl(null))
    }
}
