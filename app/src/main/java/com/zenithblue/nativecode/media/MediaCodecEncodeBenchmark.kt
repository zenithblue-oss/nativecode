package com.zenithblue.nativecode.media

import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

object MediaCodecEncodeBenchmark {

    private const val TAG = "MediaCodecEncodeBench"

    data class EncodeBenchmarkResult(
        val codecName: String,
        val isHardware: Boolean?,
        val mime: String,
        val width: Int,
        val height: Int,
        val inputFrames: Int,
        val encodedFrames: Int,
        val wallTimeMs: Long,
        val firstFrameLatencyMs: Long,
        val fps: Double,
        val realtimeFactor: Double,
        val outputSizeBytes: Long,
        val error: String? = null
    )

    /**
     * Finds the best supported YUV color format for the encoder.
     */
    fun selectColorFormat(codecInfo: MediaCodecInfo, mime: String): Int {
        val caps = codecInfo.getCapabilitiesForType(mime)
        val formats = caps.colorFormats

        val priority = listOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        )

        for (pref in priority) {
            if (formats.contains(pref)) {
                return pref
            }
        }
        return formats.firstOrNull() ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    }

    /**
     * Runs a standalone synthetic hardware encode benchmark generating moving YUV patterns.
     */
    fun runEncodeBenchmark(
        outputFile: File? = null,
        width: Int = 1280,
        height: Int = 720,
        frameCount: Int = 150,
        fps: Int = 30,
        bitrate: Int = 4_000_000,
        mime: String = "video/avc",
        preferredCodecName: String? = null,
        timeoutUs: Long = 10000L
    ): EncodeBenchmarkResult {
        val encoderInfo = if (preferredCodecName != null) {
            null
        } else {
            MediaCodecSelector.selectHardwareEncoder(mime)
        }

        val codecName = preferredCodecName ?: encoderInfo?.name
        if (codecName == null) {
            return EncodeBenchmarkResult(
                codecName = "unknown",
                isHardware = null,
                mime = mime,
                width = width,
                height = height,
                inputFrames = 0,
                encodedFrames = 0,
                wallTimeMs = 0,
                firstFrameLatencyMs = 0,
                fps = 0.0,
                realtimeFactor = 0.0,
                outputSizeBytes = 0,
                error = "No hardware encoder found for MIME: $mime"
            )
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoTrackIndex = -1

        val startTime = SystemClock.elapsedRealtime()
        var firstFrameTime: Long? = null
        var inputFrames = 0
        var encodedFrames = 0

        try {
            codec = MediaCodec.createByCodecName(codecName)
            val colorFormat = selectColorFormat(codec.codecInfo, mime)

            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            if (outputFile != null) {
                outputFile.parentFile?.mkdirs()
                if (outputFile.exists()) outputFile.delete()
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            val bufferInfo = BufferInfo()
            var inputEosSignaled = false
            var outputEosReached = false
            var currentInputIndex = 0

            val frameDurationUs = 1_000_000L / fps
            val frameSize = width * height * 3 / 2
            val rawPatternBuffer = ByteArray(frameSize)

            while (!outputEosReached) {
                // Feed input frames
                if (!inputEosSignaled) {
                    val inputBufIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputBufIndex >= 0) {
                        if (currentInputIndex >= frameCount) {
                            // Signal EOS
                            codec.queueInputBuffer(
                                inputBufIndex,
                                0,
                                0,
                                currentInputIndex * frameDurationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEosSignaled = true
                        } else {
                            val ptsUs = currentInputIndex * frameDurationUs
                            val inputImage = try { codec.getInputImage(inputBufIndex) } catch (e: Exception) { null }

                            if (inputImage != null) {
                                fillSyntheticImage(inputImage, width, height, currentInputIndex)
                                codec.queueInputBuffer(
                                    inputBufIndex,
                                    0,
                                    frameSize,
                                    ptsUs,
                                    0
                                )
                            } else {
                                val inputBuffer = codec.getInputBuffer(inputBufIndex)
                                if (inputBuffer != null) {
                                    fillSyntheticBuffer(rawPatternBuffer, width, height, currentInputIndex)
                                    inputBuffer.clear()
                                    inputBuffer.put(rawPatternBuffer, 0, frameSize)
                                    codec.queueInputBuffer(
                                        inputBufIndex,
                                        0,
                                        frameSize,
                                        ptsUs,
                                        0
                                    )
                                }
                            }
                            inputFrames++
                            currentInputIndex++
                        }
                    }
                }

                // Drain output frames
                val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxer != null && !muxerStarted) {
                            videoTrackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Wait for next output
                    }
                    outputBufIndex >= 0 -> {
                        if (firstFrameTime == null) {
                            firstFrameTime = SystemClock.elapsedRealtime()
                        }

                        val encodedBuf = codec.getOutputBuffer(outputBufIndex)
                        if (encodedBuf != null && bufferInfo.size > 0) {
                            if (muxerStarted && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                muxer?.writeSampleData(videoTrackIndex, encodedBuf, bufferInfo)
                            }
                            encodedFrames++
                        }

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEosReached = true
                        }

                        codec.releaseOutputBuffer(outputBufIndex, false)
                    }
                }
            }

            val wallTimeMs = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1L)
            val firstLatencyMs = if (firstFrameTime != null) firstFrameTime - startTime else 0L
            val fpsComputed = (encodedFrames.toDouble() / wallTimeMs.toDouble()) * 1000.0
            val realtimeFactor = fpsComputed / fps.toDouble()
            val outputSize = if (outputFile != null && outputFile.exists()) outputFile.length() else 0L

            val isHw = if (Build.VERSION.SDK_INT >= 29) {
                codec.codecInfo.isHardwareAccelerated
            } else {
                null
            }

            return EncodeBenchmarkResult(
                codecName = codecName,
                isHardware = isHw,
                mime = mime,
                width = width,
                height = height,
                inputFrames = inputFrames,
                encodedFrames = encodedFrames,
                wallTimeMs = wallTimeMs,
                firstFrameLatencyMs = firstLatencyMs,
                fps = fpsComputed,
                realtimeFactor = realtimeFactor,
                outputSizeBytes = outputSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Hardware encode benchmark failed", e)
            val elapsed = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1L)
            return EncodeBenchmarkResult(
                codecName = codecName,
                isHardware = null,
                mime = mime,
                width = width,
                height = height,
                inputFrames = inputFrames,
                encodedFrames = encodedFrames,
                wallTimeMs = elapsed,
                firstFrameLatencyMs = 0L,
                fps = 0.0,
                realtimeFactor = 0.0,
                outputSizeBytes = 0L,
                error = e.message ?: e.javaClass.simpleName
            )
        } finally {
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "muxer.stop failed", e)
                }
            }
            try { muxer?.release() } catch (ignored: Exception) {}
            try { codec?.stop() } catch (ignored: Exception) {}
            try { codec?.release() } catch (ignored: Exception) {}
        }
    }

    /**
     * Encodes a raw YUV420 byte stream arriving from an InputStream (pipe/socket) into an MP4 file.
     */
    fun runEncodeStream(
        rawInputStream: InputStream,
        outputFile: File,
        width: Int,
        height: Int,
        fps: Int = 30,
        bitrate: Int = 4_000_000,
        mime: String = "video/avc",
        preferredCodecName: String? = null,
        timeoutUs: Long = 10000L
    ): EncodeBenchmarkResult {
        val encoderInfo = if (preferredCodecName != null) {
            null
        } else {
            MediaCodecSelector.selectHardwareEncoder(mime)
        }

        val codecName = preferredCodecName ?: encoderInfo?.name
        if (codecName == null) {
            return EncodeBenchmarkResult(
                codecName = "unknown",
                isHardware = null,
                mime = mime,
                width = width,
                height = height,
                inputFrames = 0,
                encodedFrames = 0,
                wallTimeMs = 0,
                firstFrameLatencyMs = 0,
                fps = 0.0,
                realtimeFactor = 0.0,
                outputSizeBytes = 0,
                error = "No hardware encoder found for MIME: $mime"
            )
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var videoTrackIndex = -1

        val startTime = SystemClock.elapsedRealtime()
        var firstFrameTime: Long? = null
        var inputFrames = 0
        var encodedFrames = 0

        try {
            codec = MediaCodec.createByCodecName(codecName)
            val colorFormat = selectColorFormat(codec.codecInfo, mime)

            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = BufferInfo()
            var inputEosSignaled = false
            var outputEosReached = false
            var currentFrameIndex = 0

            val frameDurationUs = 1_000_000L / fps
            val frameBytes = width * height * 3 / 2
            val frameBuffer = ByteArray(frameBytes)

            while (!outputEosReached) {
                if (!inputEosSignaled) {
                    val inputBufIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputBufIndex >= 0) {
                        val readBytes = readExact(rawInputStream, frameBuffer, frameBytes)
                        if (readBytes < frameBytes) {
                            // Stream closed or EOF reached -> Signal EOS
                            codec.queueInputBuffer(
                                inputBufIndex,
                                0,
                                0,
                                currentFrameIndex * frameDurationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEosSignaled = true
                        } else {
                            val ptsUs = currentFrameIndex * frameDurationUs
                            val inputBuffer = codec.getInputBuffer(inputBufIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                inputBuffer.put(frameBuffer, 0, frameBytes)
                                codec.queueInputBuffer(
                                    inputBufIndex,
                                    0,
                                    frameBytes,
                                    ptsUs,
                                    0
                                )
                                inputFrames++
                                currentFrameIndex++
                            }
                        }
                    }
                }

                val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            videoTrackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    outputBufIndex >= 0 -> {
                        if (firstFrameTime == null) {
                            firstFrameTime = SystemClock.elapsedRealtime()
                        }

                        val encodedBuf = codec.getOutputBuffer(outputBufIndex)
                        if (encodedBuf != null && bufferInfo.size > 0) {
                            if (muxerStarted && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                muxer.writeSampleData(videoTrackIndex, encodedBuf, bufferInfo)
                            }
                            encodedFrames++
                        }

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEosReached = true
                        }

                        codec.releaseOutputBuffer(outputBufIndex, false)
                    }
                }
            }

            val wallTimeMs = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1L)
            val firstLatencyMs = if (firstFrameTime != null) firstFrameTime - startTime else 0L
            val fpsComputed = (encodedFrames.toDouble() / wallTimeMs.toDouble()) * 1000.0
            val realtimeFactor = fpsComputed / fps.toDouble()
            val outputSize = if (outputFile.exists()) outputFile.length() else 0L

            val isHw = if (Build.VERSION.SDK_INT >= 29) {
                codec.codecInfo.isHardwareAccelerated
            } else {
                null
            }

            return EncodeBenchmarkResult(
                codecName = codecName,
                isHardware = isHw,
                mime = mime,
                width = width,
                height = height,
                inputFrames = inputFrames,
                encodedFrames = encodedFrames,
                wallTimeMs = wallTimeMs,
                firstFrameLatencyMs = firstLatencyMs,
                fps = fpsComputed,
                realtimeFactor = realtimeFactor,
                outputSizeBytes = outputSize
            )
        } catch (e: Exception) {
            Log.e(TAG, "Hardware encode stream failed", e)
            val elapsed = (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(1L)
            return EncodeBenchmarkResult(
                codecName = codecName,
                isHardware = null,
                mime = mime,
                width = width,
                height = height,
                inputFrames = inputFrames,
                encodedFrames = encodedFrames,
                wallTimeMs = elapsed,
                firstFrameLatencyMs = 0L,
                fps = 0.0,
                realtimeFactor = 0.0,
                outputSizeBytes = 0L,
                error = e.message ?: e.javaClass.simpleName
            )
        } finally {
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "muxer.stop failed", e)
                }
            }
            try { muxer?.release() } catch (ignored: Exception) {}
            try { codec?.stop() } catch (ignored: Exception) {}
            try { codec?.release() } catch (ignored: Exception) {}
        }
    }

    private fun readExact(input: InputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val count = input.read(buffer, total, length - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    private fun fillSyntheticImage(image: android.media.Image, width: Int, height: Int, frameIndex: Int) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        val offset = (frameIndex * 4) % 256

        // Fill Y
        yBuf.position(0)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val lum = ((col + row + offset) and 0xFF).toByte()
                yBuf.put(row * yRowStride + col * yPixelStride, lum)
            }
        }

        // Fill U and V
        val halfW = width / 2
        val halfH = height / 2
        for (row in 0 until halfH) {
            for (col in 0 until halfW) {
                val uVal = ((128 + offset / 2) and 0xFF).toByte()
                val vVal = ((128 - offset / 2) and 0xFF).toByte()
                uBuf.put(row * uvRowStride + col * uvPixelStride, uVal)
                vBuf.put(row * vPlane.rowStride + col * vPlane.pixelStride, vVal)
            }
        }
    }

    private fun fillSyntheticBuffer(buffer: ByteArray, width: Int, height: Int, frameIndex: Int) {
        val offset = (frameIndex * 4) % 256
        val ySize = width * height

        // Fill Y
        var idx = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                buffer[idx++] = ((col + row + offset) and 0xFF).toByte()
            }
        }

        // Fill UV (semi-planar NV12)
        val halfW = width / 2
        val halfH = height / 2
        for (row in 0 until halfH) {
            for (col in 0 until halfW) {
                buffer[idx++] = ((128 + offset / 2) and 0xFF).toByte()
                buffer[idx++] = ((128 - offset / 2) and 0xFF).toByte()
            }
        }
    }
}
