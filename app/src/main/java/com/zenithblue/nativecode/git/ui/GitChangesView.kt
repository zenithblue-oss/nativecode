package com.zenithblue.nativecode.git.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.git.ChangeFile
import com.zenithblue.nativecode.git.ChangeGroup
import com.zenithblue.nativecode.git.GitGuestCommands
import com.zenithblue.nativecode.git.GitLogParse
import com.zenithblue.nativecode.git.GitRepoService
import com.zenithblue.nativecode.git.RepoStatus
import com.zenithblue.nativecode.git.RepoStatusResult
import java.util.concurrent.Executor

/**
 * CHANGES tab of the project Git page, VS Code Source Control style: branch / upstream /
 * ahead-behind header with fetch-pull-push, commit box, then Merge Conflicts, Staged, Changes
 * and Untracked sections with per-file and per-section actions.
 * A refresh is one guest start with 3 git calls ([GitGuestCommands.changesBundle]), off the main thread.
 * Lives inside the page's ScrollView, so this is a plain LinearLayout.
 */
internal class GitChangesView(
    context: Context,
    private val io: Executor,
    private val onOpenDiff: (path: String) -> Unit,
    private val onOpenFile: (path: String) -> Unit
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private var project = ""
    private var method = ""
    private var gen = 0
    private var status: RepoStatus? = null
    private var lastOid: String? = null
    private var lastCommit: String? = null
    private var busy: String? = null
    private val collapsed = HashSet<ChangeGroup>()
    private val shown = HashMap<ChangeGroup, Int>()

    // header
    private val branchTv = label(16f, NC.PRIMARY, bold = true)
    private val stateTv = label(10f, NC.ON_TERTIARY, bold = true).apply {
        setBackgroundColor(NC.TERTIARY_CON); setPadding(dp(6), dp(2), dp(6), dp(2)); visibility = View.GONE
    }
    private val upstreamTv = label(11f, NC.ON_SURF_VAR)
    private val lastTv = label(11f, NC.ON_SURFACE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val fetchBtn = chip("FETCH")
    private val pullBtn = chip("PULL")
    private val pushBtn = chip("PUSH")
    private val stashBtn = chip("STASH 0")
    private val refreshBtn = chip("REFRESH")

    // commit box
    private val msg = EditText(context).apply {
        hint = "Message (commits staged changes)"
        textSize = 13f; typeface = Typeface.MONOSPACE
        setTextColor(NC.ON_SURFACE); setHintTextColor(NC.OUTLINE)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 2; maxLines = 6; gravity = Gravity.TOP or Gravity.START
        background = GradientDrawable().apply { setColor(NC.SURFACE_LOWEST); setStroke(dp(1), NC.OUTLINE_VAR) }
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }
    private val countTv = label(10f, NC.ON_SURF_VAR)
    private val amend = CheckBox(context).apply {
        text = "AMEND"; textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(NC.ON_SURF_VAR)
        buttonTintList = android.content.res.ColorStateList.valueOf(NC.PRIMARY)
        minHeight = dp(44)
    }
    private val commitBtn = TextView(context).apply {
        text = "COMMIT"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(NC.ON_PRIMARY)
        gravity = Gravity.CENTER; setPadding(dp(18), 0, dp(18), 0); minHeight = dp(44)
        setBackgroundColor(NC.PRIMARY); isClickable = true; isFocusable = true
    }

    private val sections = LinearLayout(context).apply { orientation = VERTICAL }
    private val msgTv = label(12f, NC.ON_SURF_VAR).apply { setPadding(dp(4), dp(12), dp(4), dp(12)) }

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(4), dp(12), dp(8))

        val head = card()
        val r1 = row()
        r1.addView(branchTv, LayoutParams(0, -2, 1f))
        r1.addView(stateTv)
        head.addView(r1)
        head.addView(upstreamTv.apply { setPadding(0, dp(4), 0, 0) })
        head.addView(lastTv.apply { setPadding(0, dp(6), 0, dp(8)) })
        val acts = row()
        for (b in listOf(fetchBtn, pullBtn, pushBtn, stashBtn, refreshBtn)) acts.addView(b, LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        head.addView(acts)
        addView(head)

        val box = card()
        box.addView(msg, LayoutParams(-1, -2))
        val r2 = row().apply { setPadding(0, dp(6), 0, 0) }
        r2.addView(amend)
        r2.addView(countTv, LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        r2.addView(commitBtn)
        box.addView(r2)
        addView(box)

        addView(msgTv)
        addView(sections)

        msg.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = updateCount()
        })
        updateCount()
        commitBtn.setOnClickListener { commit() }
        fetchBtn.setOnClickListener { network("FETCH", "fetch --prune") }
        pullBtn.setOnClickListener { network("PULL", "pull --ff-only") }
        pushBtn.setOnClickListener {
            val s = status ?: return@setOnClickListener
            confirm("Push", "Push ${s.branch ?: "HEAD"} to ${s.upstream ?: "its upstream"}?", "PUSH") { network("PUSH", "push") }
        }
        refreshBtn.setOnClickListener { refresh() }
        stashBtn.setOnClickListener { showStashes() }
    }

    // -- widgets --------------------------------------------------------------------------------

    private fun label(size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = size; setTextColor(color)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
    }

    private fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** Obsidian brutalist card: flat fill, 1dp outline, hard offset shadow. */
    private fun card() = LinearLayout(context).apply {
        orientation = VERTICAL
        val off = dp(6)
        background = LayerDrawable(arrayOf<Drawable>(
            GradientDrawable().apply { setColor(NC.SHADOW_DARK) },
            GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke(dp(1), NC.OUTLINE_VAR) }
        )).apply { setLayerInset(0, off, off, 0, 0); setLayerInset(1, 0, 0, off, off) }
        setPadding(dp(12), dp(12), dp(12) + off, dp(12) + off)
        layoutParams = LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }

    private fun chip(t: String) = TextView(context).apply {
        text = t; textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(NC.PRIMARY)
        gravity = Gravity.CENTER; maxLines = 1
        setPadding(dp(6), 0, dp(6), 0); minHeight = dp(36)
        background = GradientDrawable().apply { setColor(NC.SURFACE_LOWEST); setStroke(dp(1), NC.OUTLINE_VAR) }
        isClickable = true; isFocusable = true
    }

    /** Square icon button for file rows; 40dp touch target. */
    private fun iconBtn(glyph: String, desc: String, color: Int, onClick: () -> Unit) = TextView(context).apply {
        text = glyph; textSize = 16f; setTextColor(color); gravity = Gravity.CENTER
        contentDescription = desc
        layoutParams = LayoutParams(dp(40), dp(40))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun updateCount() {
        val subj = msg.text.toString().substringBefore('\n')
        countTv.text = "${subj.length}/72"
        countTv.setTextColor(when {
            subj.length > 72 -> NC.ERROR
            subj.length > 50 -> NC.TERTIARY_CON
            else -> NC.ON_SURF_VAR
        })
        countTv.contentDescription = "Subject line ${subj.length} characters"
    }

    // -- refresh --------------------------------------------------------------------------------

    fun refresh(projectPath: String = project, projectMethod: String = method) {
        if (projectPath != project) { lastOid = null; lastCommit = null; status = null; msg.setText("") }
        project = projectPath; method = projectMethod
        val g = ++gen
        if (status == null) { msgTv.text = "Loading changes..."; msgTv.visibility = View.VISIBLE }
        refreshBtn.text = "..."
        val p = project; val m = method; val knownOid = lastOid
        io.execute {
            val res = GitRepoService.loadChanges(context, p, m)
            var last: String? = null
            if (res is RepoStatusResult.Ok && res.status.oid != null && res.status.oid != knownOid) {
                last = try { GitRepoService.run(context, p, m, GitGuestCommands.lastCommit(p)).second } catch (_: Exception) { null }
            }
            post {
                if (g != gen) return@post
                refreshBtn.text = "REFRESH"
                if (res is RepoStatusResult.Ok && res.status.oid != knownOid) { lastOid = res.status.oid; lastCommit = last }
                render(res)
            }
        }
    }

    private fun render(res: RepoStatusResult) {
        sections.removeAllViews()
        when (res) {
            is RepoStatusResult.NotGit -> { status = null; showOnly(res.message.uppercase() + "\nRun git init or clone a repo in this project"); return }
            is RepoStatusResult.Error -> { status = null; showOnly(res.message); msgTv.setTextColor(NC.ERROR); return }
            is RepoStatusResult.Ok -> Unit
        }
        val s = res.status
        status = s
        setCardsVisible(true)
        msgTv.setTextColor(NC.ON_SURF_VAR)
        branchTv.text = "⎇ " + (s.branch ?: "DETACHED @ ${s.oid?.take(7) ?: "?"}")
        stateTv.text = s.state ?: ""
        stateTv.visibility = if (s.state != null) View.VISIBLE else View.GONE
        upstreamTv.text = when {
            s.oid == null -> "no commits yet"
            s.upstream == null -> "no upstream branch"
            else -> "→ ${s.upstream}   ↑${s.ahead} ↓${s.behind}"
        }
        upstreamTv.setTextColor(if (s.ahead + s.behind > 0) NC.TERTIARY else NC.ON_SURF_VAR)
        lastTv.text = lastCommit?.split('\u0000')?.takeIf { it.size >= 3 }?.let {
            val rel = GitLogParse.relativeTime(System.currentTimeMillis() / 1000, it[1].trim().toLongOrNull() ?: 0)
            "${it[0].trim()}  ${it[2].trim()}  · $rel"
        } ?: if (s.oid == null) "" else s.oid.take(7)
        lastTv.visibility = if (lastTv.text.isEmpty()) View.GONE else View.VISIBLE
        val hasUp = s.upstream != null
        fetchBtn.isEnabled = busy == null; pullBtn.isEnabled = busy == null && hasUp; pushBtn.isEnabled = busy == null && hasUp
        for (b in listOf(fetchBtn, pullBtn, pushBtn)) b.alpha = if (b.isEnabled) 1f else 0.4f
        stashBtn.text = "STASH ${s.stash}"
        stashBtn.alpha = if (s.stash > 0) 1f else 0.4f
        stashBtn.isEnabled = s.stash > 0
        amend.isEnabled = s.oid != null

        if (s.files.isEmpty()) {
            msgTv.text = "// WORKING TREE CLEAN"; msgTv.visibility = View.VISIBLE
            return
        }
        msgTv.visibility = View.GONE
        val by = s.files.groupBy { it.group }
        for (g in ChangeGroup.values()) {
            val files = by[g] ?: continue
            addSection(g, files)
        }
    }

    private fun showOnly(text: String) {
        setCardsVisible(false)
        msgTv.text = text; msgTv.visibility = View.VISIBLE
    }

    private fun setCardsVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.GONE
        getChildAt(0).visibility = vis; getChildAt(1).visibility = vis
    }

    private fun title(g: ChangeGroup) = when (g) {
        ChangeGroup.CONFLICT -> "MERGE CONFLICTS"
        ChangeGroup.STAGED -> "STAGED CHANGES"
        ChangeGroup.UNSTAGED -> "CHANGES"
        ChangeGroup.UNTRACKED -> "UNTRACKED"
    }

    private fun addSection(g: ChangeGroup, files: List<ChangeFile>) {
        val open = g !in collapsed
        val header = row().apply {
            setPadding(dp(2), dp(10), 0, dp(4)); minimumHeight = dp(44)
            isClickable = true; isFocusable = true
            contentDescription = "${title(g)}, ${files.size} files, ${if (open) "expanded" else "collapsed"}"
            setOnClickListener { if (!collapsed.add(g)) collapsed.remove(g); status?.let { render(RepoStatusResult.Ok(it)) } }
        }
        header.addView(label(12f, if (g == ChangeGroup.CONFLICT) NC.ERROR else NC.PRIMARY, bold = true).apply {
            text = (if (open) "▾ " else "▸ ") + title(g)
        })
        header.addView(label(10f, NC.ON_SURFACE, bold = true).apply {
            text = files.size.toString()
            setBackgroundColor(NC.SURFACE_HIGH); setPadding(dp(6), dp(1), dp(6), dp(1))
        }, LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        header.addView(View(context), LayoutParams(0, 1, 1f))
        when (g) {
            ChangeGroup.STAGED -> header.addView(chip("UNSTAGE ALL").apply { setOnClickListener { git("reset -q") } })
            ChangeGroup.UNSTAGED -> header.addView(chip("STAGE ALL").apply { setOnClickListener { git("add -u") } })
            ChangeGroup.UNTRACKED -> header.addView(chip("STAGE ALL").apply {
                setOnClickListener { gitScript(GitGuestCommands.inProject(project, "ls-files -z -o --exclude-standard :/") +
                    " | xargs -0 -r " + GitGuestCommands.gitCommand(project, "--literal-pathspecs add --")) }
            })
            ChangeGroup.CONFLICT -> Unit
        }
        sections.addView(header)
        sections.addView(View(context).apply { setBackgroundColor(NC.OUTLINE_VAR) }, LayoutParams(-1, dp(1)))
        if (!open) return
        val limit = shown[g] ?: PAGE
        for (f in files.take(limit)) sections.addView(fileRow(f))
        if (files.size > limit) {
            sections.addView(chip("SHOW ${minOf(PAGE, files.size - limit)} MORE OF ${files.size - limit}").apply {
                setOnClickListener { shown[g] = limit + PAGE; status?.let { render(RepoStatusResult.Ok(it)) } }
            }, LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }

    private fun statusColor(c: Char) = when (c) {
        'M', 'T' -> 0xFFE2C08D.toInt()
        'A', 'U' -> NC.PRIMARY
        'D' -> NC.ERROR
        'R', 'C' -> 0xFF7FB4FF.toInt()
        '!' -> 0xFFFF8FB1.toInt()
        else -> NC.ON_SURF_VAR
    }

    private fun fileRow(f: ChangeFile): View {
        val r = row().apply {
            setPadding(dp(2), dp(4), 0, dp(4)); minimumHeight = dp(48)
            isClickable = true; isFocusable = true
            setOnClickListener { onOpenDiff(f.path) }
        }
        r.addView(label(13f, statusColor(f.status), bold = true).apply {
            text = f.status.toString(); gravity = Gravity.CENTER
        }, LayoutParams(dp(22), -2))
        val name = f.path.substringAfterLast('/')
        val dir = f.path.substringBeforeLast('/', "")
        val col = LinearLayout(context).apply { orientation = VERTICAL }
        col.addView(TextView(context).apply {
            text = name; textSize = 13f; setTextColor(if (f.status == 'D') NC.ON_SURF_VAR else NC.ON_SURFACE)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            if (f.status == 'D') paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        })
        val sub = if (f.origPath != null) "${f.origPath} → ${f.path}" else dir
        if (sub.isNotEmpty()) col.addView(label(10f, NC.OUTLINE).apply {
            text = sub; maxLines = 1; ellipsize = TextUtils.TruncateAt.START
        })
        r.addView(col, LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        if (f.added >= 0 || f.deleted >= 0) {
            r.addView(label(10f, NC.ON_SURF_VAR).apply {
                val sb = android.text.SpannableStringBuilder()
                fun part(t: String, c: Int) {
                    val s = sb.length; sb.append(t)
                    sb.setSpan(android.text.style.ForegroundColorSpan(c), s, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (f.added < 0) part("bin", NC.OUTLINE)
                else { part("+${f.added}", NC.PRIMARY); sb.append(' '); part("−${f.deleted}", NC.ERROR) }
                text = sb
            }, LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        }
        val spec = GitGuestCommands.topPathspec(f.path)
        if (f.status != 'D') r.addView(iconBtn("↗", "Open ${f.path}", NC.ON_SURF_VAR) { onOpenFile(f.path) })
        when (f.group) {
            ChangeGroup.STAGED -> {
                val specs = spec + (f.origPath?.let { " " + GitGuestCommands.topPathspec(it) } ?: "")
                r.addView(iconBtn("−", "Unstage ${f.path}", NC.ON_SURFACE) { git("reset -q -- $specs") })
            }
            ChangeGroup.UNSTAGED -> {
                r.addView(iconBtn("↶", "Discard changes in ${f.path}", NC.ERROR) {
                    confirm("Discard changes", "Discard changes in ${f.path}? This cannot be undone.", "DISCARD") {
                        git("checkout -q -- $spec")
                    }
                })
                r.addView(iconBtn("+", "Stage ${f.path}", NC.PRIMARY) { git("add -A -- $spec") })
            }
            ChangeGroup.UNTRACKED -> {
                r.addView(iconBtn("↶", "Delete untracked ${f.path}", NC.ERROR) {
                    confirm("Delete file", "Delete untracked ${f.path}? This cannot be undone.", "DELETE") {
                        git("clean -f -q -- $spec")
                    }
                })
                r.addView(iconBtn("+", "Stage ${f.path}", NC.PRIMARY) { git("add -- $spec") })
            }
            ChangeGroup.CONFLICT -> r.addView(iconBtn("+", "Mark ${f.path} resolved", NC.PRIMARY) { git("add -- $spec") })
        }
        r.contentDescription = "${title(f.group)}: ${f.path}, status ${f.status}" +
            (if (f.added >= 0) ", ${f.added} added, ${f.deleted} removed" else "")
        return r
    }

    // -- actions --------------------------------------------------------------------------------

    private fun confirm(title: String, message: String, ok: String, onOk: () -> Unit) {
        AlertDialog.Builder(context).setTitle(title).setMessage(message)
            .setPositiveButton(ok) { _, _ -> onOk() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun git(args: String, done: ((Boolean) -> Unit)? = null) =
        gitScript(GitGuestCommands.inProject(project, args), done)

    /** Runs [script] in the guest, toasts git's message on failure, then refreshes. */
    private fun gitScript(script: String, done: ((Boolean) -> Unit)? = null) {
        val p = project; val m = method
        if (p.isBlank()) return
        io.execute {
            val (exit, out) = try { GitRepoService.run(context, p, m, script) } catch (e: Exception) { -1 to (e.message ?: "error") }
            post {
                if (exit != 0) {
                    AlertDialog.Builder(context).setTitle("git failed (exit $exit)")
                        .setMessage(out.trim().takeLast(1500).ifEmpty { "no output" })
                        .setPositiveButton("OK", null).show()
                }
                done?.invoke(exit == 0)
                if (p == project) refresh()
            }
        }
    }

    private fun network(label: String, args: String) {
        if (busy != null) return
        busy = label
        val btn = when (label) { "FETCH" -> fetchBtn; "PULL" -> pullBtn; else -> pushBtn }
        btn.text = "$label..."
        for (b in listOf(fetchBtn, pullBtn, pushBtn)) b.isEnabled = false
        git(args) { ok ->
            busy = null
            btn.text = label
            if (ok) Toast.makeText(context, "$label done", Toast.LENGTH_SHORT).show()
        }
    }

    private fun commit() {
        val s = status ?: return
        val text = msg.text.toString().trim()
        val isAmend = amend.isChecked
        if (text.isEmpty() && !isAmend) { Toast.makeText(context, "Commit message is empty", Toast.LENGTH_SHORT).show(); return }
        // Message via stdin (one printf arg per line): the one-line guest script must not carry raw newlines.
        val body = if (text.isEmpty()) "--no-edit" else "-F -"
        val pipe = if (text.isEmpty()) "" else "printf '%s\\n' " + text.lines().joinToString(" ") { GitGuestCommands.shellQuote(it) } + " | "
        val staged = s.files.any { it.group == ChangeGroup.STAGED }
        fun run(all: Boolean) {
            val args = "commit " + (if (isAmend) "--amend " else "") + (if (all) "-a " else "") + body
            val script = "cd ${GitGuestCommands.shellQuote(project)} && $pipe${GitGuestCommands.gitCommand(project, args)}"
            gitScript(script) { ok ->
                if (ok) { msg.setText(""); amend.isChecked = false; Toast.makeText(context, if (isAmend) "Amended" else "Committed", Toast.LENGTH_SHORT).show() }
            }
        }
        val tracked = s.files.any { it.group == ChangeGroup.UNSTAGED }
        when {
            staged || isAmend -> run(false)
            tracked -> confirm("Nothing staged", "Stage all tracked changes and commit?", "COMMIT ALL") { run(true) }
            else -> Toast.makeText(context, "Nothing to commit", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showStashes() {
        val p = project; val m = method
        io.execute {
            val out = try { GitRepoService.run(context, p, m, GitGuestCommands.stashList(p)).second } catch (e: Exception) { e.message ?: "" }
            post {
                val sheet = BottomSheetDialog(context)
                val col = LinearLayout(context).apply {
                    orientation = VERTICAL; setBackgroundColor(NC.BG); setPadding(dp(20), dp(20), dp(20), dp(24))
                }
                col.addView(label(16f, NC.PRIMARY, bold = true).apply { text = "STASHES"; setPadding(0, 0, 0, dp(8)) })
                for (l in out.lines().filter { it.isNotBlank() }) col.addView(label(12f, NC.ON_SURFACE).apply {
                    text = l; setPadding(0, dp(8), 0, dp(8)); setTextIsSelectable(true)
                })
                sheet.setContentView(ScrollView(context).apply { addView(col) })
                sheet.show()
            }
        }
    }

    companion object {
        /** File rows rendered per section before "SHOW MORE". */
        private const val PAGE = 200
    }
}
