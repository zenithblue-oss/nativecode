package com.zenithblue.nativecode

import android.content.Context
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import java.io.File

/**
 * One-off in-app update of the PanVK (Mali) driver inside the installed guest rootfs.
 * Touches ONLY the 3 files below. Small files bundled in assets/panvk-26.3.1; the .so comes from [PanvkSoSource] (play: asset, fdroid: GitHub download). Background thread only.
 */
object PanvkDriverUpdate {
    private const val ASSET_DIR = "panvk-26.3.1"
    private const val SO = "opt/nativecode/panvk/lib/libvulkan_panfrost_kbase.so"
    private const val GPU_ENV = "etc/profile.d/gpu-env.sh"
    /** Shown on the update card and in the result message; keep in sync with ASSET_DIR and ReleasePins. */
    const val VERSION_LABEL = "Mesa PanVK 26.3.1"

    // asset name -> rootfs-relative target
    private val FILES = listOf(
        "libvulkan_panfrost_kbase.so" to SO,
        "panvk_kbase.json" to "opt/nativecode/panvk/icd.d/panvk_kbase.json",
        "panfrost_kbase_icd.aarch64.json" to "opt/nativecode/panvk/share/vulkan/icd.d/panfrost_kbase_icd.aarch64.json",
        // PanVK 26.x breaks with the old kbase_shim.so preload: ship the guest scripts that no longer set it.
        "gpu-env.sh" to GPU_ENV,
        "gpu-backend.sh" to "usr/local/lib/nativecode/gpu-backend.sh"
    )

    fun bundledSha(ctx: Context): String =
        ctx.assets.open("$ASSET_DIR/libvulkan_panfrost_kbase.so.sha256").bufferedReader().readText().trim()

    internal fun rootfs(ctx: Context): String = ProjectPathResolver.prootRootfsDir(ctx).absolutePath

    internal fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    internal class Res(val exitCode: Int, val stdout: String)

    /** Run sh script as app uid (proot rootfs is app-private). */
    @Suppress("UNUSED_PARAMETER")
    internal fun sh(cmd: String, timeoutMs: Long): Res = try {
        val pb = ProcessBuilder("/system/bin/sh", "-c", cmd).redirectErrorStream(true).start()
        val out = pb.inputStream.bufferedReader().readText()
        Res(pb.waitFor(), out)
    } catch (e: Exception) {
        Res(-1, e.message ?: "")
    }

    /** Installed .so sha256, or null if the guest has no such file. */
    fun installedSha(ctx: Context): String? {
        val so = q("${rootfs(ctx)}/$SO")
        val r = sh("[ -f $so ] && sha256sum $so | cut -d' ' -f1", 120_000L)
        return r.stdout.trim().takeIf { r.exitCode == 0 && it.length == 64 }
    }

    fun needsUpdate(ctx: Context): Boolean {
        val installed = installedSha(ctx) ?: return false
        if (!installed.equals(bundledSha(ctx), ignoreCase = true)) return true
        // also update when the guest gpu-env.sh still differs (old one preloads kbase_shim.so)
        val env = q("${rootfs(ctx)}/$GPU_ENV")
        val cur = sh("[ -f $env ] && sha256sum $env | cut -d' ' -f1", 120_000L).stdout.trim()
        val want = java.security.MessageDigest.getInstance("SHA-256")
            .digest(ctx.assets.open("$ASSET_DIR/gpu-env.sh").use { it.readBytes() })
            .joinToString("") { "%02x".format(it) }
        return !cur.equals(want, ignoreCase = true)
    }

    /** True if a desktop / GPU session looks active (Phosh, XFCE, supervisor script). */
    fun desktopActive(): Boolean {
        val r = sh("pgrep -f '[p]hoc|[p]hosh|[s]tart_gui|[x]fce4-session' | head -1", 15_000L)
        return r.stdout.trim().toIntOrNull() != null
    }

    /** Copy bundled files over the 3 target paths. Returns (ok, message). */
    fun update(ctx: Context): Pair<Boolean, String> {
        if (desktopActive()) return false to tr(R.string.app_desktop_running_stop_the_desktop)
        PanvkSoSource.ensure(ctx)?.let { return false to it }
        val stage = File(ctx.filesDir, "panvk_stage").apply { deleteRecursively(); mkdirs() }
        try {
            for ((name, _) in FILES) {
                (if (name.endsWith(".so")) PanvkSoSource.open(ctx) else ctx.assets.open("$ASSET_DIR/$name")).use { i -> File(stage, name).outputStream().use { i.copyTo(it) } }
            }
            stage.setReadable(true, false); stage.setExecutable(true, false)
            stage.listFiles()?.forEach { it.setReadable(true, false) }
            val root = rootfs(ctx)
            val sha = bundledSha(ctx)
            val cmd = buildString {
                append("set -e; ")
                // backup old .so, stage new files next to targets, verify, then rename into place
                append("cp -f ${q("$root/$SO")} ${q("$root/$SO.bak")}; ")
                for ((name, rel) in FILES) {
                    val dst = "$root/$rel"
                    append("mkdir -p ${q(dst.substringBeforeLast('/'))}; cp -f ${q("${stage.absolutePath}/$name")} ${q("$dst.new")}; ")
                    append("chmod ${if (name.endsWith(".so")) "755" else "644"} ${q("$dst.new")}; ")
                }
                append("[ \"\$(sha256sum ${q("$root/$SO.new")} | cut -d' ' -f1)\" = ${q(sha)} ] || { echo SHA_MISMATCH; rm -f ")
                FILES.forEach { append(q("$root/${it.second}.new")).append(' ') }
                append("; exit 3; }; ")
                for ((_, rel) in FILES) append("mv -f ${q("$root/$rel.new")} ${q("$root/$rel")}; ")
                append("[ \"\$(sha256sum ${q("$root/$SO")} | cut -d' ' -f1)\" = ${q(sha)} ] && echo UPDATE_OK")
            }
            val r = sh(cmd, 300_000L)
            return if (r.stdout.contains("UPDATE_OK")) true to "$VERSION_LABEL installed. Backup: .so.bak"
            else false to "Update failed: ${r.stdout.trim().takeLast(160)}"
        } catch (e: Exception) {
            return false to "Update failed: ${e.message}"
        } finally {
            stage.deleteRecursively()
        }
    }
}
