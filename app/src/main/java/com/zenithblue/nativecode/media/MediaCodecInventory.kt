package com.zenithblue.nativecode.media

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaCodecInventory {

    val PRIORITY_MIMES = listOf(
        "video/avc",
        "video/hevc",
        "video/x-vnd.on2.vp9",
        "video/av01"
    )

    private val PROBE_RESOLUTIONS = listOf(
        Triple(1280, 720, 30.0),
        Triple(1920, 1080, 30.0),
        Triple(1920, 1080, 60.0),
        Triple(3840, 2160, 30.0),
        Triple(3840, 2160, 60.0)
    )

    data class CodecEntry(
        val name: String,
        val canonicalName: String?,
        val isEncoder: Boolean,
        val isHardwareAccelerated: Boolean?,
        val isSoftwareOnly: Boolean?,
        val isVendor: Boolean?,
        val isAlias: Boolean?,
        val mime: String,
        val colorFormats: List<Int>,
        val maxSupportedInstances: Int,
        val widthRange: Pair<Int, Int>?,
        val heightRange: Pair<Int, Int>?,
        val bitrateRange: Pair<Int, Int>?,
        val supportedResolutions: Map<String, Boolean>
    )

    data class InventoryReport(
        val manufacturer: String,
        val model: String,
        val sdk: Int,
        val socModel: String,
        val socPlatform: String,
        val timestamp: Long,
        val codecs: List<CodecEntry>
    ) {
        fun toJsonObject(): JSONObject {
            val root = JSONObject()
            val deviceObj = JSONObject().apply {
                put("manufacturer", manufacturer)
                put("model", model)
                put("sdk", sdk)
                put("soc_model", socModel)
                put("soc_platform", socPlatform)
            }
            root.put("device", deviceObj)
            root.put("timestamp", timestamp)

            val codecArray = JSONArray()
            for (codec in codecs) {
                val cObj = JSONObject().apply {
                    put("name", codec.name)
                    put("canonical_name", codec.canonicalName ?: JSONObject.NULL)
                    put("is_encoder", codec.isEncoder)
                    put("hardware_accelerated", codec.isHardwareAccelerated ?: JSONObject.NULL)
                    put("software_only", codec.isSoftwareOnly ?: JSONObject.NULL)
                    put("vendor", codec.isVendor ?: JSONObject.NULL)
                    put("alias", codec.isAlias ?: JSONObject.NULL)
                    put("mime", codec.mime)
                    put("max_instances", codec.maxSupportedInstances)
                    val cfArr = JSONArray()
                    codec.colorFormats.forEach { cfArr.put(it) }
                    put("color_formats", cfArr)

                    val resObj = JSONObject()
                    codec.supportedResolutions.forEach { (k, v) -> resObj.put(k, v) }
                    put("supported_resolutions", resObj)
                }
                codecArray.put(cObj)
            }
            root.put("codecs", codecArray)
            return root
        }
    }

    fun collectInventory(): InventoryReport {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        val entries = mutableListOf<CodecEntry>()

        for (info in codecList) {
            val supportedPriority = info.supportedTypes.filter { type ->
                PRIORITY_MIMES.any { it.equals(type, ignoreCase = true) }
            }
            if (supportedPriority.isEmpty()) continue

            val isHw: Boolean?
            val isSw: Boolean?
            val isVendor: Boolean?
            val isAlias: Boolean?
            val canonicalName: String?

            if (Build.VERSION.SDK_INT >= 29) {
                isHw = info.isHardwareAccelerated
                isSw = info.isSoftwareOnly
                isVendor = info.isVendor
                isAlias = info.isAlias
                canonicalName = info.canonicalName
            } else {
                isHw = null
                isSw = null
                isVendor = null
                isAlias = null
                canonicalName = null
            }

            for (mime in supportedPriority) {
                val caps = try {
                    info.getCapabilitiesForType(mime)
                } catch (e: Exception) {
                    null
                }

                val colorFormats = caps?.colorFormats?.toList() ?: emptyList()
                val maxInstances = if (Build.VERSION.SDK_INT >= 23) {
                    caps?.maxSupportedInstances ?: 1
                } else {
                    1
                }

                val videoCaps = caps?.videoCapabilities
                val widthRange = videoCaps?.supportedWidths?.let { Pair(it.lower, it.upper) }
                val heightRange = videoCaps?.supportedHeights?.let { Pair(it.lower, it.upper) }
                val bitrateRange = videoCaps?.bitrateRange?.let { Pair(it.lower, it.upper) }

                val supportedResolutions = mutableMapOf<String, Boolean>()
                if (videoCaps != null) {
                    for ((w, h, fps) in PROBE_RESOLUTIONS) {
                        val supported = try {
                            videoCaps.areSizeAndRateSupported(w, h, fps)
                        } catch (e: Exception) {
                            false
                        }
                        supportedResolutions["${w}x${h}@${fps.toInt()}"] = supported
                    }
                }

                entries.add(
                    CodecEntry(
                        name = info.name,
                        canonicalName = canonicalName,
                        isEncoder = info.isEncoder,
                        isHardwareAccelerated = isHw,
                        isSoftwareOnly = isSw,
                        isVendor = isVendor,
                        isAlias = isAlias,
                        mime = mime,
                        colorFormats = colorFormats,
                        maxSupportedInstances = maxInstances,
                        widthRange = widthRange,
                        heightRange = heightRange,
                        bitrateRange = bitrateRange,
                        supportedResolutions = supportedResolutions
                    )
                )
            }
        }

        return InventoryReport(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdk = Build.VERSION.SDK_INT,
            socModel = Build.SOC_MODEL.takeIf { Build.VERSION.SDK_INT >= 31 } ?: "",
            socPlatform = Build.HARDWARE,
            timestamp = System.currentTimeMillis(),
            codecs = entries
        )
    }

    fun writeInventoryToFile(context: Context): File {
        val report = collectInventory()
        val json = report.toJsonObject().toString(2)
        val diagDir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(report.timestamp))
        val targetFile = File(diagDir, "media_codec_inventory_$dateStr.json")
        targetFile.writeText(json)
        return targetFile
    }
}
