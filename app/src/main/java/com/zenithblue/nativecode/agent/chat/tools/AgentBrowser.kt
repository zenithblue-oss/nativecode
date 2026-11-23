package com.zenithblue.nativecode.agent.chat.tools

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.zenithblue.nativecode.agent.chat.ChatAttachments
import com.zenithblue.nativecode.agent.chat.tools.NcToolSpec.Failure
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * The agent browser (feature 2): ONE shared system WebView driven by injected JS ([BrowserLogic] has the pure half),
 * hosted by the chat's browser card ([attach] / [detach]) so the user watches it and can use it. No CDP, no
 * addJavascriptInterface (results are polled with evaluateJavascript), no file/content access, own cookie profile
 * when WebView supports MULTI_PROFILE. Every WebView call runs on the main thread; every wait has a timeout below
 * 60 s (the usual MCP client limit). Tool calls arrive on MCP connection threads and block.
 *
 * ponytail: one browser, serialized calls; the last thread to call owns the panel. Clicks are JS events
 * (`isTrusted=false`), not MotionEvents. Add a per-thread session only if two agents truly browse at once.
 */
object AgentBrowser {
    class Consent(val threadId: String, val agent: String)

    /** Compose-observed state of the card. */
    class Ui {
        var owner by mutableStateOf<String?>(null)     // thread whose chat shows the panel
        var consent by mutableStateOf<Consent?>(null)
        var url by mutableStateOf("")
        var title by mutableStateOf("")
        var progress by mutableStateOf(100)
        var controlling by mutableStateOf(false)       // an agent call is running or just ran
        var collapsed by mutableStateOf(false)
        var isolated by mutableStateOf(true)           // false: cookies are shared with the app's other WebViews
    }

    val ui = Ui()

    private const val PROFILE = "nc_agent_browser"
    private const val NAV_MS = 30_000L
    private const val WAIT_MS = 25_000L
    private const val CONSENT_MS = 50_000L
    private const val BUSY_MS = 15_000L
    private const val SHOTS = "/home/flux/.nativecode/browser"
    private const val KEEP_SHOTS = 20

    private val main = Handler(Looper.getMainLooper())
    private val lock = ReentrantLock()
    private val granted = ConcurrentHashMap.newKeySet<String>()
    private val denied = ConcurrentHashMap.newKeySet<String>()
    private val notes = ArrayDeque<String>()

    @Volatile private var app: Context? = null
    @Volatile private var epoch = 0                    // bumped when the user stops the agent
    @Volatile private var topUrl: String? = null
    @Volatile private var loading = false
    @Volatile private var loadLatch = CountDownLatch(0)
    @Volatile private var loadError: String? = null
    @Volatile private var httpStatus = 0
    @Volatile private var active = 0
    private var web: WebView? = null                   // main thread only
    private var wrapper: MutableContextWrapper? = null
    private val driver: String by lazy { requireNotNull(app).assets.open("browser/driver.js").use { it.readBytes().toString(Charsets.UTF_8) } }

    fun init(ctx: Context) { if (app == null) app = ctx.applicationContext }

    // ---------------- consent (UI thread) ----------------

    /** The card's Allow / Deny. */
    fun answer(allow: Boolean) {
        val c = ui.consent ?: return
        ui.consent = null
        if (allow) { granted.add(c.threadId); denied.remove(c.threadId) } else denied.add(c.threadId)
    }

    /** The card's close: the user stops the agent. Its consent is withdrawn, so the next call asks again. */
    fun userClose() {
        epoch++
        ui.owner?.let { granted.remove(it) }
        ui.consent?.let { denied.add(it.threadId) }
        ui.consent = null
        shut()
    }

    /** Main thread: blank the page, clear the profile, hide the panel. */
    private fun shut() {
        ui.owner = null
        ui.controlling = false
        topUrl = null
        web?.let { w ->
            runCatching { w.stopLoading(); w.loadUrl("about:blank"); w.clearHistory(); w.clearCache(true); w.clearFormData() }
        }
        if (ui.isolated) runCatching {
            ProfileStore.getInstance().getProfile(PROFILE)?.let { it.cookieManager.removeAllCookies(null); it.webStorage.deleteAllData() }
        }
    }

    // ---------------- hosting (main thread) ----------------

    /** The shared WebView for the card's AndroidView; its context becomes the activity while attached. */
    fun attach(host: Context): WebView {
        val v = web ?: create()
        (v.parent as? ViewGroup)?.removeView(v)
        wrapper?.baseContext = host
        return v
    }

    fun detach(v: View) {
        (v.parent as? ViewGroup)?.removeView(v)
        wrapper?.baseContext = app
    }

    @Suppress("DEPRECATION")
    private fun create(): WebView {
        val w = MutableContextWrapper(requireNotNull(app)).also { wrapper = it }
        val v = WebView(w)
        ui.isolated = profileOk() && runCatching { ProfileStore.getInstance().getOrCreateProfile(PROFILE); WebViewCompat.setProfile(v, PROFILE) }.isSuccess
        v.setBackgroundColor(Color.WHITE)
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            saveFormData = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        v.setDownloadListener { url, _, _, _, _ -> note("Download blocked: ${url.take(150)}") }
        v.webViewClient = Client()
        v.webChromeClient = Chrome()
        // Laid out at a phone size so JS and layout work while no card hosts it.
        val d = w.resources.displayMetrics.density
        v.measure(View.MeasureSpec.makeMeasureSpec((360 * d).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((520 * d).toInt(), View.MeasureSpec.EXACTLY))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        web = v
        return v
    }

    private fun note(s: String) = synchronized(notes) { notes.addLast(s.take(240)); while (notes.size > 5) notes.removeFirst() }
    private fun drainNotes(): String = synchronized(notes) { if (notes.isEmpty()) "" else "\nNotes:\n" + notes.joinToString("\n") { "- $it" }.also { notes.clear() } }

    private class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url.toString()
            val why = BrowserLogic.blocked(u, null) ?: return false
            note("Blocked navigation to ${u.take(150)}: $why")
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url.toString()
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true) && !request.isForMainFrame) return null // data:, blob:
            val why = BrowserLogic.blocked(u, if (request.isForMainFrame) null else topUrl) ?: return null
            if (request.isForMainFrame) note("Blocked navigation to ${u.take(150)}: $why")
            return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            loading = true; topUrl = url; ui.url = url
        }

        override fun onPageFinished(view: WebView, url: String) {
            loading = false; ui.url = url; ui.title = view.title.orEmpty(); loadLatch.countDown()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) { loadError = "${error.description} (code ${error.errorCode})"; loadLatch.countDown() }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) httpStatus = errorResponse.statusCode
        }

        /** A crashed or killed renderer must not take the app down: drop the WebView, the next call builds a new one. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            note("The page's renderer stopped; the browser was reset.")
            loadError = "The page crashed"; loadLatch.countDown(); loading = false
            (view.parent as? ViewGroup)?.removeView(view)
            runCatching { view.destroy() }
            if (web === view) { web = null; ui.owner = null }
            return true
        }
    }

    private class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) { ui.progress = newProgress }
        override fun onReceivedTitle(view: WebView, title: String?) { ui.title = title.orEmpty() }
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean { note("Dialog dismissed (alert): ${message.orEmpty().take(160)}"); result.confirm(); return true }
        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean { note("Dialog dismissed (confirm): ${message.orEmpty().take(160)}"); result.cancel(); return true }
        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean { note("Dialog dismissed (prompt): ${message.orEmpty().take(160)}"); result.cancel(); return true }
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean { result.confirm(); return true }
        override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) { callback.invoke(origin, false, false) }
        override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean { cb.onReceiveValue(null); return true }
    }

    // ---------------- tool calls (MCP threads) ----------------

    /** One browser_* call: consent, then the one-at-a-time lock, then the tool. Always returns a CallToolResult. */
    fun call(threadId: String, agent: String, name: String, a: JSONObject): JSONObject {
        try {
            if (app == null) throw Failure("browser_unavailable", "The browser is not ready.")
            consent(threadId, agent)
            if (!lock.tryLock(15, TimeUnit.SECONDS)) throw Failure("browser_busy", "The browser is in use by another chat. Retry shortly.")
            val ep = epoch
            active++
            main.post { ui.controlling = true }
            try {
                if (name != "browser_close") claim(threadId)
                return run(name, a, ep)
            } finally {
                active--
                lock.unlock()
                main.postDelayed({ if (active == 0) ui.controlling = false }, 3_000)
            }
        } catch (f: Failure) {
            return NcToolSpec.fail(f)
        } catch (e: Throwable) {
            if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
            return NcToolSpec.fail(Failure("browser_error", (e.message ?: e.javaClass.simpleName).take(300)))
        }
    }

    /** Per-thread consent: the card asks the user; Allow is for this chat. Waits (below client timeouts), then asks the agent to retry. */
    private fun consent(threadId: String, agent: String) {
        if (threadId in granted) return
        if (threadId in denied) throw Failure("capability_denied", "The user denied browser access in this chat. Do not retry; tell the user if you need it.")
        main.post { if (ui.consent == null) { ui.isolated = profileOk(); ui.consent = Consent(threadId, agent) } }
        val end = System.currentTimeMillis() + CONSENT_MS
        while (System.currentTimeMillis() < end) {
            if (threadId in granted) return
            if (threadId in denied) throw Failure("capability_denied", "The user denied browser access in this chat. Do not retry; tell the user if you need it.")
            Thread.sleep(250)
        }
        throw Failure("consent_pending", "Waiting for the user to allow browser use in the chat. Ask them to tap Allow, then retry.")
    }

    private fun profileOk() = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    /** The panel belongs to the calling thread; opens it if closed. */
    private fun claim(threadId: String) {
        onMain<Unit> { cb -> if (ui.owner != threadId) { ui.owner = threadId; ui.collapsed = false }; web ?: create(); cb(Unit) }
    }

    private fun run(name: String, a: JSONObject, ep: Int): JSONObject = when (name) {
        "browser_navigate" -> BrowserLogic.text(navigate(BrowserLogic.navigateUrl(a)) + drainNotes())
        "browser_navigate_back" -> BrowserLogic.text(back() + drainNotes())
        "browser_snapshot" -> BrowserLogic.text(snapshot() + drainNotes())
        "browser_click" -> { act("click", "\"${BrowserLogic.ref(a)}\""); settle(ep); BrowserLogic.text("Clicked ${BrowserLogic.ref(a)}.\n" + where() + drainNotes()) }
        "browser_type" -> {
            val r = BrowserLogic.ref(a)
            act("type", "\"$r\"," + JSONObject.quote(a.getString("text")) + "," + a.optBoolean("submit"))
            settle(ep); BrowserLogic.text("Typed into $r.\n" + where() + drainNotes())
        }
        "browser_press_key" -> { act("press", JSONObject.quote(a.getString("key"))); settle(ep); BrowserLogic.text("Pressed ${a.getString("key")}.\n" + where() + drainNotes()) }
        "browser_evaluate" -> BrowserLogic.text(evaluate(a.getString("function"), ep) + drainNotes())
        "browser_take_screenshot" -> screenshot(ep)
        "browser_wait_for" -> BrowserLogic.text(waitFor(a, ep) + drainNotes())
        "browser_close" -> { onMain<Unit> { cb -> shut(); cb(Unit) }; BrowserLogic.text("Browser closed.") }
        else -> throw Failure("invalid_request", "Unknown tool $name.")
    }

    // ---------------- main-thread plumbing ----------------

    private fun stopped(ep: Int) { if (ep != epoch) throw Failure("stopped_by_user", "The user stopped the browser.") }

    /** Runs [f] on the main thread and waits for its callback; WebView errors become tool errors. */
    private fun <T> onMain(ms: Long = 10_000, f: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var out: Any? = null
        var err: Throwable? = null
        main.post {
            try { f { out = it; latch.countDown() } } catch (e: Throwable) { err = e; latch.countDown() }
        }
        if (!latch.await(ms, TimeUnit.MILLISECONDS)) throw Failure("timeout", "The browser did not answer in ${ms / 1000}s. The page may be stuck; try browser_close.")
        err?.let { throw Failure("browser_error", (it.message ?: it.javaClass.simpleName).take(300)) }
        @Suppress("UNCHECKED_CAST") return out as T
    }

    private fun view(): WebView = web ?: create()

    /** Driver + [expr] in the page; the page's string result, null when the script did not run. */
    private fun js(expr: String, ms: Long = 10_000): String? {
        val raw = onMain<String?>(ms) { cb -> view().evaluateJavascript("$driver;$expr") { cb(it) } }
        if (raw == null || raw == "null") return null
        return (JSONTokener(raw).nextValue() as? String) ?: raw
    }

    /** A driver call that returns {error} or a payload. */
    private fun drv(expr: String): JSONObject {
        val s = js(expr) ?: throw Failure("action_failed", "The page did not run the action (it may still be loading). Take a browser_snapshot and retry.")
        return JSONObject(s).also { o -> if (o.has("error")) throw Failure("action_failed", o.getString("error")) }
    }

    private fun act(fn: String, args: String) { drv("__ncb.$fn($args)") }

    private fun where(): String = onMain<String> { cb -> cb("Page: ${web?.url.orEmpty()}\nTitle: ${web?.title.orEmpty()}") }

    // ---------------- tools ----------------

    private fun navigate(url: String): String {
        onMain<Unit> { cb ->
            loadLatch = CountDownLatch(1); loadError = null; httpStatus = 0; loading = true
            ui.url = url; topUrl = url
            view().loadUrl(url); cb(Unit)
        }
        val done = loadLatch.await(NAV_MS, TimeUnit.MILLISECONDS)
        loadError?.let { throw Failure("navigation_failed", "$it for ${url.take(200)}") }
        return "Navigated.\n" + where() + (if (httpStatus >= 400) "\nHTTP $httpStatus" else "") + (if (done) "" else "\nStill loading after ${NAV_MS / 1000}s; use browser_wait_for or browser_snapshot.")
    }

    private fun back(): String {
        val can = onMain<Boolean> { cb ->
            val v = view()
            val ok = v.canGoBack()
            if (ok) { loadLatch = CountDownLatch(1); loadError = null; v.goBack() }
            cb(ok)
        }
        if (!can) throw Failure("action_failed", "There is no earlier page.")
        loadLatch.await(10, TimeUnit.SECONDS)
        return "Went back.\n" + where()
    }

    /** Let a click or key settle: a short pause, then wait out a page load it started. */
    private fun settle(ep: Int) {
        Thread.sleep(300)
        val end = System.currentTimeMillis() + 8_000
        while (loading && System.currentTimeMillis() < end) { stopped(ep); Thread.sleep(200) }
    }

    private fun snapshot(): String {
        val o = drv("__ncb.snapshot()")
        val (url, title) = onMain<Pair<String, String>> { cb -> cb(web?.url.orEmpty() to web?.title.orEmpty()) }
        return BrowserLogic.snapshotText(url, title, o.optJSONArray("tree") ?: JSONArray())
    }

    private fun evaluate(fn: String, ep: Int): String {
        val id = "r" + System.nanoTime()
        // The user code is embedded (not eval'd) so a page CSP without unsafe-eval cannot block it.
        js("__ncb.run(\"$id\", (${fn}\n))") ?: throw Failure("evaluate_failed", "The script did not run; check its syntax.")
        val end = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < end) {
            stopped(ep)
            val r = js("__ncb.result(\"$id\")")
            if (r != null) {
                val o = JSONObject(r)
                if (o.has("e")) throw Failure("evaluate_failed", BrowserLogic.cap(o.getString("e"), 500))
                return BrowserLogic.cap(o.optString("v"), BrowserLogic.EVAL_CHARS).ifEmpty { "(empty)" }
            }
            Thread.sleep(150)
        }
        throw Failure("timeout", "The function did not finish in ${WAIT_MS / 1000}s.")
    }

    private fun waitFor(a: JSONObject, ep: Int): String {
        if (a.has("time")) Thread.sleep((a.getDouble("time").coerceIn(0.0, 25.0) * 1000).toLong())
        val end = System.currentTimeMillis() + WAIT_MS
        for ((key, want) in listOf("text" to true, "textGone" to false)) {
            if (!a.has(key)) continue
            val q = JSONObject.quote(a.getString(key))
            while (true) {
                stopped(ep)
                if ((js("__ncb.hasText($q)") == "true") == want) break
                if (System.currentTimeMillis() >= end) throw Failure("timeout", "`${a.getString(key).take(60)}` ${if (want) "did not appear" else "did not go away"} in ${WAIT_MS / 1000}s.")
                Thread.sleep(250)
            }
        }
        return "Done.\n" + where()
    }

    private fun screenshot(ep: Int): JSONObject {
        if (ui.collapsed) { main.post { ui.collapsed = false }; Thread.sleep(500) }
        val bmp = onMain<Bitmap?>(8_000) { cb -> grab(view(), cb) }
            ?: throw Failure("browser_not_visible", "The browser panel is not on screen. Ask the user to open this chat so the panel is visible, then retry. browser_snapshot works without it.")
        stopped(ep)
        val small = if (bmp.width > 800) Bitmap.createScaledBitmap(bmp, 800, (bmp.height * 800L / bmp.width).toInt().coerceAtLeast(1), true) else bmp
        val png = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val jpg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
        val guest = "$SHOTS/shot-${System.currentTimeMillis()}.png"
        val saved = runCatching {
            val f = ChatAttachments.hostFile(requireNotNull(app), guest)
            f.parentFile?.mkdirs()
            f.writeBytes(png)
            f.parentFile?.listFiles { x -> x.name.startsWith("shot-") }?.sortedByDescending { it.name }?.drop(KEEP_SHOTS)?.forEach { it.delete() }
            true
        }.getOrDefault(false)
        val text = "Screenshot ${small.width}x${small.height}." + (if (saved) " Saved: $guest" else " (could not save a file)") + "\n" + where() + drainNotes()
        return BrowserLogic.image(text, Base64.encodeToString(jpg, Base64.NO_WRAP), "image/jpeg")
    }

    /** Main thread: PixelCopy of the attached view (draw() as the fallback); null when it is not on screen. */
    private fun grab(v: WebView, cb: (Bitmap?) -> Unit) {
        if (!v.isAttachedToWindow || v.width <= 0 || v.height <= 0 || !v.isShown) { cb(null); return }
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        fun draw() = cb(runCatching { v.draw(Canvas(bmp)); bmp }.getOrNull())
        val act = generateSequence(v.context) { (it as? ContextWrapper)?.baseContext }.firstOrNull { it is Activity } as? Activity
        if (act == null || Build.VERSION.SDK_INT < 26) { draw(); return }
        val loc = IntArray(2).also { v.getLocationInWindow(it) }
        runCatching {
            PixelCopy.request(act.window, Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height), bmp, { r -> if (r == PixelCopy.SUCCESS) cb(bmp) else draw() }, main)
        }.onFailure { draw() }
    }
}
