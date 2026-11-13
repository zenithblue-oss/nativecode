package com.zenithblue.nativecode.agent.chat

import com.zenithblue.nativecode.agent.chat.core.PipeProcess
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-scoped registry of agent chat processes (item 12), so they outlive Activity recreation and are
 * counted with terminal tabs: in the AI session cap (`MainActivity.aiLaunchAllowed`) and in the
 * TerminalService foreground notification. M0 holds only what those two call sites need; the thread
 * controller (M1) adds entries and marks them live after the handshake.
 */
object AgentChats {
    /** One chat process. [projectPath] null = TERMINAL page (app scope), else its workspace project. */
    class Entry(val tool: String, val projectPath: String?, val process: PipeProcess) {
        @Volatile var starting = true
    }

    private val entries = CopyOnWriteArrayList<Entry>()

    /** Called on any add/remove/state change, from any thread (MainActivity posts to its main thread). */
    @Volatile var onChanged: (() -> Unit)? = null

    fun add(e: Entry) { entries += e; onChanged?.invoke() }
    fun markLive(e: Entry) { e.starting = false; onChanged?.invoke() }
    fun remove(e: Entry) { if (entries.remove(e)) onChanged?.invoke() }

    fun startingCount(): Int = entries.count { it.starting }
    fun liveCount(): Int = entries.count { !it.starting }

    /** Live + starting chats of one scope (FGS notification). */
    fun countFor(projectPath: String?): Int = entries.count { it.projectPath == projectPath }
    fun toolsFor(projectPath: String?): List<String> = entries.filter { it.projectPath == projectPath }.map { "chat: ${it.tool}" }
    fun projectPaths(): Set<String> = entries.mapNotNull { it.projectPath }.toSet()

    fun stopProject(path: String) = stop(entries.filter { it.projectPath == path })
    fun stopAll() = stop(entries.toList())

    private fun stop(list: List<Entry>) {
        if (list.isEmpty()) return
        list.forEach { it.process.close() }
        entries.removeAll(list.toSet())
        onChanged?.invoke()
    }
}
