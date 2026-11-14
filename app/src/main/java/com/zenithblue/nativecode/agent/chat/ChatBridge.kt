package com.zenithblue.nativecode.agent.chat

import android.content.Context
import com.zenithblue.nativecode.agent.chat.core.ItemPayload.Bridge
import com.zenithblue.nativecode.agent.chat.store.ChatWrite
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent bridge (`nativecode-agent`, AgentBridgeServer) requests from a chat thread's CLI become timeline cards
 * instead of popups. Routing: every chat CLI is spawned with [ENV] = its thread id (ThreadHost); the bridge
 * reads it from the client's `/proc/<pid>/environ` (peer pid of the socket, same uid) or an ancestor's.
 * No match, or the thread has no live process → null, and the caller keeps the popup path (terminal sessions).
 */
object ChatBridge {
    const val ENV = "NC_CHAT_THREAD"
    private const val MAX_DEPTH = 12

    /** "thread|key" → item id of a `status` card, so later updates edit it in place. */
    private val statusCards = ConcurrentHashMap<String, String>()

    /** Thread of the CLI that runs [pid] (only a thread whose controller has a live process). */
    fun threadOf(pid: Int): String? =
        findThread(pid, { p -> runCatching { File("/proc/$p/environ").readBytes() }.getOrNull() }, ::ppidOf)
            ?.takeIf { AgentChats.peekController(it)?.state?.live == true }

    /** Walks [pid] and its parents (≤ [MAX_DEPTH]) for [ENV]; pure for tests. */
    internal fun findThread(pid: Int, environ: (Int) -> ByteArray?, ppid: (Int) -> Int?): String? {
        var p = pid
        repeat(MAX_DEPTH) {
            if (p <= 1) return null
            environ(p)?.let { parseEnv(it) }?.let { return it }
            p = ppid(p) ?: return null
        }
        return null
    }

    internal fun parseEnv(bytes: ByteArray): String? = String(bytes, Charsets.UTF_8).split('\u0000')
        .firstOrNull { it.startsWith("$ENV=") }?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }

    /** `/proc/<pid>/stat` field 4 (after the parenthesised comm, which may hold spaces). */
    internal fun parsePpid(stat: String): Int? = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()

    private fun ppidOf(pid: Int): Int? = runCatching { parsePpid(File("/proc/$pid/stat").readText()) }.getOrNull()

    /** Adds the card to [threadId] (+ a thread notification when it is not on screen). False = no controller: use the popup. */
    fun post(ctx: Context, threadId: String, card: Bridge): Boolean {
        val c = AgentChats.peekController(threadId) ?: return false
        val p = card.copy(at = card.at.takeIf { it > 0 } ?: System.currentTimeMillis())
        val k = p.key?.let { "$threadId|$it" }
        val existing = k?.let { statusCards[it] }
        if (existing != null) {
            update(ctx, threadId, existing) { old -> p.copy(at = old.at) }
            return true
        }
        c.appendBridge(p) { id -> k?.let { statusCards[it] = id } }
        if (p.action != "status" && p.action != "toast") ChatNotifications.notify(ctx, c.thread, ChatNotifications.Kind.BRIDGE, p.title)
        return true
    }

    /** Card button / dismiss: [state] = done | dismissed. */
    fun setState(ctx: Context, threadId: String, itemId: String, state: String) = update(ctx, threadId, itemId) { it.copy(state = state) }

    /** `dismiss-alert` from this thread's CLI: its pending actionable cards → dismissed. True = any changed. */
    fun dismissPending(ctx: Context, threadId: String): Boolean {
        val store = AgentChats.store(ctx)
        val open = store.bridgeItems(threadId).filter { (it.payload as? Bridge)?.let { b -> b.state == Bridge.PENDING && b.action in ACTIONABLE } == true }
        open.forEach { setState(ctx, threadId, it.id, Bridge.DISMISSED) }
        return open.isNotEmpty()
    }

    private fun update(ctx: Context, threadId: String, itemId: String, f: (Bridge) -> Bridge) =
        AgentChats.store(ctx).apply(ChatWrite.UpdateItem(threadId, itemId) { i ->
            (i.payload as? Bridge)?.let { i.copy(payload = f(it), updatedAt = System.currentTimeMillis()) } ?: i
        })

    val ACTIONABLE = setOf("open_link", "install_apk", "share_file")
}
