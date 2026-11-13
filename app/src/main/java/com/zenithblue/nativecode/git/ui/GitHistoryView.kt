package com.zenithblue.nativecode.git.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.git.GitCommitDetail
import com.zenithblue.nativecode.git.GitGraphLayout
import com.zenithblue.nativecode.git.GitGuestCommands
import com.zenithblue.nativecode.git.GitLogParse
import com.zenithblue.nativecode.git.GitLogStream
import com.zenithblue.nativecode.git.GitRefKind
import com.zenithblue.nativecode.git.GitRepoService
import com.zenithblue.nativecode.git.GitSearch
import com.zenithblue.nativecode.git.GraphRow
import com.zenithblue.nativecode.git.RefTarget
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * HISTORY tab of the project Git page: commit graph over all branches/tags/remotes.
 * Rows come from one streaming `git log` ([GitLogStream]); this view only appends and draws.
 * Fixed-height rows, RecyclerView, custom Canvas cell for the lanes.
 */
internal class GitHistoryView(
    context: Context,
    private val io: Executor,
    private val onFileDiff: (hash: String, path: String) -> Unit
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private var project = ""
    private var method = ""
    private var ref: String? = null
    private val rows = ArrayList<GraphRow>()
    private var resume = GitGraphLayout()
    private var stream: GitLogStream? = null
    private var finished = false
    private var maxRows = PAGE_CAP
    private var cellLanes = 1
    private var t0 = 0L
    private var tFirst = 0L
    private var tFull = 0L
    private var startedSkip = 0

    private val filterBtn = chip("ALL REFS  v")
    private val statusTv = TextView(context).apply {
        textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(NC.ON_SURF_VAR)
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        maxLines = 1; ellipsize = TextUtils.TruncateAt.START
    }
    private val moreBtn = chip("LOAD MORE").apply { visibility = View.GONE }
    private val msgTv = TextView(context).apply {
        textSize = 13f; setTextColor(NC.ON_SURF_VAR); gravity = Gravity.CENTER
        typeface = Typeface.MONOSPACE; setPadding(dp(24), dp(24), dp(24), dp(24))
        visibility = View.GONE
    }
    private val lm = LinearLayoutManager(context)
    private val adapter = Adapter()
    private val list: RecyclerView = RecyclerView(context).apply {
        layoutManager = lm
        adapter = this@GitHistoryView.adapter
        setHasFixedSize(true)
        itemAnimator = null
        clipToPadding = false
        setPadding(0, 0, 0, dp(100))
        overScrollMode = OVER_SCROLL_NEVER
        addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                onScrolledNearEnd()
                topFab.visibility = if (lm.findFirstVisibleItemPosition() > FAB_AFTER) View.VISIBLE else View.GONE
            }
        })
    }
    private val topFab: TextView = TextView(context).apply {
        text = "↑"; textSize = 20f; setTextColor(NC.ON_PRIMARY); gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(NC.PRIMARY); setStroke(dp(2), NC.ON_PRIMARY_CON) }
        contentDescription = "Scroll to top"
        visibility = View.GONE
        isClickable = true; isFocusable = true
        setOnClickListener {
            // Smooth only for short distances; a smooth scroll over thousands of rows takes seconds.
            if (lm.findFirstVisibleItemPosition() > 60) list.scrollToPosition(0) else list.smoothScrollToPosition(0)
            visibility = View.GONE
        }
    }
    /** Row flashed after a search jump. */
    private var highlight = -1
    private val killTask = Runnable {
        if (!finished) { stream?.cancel(); stream = null }
    }

    init {
        setBackgroundColor(NC.BG)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        bar.addView(filterBtn)
        val search = chip("SEARCH").apply {
            contentDescription = "Search branches, tags and commits"
            setOnClickListener { openSearch() }
        }
        bar.addView(search, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        bar.addView(statusTv, LinearLayout.LayoutParams(0, dp(30), 1f).apply { leftMargin = dp(10); rightMargin = dp(8) })
        bar.addView(moreBtn)
        val reload = chip("RELOAD")
        bar.addView(reload)
        col.addView(bar)
        col.addView(View(context).apply { setBackgroundColor(NC.OUTLINE_VAR) }, LinearLayout.LayoutParams(-1, dp(1)))
        col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(col, LayoutParams(-1, -1))
        addView(msgTv, LayoutParams(-1, -2, Gravity.CENTER))
        addView(topFab, LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(16); bottomMargin = dp(96) })
        filterBtn.setOnClickListener { pickFilter() }
        reload.setOnClickListener { restart() }
        moreBtn.setOnClickListener { maxRows += PAGE_CAP; moreBtn.visibility = View.GONE; updateAllow() }
    }

    private fun chip(label: String) = TextView(context).apply {
        text = label; textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(NC.PRIMARY)
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        minHeight = dp(34)
        background = GradientDrawable().apply { setColor(NC.SURFACE_LOWEST); setStroke(dp(1), NC.OUTLINE_VAR) }
        isClickable = true; isFocusable = true
    }

    /** Idempotent: keeps loaded rows and scroll when called again for the same project. */
    fun show(projectPath: String, projectMethod: String) {
        if (projectPath == project && projectMethod == method && (rows.isNotEmpty() || stream != null || finished)) {
            ensureStream()
            return
        }
        project = projectPath; method = projectMethod; ref = null
        filterBtn.text = "ALL REFS  v"
        restart()
    }

    fun restart() {
        stream?.cancel(); stream = null
        rows.clear(); resume = GitGraphLayout(); finished = false; maxRows = PAGE_CAP
        cellLanes = 1; tFirst = 0; tFull = 0; startedSkip = 0; highlight = -1
        topFab.visibility = View.GONE
        adapter.notifyDataSetChanged()
        moreBtn.visibility = View.GONE
        showMsg("LOADING HISTORY...")
        updateStatus()
        begin(0)
    }

    private fun begin(skip: Int) {
        if (project.isBlank()) { showMsg("NO ACTIVE PROJECT"); return }
        startedSkip = skip
        t0 = System.nanoTime()
        // Resume overlaps one row: git must return the last shown commit first, else history moved.
        val s = GitLogStream(
            context, project, method, ref, if (skip == 0) 0 else skip - 1,
            if (skip == 0) GitGraphLayout() else resume.copy(),
            if (skip == 0) null else rows[skip - 1].commit.hash,
            onBatch = { batch, r -> onBatch(batch, r) },
            onEnd = { fin, exit -> onEnd(fin, exit) }
        )
        stream = s
        s.allowUpTo(skip + LOOKAHEAD)
        s.start()
    }

    private fun onBatch(batch: List<GraphRow>, r: GitGraphLayout) {
        val first = rows.isEmpty()
        val start = rows.size
        rows.addAll(batch)
        resume = r
        if (first) {
            tFirst = ms(); msgTv.visibility = View.GONE
            Log.i(TAG, "first rows ${batch.size} in ${tFirst}ms")
            warmCommitGraph()
        }
        val lanes = minOf(r.maxLanes, laneCap)
        if (lanes != cellLanes) { cellLanes = lanes; adapter.notifyDataSetChanged() }
        else adapter.notifyItemRangeInserted(start, batch.size)
        if (rows.size >= maxRows) moreBtn.visibility = View.VISIBLE
        Log.d(TAG, "batch rows=${rows.size} t=${ms()}ms")
        updateStatus()
    }

    private fun onEnd(fin: Boolean, exit: Int) {
        stream = null
        if (exit == GitLogStream.STALE) {
            Log.i(TAG, "resume offset stale (history changed): reloading")
            restart(); return
        }
        if (!fin) return
        finished = true
        tFull = ms()
        Log.i(TAG, "full ${rows.size} commits in ${tFull}ms (lanes ${resume.maxLanes})")
        if (rows.isEmpty()) showMsg(if (exit == 0 || exit == 128) "NO COMMITS" else "git log failed (exit $exit)")
        updateStatus()
    }

    private fun ms() = (System.nanoTime() - t0) / 1_000_000L

    /** Lanes drawn before the rest collapse into the last column: a third of the width, at least 6. */
    private var laneCap = GraphCellView.MAX_LANES

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cap = maxOf(GraphCellView.MAX_LANES, (w / 3f / (GraphCellView.LANE_DP * d)).toInt())
        if (cap == laneCap) return
        laneCap = cap
        cellLanes = minOf(resume.maxLanes, cap).coerceAtLeast(1)
        post { adapter.notifyDataSetChanged() }
    }

    private fun updateStatus() {
        val sb = StringBuilder().append(rows.size).append(" commits")
        if (!finished && rows.isNotEmpty()) sb.append("...")
        if (tFirst > 0 && startedSkip == 0) sb.append(" | first ").append(tFirst).append("ms")
        if (finished && startedSkip == 0) sb.append(" | full ").append(tFull).append("ms")
        statusTv.text = sb
    }

    private fun showMsg(m: String) { msgTv.text = m; msgTv.visibility = View.VISIBLE }

    private fun updateAllow() {
        val last = lm.findLastVisibleItemPosition().coerceAtLeast(0)
        stream?.allowUpTo(minOf(last + LOOKAHEAD, maxRows))
    }

    private fun onScrolledNearEnd() {
        updateAllow()
        ensureStream()
    }

    /** A stream killed while hidden restarts (with --skip and the saved lane state) once more rows are wanted. */
    private fun ensureStream() {
        if (stream != null || finished || rows.isEmpty() || rows.size >= maxRows) return
        if (lm.findLastVisibleItemPosition() >= rows.size - 200) begin(rows.size)
    }

    // ponytail: one-time per project per process; skipped when a commit-graph already exists.
    private fun warmCommitGraph() {
        if (!warmed.add(project)) return
        val p = project; val m = method
        io.execute {
            try {
                val (args, env) = LinuxCommandBuilder.build(context, GitGuestCommands.ensureCommitGraph(p), method = m)
                ShellCommandRunner.runCaptureExit(context, args, HashMap(env).apply { putAll(GitGuestCommands.gitEnv(p)) })
            } catch (_: Exception) {}
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        removeCallbacks(killTask)
        if (isVisible) ensureStream() else postDelayed(killTask, 30_000L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stream?.cancel(); stream = null
    }

    // -- filter ---------------------------------------------------------------------------------

    private fun pickFilter() {
        val p = project; val m = method
        io.execute {
            val info = GitRepoService.listBranches(context, p, m)
            post {
                val sheet = BottomSheetDialog(context)
                val col = sheetColumn()
                col.addView(sheetTitle("FILTER BY BRANCH"))
                fun item(label: String, value: String?) {
                    col.addView(TextView(context).apply {
                        text = label; textSize = 13f; typeface = Typeface.MONOSPACE
                        setTextColor(if (value == ref) NC.PRIMARY else NC.ON_SURFACE)
                        setPadding(dp(4), dp(12), dp(4), dp(12))
                        setOnClickListener { sheet.dismiss(); applyFilter(value) }
                    })
                }
                item("ALL REFS", null)
                info.localBranches.forEach { item(it, it) }
                // short name of refs/remotes/<remote>/HEAD is the bare remote name ("origin"): skip it
                info.remoteBranches.filter { it.contains('/') && !it.endsWith("/HEAD") }.forEach { item(it, it) }
                sheet.setContentView(ScrollView(context).apply { addView(col) })
                sheet.show()
            }
        }
    }

    private fun applyFilter(value: String?) {
        ref = value
        filterBtn.text = (value ?: "ALL REFS").take(24) + "  v"
        restart()
    }

    // -- search ---------------------------------------------------------------------------------

    /**
     * Type to rank branches / remotes / tags (substring, then fuzzy) and loaded commits
     * (subject, author, hash prefix). Tap a ref: scroll to its head if loaded, else filter by it.
     * FILTER on a ref row always applies the branch filter.
     */
    private fun openSearch() {
        val sheet = BottomSheetDialog(context)
        val col = sheetColumn()
        col.addView(sheetTitle("SEARCH REFS + COMMITS"))
        val input = EditText(context).apply {
            hint = "branch, tag, subject, author or hash"
            textSize = 14f; typeface = Typeface.MONOSPACE
            setTextColor(NC.ON_SURFACE); setHintTextColor(NC.OUTLINE)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply { setColor(NC.SURFACE_LOWEST); setStroke(dp(1), NC.PRIMARY) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(48)
        }
        col.addView(input, LinearLayout.LayoutParams(-1, -2))
        val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(results)
        var refs: List<RefTarget>? = null

        fun render() {
            results.removeAllViews()
            val q = input.text.toString().trim()
            val rs = refs
            results.addView(text(if (rs == null) "// REFS  loading..." else "// REFS", 11f, NC.ON_SURF_VAR).apply {
                setPadding(0, dp(12), 0, dp(4)); setTextIsSelectable(false)
            })
            if (rs != null) {
                val ranked = GitSearch.rankRefs(q, rs, if (q.isEmpty()) 30 else 40)
                if (ranked.isEmpty()) results.addView(text("no matching refs", 12f, NC.OUTLINE).apply { setTextIsSelectable(false) })
                for (r in ranked) results.addView(refRow(r, { sheet.dismiss(); jumpTo(r) }, { sheet.dismiss(); applyFilter(r.name) }))
            }
            if (q.isEmpty()) return
            val hits = ArrayList<Int>()
            for (i in rows.indices) {
                if (GitSearch.commitMatches(q, rows[i].commit)) { hits += i; if (hits.size == 50) break }
            }
            results.addView(text("// COMMITS  ${hits.size}${if (hits.size == 50) "+" else ""} in ${rows.size} loaded", 11f, NC.ON_SURF_VAR).apply {
                setPadding(0, dp(16), 0, dp(4)); setTextIsSelectable(false)
            })
            for (i in hits) {
                val c = rows[i].commit
                results.addView(TextView(context).apply {
                    val sb = SpannableStringBuilder(c.hash.take(7))
                    sb.setSpan(ForegroundColorSpan(NC.PRIMARY), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append("  ").append(c.subject)
                    val s = sb.length
                    sb.append("  ").append(c.author)
                    sb.setSpan(ForegroundColorSpan(NC.ON_SURF_VAR), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text = sb; textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(NC.ON_SURFACE)
                    maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(4), dp(10), dp(4), dp(10)); minHeight = dp(44)
                    setOnClickListener { sheet.dismiss(); scrollToRow(i) }
                })
            }
        }
        val debounce = Runnable { if (sheet.isShowing) render() }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { removeCallbacks(debounce); postDelayed(debounce, 150) }
        })
        input.setOnEditorActionListener { _, _, _ ->
            // Enter: open the best ref, if any
            val best = refs?.let { GitSearch.rankRefs(input.text.toString(), it, 1) }?.firstOrNull()
            if (best != null && input.text.isNotBlank()) { sheet.dismiss(); jumpTo(best) }
            true
        }
        sheet.setContentView(ScrollView(context).apply { addView(col) })
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
        input.requestFocus()
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        render()
        val p = project; val m = method
        io.execute {
            val list = try {
                GitSearch.parseRefList(GitRepoService.run(context, p, m, GitGuestCommands.refList(p)).second)
            } catch (_: Exception) { emptyList() }
            post { if (sheet.isShowing) { refs = list; render() } }
        }
    }

    private fun refRow(r: RefTarget, onJump: () -> Unit, onFilter: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        val (fg, label) = when (r.kind) {
            GitRefKind.LOCAL -> NC.PRIMARY to "branch"
            GitRefKind.REMOTE -> 0xFF7FB4FF.toInt() to "remote"
            GitRefKind.TAG -> NC.TERTIARY to "tag"
        }
        addView(TextView(context).apply {
            text = label; textSize = 9f; typeface = Typeface.MONOSPACE; setTextColor(fg)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setStroke(dp(1), fg) }
            setPadding(dp(4), dp(1), dp(4), dp(1))
        }, LinearLayout.LayoutParams(dp(52), -2))
        addView(TextView(context).apply {
            text = r.name; textSize = 13f; typeface = Typeface.MONOSPACE
            setTextColor(if (r.name == ref) NC.PRIMARY else NC.ON_SURFACE)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(dp(10), dp(10), dp(8), dp(10))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(chip("FILTER").apply {
            contentDescription = "Filter history by ${r.name}"
            setOnClickListener { onFilter() }
        })
        isClickable = true; isFocusable = true
        contentDescription = "Jump to $label ${r.name}"
        setOnClickListener { onJump() }
    }

    private fun jumpTo(r: RefTarget) {
        val i = rows.indexOfFirst { it.commit.hash == r.hash }
        if (i >= 0) { scrollToRow(i); return }
        Toast.makeText(context, "${r.name} not loaded yet: filtering", Toast.LENGTH_SHORT).show()
        applyFilter(r.name)
    }

    private fun scrollToRow(i: Int) {
        val old = highlight
        highlight = i
        if (old >= 0) adapter.notifyItemChanged(old)
        adapter.notifyItemChanged(i)
        lm.scrollToPositionWithOffset(i, list.height / 3)
        updateAllow()
    }

    private fun sheetColumn() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(NC.BG)
        setPadding(dp(20), dp(20), dp(20), dp(24))
    }

    private fun sheetTitle(t: String) = TextView(context).apply {
        text = t; textSize = 16f; setTextColor(NC.PRIMARY); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(8))
    }

    // -- detail ---------------------------------------------------------------------------------

    private fun showDetail(row: GraphRow) {
        val c = row.commit
        val sheet = BottomSheetDialog(context)
        val col = sheetColumn()
        col.addView(sheetTitle(c.hash.take(7).uppercase(Locale.ROOT)))
        col.addView(text(c.subject, 14f, NC.ON_SURFACE, bold = true))
        val body = text("loading...", 12f, NC.ON_SURF_VAR)
        col.addView(body)
        val files = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(files)
        col.addView(chip("COPY HASH").apply {
            setOnClickListener {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("commit", c.hash))
                Toast.makeText(context, "Hash copied", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        sheet.setContentView(ScrollView(context).apply { addView(col) })
        sheet.show()
        val p = project; val m = method
        io.execute {
            val detail: GitCommitDetail? = try {
                val (args, env) = LinuxCommandBuilder.build(context, GitGuestCommands.commitDetail(p, c.hash), method = m)
                val (_, out) = ShellCommandRunner.runCaptureExit(context, args, HashMap(env).apply { putAll(GitGuestCommands.gitEnv(p)) })
                GitLogParse.parseDetail(out)
            } catch (_: Exception) { null }
            post {
                if (!sheet.isShowing) return@post
                if (detail == null) { body.text = "Could not load commit"; return@post }
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(detail.time * 1000))
                body.text = detail.message.lines().drop(1).joinToString("\n").trim()
                    .let { if (it.isEmpty()) "" else it + "\n\n" } +
                    "${detail.author}\n$date\n" +
                    "parents: " + (detail.parents.joinToString(" ") { it.take(7) }.ifEmpty { "none (root)" })
                files.addView(text("// ${detail.files.size} FILES", 11f, NC.ON_SURF_VAR).apply { setPadding(0, dp(12), 0, dp(4)) })
                for (f in detail.files) files.addView(fileRow(f.status, f.path, f.added, f.deleted) {
                    sheet.dismiss(); onFileDiff(c.hash, f.path)
                })
            }
        }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = t; textSize = size; setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
        setPadding(0, 0, 0, dp(6)); setTextIsSelectable(true)
    }

    private fun fileRow(status: Char, path: String, add: Int, del: Int, onClick: () -> Unit) =
        TextView(context).apply {
            val sc = when (status) { 'A' -> NC.PRIMARY; 'D' -> NC.ERROR; else -> NC.TERTIARY }
            val sb = SpannableStringBuilder("$status  $path")
            sb.setSpan(ForegroundColorSpan(sc), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (add + del > 0) {
                val s = sb.length
                sb.append("  +$add -$del")
                sb.setSpan(ForegroundColorSpan(NC.ON_SURF_VAR), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            text = sb; textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(NC.ON_SURFACE)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { onClick() }
        }

    // -- list -----------------------------------------------------------------------------------

    private inner class VH(val root: LinearLayout, val cell: GraphCellView, val subject: TextView, val meta: TextView, val date: TextView) :
        RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = rows.size
        override fun getItemId(position: Int) = position.toLong()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(-1, dp(ROW_DP))
                isClickable = true; isFocusable = true
                setPadding(dp(8), 0, dp(12), 0)
            }
            val cell = GraphCellView(context)
            root.addView(cell, LinearLayout.LayoutParams(GraphCellView.widthPx(d, cellLanes), -1))
            val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val subject = TextView(context).apply {
                textSize = 13f; setTextColor(NC.ON_SURFACE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            val meta = TextView(context).apply {
                textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(NC.ON_SURF_VAR)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            textCol.addView(subject); textCol.addView(meta)
            root.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
            val date = TextView(context).apply {
                textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(NC.ON_SURF_VAR)
            }
            root.addView(date, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            val vh = VH(root, cell, subject, meta, date)
            root.setOnClickListener {
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) showDetail(rows[pos])
            }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            val c = r.commit
            h.root.setBackgroundColor(if (position == highlight) NC.SURFACE_HIGH else Color.TRANSPARENT)
            h.cell.capLanes = laneCap
            h.cell.row = r
            h.cell.layoutParams.width.let { w ->
                val want = GraphCellView.widthPx(d, cellLanes)
                if (w != want) h.cell.layoutParams = LinearLayout.LayoutParams(want, -1)
            }
            val sb = SpannableStringBuilder()
            if (c.isHead) chipSpan(sb, "HEAD", NC.ON_PRIMARY, NC.PRIMARY)
            var shown = 0
            for (ref in c.refs) {
                if (shown == 3) { sb.append("+${c.refs.size - 3} "); break }
                val (fg, bg) = when (ref.kind) {
                    GitRefKind.LOCAL -> NC.PRIMARY to NC.ON_PRIMARY_CON
                    GitRefKind.REMOTE -> 0xFF7FB4FF.toInt() to 0xFF1F3347.toInt()
                    GitRefKind.TAG -> NC.TERTIARY to NC.ON_TERTIARY
                }
                chipSpan(sb, (if (ref.kind == GitRefKind.TAG) "tag " else "") + ref.name.take(22), fg, bg)
                shown++
            }
            sb.append(c.subject)
            h.subject.text = sb
            val rel = GitLogParse.relativeTime(System.currentTimeMillis() / 1000, c.time)
            h.meta.text = "${c.hash.take(7)}  ${c.author}"
            h.date.text = rel
            h.root.contentDescription = "${c.subject}, ${c.author}, $rel ago" +
                if (c.refs.isNotEmpty()) ", " + c.refs.joinToString { it.name } else ""
        }

        private fun chipSpan(sb: SpannableStringBuilder, label: String, fg: Int, bg: Int) {
            val s = sb.length
            sb.append(' ').append(label).append(' ')
            sb.setSpan(ForegroundColorSpan(fg), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(BackgroundColorSpan(bg), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(' ')
        }
    }

    companion object {
        private const val TAG = "GitHistory"
        const val ROW_DP = 52
        /** Scroll-to-top button shows once the first visible row is past this. */
        private const val FAB_AFTER = 15
        /** Rows parsed ahead of the last visible row before git is left blocked on the pipe. */
        const val LOOKAHEAD = 5000
        /** Rows loaded before "LOAD MORE". */
        const val PAGE_CAP = 50_000
        private val warmed = HashSet<String>()
    }
}
