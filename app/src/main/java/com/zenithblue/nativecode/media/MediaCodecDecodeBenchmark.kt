package com.zenithblue.nativecode.media

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import java.io.File

object MediaCodecDecodeBenchmark {

    enum class OutputMode {
        BUFFER_DISCARD,
        SURFACE_IMAGE_READER
    }

    data class DecodeBenchmarkResult(
        val codecName: String,
        val isHardware: Boolean?,
        val mime: String,
        val width: Int,
        val height: Int,
        val inputSamples: Int,
        val decodedFrames: Int,
        val wallTimeMs: Long,
        val firstFrameLatencyMs: Long,
        val fps: Double,
        val realtimeFactor: Double,
        val error: String? = null
    )

    fun runDecode(
        videoFile: File,
        mode: OutputMode = OutputMode.BUFFER_DISCARD,
        preferredCodecName: String? = null,
        timeoutUs: Long = 10000L
    ): DecodeBenchmarkResult {
        if (!videoFile.exists() || !videoFile.canRead()) {
            return DecodeBenchmarkResult(
                codecName = preferredCodecName ?: "unknown",
                isHardware = null,
                mime = "unknown",
                width = 0,
                height = 0,
                inputSamples = 0,
                decodedFrames = 0,
                wallTimeMs = 0,
                firstFrameLatencyMs = 0,
                fps = 0.0,
                realtimeFactor = 0.0,
                error = "File does not exist or cannot be read: ${videoFile.absolutePath}"
            )
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var drainPipeline: ImageReaderDrainPipeline? = null

        var width = 0
        var height = 0
        var mime = ""
        var isHardware: Boolean? = null
        var selectedCodecName = preferredCodecName ?: ""

        val startTime = SystemClock.elapsedRealtime()
        var firstFrameTime: Long? = null
        var inputSamples = 0
        var decodedFrames = 0

        try {
            extractor.setDataSource(videoFile.absolutePath)
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (trackMime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    mime = trackMime
                    break
                }
            }

            if (videoTrackIndex < 0 || videoFormat == null) {
                return DecodeBenchmarkResult(
                    codecName = selectedCodecName,
                    isHardware = null,
                    mime = mime,
                    width = 0,
                    height = 0,
                    inputSamples = 0,
                    decodedFrames = 0,
                    wallTimeMs = 0,
                    firstFrameLatencyMs = 0,
                    fps = 0.0,
                    realtimeFactor = 0.0,
                    error = "No video track found in ${videoFile.name}"
                )
            }

            extractor.selectTrack(videoTrackIndex)
            width = if (videoFormat.containsKey(MediaFormat.KEY_WIDTH)) videoFormat.getInteger(MediaFormat.KEY_WIDTH) else 1920
            height = if (videoFormat.containsKey(MediaFormat.KEY_HEIGHT)) videoFormat.getInteger(MediaFormat.KEY_HEIGHT) else 1080

            if (selectedCodecName.isEmpty()) {
                val candidate = MediaCodecSelector.selectHardwareDecoder(mime)
                if (candidate != null) {
                    selectedCodecName = candidate.name
                    if (Build.VERSION.SDK_INT >= 29) {
                        isHardware = candidate.isHardwareAccelerated
                    }
                }
            }

            codec = if (selectedCodecName.isNotEmpty()) {
                MediaCodec.createByCodecName(selectedCodecName)
            } else {
                MediaCodec.createDecoderByType(mime)
            }
            selectedCodecName = codec.name

            val surface = if (mode == OutputMode.SURFACE_IMAGE_READER) {
                drainPipeline = ImageReaderDrainPipeline(width, height)
                drainPipeline.surface
            } else {
                null
            }

            codec.configure(videoFormat, surface, null, 0)
            codec.start()

            var inputEos = false
            var outputEos = false
            val bufferInfo = BufferInfo()

            while (!outputEos) {
                if (!inputEos) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                            } else {
                                val pts = extractor.sampleTime
                                codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                                extractor.advance()
                                inputSamples++
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outIndex >= 0 -> {
                        if (firstFrameTime == null && bufferInfo.size > 0) {
                            firstFrameTime = SystemClock.elapsedRealtime()
                        }
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEos = true
                        }
                        if (bufferInfo.size > 0) {
                            decodedFrames++
                        }
                        val render = (mode == OutputMode.SURFACE_IMAGE_READER && !outputEos)
                        codec.releaseOutputBuffer(outIndex, render)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                            width = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                        }
                        if (newFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                            height = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        }
                    }
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // wait and retry
                    }
                }
            }

            val totalWallMs = SystemClock.elapsedRealtime() - startTime
            val firstLatency = (firstFrameTime ?: SystemClock.elapsedRealtime()) - startTime
            val fps = if (totalWallMs > 0) (decodedFrames * 1000.0) / totalWallMs else 0.0
            val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) videoFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val videoDurationSec = durationUs / 1_000_000.0
            val realtimeFactor = if (totalWallMs > 0 && videoDurationSec > 0) videoDurationSec / (totalWallMs / 1000.0) else 0.0

            return DecodeBenchmarkResult(
                codecName = selectedCodecName,
                isHardware = isHardware,
                mime = mime,
                width = width,
                height = height,
                inputSamples = inputSamples,
                decodedFrames = decodedFrames,
                wallTimeMs = totalWallMs,
                firstFrameLatencyMs = firstLatency,
                fps = fps,
                realtimeFactor = realtimeFactor
            )
        } catch (t: Throwable) {
            val totalWallMs = SystemClock.elapsedRealtime() - startTime
            return DecodeBenchmarkResult(
                codecName = selectedCodecName,
                isHardware = isHardware,
                mime = mime,
                width = width,
                height = height,
                inputSamples = inputSamples,
                decodedFrames = decodedFrames,
                wallTimeMs = totalWallMs,
                firstFrameLatencyMs = 0,
                fps = 0.0,
                realtimeFactor = 0.0,
                error = t.message ?: t.javaClass.simpleName
            )
        } finally {
            try {
                codec?.stop()
            } catch (ignored: Throwable) {}
            try {
                codec?.release()
            } catch (ignored: Throwable) {}
            try {
                extractor.release()
            } catch (ignored: Throwable) {}
            try {
                drainPipeline?.close()
            } catch (ignored: Throwable) {}
        }
    }
}
