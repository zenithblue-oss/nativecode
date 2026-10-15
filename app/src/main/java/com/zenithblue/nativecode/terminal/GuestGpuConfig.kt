package com.zenithblue.nativecode.terminal

import android.content.Context
import java.io.File

/** Writes guest /etc/fluxlinux GPU state from host detection. No shell scripts. */
object GuestGpuConfig {
    fun applyToRootfs(ctx: Context) {
        val detect = GpuAccelDetector.detect()
        val alpaquita = File(ctx.filesDir, "usr/var/lib/proot-distro/containers/alpaquita/rootfs")
        val alpine = File(ctx.filesDir, "usr/var/lib/proot-distro/containers/alpine/rootfs")
        val root = when {
            alpaquita.isDirectory -> alpaquita
            alpine.isDirectory -> alpine
            else -> return
        }
        val dir = File(root, "etc/fluxlinux")
        if (!dir.exists() && !dir.mkdirs()) return
        val backend = when (detect.mode) {
            GpuAccelDetector.MODE_TURNIP -> GpuAccelDetector.MODE_TURNIP
            GpuAccelDetector.MODE_PANVK -> "panvk-zink"
            else -> "zink-venus"
        }
        write(dir, "gpu_mode", detect.mode)
        write(dir, "gpu_backend", backend)
        write(dir, "gpu_vendor", detect.vendorHint)
        write(dir, "gpu_method", GpuAccelDetector.methodLabel(backend))
    }

    /** Atomic replace, and only when the value actually changed. */
    private fun write(dir: File, name: String, value: String) {
        try {
            val dest = File(dir, name)
            val text = "$value\n"
            if (dest.isFile && dest.readText() == text) return
            val tmp = File(dir, ".$name.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(dest)) {
                dest.writeText(text)
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }
}
