package com.zenithblue.nativecode.agent.chat.tools

import android.content.Context
import com.zenithblue.nativecode.agent.chat.acp.AcpMcp
import com.zenithblue.nativecode.agent.chat.core.ChatMcp
import com.zenithblue.nativecode.agent.chat.core.McpEndpoint
import com.zenithblue.nativecode.agent.chat.core.McpProvider

/**
 * Per-CLI wiring of the NativeCode tools server (item 12 M7), config only (no guest scripts). The guest shares the
 * app's loopback under PRoot, so every CLI reaches `http://127.0.0.1:<port>/mcp` with its thread's bearer token.
 * Runtimes ask once per spawn through the [ChatMcp] / [AcpMcp] hooks; a new token revokes the thread's previous one.
 *  - opencode: `PUT /api/experimental/mcp/nc?location[directory]=<cwd>` (t3 `client.mcp.add`);
 *  - claude: `--mcp-config` JSON with `${NC_MCP_H0}` header refs from the env + `--allowedTools mcp__nc`;
 *  - codex: `config["mcp_servers.nc"] = {url, http_headers}` on thread/start|resume;
 *  - agy, grok: ACP `mcpServers` http entry when `mcpCapabilities.http`.
 */
object NcMcp {
    const val SERVER = "nc"

    class Endpoint(val url: String, val token: String) {
        val authorization: String get() = "Bearer $token"
    }

    @Volatile private var server: NcMcpServer? = null

    /** Once, from MainActivity: registers the runtime hooks. The server itself binds on the first chat spawn. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        NcTools.init(app)
        AcpMcp.provider = { id -> endpoint(app, id)?.let { AcpMcp.Endpoint(it.url, it.token, SERVER) } }
        ChatMcp.provider = object : McpProvider {
            override fun endpoint(threadId: String, tool: String): McpEndpoint? = endpoint(app, threadId)?.let {
                McpEndpoint(SERVER, it.url, mapOf("Authorization" to it.authorization), NcTools.instructions(threadId))
            }
            override fun release(threadId: String) = revoke(threadId)
        }
    }

    /** Starts the loopback server on first use. Null = tools unavailable (the chat still works without them). */
    @Synchronized
    fun endpoint(ctx: Context, threadId: String): Endpoint? = runCatching {
        NcTools.init(ctx)
        val s = server ?: NcMcpServer(NcTools).start().also { server = it }
        Endpoint("http://127.0.0.1:${s.port}/mcp", s.issue(threadId))
    }.getOrNull()

    fun revoke(threadId: String) { server?.revoke(threadId) }
}
