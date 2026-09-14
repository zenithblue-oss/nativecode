package com.zenithblue.nativecode.media

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * Manages an ImageReader with a dedicated background draining loop.
 * Acquires and immediately closes acquired frames to prevent backpressure
 * stalling the MediaCodec hardware decoder during Mode B (Surface decode).
 */
class ImageReaderDrainPipeline(
    val width: Int,
    val height: Int,
    maxImages: Int = 3
) : AutoCloseable {

    private val drainThread = HandlerThread("ImageReaderDrain").apply { start() }
    private val handler = Handler(drainThread.looper)

    val imageReader: ImageReader = ImageReader.newInstance(
        width,
        height,
        ImageFormat.PRIVATE,
        maxImages
    ).apply {
        setOnImageAvailableListener({ reader ->
            try {
                reader.acquireLatestImage()?.close()
            } catch (ignored: Throwable) {
            }
        }, handler)
    }

    val surface: Surface get() = imageReader.surface

    override fun close() {
        try {
            imageReader.close()
        } catch (ignored: Throwable) {
        }
        drainThread.quitSafely()
    }
}
