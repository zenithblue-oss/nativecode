package com.zenithblue.nativecode.terminal

import android.content.Context
import android.os.Build
import java.io.File

/** Host + guest GPU snapshot for the Hardware page. No PRoot spawn. */
object GpuRuntimeStatus {

    data class Snapshot(
        val vendor: String,
        val gpuName: String,
        val glVendor: String,
        val glVersion: String,
        val glslVersion: String,
        val vulkanVersion: String,
        val modeHint: String,
        val backend: String,
        val method: String,
        val kgsl: Boolean,
        val hostSocket: Boolean,
        val venusServer: String,
        val pulseServer: String,
        val lastProbe: String,
        val icd: String,
        val soc: String,
        val badge: String
    )

    fun snapshot(ctx: Context): Snapshot {
        val detect = GpuAccelDetector.detect()
        val gl = HostGlInfo.query(ctx)
        val kgsl = detect.mode == GpuAccelDetector.MODE_TURNIP
        val venus = HostServerController.status(HostServerController.Kind.VENUS)
        val pulse = HostServerController.status(HostServerController.Kind.PULSE)
        val hostSocket = venus.running && File(TermuxHostPaths.TMPDIR, ".virgl_test").exists()
        val rootfs = guestRoot(ctx)
        val gpuMode = readTrim(File(rootfs, "etc/fluxlinux/gpu_mode"))
        val backend = readTrim(File(rootfs, "etc/fluxlinux/gpu_backend"))
            .ifBlank { gpuMode }
            .ifBlank { detect.mode }
        val lastProbe = readTrim(File(rootfs, "etc/fluxlinux/gpu_last_probe"))

        val reportedBackend = when {
            lastProbe.startsWith("panvk") || lastProbe == "panfrost" -> lastProbe
            backend.startsWith("panvk") || backend == "panfrost" -> backend
            detect.mode == GpuAccelDetector.MODE_PANVK -> "panvk-zink"
            backend.isNotBlank() && backend != "venus" && backend != "zink-venus" -> backend
            gpuMode.isNotBlank() && gpuMode != "venus" -> gpuMode
            backend.isNotBlank() -> backend
            else -> detect.mode
        }
        val effectiveBackend = sanitizeBackend(reportedBackend, kgsl)

        val method = when (effectiveBackend) {
            "turnip" -> GpuAccelDetector.methodLabel("turnip")
            "panvk" -> GpuAccelDetector.methodLabel("panvk")
            "panvk-zink" -> GpuAccelDetector.methodLabel("panvk-zink")
            "panfrost" -> GpuAccelDetector.methodLabel("panfrost")
            "venus", "zink-venus" -> GpuAccelDetector.methodLabel("zink-venus")
            "virgl", "virgl-angle" -> GpuAccelDetector.methodLabel("virgl")
            else -> readTrim(File(rootfs, "etc/fluxlinux/gpu_method"))
                .ifBlank { GpuAccelDetector.methodLabel(effectiveBackend) }
        }
        val vendor = readTrim(File(rootfs, "etc/fluxlinux/gpu_vendor"))
            .ifBlank { detect.vendorHint }
        val icd = firstIcd(rootfs, effectiveBackend, detect.mode)
        val soc = buildString {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sm = Build.SOC_MODEL?.trim().orEmpty()
                if (sm.isNotEmpty()) append(sm)
            }
            if (isEmpty()) append(Build.HARDWARE)
        }
        return Snapshot(
            vendor = vendor,
            gpuName = gl.gpuName,
            glVendor = gl.glVendor,
            glVersion = gl.glVersion,
            glslVersion = gl.glslVersion,
            vulkanVersion = gl.vulkanVersion,
            modeHint = detect.mode,
            backend = effectiveBackend,
            method = method,
            kgsl = kgsl,
            hostSocket = hostSocket,
            venusServer = venus.detail,
            pulseServer = pulse.detail,
            lastProbe = lastProbe,
            icd = icd,
            soc = soc,
            badge = GpuAccelDetector.badgeLabel(effectiveBackend)
        )
    }

    private fun guestRoot(ctx: Context): File {
        val method = LinuxCommandBuilder.currentMethod
        return if (method == "chroot") {
            File(ChrootCommandBuilder.CHROOT_PATH)
        } else {
            ProjectPathResolver.prootRootfsDir(ctx)
        }
    }

    private fun readTrim(file: File): String =
        try {
            if (file.isFile) file.readText().trim() else ""
        } catch (_: Throwable) {
            ""
        }

    internal fun sanitizeBackend(backend: String, kgsl: Boolean): String =
        if (kgsl && (backend.startsWith("panvk") || backend == "panfrost")) {
            GpuAccelDetector.MODE_TURNIP
        } else {
            backend
        }

    private fun firstIcd(rootfs: File, backend: String, mode: String): String {
        val panvkIcds = listOf(
            File(rootfs, "opt/nativecode/panvk/icd.d/panvk_kbase.json"),
            File(rootfs, "opt/nativecode/mesa-mali/share/vulkan/icd.d/panvk_kbase.json"),
            File(rootfs, "usr/share/vulkan/icd.d/panfrost_kbase_icd.aarch64.json"),
            File(rootfs, "usr/share/vulkan/icd.d/panfrost_icd.aarch64.json")
        )
        if (backend.contains("panvk") || backend.contains("panfrost") || mode == GpuAccelDetector.MODE_PANVK) {
            val hit = panvkIcds.firstOrNull { it.isFile }
            if (hit != null) {
                return hit.absolutePath.removePrefix(rootfs.absolutePath)
            }
        }
        val turnipOverlay = File(rootfs, "opt/nativecode-turnip/icd/freedreno_icd.aarch64.json")
        if ((backend.contains("turnip") || mode == GpuAccelDetector.MODE_TURNIP) && turnipOverlay.isFile) {
            return "/opt/nativecode-turnip/icd/freedreno_icd.aarch64.json"
        }
        val dir = File(rootfs, "usr/share/vulkan/icd.d")
        val key = "$backend $mode"
        val needle = when {
            key.contains("turnip") || key.contains("freedreno") -> "freedreno"
            key.contains("panvk") || key.contains("panfrost") -> "pan"
            key.contains("venus") || key.contains("virtio") -> "virtio"
            else -> null
        }
        if (needle != null && dir.isDirectory) {
            dir.listFiles()
                ?.firstOrNull { it.name.contains(needle, ignoreCase = true) && it.name.endsWith(".json") }
                ?.let { return "/usr/share/vulkan/icd.d/${it.name}" }
        }
        if (mode == GpuAccelDetector.MODE_TURNIP) return "none"
        val panvkFallback = panvkIcds.firstOrNull { it.isFile }
        if (panvkFallback != null) {
            return panvkFallback.absolutePath.removePrefix(rootfs.absolutePath)
        }
        return "none"
    }
}
