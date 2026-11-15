package com.zenithblue.nativecode.preview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import java.util.concurrent.Executors
import com.zenithblue.nativecode.tr

/**
 * In-app preview of a dev server running in the guest (plan 12). Loopback only: any other host opens in the
 * external browser. JavaScript + DOM storage on (dev servers need them); file/content access off; no
 * JavaScript interface is exposed. Cleartext is allowed for 127.0.0.1/localhost by network_security_config.
 */
class PreviewActivity : Activity() {
    companion object {
        private const val EXTRA_URL = "url"

        /** [url] null = probe the common dev ports and open the first listening one. */
        fun open(ctx: Context, url: String?) {
            if (url != null && !PortProbe.isLoopback(url)) return openExternal(ctx, url)
            ctx.startActivity(Intent(ctx, PreviewActivity::class.java).putExtra(EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun openExternal(ctx: Context, url: String) {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Toast.makeText(ctx, tr(R.string.preview_no_browser_can_open_this), Toast.LENGTH_SHORT).show() }
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var portBtn: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorTitle: TextView
    private lateinit var errorDetail: TextView
    private lateinit var backBtn: ImageView
    private lateinit var fwdBtn: ImageView
    private var current: String? = null
    private var ports: List<Int> = emptyList()

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = NC.BG
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(NC.BG) }

        // Row 1: close, URL bar, port switcher
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(8), dp(6))
            setBackgroundColor(NC.SURFACE_LOW)
        }
        top.addView(iconBtn(R.drawable.ic_close, tr(R.string.preview_close_preview)) { finish() })
        urlBar = EditText(this).apply {
            setSingleLine()
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setHintTextColor(NC.ON_SURF_VAR)
            hint = "http://127.0.0.1:5173/"
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = GradientDrawable().apply { setColor(NC.BG); setStroke(dp(1), NC.OUTLINE_VAR) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(8) }
            contentDescription = "Preview address"
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_GO) { go(text.toString()); true } else false
            }
        }
        top.addView(urlBar)
        portBtn = TextView(this).apply {
            text = tr(R.string.preview_ports)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            setTextColor(NC.PRIMARY)
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), NC.PRIMARY) }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            contentDescription = "Switch port"
            setOnClickListener { showPorts() }
        }
        top.addView(portBtn)
        root.addView(top)

        // Row 2: back, forward, reload, open in browser
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(4))
            setBackgroundColor(NC.SURFACE_LOW)
        }
        backBtn = iconBtn(R.drawable.ic_lucide_arrow_left, tr(R.string.onb_back)) { if (web.canGoBack()) web.goBack() }
        fwdBtn = iconBtn(R.drawable.ic_lucide_arrow_right, tr(R.string.preview_forward)) { if (web.canGoForward()) web.goForward() }
        nav.addView(backBtn)
        nav.addView(fwdBtn)
        nav.addView(iconBtn(R.drawable.ic_lucide_rotate_cw, tr(R.string.preview_reload)) { retry() })
        nav.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        nav.addView(TextView(this).apply {
            text = tr(R.string.preview_loopback_only)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(NC.ON_SURF_VAR)
            setPadding(0, 0, dp(8), 0)
        })
        nav.addView(iconBtn(R.drawable.ic_lucide_external_link, tr(R.string.preview_open_in_browser)) {
            (current ?: urlBar.text.toString()).takeIf { it.isNotBlank() }?.let { openExternal(this, it) }
        })
        root.addView(nav)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(NC.PRIMARY)
            progressBackgroundTintList = ColorStateList.valueOf(NC.SURFACE_LOW)
            visibility = View.INVISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        val stage = FrameLayout(this)
        web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = Client()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, p: Int) {
                    this@PreviewActivity.progress.progress = p
                    this@PreviewActivity.progress.visibility = if (p in 1..99) View.VISIBLE else View.INVISIBLE
                }
            }
        }
        stage.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(NC.BG)
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            addView(ImageView(this@PreviewActivity).apply {
                setImageResource(R.drawable.ic_lucide_globe)
                setColorFilter(NC.ON_SURF_VAR)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(16) }
            })
            errorTitle = TextView(this@PreviewActivity).apply {
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }
            addView(errorTitle)
            errorDetail = TextView(this@PreviewActivity).apply {
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(NC.ON_SURF_VAR)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(20))
            }
            addView(errorDetail)
            addView(TextView(this@PreviewActivity).apply {
                text = tr(R.string.preview_retry)
                textSize = 14f
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#0A0A0A"))
                setBackgroundColor(NC.PRIMARY_CON)
                setPadding(dp(28), dp(12), dp(28), dp(12))
                setOnClickListener { retry() }
            })
        }
        stage.addView(errorBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        updateNav()

        val start = intent.getStringExtra(EXTRA_URL)
        if (start != null) go(start) else scanThenOpen()
    }

    private fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(NC.ON_SURF_VAR)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        contentDescription = desc
        tooltipText = desc
        setOnClickListener { onClick() }
    }

    private fun updateNav() {
        if (!::web.isInitialized) return
        backBtn.alpha = if (web.canGoBack()) 1f else 0.35f
        fwdBtn.alpha = if (web.canGoForward()) 1f else 0.35f
        portBtn.text = PortProbe.portOf(current ?: "")?.let { ":$it" } ?: tr(R.string.preview_ports)
    }

    /** Typed address: bare port ("5173", ":5173") or URL. Non-loopback goes to the browser. */
    private fun go(raw: String) {
        val t = raw.trim()
        val url = when {
            t.matches(Regex(":?\\d{2,5}")) -> "http://127.0.0.1:${t.trimStart(':')}/"
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            else -> "http://$t"
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
        urlBar.clearFocus()
        if (!PortProbe.isLoopback(url)) { openExternal(this, url); return }
        current = url
        urlBar.setText(url)
        updateNav()
        val port = PortProbe.portOf(url) ?: return web.loadUrl(url)
        io.execute {
            val up = PortProbe.isOpen(port, 400)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (up) { errorBox.visibility = View.GONE; web.loadUrl(url) } else showError(port)
            }
        }
    }

    private fun retry() {
        val u = current
        if (u == null) scanThenOpen() else if (errorBox.visibility == View.VISIBLE) go(u) else web.reload()
    }

    private fun scanThenOpen() {
        showMessage(tr(R.string.preview_looking_for_a_dev_server), tr(R.string.preview_probing_127_0_0_1))
        io.execute {
            val found = (PortProbe.seenPorts() + PortProbe.probe()).distinct().filter { PortProbe.isOpen(it) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                ports = found
                if (found.isEmpty()) showMessage(tr(R.string.preview_no_dev_server_found),
                    "Nothing listens on 127.0.0.1 (${PortProbe.COMMON.joinToString(", ")}).\nStart one (npx vite, python3 -m http.server) or type a port above.")
                else go("http://127.0.0.1:${found.first()}/")
            }
        }
    }

    private fun showPorts() {
        io.execute {
            val found = (PortProbe.seenPorts() + PortProbe.probe()).distinct().filter { PortProbe.isOpen(it) }.sorted()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                ports = found
                val menu = PopupMenu(this, portBtn)
                if (found.isEmpty()) menu.menu.add("No listening ports found").isEnabled = false
                found.forEach { p -> menu.menu.add(0, p, 0, "127.0.0.1:$p") }
                menu.menu.add(0, -1, 1, tr(R.string.preview_scan_again))
                menu.setOnMenuItemClickListener { item ->
                    if (item.itemId == -1) showPorts() else if (item.itemId > 0) go("http://127.0.0.1:${item.itemId}/")
                    true
                }
                menu.show()
            }
        }
    }

    private fun showError(port: Int?) = showMessage(tr(R.string.preview_server_not_running),
        "Nothing is listening on 127.0.0.1${port?.let { ":$it" } ?: ""}.\nStart the dev server, then retry.")

    private fun showMessage(title: String, detail: String) {
        errorTitle.text = title
        errorDetail.text = detail
        errorBox.visibility = View.VISIBLE
        progress.visibility = View.INVISIBLE
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url.toString()
            if (PortProbe.isLoopback(u)) return false
            openExternal(this@PreviewActivity, u) // other hosts never load in-app
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            if (PortProbe.isLoopback(url)) { current = url; urlBar.setText(url) }
            updateNav()
        }

        override fun onPageFinished(view: WebView, url: String) = updateNav()

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) showError(PortProbe.portOf(request.url.toString()))
        }
    }

    private fun back() {
        if (::web.isInitialized && web.canGoBack() && errorBox.visibility != View.VISIBLE) web.goBack() else finish()
    }

    override fun onStart() {
        super.onStart()
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCb == null) {
            backCb = android.window.OnBackInvokedCallback { back() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCb!!)
        }
    }
    private var backCb: android.window.OnBackInvokedCallback? = null

    @Deprecated("Activity back (API < 33)")
    override fun onBackPressed() = back()

    override fun onDestroy() {
        io.shutdownNow()
        if (::web.isInitialized) { web.stopLoading(); web.destroy() }
        super.onDestroy()
    }
}
