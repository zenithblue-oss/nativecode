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
        val backend = if (detect.mode == GpuAccelDetector.MODE_TURNIP) {
            GpuAccelDetector.MODE_TURNIP
        } else {
            "zink-venus"
        }
        write(dir, "gpu_mode", detect.mode)
        write(dir, "gpu_backend", backend)
        write(dir, "gpu_vendor", detect.vendorHint)
        write(dir, "gpu_method", GpuAccelDetector.methodLabel(backend))
    }

    private fun write(dir: File, name: String, value: String) {
        try {
            File(dir, name).writeText("$value\n")
        } catch (_: Throwable) {
        }
    }
}
