package com.zenithblue.nativecode

import android.content.Context
import com.zenithblue.nativecode.terminal.GpuAccelDetector
import java.io.File

/**
 * In-app update of the Turnip (Adreno) Vulkan driver inside the installed guest rootfs: LFDev Mesa 27.0.0-devel-20261025-r2.
 * Replaces ONLY the Vulkan .so and its ICD json under /opt/nativecode-turnip (the Mesa GL libs there stay as shipped).
 * Small files in assets/turnip-27.0.0-r2; the .so comes from [TurnipSoSource] (play: asset, fdroid: GitHub download).
 */
object TurnipDriverUpdate {
    private const val ASSET_DIR = "turnip-27.0.0-r2"
    private const val SO = "opt/nativecode-turnip/lib/libvulkan_freedreno.so"
    private const val ICD = "opt/nativecode-turnip/icd/freedreno_icd.aarch64.json"
    const val VERSION_LABEL = "LFDev Turnip 27.0.0-r2"

    fun bundledSha(ctx: Context): String =
        ctx.assets.open("$ASSET_DIR/libvulkan_freedreno.so.sha256").bufferedReader().readText().trim()

    fun installedSha(ctx: Context): String? {
        val so = PanvkDriverUpdate.q("${PanvkDriverUpdate.rootfs(ctx)}/$SO")
        val r = PanvkDriverUpdate.sh("[ -f $so ] && sha256sum $so | cut -d' ' -f1", 120_000L)
        return r.stdout.trim().takeIf { r.exitCode == 0 && it.length == 64 }
    }

    /** Adreno device whose guest has the old Turnip .so. Mali devices never see this card. */
    fun needsUpdate(ctx: Context): Boolean {
        if (!GpuAccelDetector.isTurnip()) return false
        val installed = installedSha(ctx) ?: return false
        return !installed.equals(bundledSha(ctx), ignoreCase = true)
    }

    /** Pure: the sh script that backs up, stages, verifies and swaps the 2 files. Run by [update]; unit-tested on the JVM. */
    internal fun installScript(root: String, stage: String, sha: String): String {
        val q = PanvkDriverUpdate::q
        val files = listOf("libvulkan_freedreno.so" to SO, "freedreno_icd.aarch64.json" to ICD)
        return buildString {
            append("set -e; cp -f ${q("$root/$SO")} ${q("$root/$SO.bak")}; ")
            for ((name, rel) in files) {
                val dst = "$root/$rel"
                append("mkdir -p ${q(dst.substringBeforeLast('/'))}; cp -f ${q("$stage/$name")} ${q("$dst.new")}; ")
                append("chmod ${if (name.endsWith(".so")) "755" else "644"} ${q("$dst.new")}; ")
            }
            append("[ \"\$(sha256sum ${q("$root/$SO.new")} | cut -d' ' -f1)\" = ${q(sha)} ] || { echo SHA_MISMATCH; rm -f ")
            files.forEach { append(q("$root/${it.second}.new")).append(' ') }
            append("; exit 3; }; ")
            for ((_, rel) in files) append("mv -f ${q("$root/$rel.new")} ${q("$root/$rel")}; ")
            append("[ \"\$(sha256sum ${q("$root/$SO")} | cut -d' ' -f1)\" = ${q(sha)} ] && echo UPDATE_OK")
        }
    }

    /** Returns (ok, message). Background thread only. */
    fun update(ctx: Context): Pair<Boolean, String> {
        if (PanvkDriverUpdate.desktopActive()) return false to tr(R.string.app_desktop_running_stop_the_desktop)
        TurnipSoSource.ensure(ctx)?.let { return false to it }
        val stage = File(ctx.filesDir, "turnip_stage").apply { deleteRecursively(); mkdirs() }
        try {
            for (name in listOf("libvulkan_freedreno.so", "freedreno_icd.aarch64.json")) {
                (if (name.endsWith(".so")) TurnipSoSource.open(ctx) else ctx.assets.open("$ASSET_DIR/$name"))
                    .use { i -> File(stage, name).outputStream().use { i.copyTo(it) } }
            }
            stage.setReadable(true, false); stage.setExecutable(true, false)
            stage.listFiles()?.forEach { it.setReadable(true, false) }
            val r = PanvkDriverUpdate.sh(installScript(PanvkDriverUpdate.rootfs(ctx), stage.absolutePath, bundledSha(ctx)), 300_000L)
            return if (r.stdout.contains("UPDATE_OK")) true to "$VERSION_LABEL installed. Backup: .so.bak"
            else false to "Update failed: ${r.stdout.trim().takeLast(160)}"
        } catch (e: Exception) {
            return false to "Update failed: ${e.message}"
        } finally {
            stage.deleteRecursively()
        }
    }
}
