package com.zenithblue.nativecode.workspace

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.editor.showThemed
import java.io.File
import java.util.concurrent.Executors

/** What the Directory page needs from the activity. */
internal interface DirectoryHost {
    /** Host directory of the active project. */
    fun root(): File
    fun openFile(file: File)
    fun copyText(label: String, text: String)
    /** The path the guest shell sees for [file], or null when it can not be mapped. */
    fun guestPath(file: File): String?
    fun openTerminalHere(dir: File)
    /** Project-relative (path, status letter) pairs from git, or null when the project is not a repo. Blocking: bg thread. */
    fun gitChanges(): List<Pair<String, Char>>?
    fun confirmDelete(name: String, isDir: Boolean, onConfirm: () -> Unit)
}

/**
 * Workspace Directory page: breadcrumbs, folder list with type icons, size and modified time, git markers,
 * filter / project-wide search, sort, hidden files, per-row actions. Sits under the shared WorkspaceTopBar.
 */
internal class DirectoryPage(ctx: Context, private val host: DirectoryHost) : LinearLayout(ctx) {
    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val prefs = ctx.getSharedPreferences("nativecode_prefs", Context.MODE_PRIVATE)

    private var cur: File = host.root()
    private var query = ""
    private var showHidden = prefs.getBoolean(PREF_HIDDEN, false)
    private var sort = runCatching { DirSort.valueOf(prefs.getString(PREF_SORT, "NAME")!!) }.getOrDefault(DirSort.NAME)
    private var markers: Map<String, Char> = emptyMap()
    private var token = 0
    private var entries: List<DirEntry> = emptyList()

    private val crumbRow = LinearLayout(ctx)
    private val crumbScroll = HorizontalScrollView(ctx)
    private val searchEt = EditText(ctx)
    private val clearBtn = ImageView(ctx)
    private val hiddenBtn = ImageView(ctx)
    private val summaryTv = TextView(ctx)
    private val list = RecyclerView(ctx)
    private val emptyBox = LinearLayout(ctx)
    private val emptyTitle = TextView(ctx)
    private val emptyBody = TextView(ctx)
    private val emptyActions = LinearLayout(ctx)

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(NC.BG)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        buildHeader()
        val body = FrameLayout(ctx)
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = Adapter()
        list.clipToPadding = false
        list.setPadding(0, 0, 0, dp(96))
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        buildEmpty()
        body.addView(emptyBox, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        addView(body, LayoutParams(-1, 0, 1f))
    }

    // region header

    private fun iconBtn(res: Int, desc: String, onClick: (View) -> Unit) = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(NC.ON_SURF_VAR)
        contentDescription = desc
        val p = dp(10)
        setPadding(p, p, p, p)
        layoutParams = LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick(this) }
    }

    private fun buildHeader() {
        val header = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(NC.BG)
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        crumbScroll.isHorizontalScrollBarEnabled = false
        crumbRow.orientation = HORIZONTAL
        crumbRow.gravity = Gravity.CENTER_VERTICAL
        crumbScroll.addView(crumbRow)
        header.addView(crumbScroll, LayoutParams(-1, -2))

        val bar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val searchBox = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(NC.SURFACE_LOWEST); setStroke(dp(1), NC.OUTLINE_VAR) }
            setPadding(dp(10), 0, dp(4), 0)
        }
        searchBox.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_lucide_search)
            setColorFilter(NC.PRIMARY)
        }, LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) })
        searchEt.apply {
            hint = "Filter or search project"
            setHintTextColor(NC.OUTLINE)
            setTextColor(NC.ON_SURFACE)
            textSize = 14f
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            minHeight = dp(44)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    query = s?.toString()?.trim() ?: ""
                    clearBtn.visibility = if (query.isEmpty()) GONE else VISIBLE
                    ui.removeCallbacks(reload); ui.postDelayed(reload, 220)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchBox.addView(searchEt, LayoutParams(0, -2, 1f))
        clearBtn.apply {
            setImageResource(R.drawable.ic_lucide_x)
            setColorFilter(NC.OUTLINE)
            contentDescription = "Clear search"
            val p = dp(10); setPadding(p, p, p, p)
            visibility = GONE
            setOnClickListener { searchEt.setText("") }
        }
        searchBox.addView(clearBtn, LayoutParams(dp(40), dp(40)))
        bar.addView(searchBox, LayoutParams(0, -2, 1f))
        bar.addView(iconBtn(R.drawable.ic_lucide_arrow_up_down, "Sort") { showSortMenu(it) })
        hiddenBtn.apply {
            setImageResource(if (showHidden) R.drawable.ic_lucide_eye else R.drawable.ic_lucide_eye_off)
            setColorFilter(if (showHidden) NC.PRIMARY else NC.ON_SURF_VAR)
            contentDescription = "Show hidden files"
            val p = dp(10); setPadding(p, p, p, p)
            setOnClickListener {
                showHidden = !showHidden
                prefs.edit().putBoolean(PREF_HIDDEN, showHidden).apply()
                setImageResource(if (showHidden) R.drawable.ic_lucide_eye else R.drawable.ic_lucide_eye_off)
                setColorFilter(if (showHidden) NC.PRIMARY else NC.ON_SURF_VAR)
                reload()
            }
        }
        bar.addView(hiddenBtn, LayoutParams(dp(44), dp(44)))
        bar.addView(iconBtn(R.drawable.ic_lucide_plus, "New") { showNewMenu(it) })
        header.addView(bar, LayoutParams(-1, -2).apply { topMargin = dp(6) })

        summaryTv.apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(NC.OUTLINE); setPadding(dp(2), dp(6), 0, dp(2)) }
        header.addView(summaryTv)
        header.addView(View(context).apply { setBackgroundColor(NC.OUTLINE_VAR) }, LayoutParams(-1, dp(1)))
        addView(header)
    }

    private fun buildEmpty() {
        emptyBox.orientation = VERTICAL
        emptyBox.gravity = Gravity.CENTER_HORIZONTAL
        emptyBox.setPadding(dp(24), dp(24), dp(24), dp(24))
        emptyBox.visibility = GONE
        emptyBox.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_lucide_folder)
            setColorFilter(NC.OUTLINE)
        }, LayoutParams(dp(64), dp(64)))
        emptyTitle.apply { textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(NC.ON_SURFACE); gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) }
        emptyBody.apply { textSize = 13f; setTextColor(NC.ON_SURF_VAR); gravity = Gravity.CENTER }
        emptyActions.orientation = HORIZONTAL
        emptyActions.gravity = Gravity.CENTER
        emptyActions.setPadding(0, dp(16), 0, 0)
        emptyActions.addView(pill("NEW FILE", R.drawable.ic_lucide_file_plus) { promptCreate(false) })
        emptyActions.addView(pill("NEW FOLDER", R.drawable.ic_lucide_folder_plus) { promptCreate(true) },
            LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        emptyBox.addView(emptyTitle); emptyBox.addView(emptyBody); emptyBox.addView(emptyActions)
    }

    private fun pill(label: String, iconRes: Int, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(NC.PRIMARY_CON) }
        setPadding(dp(12), dp(10), dp(14), dp(10))
        addView(ImageView(context).apply { setImageResource(iconRes); setColorFilter(Color.parseColor("#0A0A0A")) }, LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(6) })
        addView(TextView(context).apply { text = label; textSize = 12f; typeface = Typeface.MONOSPACE; paint.isFakeBoldText = true; setTextColor(Color.parseColor("#0A0A0A")) })
        setOnClickListener { onClick() }
    }

    private fun renderCrumbs() {
        crumbRow.removeAllViews()
        val crumbs = DirModel.breadcrumbs(host.root(), cur)
        crumbs.forEachIndexed { i, c ->
            if (i > 0) crumbRow.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_lucide_chevron_right); setColorFilter(NC.OUTLINE)
            }, LayoutParams(dp(16), dp(16)))
            val last = i == crumbs.lastIndex
            crumbRow.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(40)
                setPadding(dp(6), 0, dp(6), 0)
                if (i == 0) addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_lucide_house); setColorFilter(if (last) NC.PRIMARY else NC.ON_SURF_VAR)
                }, LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(6) })
                addView(TextView(context).apply {
                    text = c.label; textSize = 13f; typeface = Typeface.MONOSPACE
                    setTextColor(if (last) NC.PRIMARY else NC.ON_SURF_VAR)
                    if (last) paint.isFakeBoldText = true
                    maxLines = 1
                })
                contentDescription = "Open ${c.label}"
                setOnClickListener { if (!last || query.isNotEmpty()) { searchEt.setText(""); go(c.dir) } }
            })
        }
        crumbScroll.post { crumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // endregion

    // region state

    /** Page shown (again) or the project changed: re-list and refresh git markers. */
    fun refresh() {
        val root = host.root()
        if (!DirModel.isInside(root, cur) || !cur.isDirectory) cur = root
        reload()
        loadGit()
    }

    /** Back inside the page: clear search, then go up one folder. Returns false at the project root. */
    fun handleBack(): Boolean {
        if (query.isNotEmpty()) { searchEt.setText(""); return true }
        val root = host.root()
        if (cur.absolutePath != root.absolutePath && DirModel.isInside(root, cur)) {
            go(cur.parentFile ?: root)
            return true
        }
        return false
    }

    private fun go(dir: File) {
        cur = dir
        reload()
    }

    private val reload = Runnable { reload() }

    private fun reload() {
        val root = host.root()
        val dir = cur
        val q = query
        val hidden = showHidden
        val s = sort
        val my = ++token
        renderCrumbs()
        bg.execute {
            val result = if (q.isEmpty()) DirModel.list(dir, hidden, "", s) else DirModel.search(root, q, hidden, s)
            ui.post { if (my == token) show(result, q) }
        }
    }

    private fun show(result: List<DirEntry>, q: String) {
        entries = result
        list.adapter?.notifyDataSetChanged()
        val dirs = result.count { it.isDir }
        summaryTv.text = if (q.isNotEmpty()) "${result.size} matches for \"$q\"" else "$dirs folders, ${result.size - dirs} files"
        if (result.isEmpty()) {
            emptyBox.visibility = VISIBLE
            if (q.isNotEmpty()) {
                emptyTitle.text = "No matches"
                emptyBody.text = "Nothing in this project matches \"$q\"."
                emptyActions.visibility = GONE
            } else {
                emptyTitle.text = "This folder is empty"
                emptyBody.text = if (showHidden) "Create a file or folder to get started." else "Create a file or folder to get started. Hidden files are not shown."
                emptyActions.visibility = VISIBLE
            }
        } else emptyBox.visibility = GONE
    }

    private fun loadGit() {
        bg.execute {
            val m = try { host.gitChanges()?.let { DirModel.markers(it) } } catch (_: Exception) { null } ?: emptyMap()
            ui.post { markers = m; list.adapter?.notifyDataSetChanged() }
        }
    }

    // endregion

    // region menus and actions

    private fun showSortMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 0, 0, "Name").isCheckable = true
            menu.add(0, 1, 1, "Modified").isCheckable = true
            menu.add(0, 2, 2, "Size").isCheckable = true
            menu.setGroupCheckable(0, true, true)
            menu.getItem(sort.ordinal).isChecked = true
            setOnMenuItemClickListener {
                sort = DirSort.values()[it.itemId]
                prefs.edit().putString(PREF_SORT, sort.name).apply()
                reload(); true
            }
        }.show()
    }

    private fun showNewMenu(anchor: View) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 0, 0, "New file")
            menu.add(0, 1, 1, "New folder")
            menu.add(0, 2, 2, "Open terminal here")
            setOnMenuItemClickListener {
                when (it.itemId) { 0 -> promptCreate(false); 1 -> promptCreate(true); else -> host.openTerminalHere(cur) }
                true
            }
        }.show()
    }

    private fun showRowMenu(anchor: View, e: DirEntry) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 0, 0, if (e.isDir) "Open folder" else "Open")
            menu.add(0, 1, 1, "Rename")
            menu.add(0, 2, 2, "Copy path")
            menu.add(0, 3, 3, "Copy relative path")
            menu.add(0, 4, 4, "Open terminal here")
            menu.add(0, 5, 5, "Delete")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    0 -> open(e)
                    1 -> promptRename(e)
                    2 -> host.copyText("File Path", host.guestPath(e.file) ?: e.file.absolutePath)
                    3 -> host.copyText("File Path", DirModel.relativePath(host.root(), e.file))
                    4 -> host.openTerminalHere(if (e.isDir) e.file else (e.file.parentFile ?: host.root()))
                    5 -> host.confirmDelete(e.name, e.isDir) { delete(e) }
                }
                true
            }
        }.show()
    }

    private fun open(e: DirEntry) {
        if (e.isDir) { searchEt.setText(""); go(e.file) } else host.openFile(e.file)
    }

    private fun nameDialog(title: String, initial: String, action: String, onName: (String) -> Unit) {
        val input = EditText(context).apply {
            setText(initial)
            setSelection(initial.length)
            setSingleLine(true)
            setTextColor(NC.ON_SURFACE)
            typeface = Typeface.MONOSPACE
        }
        val box = FrameLayout(context).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(action) { _, _ -> onName(input.text.toString().trim()) }
            .setNegativeButton("CANCEL", null)
            .create().also { dlg ->
                input.imeOptions = EditorInfo.IME_ACTION_DONE
                input.setOnEditorActionListener { _, _, _ -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); true }
            }.showThemed(context)
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    private fun promptCreate(folder: Boolean) {
        val parent = if (query.isEmpty()) cur else host.root()
        nameDialog(if (folder) "New folder" else "New file", "", "CREATE") { name ->
            if (!DirModel.validName(name)) { toast("Invalid name"); return@nameDialog }
            val f = File(parent, name)
            if (f.exists()) { toast("$name already exists"); return@nameDialog }
            val ok = try { if (folder) f.mkdirs() else f.createNewFile() } catch (_: Exception) { false }
            if (!ok) { toast("Could not create $name"); return@nameDialog }
            searchEt.setText("")
            cur = parent
            reload(); loadGit()
            if (!folder) host.openFile(f)
        }
    }

    private fun promptRename(e: DirEntry) {
        nameDialog("Rename", e.name, "RENAME") { name ->
            if (name == e.name) return@nameDialog
            if (!DirModel.validName(name)) { toast("Invalid name"); return@nameDialog }
            val dest = File(e.file.parentFile, name)
            if (dest.exists()) { toast("$name already exists"); return@nameDialog }
            if (!e.file.renameTo(dest)) toast("Rename failed")
            reload(); loadGit()
        }
    }

    private fun delete(e: DirEntry) {
        bg.execute {
            val err = try { DirModel.deleteTree(host.root(), e.file); null } catch (ex: Exception) { ex.message ?: "failed" }
            ui.post {
                if (err != null) toast("Delete failed: $err") else toast("Deleted ${e.name}")
                reload(); loadGit()
            }
        }
    }

    // endregion

    // region list

    private fun iconFor(e: DirEntry): Int = when (DirModel.kindOf(e.name, e.isDir)) {
        FileKind.FOLDER -> R.drawable.ic_lucide_folder
        FileKind.CODE -> R.drawable.ic_lucide_file_code
        FileKind.TEXT -> R.drawable.ic_lucide_file_text
        FileKind.IMAGE -> R.drawable.ic_lucide_image
        FileKind.ARCHIVE -> R.drawable.ic_lucide_archive
        FileKind.SCRIPT -> R.drawable.ic_lucide_terminal
        FileKind.OTHER -> R.drawable.ic_lucide_file
    }

    private fun markerColor(c: Char) = when (c) {
        'A', 'U', '?' -> NC.PRIMARY
        'D', '!' -> NC.ERROR
        else -> NC.TERTIARY_CON
    }

    private inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val icon = ImageView(context)
        val name = TextView(context)
        val sub = TextView(context)
        val marker = TextView(context)
        val dot = View(context)
        val more = ImageView(context)

        init {
            row.orientation = HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = dp(56)
            row.setPadding(dp(14), dp(6), dp(4), dp(6))
            row.layoutParams = RecyclerView.LayoutParams(-1, -2)
            row.isClickable = true
            row.isFocusable = true
            icon.layoutParams = LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(14) }
            val col = LinearLayout(context).apply { orientation = VERTICAL }
            name.apply { textSize = 14f; setTextColor(NC.ON_SURFACE); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
            sub.apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(NC.OUTLINE); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
            col.addView(name); col.addView(sub)
            marker.apply { textSize = 12f; typeface = Typeface.MONOSPACE; paint.isFakeBoldText = true; setPadding(dp(6), 0, dp(6), 0) }
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            more.apply {
                setImageResource(R.drawable.ic_lucide_more_vertical); setColorFilter(NC.ON_SURF_VAR)
                contentDescription = "More actions"
                val p = dp(10); setPadding(p, p, p, p)
            }
            row.addView(icon); row.addView(col, LayoutParams(0, -2, 1f)); row.addView(marker)
            row.addView(dot, LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
            row.addView(more, LayoutParams(dp(44), dp(44)))
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(parent.context))
        override fun getItemCount() = entries.size
        override fun onBindViewHolder(h: Holder, position: Int) {
            val e = entries[position]
            h.icon.setImageResource(iconFor(e))
            h.icon.setColorFilter(if (e.isDir) NC.PRIMARY else NC.ON_SURF_VAR)
            h.name.text = e.name
            val rel = DirModel.relativePath(host.root(), e.file)
            val whenText = DateUtils.getRelativeTimeSpanString(e.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            h.sub.text = when {
                query.isNotEmpty() -> rel
                e.isDir -> whenText.toString()
                else -> "${DirModel.formatSize(e.size)}  -  $whenText"
            }
            val m = markers[rel]
            if (m == null) { h.marker.visibility = GONE; h.dot.visibility = GONE }
            else if (m == '*') {
                h.marker.visibility = GONE; h.dot.visibility = VISIBLE
                (h.dot.background as GradientDrawable).setColor(NC.TERTIARY_CON)
            } else {
                h.dot.visibility = GONE; h.marker.visibility = VISIBLE
                h.marker.text = if (m == 'U') "?" else m.toString()
                h.marker.setTextColor(markerColor(m))
            }
            h.row.setOnClickListener { open(e) }
            h.row.setOnLongClickListener { showRowMenu(h.more, e); true }
            h.more.setOnClickListener { showRowMenu(h.more, e) }
        }
    }

    // endregion

    companion object {
        private const val PREF_HIDDEN = "dir_show_hidden"
        private const val PREF_SORT = "dir_sort"
    }
}
