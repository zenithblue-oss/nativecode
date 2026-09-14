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
        val kgsl = File("/dev/kgsl-3d0").exists()
        val venus = HostServerController.status(HostServerController.Kind.VENUS)
        val pulse = HostServerController.status(HostServerController.Kind.PULSE)
        val hostSocket = venus.running && File(TermuxHostPaths.TMPDIR, ".virgl_test").exists()
        val rootfs = guestRoot(ctx)
        val gpuMode = readTrim(File(rootfs, "etc/fluxlinux/gpu_mode"))
        val backend = readTrim(File(rootfs, "etc/fluxlinux/gpu_backend"))
            .ifBlank { gpuMode }
            .ifBlank { detect.mode }
        val lastProbe = readTrim(File(rootfs, "etc/fluxlinux/gpu_last_probe"))
        val method = when (gpuMode) {
            "turnip" -> GpuAccelDetector.methodLabel("turnip")
            "venus" -> GpuAccelDetector.methodLabel("zink-venus")
            "virgl" -> GpuAccelDetector.methodLabel("virgl")
            else -> readTrim(File(rootfs, "etc/fluxlinux/gpu_method"))
                .ifBlank { GpuAccelDetector.methodLabel(backend) }
        }
        val vendor = readTrim(File(rootfs, "etc/fluxlinux/gpu_vendor"))
            .ifBlank { detect.vendorHint }
        val icd = firstIcd(rootfs, backend, gpuMode.ifBlank { detect.mode })
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
            backend = backend,
            method = method,
            kgsl = kgsl,
            hostSocket = hostSocket,
            venusServer = venus.detail,
            pulseServer = pulse.detail,
            lastProbe = lastProbe,
            icd = icd,
            soc = soc,
            badge = GpuAccelDetector.badgeLabel(gpuMode.ifBlank { backend })
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

    private fun firstIcd(rootfs: File, backend: String, mode: String): String {
        val dir = File(rootfs, "usr/share/vulkan/icd.d")
        val key = "$backend $mode"
        val needle = when {
            key.contains("turnip") -> "freedreno"
            key.contains("venus") || key.contains("virtio") -> "virtio"
            else -> null
        }
        if (needle != null && dir.isDirectory) {
            dir.listFiles()
                ?.firstOrNull { it.name.contains(needle, ignoreCase = true) && it.name.endsWith(".json") }
                ?.let { return "/usr/share/vulkan/icd.d/${it.name}" }
        }
        val overlay = File(rootfs, "opt/nativecode-turnip/icd/freedreno_icd.aarch64.json")
        if (backend == "turnip" && overlay.isFile) {
            return "/opt/nativecode-turnip/icd/freedreno_icd.aarch64.json"
        }
        return "none"
    }
}
