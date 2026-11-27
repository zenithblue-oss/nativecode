package com.zenithblue.nativecode.agent

import android.content.Context
import android.os.Process
import android.util.Log
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import java.io.File
import org.json.JSONObject

/**
 * `nativecode_bridge.json`: how guest CLIs (`nativecode-agent` / `nativecode-media`) find this app's bridge sockets.
 * Play and F-Droid builds share the abstract namespace: the first app owns the plain `@nativecode.*.v1` names and
 * the other binds `@nativecode.*.v1.<uid>`, so the guest must be told the uid. The guest reads `/run/...` (proot
 * rootfs `run/`); it cannot see the app's `files/run`, which stays only for host tools.
 */
object BridgeRendezvous {
    private const val TAG = "BridgeRendezvous"
    const val FILE_NAME = "nativecode_bridge.json"

    private fun warn(msg: String) { runCatching { Log.w(TAG, msg) } } // android.util.Log is a stub in JVM tests

    internal fun json(uid: Int): String = JSONObject().apply {
        put("v", 1)
        put("host_uid", uid)
        put("agent_socket", "@${AgentBridgeServer.SOCKET_NAME_PREFIX}$uid")
        put("media_socket", "@nativecode.media.v1.$uid")
    }.toString(2)

    /** Host copy + the proot rootfs copy (`/run` in the guest, if a rootfs is installed). */
    fun write(context: Context) {
        runCatching {
            File(context.filesDir, "run").apply { mkdirs() }.resolve(FILE_NAME).writeText(json(Process.myUid()))
            writeToRootfs(ProjectPathResolver.prootRootfsDir(context))
        }.onFailure { warn("Failed to write rendezvous file: ${it.message}") }
    }

    /** Also called after the guest defaults are (re)installed, so a fresh rootfs gets it without an app restart. */
    fun writeToRootfs(rootfs: File) {
        if (!File(rootfs, "etc").isDirectory) return
        runCatching {
            val f = File(rootfs, "run/$FILE_NAME")
            val text = json(Process.myUid())
            f.parentFile?.mkdirs()
            if (!f.isFile || f.readText() != text) f.writeText(text)
        }.onFailure { warn("Failed to write rootfs rendezvous: ${it.message}") }
    }
}
