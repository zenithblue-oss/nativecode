package com.zenithblue.nativecode.terminal

import android.os.Build
import android.util.Log
import java.io.File

/**
 * Host GPU vendor → guest accel mode hint.
 *
 * Snapdragon / Adreno (KGSL) → Turnip + Zink
 * Everything else → Venus (VirGL remains fallback at runtime)
 *
 * Runtime backend is chosen by guest `gpu-launch --auto` probes, not this hint alone.
 */
object GpuAccelDetector {

    private const val TAG = "GpuAccelDetector"

    /** Guest FLUX_GPU values accepted by setup_hw_accel_debian.sh */
    const val MODE_TURNIP = "turnip"
    const val MODE_PANVK = "panvk"
    const val MODE_VENUS = "venus"
    const val MODE_VIRGL = "virgl"

    data class Detection(
        val mode: String,
        val vendorHint: String,
        val signals: String
    )

    fun detect(): Detection {
        val parts = mutableListOf<String>()
        fun add(label: String, value: String?) {
            val v = value?.trim().orEmpty()
            if (v.isNotEmpty()) parts += "$label=$v"
        }

        add("HARDWARE", Build.HARDWARE)
        add("BOARD", Build.BOARD)
        add("DEVICE", Build.DEVICE)
        add("PRODUCT", Build.PRODUCT)
        add("MANUFACTURER", Build.MANUFACTURER)
        add("BRAND", Build.BRAND)
        add("MODEL", Build.MODEL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add("SOC_MANUFACTURER", Build.SOC_MANUFACTURER)
            add("SOC_MODEL", Build.SOC_MODEL)
        }

        val props = listOf(
            "ro.hardware",
            "ro.hardware.chipname",
            "ro.chipname",
            "ro.board.platform",
            "ro.soc.model",
            "ro.soc.manufacturer",
            "ro.product.board",
            "ro.hardware.egl",
            "ro.hardware.vulkan",
            "ro.gfx.driver.0",
            "ro.opengles.version"
        )
        for (key in props) {
            add(key, sysProp(key))
        }

        val kgsl = File("/dev/kgsl-3d0").exists() || File("/dev/kgsl-3d0").canRead()
        if (kgsl) parts += "kgsl=/dev/kgsl-3d0"

        val mali = File("/dev/mali0").exists() || File("/dev/mali0").canRead()
        if (mali) parts += "mali=/dev/mali0"

        val blob = parts.joinToString(" ").lowercase()
        val result = classify(blob, kgsl, mali)
        Log.i(TAG, "GPU detect mode=${result.mode} vendor=${result.vendorHint} signals=${result.signals}")
        return result
    }

    /** Value for env FLUX_GPU=… */
    fun fluxGpuEnv(): String = detect().mode

    fun isTurnip(): Boolean = fluxGpuEnv() == MODE_TURNIP

    fun isPanvk(): Boolean = fluxGpuEnv() == MODE_PANVK

    /**
     * Pure classification used by [detect] and unit tests.
     * [blob] is a lowercase concat of Build / getprop / node signals.
     */
    fun classify(blob: String, kgslPresent: Boolean, maliPresent: Boolean = false): Detection {
        val isAdreno = kgslPresent || matchesAdreno(blob)
        val isMali = matchesMali(blob)
        val mode = when {
            isAdreno -> MODE_TURNIP
            isMali && maliPresent -> MODE_PANVK
            else -> MODE_VENUS
        }
        val vendor = when {
            isAdreno -> "adreno/snapdragon"
            isMali -> "mali"
            matchesPowerVr(blob) -> "powervr"
            matchesXclipse(blob) -> "xclipse"
            else -> "unknown"
        }
        return Detection(mode = mode, vendorHint = vendor, signals = blob)
    }

    fun methodLabel(mode: String): String = when (mode) {
        MODE_TURNIP -> "Turnip Vulkan + Freedreno GL (KGSL)"
        MODE_PANVK -> "PanVK (Kbase)"
        "panvk-zink" -> "Zink over PanVK"
        "panfrost" -> "Panfrost (Gallium Kbase)"
        MODE_VENUS -> "Venus (VirtIO Vulkan)"
        MODE_VIRGL -> "VirGL (virpipe)"
        "zink-venus" -> "Zink over Venus"
        "virgl-angle" -> "VirGL (virpipe)"
        "llvmpipe", "software" -> "llvmpipe (software)"
        else -> mode
    }

    fun badgeLabel(backend: String): String = when (backend) {
        MODE_TURNIP -> "TURNIP"
        MODE_PANVK, "panvk-zink" -> "PANVK"
        "panfrost" -> "PANFROST"
        MODE_VENUS, "zink-venus" -> "VENUS"
        MODE_VIRGL, "virgl-angle" -> "VIRGL"
        "llvmpipe", "software" -> "SOFTWARE"
        else -> "UNAVAILABLE"
    }

    private fun matchesAdreno(blob: String): Boolean {
        val needles = listOf(
            "qcom", "qualcomm", "adreno", "kgsl", "snapdragon",
            "msm", "sdm", "sm8150", "sm8250", "sm8350", "sm8450", "sm8550", "sm8650", "sm8750",
            "sm6", "sm7", "sm8", "sm4", "sm5",
            "lahaina", "taro", "kalama", "pineapple", "canoe", "sun",
            "kona", "lito", "bengal", "holi", "crow", "ravelin", "parrot", "blair",
            "anorak", "hamoa", "volcano", "pitti", "niobe", "cliq", "shima", "yupik",
            "atoll", "trinket", "guppy", "strait", "bitra", "waipio"
        )
        return needles.any { blob.contains(it) }
    }

    private fun matchesMali(blob: String): Boolean {
        val needles = listOf(
            "mali", "exynos", "kirin", "hisi", "mediatek", "mt68", "mt67", "mt69",
            "dimensity", "helio", "tensor", "gs10", "gs20", "gs30"
        )
        return needles.any { blob.contains(it) }
    }

    private fun matchesPowerVr(blob: String): Boolean =
        listOf("powervr", "imgtec", "imagination", "rogue").any { blob.contains(it) }

    private fun matchesXclipse(blob: String): Boolean =
        listOf("xclipse", "amdgpu", "samsung_xclipse").any { blob.contains(it) }

    private fun sysProp(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, key, "") as? String).orEmpty()
        } catch (_: Throwable) {
            try {
                val p = ProcessBuilder("getprop", key)
                    .redirectErrorStream(true)
                    .start()
                p.inputStream.bufferedReader().use { it.readText() }.trim()
            } catch (_: Throwable) {
                ""
            }
        }
    }
}
