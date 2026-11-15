package com.zenithblue.nativecode.editor

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.event.TextSizeChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File
import java.util.concurrent.Executors

/** One-time Sora TextMate registry setup (grammars + the obsidian theme from assets). */
internal object EditorSetup {
    private var done = false

    @Synchronized
    fun ensure(ctx: Context) {
        if (done) return
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(ctx.applicationContext.assets))
        val path = "textmate/nc-obsidian.json"
        ThemeRegistry.getInstance().loadTheme(
            ThemeModel(
                IThemeSource.fromInputStream(FileProviderRegistry.getInstance().tryGetInputStream(path), path, null),
                "nc-obsidian",
            ).apply { isDark = true },
        )
        ThemeRegistry.getInstance().setTheme("nc-obsidian")
        GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
        done = true
    }
}

/**
 * Built-in code editor (plan 16): Sora Editor + TextMate, obsidian theme. Reads and writes the host path directly,
 * saves atomically, guards unsaved changes and edits made to the file behind its back.
 */
internal class EditorView(ctx: Context, val file: File) : LinearLayout(ctx) {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("nativecode_prefs", Context.MODE_PRIVATE)

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private val editor = CodeEditor(ctx)
    private var mode = EditMode.EDIT
    private var stamp = DiskStamp(0, 0)
    private var loaded = false
    private var loadingText = false
    private var released = false

    var dirty = false
        private set
    /** Called whenever dirty / mode changes so the host can refresh chrome. */
    var onStateChanged: () -> Unit = {}

    private val nameTv = TextView(ctx)
    private val dirtyDot = View(ctx)
    private val chipTv = TextView(ctx)
    private val posTv = TextView(ctx)
    private val saveBtn = ImageView(ctx)
    private val findBar = LinearLayout(ctx)
    private val gotoBar = LinearLayout(ctx)
    private val findEt = EditText(ctx)
    private val replaceEt = EditText(ctx)
    private val replaceRow = LinearLayout(ctx)
    private val countTv = TextView(ctx)
    private val caseChip = TextView(ctx)
    private val regexChip = TextView(ctx)
    private val gotoEt = EditText(ctx)
    private val symbolBar = HorizontalScrollView(ctx)
    private var caseInsensitive = true
    private var useRegex = false

    init {
        orientation = VERTICAL
        setBackgroundColor(NC.BG)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        EditorSetup.ensure(ctx)
        buildHeader()
        buildFindBar()
        buildGotoBar()
        addView(findBar); addView(gotoBar)
        configureEditor()
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        buildSymbolBar()
        addView(symbolBar)
        refreshChrome()
        loadFile()
    }

    // region chrome

    private fun icon(res: Int, desc: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(NC.ON_SURF_VAR)
        contentDescription = desc
        val p = dp(8)
        setPadding(p, p, p, p)
        layoutParams = LayoutParams(dp(40), dp(40))
        setOnClickListener { onClick() }
    }

    private fun chip(tv: TextView, label: String, onClick: (() -> Unit)? = null) = tv.apply {
        text = label
        textSize = 10f
        typeface = Typeface.MONOSPACE
        setTextColor(NC.ON_SURF_VAR)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        background = GradientDrawable().apply { setColor(NC.SURFACE_HIGH); setStroke(dp(1), NC.OUTLINE_VAR) }
        if (onClick != null) setOnClickListener { onClick() }
    }

    private fun buildHeader() {
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(NC.SURFACE_CONTAINER)
            setPadding(dp(12), dp(6), dp(4), dp(0))
        }
        nameTv.apply {
            text = file.name
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(NC.ON_SURFACE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        dirtyDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(NC.TERTIARY_CON) }
        dirtyDot.contentDescription = "Unsaved changes"
        chip(chipTv, "")
        saveBtn.apply {
            setImageResource(R.drawable.ic_lucide_save)
            contentDescription = "Save"
            val p = dp(8); setPadding(p, p, p, p)
            setOnClickListener { save() }
        }
        top.addView(nameTv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(dirtyDot, LayoutParams(dp(8), dp(8)).apply { leftMargin = dp(8); rightMargin = dp(8) })
        top.addView(chipTv)
        top.addView(saveBtn, LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(4) })

        val tools = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val undo = icon(R.drawable.ic_lucide_undo, "Undo") { editor.undo(); refreshChrome() }
        val redo = icon(R.drawable.ic_lucide_redo, "Redo") { editor.redo(); refreshChrome() }
        val find = icon(R.drawable.ic_lucide_search, "Find and replace") { toggleFind() }
        val goto = icon(R.drawable.ic_lucide_hash, "Go to line") { toggleGoto() }
        val wrap = icon(R.drawable.ic_lucide_wrap_text, "Toggle word wrap") { setWrap(!editor.isWordwrap) }
        val smaller = icon(R.drawable.ic_lucide_type, "Smaller text") { bumpFont(-1f) }
        val bigger = icon(R.drawable.ic_lucide_type, "Larger text") { bumpFont(1f) }
        bigger.setPadding(dp(5), dp(5), dp(5), dp(5)); smaller.setPadding(dp(11), dp(11), dp(11), dp(11))
        undoRef = undo; redoRef = redo; findRef = find; wrapRef = wrap
        for (v in listOf(undo, redo, find, goto, wrap, smaller, bigger)) tools.addView(v)
        posTv.apply { textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(NC.OUTLINE); setPadding(dp(8), 0, dp(12), 0) }
        val toolsRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(NC.SURFACE_CONTAINER)
            setPadding(dp(4), 0, 0, 0)
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(tools)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(posTv)
        }
        addView(top)
        addView(toolsRow)
        addView(View(context).apply { setBackgroundColor(NC.OUTLINE_VAR) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
    }

    private lateinit var undoRef: ImageView
    private lateinit var redoRef: ImageView
    private lateinit var findRef: ImageView
    private lateinit var wrapRef: ImageView

    private fun textInput(et: EditText, hint: String) = et.apply {
        this.hint = hint
        setHintTextColor(NC.OUTLINE)
        setTextColor(NC.ON_SURFACE)
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE
        background = GradientDrawable().apply { setColor(Color.parseColor("#0A0A0A")); setStroke(dp(1), NC.OUTLINE_VAR) }
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun textBtn(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 11f
        typeface = Typeface.MONOSPACE
        paint.isFakeBoldText = true
        setTextColor(Color.parseColor("#0A0A0A"))
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(NC.PRIMARY_CON) }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { onClick() }
    }

    private fun buildFindBar() {
        findBar.orientation = VERTICAL
        findBar.visibility = GONE
        findBar.setBackgroundColor(NC.SURFACE_LOW)
        findBar.setPadding(dp(8), dp(6), dp(8), dp(6))
        val row1 = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        textInput(findEt, "Find")
        findEt.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { runSearch() }
            override fun afterTextChanged(s: Editable?) {}
        })
        chip(caseChip, "Aa") { caseInsensitive = !caseInsensitive; refreshChips(); runSearch() }
        chip(regexChip, ".*") { useRegex = !useRegex; refreshChips(); runSearch() }
        countTv.apply { textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(NC.OUTLINE); setPadding(dp(6), 0, dp(6), 0) }
        row1.addView(findEt, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(caseChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        row1.addView(regexChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
        row1.addView(countTv)
        row1.addView(icon(R.drawable.ic_lucide_chevron_up, "Previous match") { runCatching { editor.searcher.gotoPrevious() } })
        row1.addView(icon(R.drawable.ic_lucide_chevron_down, "Next match") { runCatching { editor.searcher.gotoNext() } })
        row1.addView(icon(R.drawable.ic_lucide_x, "Close find") { closeFind() })
        replaceRow.orientation = HORIZONTAL
        replaceRow.gravity = Gravity.CENTER_VERTICAL
        textInput(replaceEt, "Replace")
        replaceRow.addView(replaceEt, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        replaceRow.addView(textBtn("REPLACE") { runCatching { editor.searcher.replaceCurrentMatch(replaceEt.text.toString()) } },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        replaceRow.addView(textBtn("ALL") { runCatching { editor.searcher.replaceAll(replaceEt.text.toString()) } },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
        findBar.addView(row1)
        findBar.addView(replaceRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        refreshChips()
    }

    private fun refreshChips() {
        caseChip.setTextColor(if (!caseInsensitive) NC.PRIMARY else NC.ON_SURF_VAR)
        regexChip.setTextColor(if (useRegex) NC.PRIMARY else NC.ON_SURF_VAR)
    }

    private fun buildGotoBar() {
        gotoBar.orientation = HORIZONTAL
        gotoBar.gravity = Gravity.CENTER_VERTICAL
        gotoBar.visibility = GONE
        gotoBar.setBackgroundColor(NC.SURFACE_LOW)
        gotoBar.setPadding(dp(8), dp(6), dp(8), dp(6))
        textInput(gotoEt, "Line number")
        gotoEt.inputType = InputType.TYPE_CLASS_NUMBER
        gotoEt.setOnEditorActionListener { _, _, _ -> gotoLine(); true }
        gotoBar.addView(gotoEt, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        gotoBar.addView(textBtn("GO") { gotoLine() }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        gotoBar.addView(icon(R.drawable.ic_lucide_x, "Close go to line") { gotoBar.visibility = GONE })
    }

    private data class Key(val label: String? = null, val iconRes: Int = 0, val insert: String? = null, val keyCode: Int = 0, val desc: String = "")

    private fun buildSymbolBar() {
        symbolBar.isHorizontalScrollBarEnabled = false
        symbolBar.setBackgroundColor(NC.SURFACE_LOWEST)
        symbolBar.visibility = GONE
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val keys = listOf(
            Key("TAB", keyCode = KeyEvent.KEYCODE_TAB, desc = "Tab"),
            Key(iconRes = R.drawable.ic_lucide_arrow_left, keyCode = KeyEvent.KEYCODE_DPAD_LEFT, desc = "Cursor left"),
            Key(iconRes = R.drawable.ic_lucide_arrow_down, keyCode = KeyEvent.KEYCODE_DPAD_DOWN, desc = "Cursor down"),
            Key(iconRes = R.drawable.ic_lucide_arrow_up, keyCode = KeyEvent.KEYCODE_DPAD_UP, desc = "Cursor up"),
            Key(iconRes = R.drawable.ic_lucide_arrow_right, keyCode = KeyEvent.KEYCODE_DPAD_RIGHT, desc = "Cursor right"),
        ) + "{}()[]<>\"'`;:=+-_*/\\|&!?#\$%^~@,.".map { Key(it.toString(), insert = it.toString()) }
        for (k in keys) {
            val v: View = if (k.iconRes != 0) ImageView(context).apply {
                setImageResource(k.iconRes); setColorFilter(NC.ON_SURF_VAR)
                val p = dp(10); setPadding(p, p, p, p)
            } else TextView(context).apply {
                text = k.label; textSize = 14f; typeface = Typeface.MONOSPACE; setTextColor(NC.ON_SURFACE); gravity = Gravity.CENTER
            }
            v.contentDescription = k.desc.ifEmpty { k.label }
            v.isFocusable = false // keep the IME on the editor
            v.isFocusableInTouchMode = false
            v.minimumWidth = dp(34)
            v.setOnClickListener {
                if (!editor.isEditable && k.insert != null) return@setOnClickListener
                if (k.insert != null) editor.insertText(k.insert, k.insert.length)
                else {
                    editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, k.keyCode))
                    editor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, k.keyCode))
                }
            }
            row.addView(v, LayoutParams(if (k.label == "TAB") dp(46) else dp(36), dp(40)))
        }
        symbolBar.addView(row)
    }

    // endregion

    private fun configureEditor() {
        editor.typefaceText = Typeface.MONOSPACE
        editor.isLineNumberEnabled = true
        editor.isScalable = true
        editor.setPinLineNumber(true)
        editor.setTextSize(prefs.getFloat(PREF_FONT_SP, 13f))
        editor.isWordwrap = prefs.getBoolean(PREF_WRAP, false)
        editor.tabWidth = 4
        editor.setHighlightBracketPair(true)
        val scheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, Color.parseColor("#0A0A0A"))
        scheme.setColor(EditorColorScheme.LINE_NUMBER, NC.OUTLINE)
        scheme.setColor(EditorColorScheme.LINE_DIVIDER, NC.OUTLINE_VAR)
        editor.colorScheme = scheme
        editor.onFocusChangeListener = OnFocusChangeListener { _, focus ->
            symbolBar.visibility = if (focus && editor.isEditable) VISIBLE else GONE
        }
        editor.subscribeEvent(ContentChangeEvent::class.java) { ev, _ ->
            if (!loadingText && ev.action != ContentChangeEvent.ACTION_SET_NEW_TEXT && !dirty) {
                dirty = true; refreshChrome()
            } else refreshUndo()
        }
        editor.subscribeEvent(SelectionChangeEvent::class.java) { _, _ -> updatePos() }
        editor.subscribeEvent(PublishSearchResultEvent::class.java) { _, _ -> updateCount() }
        editor.subscribeEvent(TextSizeChangeEvent::class.java) { ev, _ ->
            val sp = ev.newTextSize / resources.displayMetrics.scaledDensity
            prefs.edit().putFloat(PREF_FONT_SP, sp.coerceIn(8f, 32f)).apply()
        }
    }

    private fun loadFile() {
        io.execute {
            val result: Triple<EditMode, String?, DiskStamp> = try {
                val st = DiskStamp.of(file)
                val head = ByteArray(8192)
                val n = file.inputStream().use { it.read(head).coerceAtLeast(0) }
                var m = EditorLimits.modeFor(st.length, head.copyOf(n), file.canWrite())
                var text: String? = null
                if (m != EditMode.READ_ONLY_BINARY && st.length <= EditorLimits.VIEW_MAX_BYTES) {
                    val bytes = file.readBytes()
                    text = EditorLimits.decodeUtf8(bytes)
                    if (text == null) { text = String(bytes, Charsets.UTF_8); m = EditMode.READ_ONLY_BINARY } // not valid UTF-8: never write back
                }
                Triple(m, text, st)
            } catch (e: Exception) {
                Triple(EditMode.READ_ONLY_BINARY, "Error reading file: ${e.message}", DiskStamp(0, 0))
            }
            ui.post { if (!released) applyLoaded(result.first, result.second ?: "", result.third) }
        }
    }

    private fun applyLoaded(m: EditMode, text: String, st: DiskStamp) {
        mode = m
        stamp = st
        loadingText = true
        editor.setText(text)
        loadingText = false
        dirty = false
        loaded = true
        editor.isEditable = m == EditMode.EDIT
        if (m == EditMode.EDIT || m == EditMode.READ_ONLY_NO_WRITE) applyLanguage()
        refreshChrome()
    }

    private fun applyLanguage() {
        val scope = EditorLanguages.scopeFor(file.name) ?: return
        io.execute {
            val lang = try { TextMateLanguage.create(scope, true) } catch (_: Throwable) { null }
            if (lang != null) ui.post { if (!released) editor.setEditorLanguage(lang) }
        }
    }

    private fun refreshUndo() {
        undoRef.alpha = if (editor.canUndo()) 1f else .35f
        redoRef.alpha = if (editor.canRedo()) 1f else .35f
    }

    private fun refreshChrome() {
        val ro = mode != EditMode.EDIT
        dirtyDot.visibility = if (dirty) VISIBLE else GONE
        chipTv.text = when (mode) {
            EditMode.READ_ONLY_LARGE -> "READ ONLY - LARGE"
            EditMode.READ_ONLY_BINARY -> "READ ONLY - BINARY"
            EditMode.READ_ONLY_NO_WRITE -> "READ ONLY - NO WRITE ACCESS"
            EditMode.EDIT -> (EditorLanguages.scopeFor(file.name)?.substringAfterLast('.')?.uppercase() ?: "TEXT")
        }
        chipTv.setTextColor(if (ro) NC.TERTIARY_CON else NC.ON_SURF_VAR)
        saveBtn.setColorFilter(if (dirty) NC.PRIMARY else NC.OUTLINE)
        saveBtn.alpha = if (ro) .35f else 1f
        replaceRow.visibility = if (ro) GONE else VISIBLE
        if (ro) { undoRef.alpha = .35f; redoRef.alpha = .35f } else refreshUndo()
        wrapRef.setColorFilter(if (editor.isWordwrap) NC.PRIMARY else NC.ON_SURF_VAR)
        findRef.setColorFilter(if (findBar.visibility == VISIBLE) NC.PRIMARY else NC.ON_SURF_VAR)
        updatePos()
        onStateChanged()
    }

    private fun updatePos() {
        val c = editor.cursor
        posTv.text = "${c.leftLine + 1}:${c.leftColumn + 1}"
    }

    private fun setWrap(on: Boolean) {
        editor.isWordwrap = on
        prefs.edit().putBoolean(PREF_WRAP, on).apply()
        refreshChrome()
    }

    private fun bumpFont(deltaSp: Float) {
        val cur = prefs.getFloat(PREF_FONT_SP, 13f)
        val next = (cur + deltaSp).coerceIn(8f, 32f)
        prefs.edit().putFloat(PREF_FONT_SP, next).apply()
        editor.setTextSize(next)
    }

    // region find / goto

    private fun toggleFind() {
        if (findBar.visibility == VISIBLE) closeFind() else showFind()
    }

    fun showFind() {
        gotoBar.visibility = GONE
        findBar.visibility = VISIBLE
        val sel = editor.cursorRange
        if (editor.isTextSelected) {
            val s = editor.text.substring(sel.startIndex, sel.endIndex)
            if (s.isNotEmpty() && !s.contains('\n')) findEt.setText(s)
        }
        findEt.requestFocus()
        refreshChrome()
    }

    private fun closeFind() {
        runCatching { editor.searcher.stopSearch() }
        findBar.visibility = GONE
        countTv.text = ""
        editor.requestFocus()
        refreshChrome()
    }

    private fun runSearch() {
        val q = findEt.text.toString()
        try {
            if (q.isEmpty()) editor.searcher.stopSearch()
            else editor.searcher.search(q, EditorSearcher.SearchOptions(caseInsensitive, useRegex))
        } catch (_: Exception) { /* bad regex while typing */ }
        updateCount()
    }

    private fun updateCount() {
        ui.post {
            val s = editor.searcher
            countTv.text = if (!s.hasQuery()) "" else {
                val n = s.matchedPositionCount
                val i = s.currentMatchedPositionIndex
                if (n == 0) "0" else if (i < 0) "$n" else "${i + 1}/$n"
            }
        }
    }

    private fun toggleGoto() {
        findBar.visibility = GONE
        gotoBar.visibility = if (gotoBar.visibility == VISIBLE) GONE else VISIBLE
        if (gotoBar.visibility == VISIBLE) {
            gotoEt.setText("")
            gotoEt.hint = "Line 1-${editor.lineCount}"
            gotoEt.requestFocus()
        }
        refreshChrome()
    }

    private fun gotoLine() {
        val n = gotoEt.text.toString().toIntOrNull() ?: return
        val line = n.coerceIn(1, editor.lineCount) - 1
        editor.jumpToLine(line)
        editor.setSelection(line, 0)
        gotoBar.visibility = GONE
        editor.requestFocus()
    }

    // endregion

    // region save / close guards

    /** Ctrl+S / button. [then] runs only after a successful save. */
    fun save(then: (() -> Unit)? = null) {
        if (mode != EditMode.EDIT || !loaded) {
            Toast.makeText(context, "Read-only file", Toast.LENGTH_SHORT).show()
            return
        }
        if (DiskStamp.of(file) != stamp && file.exists()) {
            AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("File changed on disk")
                .setMessage("${file.name} was modified since you opened it.")
                .setPositiveButton("OVERWRITE") { _, _ -> doSave(then) }
                .setNeutralButton("RELOAD") { _, _ -> reload() }
                .setNegativeButton("CANCEL", null)
                .create().showThemed(context, setOf(AlertDialog.BUTTON_POSITIVE))
            return
        }
        doSave(then)
    }

    private fun doSave(then: (() -> Unit)?) {
        val bytes = editor.text.toString().toByteArray(Charsets.UTF_8)
        io.execute {
            val err = try { AtomicSave.write(file, bytes); null } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            ui.post {
                if (released) return@post
                if (err != null) {
                    Toast.makeText(context, "Save failed: $err", Toast.LENGTH_LONG).show()
                } else {
                    stamp = DiskStamp.of(file)
                    dirty = false
                    refreshChrome()
                    Toast.makeText(context, "Saved ${file.name}", Toast.LENGTH_SHORT).show()
                    then?.invoke()
                }
            }
        }
    }

    private fun reload() {
        dirty = false
        loaded = false
        loadFile()
    }

    /** Clean + file changed behind our back: take the new content silently. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && loaded && !dirty && file.exists() && DiskStamp.of(file) != stamp) reload()
    }

    /** Runs [proceed] now when clean, else asks Save / Discard / Cancel first. */
    fun confirmLeave(proceed: () -> Unit) {
        if (!dirty) { proceed(); return }
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Unsaved changes")
            .setMessage("Save changes to ${file.name}?")
            .setPositiveButton("SAVE") { _, _ -> save { proceed() } }
            .setNeutralButton("DISCARD") { _, _ -> dirty = false; proceed() }
            .setNegativeButton("CANCEL", null)
            .create().showThemed(context, setOf(AlertDialog.BUTTON_NEUTRAL))
    }

    fun release() {
        released = true
        io.shutdown()
        runCatching { editor.release() }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.isCtrlPressed) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_S -> { save(); return true }
                KeyEvent.KEYCODE_F -> { showFind(); return true }
                KeyEvent.KEYCODE_G -> { toggleGoto(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // endregion

    companion object {
        private const val PREF_FONT_SP = "editor_font_sp"
        private const val PREF_WRAP = "editor_wrap"
    }
}
