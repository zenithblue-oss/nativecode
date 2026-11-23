package com.zenithblue.nativecode.workspace

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.agent.chat.ui.FileThumbs
import com.zenithblue.nativecode.agent.chat.ui.PdfDoc
import com.zenithblue.nativecode.agent.chat.ui.bridgeSize
import com.zenithblue.nativecode.tr
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Full-screen viewers the Directory file dispatcher (and so every chat link) uses for files the editor cannot show:
 * images (pinch zoom, animated GIF/WebP), SVG, HTML, audio, PDF. Plain Views, built for the file viewer content host.
 * Which viewer a file gets is decided by `FileKinds`; nothing here knows extensions.
 *
 * Safety rules: files are decoded downsampled and off the main thread, a PDF page is rendered one at a time, and any
 * failure turns the viewer into a small error card ([error]) instead of throwing. HTML and SVG come from an agent
 * and are untrusted: they only ever load into [lockedWebView] (JavaScript off, no network, no file or content access).
 */
internal object FileViewers {
    private val ui = Handler(Looper.getMainLooper())

    /** Images bigger than this are not decoded in-app (the error card offers an external app). */
    private const val MAX_IMAGE_FILE = 64L shl 20
    /** HTML / SVG source is read whole into a string, so it is capped. */
    private const val MAX_MARKUP_FILE = 4L shl 20
    private const val MAX_PIXELS = 8_000_000L

    private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun View.onDetach(block: () -> Unit) = addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) = block()
    })

    private fun box(ctx: Context, fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(ctx.dp(1), stroke)
        cornerRadius = ctx.dp(8).toFloat()
    }

    private fun mono(ctx: Context, text: String, size: Float, color: Int) = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = Typeface.MONOSPACE
    }

    private fun pill(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(NC.PRIMARY)
        background = box(ctx, NC.SURFACE_VAR, NC.BORDER)
        setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
        minHeight = ctx.dp(40)
        gravity = Gravity.CENTER
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Small error card: what failed, and a way out through an external app. */
    fun error(ctx: Context, file: File, message: String, onExternal: () -> Unit): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = box(ctx, NC.SURFACE, NC.ERROR)
        setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(ctx.dp(16), ctx.dp(16), ctx.dp(16), 0)
        }
        addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_lucide_file)
            setColorFilter(NC.ERROR)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32))
        })
        addView(mono(ctx, file.name, 14f, NC.ON_SURFACE).apply { paint.isFakeBoldText = true; setPadding(0, ctx.dp(8), 0, 0) })
        addView(mono(ctx, message, 12f, NC.ON_SURF_VAR).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(12)) })
        addView(pill(ctx, tr(R.string.viewer_open_external), onExternal))
    }

    private fun swapToError(host: ViewGroup, file: File, message: String, onExternal: () -> Unit) {
        host.removeAllViews()
        host.addView(error(host.context, file, message, onExternal))
    }

    // ── Images ───────────────────────────────────────────────────────────────

    /** Pinch-zoom / pan / double-tap-reset image. Decoding happens off the main thread. */
    fun image(ctx: Context, file: File, onExternal: () -> Unit): View {
        val host = FrameLayout(ctx).apply { setBackgroundColor(NC.BG) }
        if (file.length() > MAX_IMAGE_FILE) {
            swapToError(host, file, tr(R.string.viewer_too_large, bridgeSize(file.length())), onExternal)
            return host
        }
        val zoom = ZoomImageView(ctx)
        val info = mono(ctx, file.name + " · " + bridgeSize(file.length()), 11f, NC.ON_SURF_VAR).apply {
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        host.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(zoom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
                addView(info)
                addView(pill(ctx, tr(R.string.viewer_open_external), onExternal))
            })
        })
        val target = ctx.resources.displayMetrics.widthPixels
        Thread {
            val decoded = try { decode(ctx, file, target) } catch (_: Throwable) { null } // incl. OutOfMemoryError
            host.post {
                if (decoded == null) swapToError(host, file, tr(R.string.viewer_cant_open), onExternal)
                else {
                    zoom.setImageDrawable(decoded.first)
                    info.text = decoded.second + " · " + bridgeSize(file.length())
                    (decoded.first as? AnimatedImageDrawable)?.start()
                }
            }
        }.apply { isDaemon = true; start() }
        host.onDetach { (zoom.drawable as? AnimatedImageDrawable)?.stop() }
        return host
    }

    /** Drawable (animated for GIF / animated WebP on API 28+) and "W×H"; sample size keeps it under [MAX_PIXELS]. */
    private fun decode(ctx: Context, file: File, target: Int): Pair<Drawable, String>? {
        if (Build.VERSION.SDK_INT >= 28) {
            var dims = ""
            val d = runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { dec, i, _ ->
                    dims = "${i.size.width}×${i.size.height}"
                    dec.setTargetSampleSize(FileThumbs.sampleSize(i.size.width, i.size.height, target, MAX_PIXELS))
                }
            }.getOrNull()
            if (d != null) return d to dims
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val bmp: Bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = FileThumbs.sampleSize(o.outWidth, o.outHeight, target, MAX_PIXELS)
        }) ?: return null
        return BitmapDrawable(ctx.resources, bmp) to "${o.outWidth}×${o.outHeight}"
    }

    // ── SVG / HTML (agent-authored, untrusted) ───────────────────────────────

    /**
     * The only WebView configuration used for agent markup: JavaScript off, no network (blocked at the engine and
     * again by answering every request with an empty body), no file or content access, no navigation, no storage.
     * The document is handed over as a string with a null base URL, so it cannot reach any file but itself.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun lockedWebView(ctx: Context): WebView? = try {
        WebView(ctx).apply {
            settings.apply {
                javaScriptEnabled = false
                blockNetworkLoads = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                setGeolocationEnabled(false)
                setSupportMultipleWindows(false)
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            setBackgroundColor(Color.WHITE)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail) = true
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
    } catch (_: Throwable) { // no WebView provider installed
        null
    }

    private fun markupHost(ctx: Context, file: File, onExternal: () -> Unit, banner: Boolean, document: (File) -> String): View {
        val host = FrameLayout(ctx).apply { setBackgroundColor(NC.BG) }
        if (file.length() > MAX_MARKUP_FILE) {
            swapToError(host, file, tr(R.string.viewer_too_large, bridgeSize(file.length())), onExternal)
            return host
        }
        val html = try { document(file) } catch (_: Throwable) { null }
        val web = if (html == null) null else lockedWebView(ctx)
        if (web == null) {
            swapToError(host, file, tr(R.string.viewer_cant_open), onExternal)
            return host
        }
        host.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            if (banner) addView(mono(ctx, tr(R.string.viewer_html_sandboxed), 11f, NC.ON_SURF_VAR).apply {
                setBackgroundColor(NC.SURFACE_LOW)
                setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            })
            addView(web)
        })
        web.loadDataWithBaseURL(null, html!!, "text/html", "UTF-8", null)
        host.onDetach {
            runCatching {
                web.stopLoading()
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
        }
        return host
    }

    /** SVG shown as an `<img>` data URI: the browser never runs script or loads anything external from an image. */
    fun svg(ctx: Context, file: File, onExternal: () -> Unit): View = markupHost(ctx, file, onExternal, banner = false) { f ->
        val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;height:100%;background:#fff}body{display:flex;align-items:center;justify-content:center}" +
            "img{max-width:100%;max-height:100%}</style></head><body><img src=\"data:image/svg+xml;base64,$b64\"></body></html>"
    }

    /** HTML rendered in the lockdown WebView. */
    fun html(ctx: Context, file: File, onExternal: () -> Unit): View = markupHost(ctx, file, onExternal, banner = true) { it.readText() }

    // ── Audio ────────────────────────────────────────────────────────────────

    /** Pauses when an ancestor is hidden (leaving the viewer without removing it must not keep the music going). */
    private class AudioPane(ctx: Context) : LinearLayout(ctx) {
        var onHide: (() -> Unit)? = null
        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (visibility != VISIBLE) onHide?.invoke()
        }
    }

    private fun clock(ms: Int) = String.format(Locale.US, "%d:%02d", ms / 60000, ms / 1000 % 60)

    /** Play / pause + seek bar over [MediaPlayer]; the player is released when the view leaves the window. */
    fun audio(ctx: Context, file: File, onExternal: () -> Unit): View {
        val pane = AudioPane(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = box(ctx, NC.SURFACE, NC.BORDER)
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val mp = MediaPlayer()
        var released = false
        fun release() {
            if (released) return
            released = true
            runCatching { mp.release() }
        }
        try {
            FileInputStream(file).use { mp.setDataSource(it.fd) }
        } catch (_: Throwable) {
            release()
            pane.addView(error(ctx, file, tr(R.string.viewer_cant_open), onExternal))
            return pane
        }
        val play = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_lucide_play)
            setColorFilter(NC.PRIMARY)
            contentDescription = tr(R.string.viewer_play)
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            layoutParams = LinearLayout.LayoutParams(ctx.dp(56), ctx.dp(56))
            background = box(ctx, NC.SURFACE_VAR, NC.BORDER)
            isClickable = true
            isEnabled = false
        }
        val seek = SeekBar(ctx).apply { isEnabled = false }
        val time = mono(ctx, "0:00 / 0:00", 11f, NC.ON_SURF_VAR)
        fun playing() = !released && runCatching { mp.isPlaying }.getOrDefault(false)
        fun showPlaying(on: Boolean) {
            play.setImageResource(if (on) R.drawable.ic_lucide_pause else R.drawable.ic_lucide_play)
            play.contentDescription = tr(if (on) R.string.viewer_pause else R.string.viewer_play)
        }
        val tick = object : Runnable {
            override fun run() {
                if (released) return
                val pos = runCatching { mp.currentPosition }.getOrDefault(0)
                seek.progress = pos
                time.text = clock(pos) + " / " + clock(seek.max)
                if (playing()) ui.postDelayed(this, 300)
            }
        }
        mp.setOnPreparedListener {
            seek.max = it.duration
            seek.isEnabled = true
            play.isEnabled = true
            time.text = "0:00 / " + clock(it.duration)
        }
        mp.setOnCompletionListener { showPlaying(false); seek.progress = 0; time.text = "0:00 / " + clock(seek.max) }
        mp.setOnErrorListener { _, _, _ ->
            release()
            ui.removeCallbacks(tick)
            pane.removeAllViews()
            pane.addView(error(ctx, file, tr(R.string.viewer_cant_open), onExternal))
            true
        }
        play.setOnClickListener {
            if (released) return@setOnClickListener
            if (playing()) { mp.pause(); showPlaying(false) } else { mp.start(); showPlaying(true); ui.post(tick) }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && !released) { runCatching { mp.seekTo(p) }; time.text = clock(p) + " / " + clock(s.max) }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        pane.onHide = { if (playing()) { mp.pause(); showPlaying(false) } }
        pane.onDetach { ui.removeCallbacks(tick); release() }
        pane.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_lucide_music)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40))
        })
        pane.addView(mono(ctx, file.name, 14f, NC.ON_SURFACE).apply { paint.isFakeBoldText = true; setPadding(0, ctx.dp(8), 0, ctx.dp(8)) })
        pane.addView(play)
        pane.addView(seek)
        pane.addView(time)
        pane.addView(pill(ctx, tr(R.string.viewer_open_external), onExternal),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })
        try { mp.prepareAsync() } catch (_: Throwable) {
            release()
            pane.removeAllViews()
            pane.addView(error(ctx, file, tr(R.string.viewer_cant_open), onExternal))
        }
        return pane
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    /** Lazy page list: each page is rendered on demand (one at a time, off the main thread) and the last few stay cached. */
    fun pdf(ctx: Context, file: File, onExternal: () -> Unit): View {
        val host = FrameLayout(ctx).apply { setBackgroundColor(NC.SURFACE_LOWEST) }
        val doc = PdfDoc.open(file)
        if (doc == null) {
            swapToError(host, file, tr(R.string.viewer_pdf_unreadable), onExternal)
            return host
        }
        val widthPx = minOf(ctx.resources.displayMetrics.widthPixels, 1400)
        val cache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
        }
        val exec = Executors.newSingleThreadExecutor()
        val label = mono(ctx, tr(R.string.viewer_page_of, 1, doc.pageCount), 11f, NC.ON_SURFACE).apply {
            background = box(ctx, NC.SURFACE_VAR, NC.BORDER)
            setPadding(ctx.dp(10), ctx.dp(4), ctx.dp(10), ctx.dp(4))
        }
        val rv = RecyclerView(ctx).apply {
            val lm = LinearLayoutManager(ctx)
            layoutManager = lm
            clipToPadding = false
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(56))
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = doc.pageCount
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val iv = ImageView(ctx).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(Color.WHITE)
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ctx.dp(8) }
                    }
                    return object : RecyclerView.ViewHolder(iv) {}
                }
                override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
                    val iv = h.itemView as ImageView
                    iv.tag = position
                    val hit = cache.get(position)
                    if (hit != null) { iv.minimumHeight = 0; iv.setImageBitmap(hit); return }
                    iv.setImageDrawable(null)
                    iv.minimumHeight = (widthPx * 1.4f).toInt()
                    try {
                        exec.execute {
                            val b = doc.render(position, widthPx) ?: return@execute
                            cache.put(position, b)
                            iv.post { if (iv.tag == position) { iv.minimumHeight = 0; iv.setImageBitmap(b) } }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {}
                }
                override fun onViewRecycled(h: RecyclerView.ViewHolder) { (h.itemView as ImageView).setImageDrawable(null) }
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val p = lm.findFirstVisibleItemPosition()
                    if (p >= 0) label.text = tr(R.string.viewer_page_of, p + 1, doc.pageCount)
                }
            })
        }
        host.addView(rv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        host.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ctx.dp(12) })
        host.onDetach {
            exec.shutdownNow()
            cache.evictAll()
            Thread { doc.close() }.apply { isDaemon = true; start() } // waits for a render in flight; not on the main thread
        }
        return host
    }
}

/** ImageView with pinch zoom (1x to 8x of fit), drag and double-tap to reset. */
private class ZoomImageView(ctx: Context) : ImageView(ctx) {
    private val m = Matrix()
    private val vals = FloatArray(9)
    private var base = 1f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val cur = scale()
            val k = (cur * d.scaleFactor).coerceIn(base, base * 8f) / cur
            m.postScale(k, k, d.focusX, d.focusY)
            commit()
            return true
        }
    })
    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { fit(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            m.postTranslate(-dx, -dy)
            commit()
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    override fun setImageDrawable(d: Drawable?) { super.setImageDrawable(d); fit() }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

    private fun scale(): Float { m.getValues(vals); return vals[Matrix.MSCALE_X] }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        base = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        m.reset()
        m.postScale(base, base)
        m.postTranslate((width - d.intrinsicWidth * base) / 2f, (height - d.intrinsicHeight * base) / 2f)
        imageMatrix = m
    }

    /** Keeps the image on screen: centered when smaller than the view, edge-to-edge when larger. */
    private fun commit() {
        val d = drawable ?: return
        val r = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(r)
        fun shift(size: Float, lo: Float, hi: Float, view: Int) = when {
            size <= view -> (view - size) / 2f - lo
            lo > 0f -> -lo
            hi < view -> view - hi
            else -> 0f
        }
        m.postTranslate(shift(r.width(), r.left, r.right, width), shift(r.height(), r.top, r.bottom, height))
        imageMatrix = m
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) taps.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
