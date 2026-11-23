package com.zenithblue.nativecode.agent.chat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Bounded decoders for inline preview cards. Every function is blocking (call off the main thread) and returns
 * null on any failure: a bad, huge or unsupported file shows an error card, it never throws.
 */
internal object FileThumbs {
    /** Inline decode refuses bigger files (the card offers the viewer / external app instead). */
    const val MAX_BYTES = 20L shl 20

    /**
     * Power-of-two `inSampleSize`: keeps the shorter side at least [target] px, then halves further until the
     * bitmap has at most [maxPixels] pixels (a 1 x 10000 strip must not decode at full size).
     */
    fun sampleSize(w: Int, h: Int, target: Int, maxPixels: Long = 4_000_000L): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        while (w / (s * 2) >= target && h / (s * 2) >= target) s *= 2
        while ((w / s).toLong() * (h / s) > maxPixels) s *= 2
        return s
    }

    fun image(file: File, target: Int): Bitmap? = runCatching {
        if (!file.isFile || file.length() > MAX_BYTES) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSize(o.outWidth, o.outHeight, target) })
    }.getOrNull()

    /** First video frame, scaled to at most [maxPx] on the long side. The retriever seeks the file; it never loads it whole. */
    fun videoPoster(file: File, maxPx: Int): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val f = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val k = maxPx.toFloat() / maxOf(f.width, f.height)
            if (k >= 1f) f else Bitmap.createScaledBitmap(f, (f.width * k).toInt().coerceAtLeast(1), (f.height * k).toInt().coerceAtLeast(1), true)
        } finally {
            runCatching { r.release() }
        }
    }.getOrNull()

    /** First page of a PDF as a [widthPx] wide bitmap. */
    fun pdfPage(file: File, widthPx: Int): Bitmap? = PdfDoc.open(file)?.let { d -> try { d.render(0, widthPx) } finally { d.close() } }
}

/**
 * One open PDF. PdfRenderer is not thread safe and shows one page at a time, so every call is serialized on this
 * object; [close] is final. A password protected or corrupt file does not open ([open] returns null).
 */
internal class PdfDoc private constructor(private val fd: ParcelFileDescriptor, private val r: PdfRenderer) {
    val pageCount: Int = r.pageCount
    private var closed = false

    /** Page [page] on white, [widthPx] wide (height follows the page ratio, capped at 3x the width); null once closed or on failure. */
    fun render(page: Int, widthPx: Int): Bitmap? = synchronized(this) {
        if (closed || page !in 0 until pageCount) return null
        runCatching {
            r.openPage(page).use { p ->
                val h = (widthPx.toLong() * p.height / p.width.coerceAtLeast(1)).toInt().coerceIn(1, widthPx * 3)
                val b = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE)
                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                b
            }
        }.getOrNull()
    }

    fun close() = synchronized(this) {
        if (!closed) {
            closed = true
            runCatching { r.close() }
            runCatching { fd.close() }
        }
    }

    companion object {
        fun open(file: File): PdfDoc? {
            var fd: ParcelFileDescriptor? = null
            return try {
                fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val r = PdfRenderer(fd)
                if (r.pageCount > 0) PdfDoc(fd, r) else { r.close(); fd.close(); null }
            } catch (_: Throwable) { // SecurityException (password), IOException (corrupt), OOM
                runCatching { fd?.close() }
                null
            }
        }
    }
}
