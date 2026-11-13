package com.zenithblue.nativecode.agent.chat.acp

/**
 * Superseded by core `ChatMcp` (AcpRuntime reads `ChatMcp.endpoint`). Kept only so `tools/NcMcp.kt` still compiles;
 * delete together with its `AcpMcp.provider = …` line.
 */
@Deprecated("Use core ChatMcp")
object AcpMcp {
    data class Endpoint(val url: String, val token: String, val name: String = "nc")

    @Volatile var provider: ((threadId: String) -> Endpoint?)? = null
}
