package com.zenithblue.nativecode.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

object MediaCodecSelector {

    private val SOFTWARE_PREFIXES = listOf(
        "c2.android.",
        "omx.google.",
        "omx.ffmpeg.",
        "c2.google."
    )

    private val HARDWARE_VENDOR_SIGNATURES = listOf(
        "qti", "qcom", "mtk", "mediatek", "exynos", "samsung", "hisilicon", "kirin"
    )

    /**
     * Checks whether a codec name indicates a secure DRM decoder.
     * Secure decoders require DRM crypto sessions and throw CryptoException/CodecException
     * if configured with normal non-secure bytebuffers or surfaces.
     */
    fun isSecureCodec(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".secure") || lower.contains(".secure.")
    }

    /**
     * Universal hardware decoder selection supporting MediaTek, Qualcomm, Exynos, Tensor,
     * and generic Android vendor HALs.
     */
    fun selectHardwareDecoder(
        mime: String,
        codecList: Array<MediaCodecInfo> = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    ): MediaCodecInfo? {
        val candidates = codecList.filter { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                !isSecureCodec(info.name)
        }

        if (Build.VERSION.SDK_INT >= 29) {
            // Priority 1: Hardware-accelerated vendor codec (e.g. c2.mtk.avc.decoder, c2.qti.avc.decoder)
            val vendorHw = candidates.firstOrNull { info ->
                info.isHardwareAccelerated && !info.isSoftwareOnly && info.isVendor
            }
            if (vendorHw != null) return vendorHw

            // Priority 2: Hardware-accelerated non-software codec
            val hw = candidates.firstOrNull { info ->
                info.isHardwareAccelerated && !info.isSoftwareOnly
            }
            if (hw != null) return hw
        }

        // Fallback for API 26-28 or when API 29+ flags are unpopulated:
        // Filter out known software prefixes, then prioritize vendor signatures.
        val nonSoftware = candidates.filter { info ->
            val lower = info.name.lowercase()
            SOFTWARE_PREFIXES.none { lower.startsWith(it) }
        }

        val vendorMatch = nonSoftware.firstOrNull { info ->
            val lower = info.name.lowercase()
            HARDWARE_VENDOR_SIGNATURES.any { lower.contains(it) }
        }
        if (vendorMatch != null) return vendorMatch

        return nonSoftware.firstOrNull()
    }

    /**
     * Universal hardware encoder selection.
     */
    fun selectHardwareEncoder(
        mime: String,
        codecList: Array<MediaCodecInfo> = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
    ): MediaCodecInfo? {
        val candidates = codecList.filter { info ->
            info.isEncoder &&
                info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                !isSecureCodec(info.name)
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val vendorHw = candidates.firstOrNull { info ->
                info.isHardwareAccelerated && !info.isSoftwareOnly && info.isVendor
            }
            if (vendorHw != null) return vendorHw

            val hw = candidates.firstOrNull { info ->
                info.isHardwareAccelerated && !info.isSoftwareOnly
            }
            if (hw != null) return hw
        }

        val nonSoftware = candidates.filter { info ->
            val lower = info.name.lowercase()
            SOFTWARE_PREFIXES.none { lower.startsWith(it) }
        }

        val vendorMatch = nonSoftware.firstOrNull { info ->
            val lower = info.name.lowercase()
            HARDWARE_VENDOR_SIGNATURES.any { lower.contains(it) }
        }
        if (vendorMatch != null) return vendorMatch

        return nonSoftware.firstOrNull()
    }
}
