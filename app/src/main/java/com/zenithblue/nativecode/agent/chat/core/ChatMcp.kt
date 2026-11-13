package com.zenithblue.nativecode.agent.chat.core

/**
 * M7 hook (plan 9.2.15 "NC tools MCP"): the app's loopback HTTP MCP server, as one thread's runtime should
 * configure it. Runtimes ask [ChatMcp.endpoint] when they spawn and inject the result into their CLI's MCP config
 * (opencode `PUT /api/experimental/mcp/<name>`; claude `--mcp-config` file; codex `mcp_servers.<name>`; ACP
 * `mcpServers` http entry). Null = M7 not installed or no tools for this thread: inject nothing.
 */
data class McpEndpoint(
    val name: String,                       // server key, e.g. "nc"
    val url: String,                        // http://127.0.0.1:<port>/mcp
    val headers: Map<String, String> = emptyMap(), // Authorization: Bearer <per-thread token>; never logged
    val instructions: String? = null,       // short delegation rule for runtimes that can deliver it
)

/** Implemented by M7 (`agent/chat/tools/`) and installed once in [ChatMcp.provider]. Called off the main thread. */
interface McpProvider {
    fun endpoint(threadId: String, tool: String): McpEndpoint?

    /** The thread's process ended: revoke its token. */
    fun release(threadId: String) {}
}

object ChatMcp {
    @Volatile var provider: McpProvider? = null

    fun endpoint(threadId: String, tool: String): McpEndpoint? = runCatching { provider?.endpoint(threadId, tool) }.getOrNull()
    fun release(threadId: String) { runCatching { provider?.release(threadId) } }
}
