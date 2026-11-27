package com.zenithblue.nativecode.backup.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.zenithblue.nativecode.BuildConfig
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.backup.AppDataBackup
import com.zenithblue.nativecode.backup.BackupJob
import com.zenithblue.nativecode.backup.BackupPlan
import com.zenithblue.nativecode.backup.BackupScope
import com.zenithblue.nativecode.backup.BackupService
import com.zenithblue.nativecode.backup.EntryMode
import com.zenithblue.nativecode.backup.EntryPlan
import com.zenithblue.nativecode.backup.Manifest
import com.zenithblue.nativecode.backup.RestoreSwap
import com.zenithblue.nativecode.editor.showThemed
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import com.zenithblue.nativecode.workspace.DirModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/** What the page needs from the activity (document pickers, open AI sessions, keep-awake). */
internal interface BackupHost {
    fun createDocument(name: String, onResult: (Uri?) -> Unit)
    fun openDocument(onResult: (Uri?) -> Unit)
    fun aiSessionsOpen(): Boolean
    fun keepAwake(on: Boolean)
}

/**
 * Settings -> Backup and restore (plan 15). Backup streams guest `tar --zstd` into a SAF document with sha256;
 * restore verifies, extracts into a staging folder, then swaps entries in with rollback.
 */
internal class BackupPage(private val ctx: Context, private val host: BackupHost) {
    val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val estimateBg = Executors.newSingleThreadExecutor()

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private var scope = BackupScope.HOME
    private val selected = BackupPlan.defaultExcludeIds().toMutableSet()
    private var includeCreds = BackupPlan.INCLUDE_CREDENTIALS_DEFAULT
    private var estimate: Long? = null
    private var estimateJob: BackupJob? = null
    private var estimateRun = 0

    private var job: BackupJob? = null
    private var busy = false

    private val scopeRow = LinearLayout(ctx)
    private val exclBox = LinearLayout(ctx)
    private val estimateTv = text(12f, NC.ON_SURF_VAR, mono = true)
    private val backupBtn = button(tr(R.string.backup_back_up_now), true)
    private val restoreBtn = button(tr(R.string.backup_restore_from_backup), false)
    private val progressCard = LinearLayout(ctx)
    private val progressTitle = text(14f, NC.PRIMARY, bold = true, mono = true)
    private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
    private val progressTv = text(12f, NC.ON_SURF_VAR, mono = true)
    private val cancelBtn = button(tr(R.string.x11_cancel), false)
    private val resultCard = LinearLayout(ctx)
    private val resultTitle = text(14f, NC.PRIMARY, bold = true, mono = true)
    private val resultTv = text(12f, NC.ON_SURFACE, mono = true)

    private fun text(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(ctx).apply {
        textSize = size
        setTextColor(color)
        if (bold) paint.isFakeBoldText = true
        if (mono) typeface = Typeface.MONOSPACE
    }

    private fun button(label: String, primary: Boolean) = TextView(ctx).apply {
        text = label
        textSize = 13f
        typeface = Typeface.MONOSPACE
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.parseColor("#0A0A0A") else NC.PRIMARY)
        background = GradientDrawable().apply {
            setColor(if (primary) NC.PRIMARY_CON else Color.TRANSPARENT)
            setStroke(dp(1), if (primary) NC.PRIMARY_CON else NC.OUTLINE_VAR)
        }
        minHeight = dp(48)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke(dp(1), NC.OUTLINE_VAR) }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
    }

    init {
        // Back up
        val backup = card()
        backup.addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = tr(R.string.backup_back_up) })
        backup.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = tr(R.string.backup_saves_your_guest_home_to)
            setPadding(0, dp(4), 0, dp(12))
        })
        scopeRow.orientation = LinearLayout.HORIZONTAL
        exclBox.orientation = LinearLayout.VERTICAL
        backup.addView(scopeRow)
        backup.addView(text(12f, NC.ON_SURF_VAR, mono = true).apply { text = tr(R.string.backup_leave_out); setPadding(0, dp(14), 0, dp(2)) })
        backup.addView(exclBox)
        backup.addView(text(11f, NC.ON_SURF_VAR).apply { text = tr(R.string.backup_one_file_note); setPadding(0, dp(8), 0, 0) })
        estimateTv.setPadding(0, dp(10), 0, dp(12))
        backup.addView(estimateTv)
        backup.addView(backupBtn, LinearLayout.LayoutParams(-1, -2))
        content.addView(backup)

        // Progress
        progressCard.orientation = LinearLayout.VERTICAL
        progressCard.background = GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke(dp(1), NC.PRIMARY) }
        progressCard.setPadding(dp(16), dp(16), dp(16), dp(16))
        progressCard.visibility = View.GONE
        progressCard.addView(progressTitle)
        bar.max = 1000
        bar.progressTintList = ColorStateList.valueOf(NC.PRIMARY)
        progressCard.addView(bar, LinearLayout.LayoutParams(-1, dp(12)).apply { topMargin = dp(10); bottomMargin = dp(6) })
        progressCard.addView(progressTv)
        progressCard.addView(cancelBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        content.addView(progressCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        cancelBtn.setOnClickListener { job?.cancel(); progressTitle.text = tr(R.string.backup_cancelling) }

        // Result
        resultCard.orientation = LinearLayout.VERTICAL
        resultCard.background = GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke(dp(1), NC.OUTLINE_VAR) }
        resultCard.setPadding(dp(16), dp(16), dp(16), dp(16))
        resultCard.visibility = View.GONE
        resultTv.setTextIsSelectable(true)
        resultCard.addView(resultTitle)
        resultCard.addView(resultTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        content.addView(resultCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        // Restore
        val restore = card()
        restore.addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = tr(R.string.backup_restore) })
        restore.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = tr(R.string.backup_pick_a_backup_file_it)
            setPadding(0, dp(4), 0, dp(12))
        })
        restore.addView(restoreBtn, LinearLayout.LayoutParams(-1, -2))
        content.addView(restore)

        backupBtn.setOnClickListener { startBackup() }
        restoreBtn.setOnClickListener { startRestore() }
        renderOptions()
    }

    /** Page shown: re-run the size estimate. */
    fun refresh() {
        if (!busy) runEstimate()
    }

    // region options

    private fun renderOptions() {
        scopeRow.removeAllViews()
        for (s in BackupScope.values()) {
            val on = s == scope
            scopeRow.addView(TextView(ctx).apply {
                text = s.label.uppercase(Locale.ROOT)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = on
                gravity = Gravity.CENTER
                setTextColor(if (on) Color.parseColor("#0A0A0A") else NC.ON_SURF_VAR)
                background = GradientDrawable().apply { setColor(if (on) NC.PRIMARY_CON else Color.TRANSPARENT); setStroke(dp(1), if (on) NC.PRIMARY_CON else NC.OUTLINE_VAR) }
                minHeight = dp(44)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                contentDescription = "${s.label}: ${s.blurb}"
                setOnClickListener { scope = s; renderOptions(); runEstimate() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (s.ordinal > 0) leftMargin = dp(8) })
        }
        exclBox.removeAllViews()
        for (e in BackupPlan.EXCLUSIONS) {
            val credBox = e.id == "creds"
            exclBox.addView(CheckBox(ctx).apply {
                text = if (credBox) tr(R.string.backup_include_creds) else e.label
                setTextColor(NC.ON_SURFACE)
                textSize = 13f
                buttonTintList = ColorStateList.valueOf(NC.PRIMARY)
                minHeight = dp(44)
                isChecked = if (credBox) includeCreds else e.id in selected
                setOnCheckedChangeListener { _, on ->
                    if (credBox) includeCreds = on else if (on) selected.add(e.id) else selected.remove(e.id)
                    runEstimate()
                }
            })
            if (credBox) exclBox.addView(text(11f, NC.TERTIARY_CON).apply { text = tr(R.string.backup_creds_warn); setPadding(dp(32), 0, 0, dp(4)) })
        }
        updateEstimateText()
    }

    private fun patterns() = BackupPlan.excludePatterns(selected, includeCreds)

    private fun runEstimate() {
        estimateJob?.cancel()
        val my = ++estimateRun
        val j = BackupJob().also { estimateJob = it }
        estimate = null
        updateEstimateText(working = true)
        val sc = scope
        val pat = patterns()
        estimateBg.execute {
            val r = BackupService.estimate(ctx, sc, pat, j)
            ui.post { if (my == estimateRun) { estimate = r; updateEstimateText() } }
        }
    }

    private fun updateEstimateText(working: Boolean = false) {
        estimateTv.text = when {
            working -> tr(R.string.backup_estimating_size)
            estimate == null -> "Size estimate unavailable."
            else -> tr(R.string.backup_about_before_compression, BackupPlan.formatBytes(estimate!!))
        }
    }

    // endregion

    // region shared progress UI

    private fun setBusy(on: Boolean, title: String = "") {
        busy = on
        backupBtn.isEnabled = !on; restoreBtn.isEnabled = !on
        backupBtn.alpha = if (on) .4f else 1f; restoreBtn.alpha = if (on) .4f else 1f
        host.keepAwake(on)
        progressCard.visibility = if (on) View.VISIBLE else View.GONE
        if (on) { progressTitle.text = title; progressTv.text = ""; bar.progress = 0; bar.isIndeterminate = true; resultCard.visibility = View.GONE; reveal(progressCard) }
    }

    /** Scrolls the page so [v] is visible (the cards sit below the fold on a phone). */
    private fun reveal(v: View) = v.post { v.requestRectangleOnScreen(android.graphics.Rect(0, 0, v.width, v.height), false) }

    private fun progress(done: Long, total: Long?) {
        if (total != null && total > 0) {
            bar.isIndeterminate = false
            bar.progress = (done * 1000 / total).coerceIn(0, 1000).toInt()
            progressTv.text = "${BackupPlan.formatBytes(done)} of ${BackupPlan.formatBytes(total)}"
        } else {
            bar.isIndeterminate = true
            progressTv.text = BackupPlan.formatBytes(done)
        }
    }

    private fun showResult(ok: Boolean, title: String, body: String) {
        setBusy(false)
        resultCard.visibility = View.VISIBLE
        resultTitle.text = title
        resultTitle.setTextColor(if (ok) NC.PRIMARY else NC.ERROR)
        resultTv.text = body
        reveal(resultCard)
    }

    private fun displayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null }

    private fun docSize(uri: Uri): Long? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    } catch (_: Exception) { null }

    // endregion

    // region backup

    private fun startBackup() {
        if (busy) return
        host.createDocument(BackupPlan.defaultName()) { uri -> if (uri != null) runBackup(uri) }
    }

    private fun runBackup(uri: Uri) {
        val j = BackupJob().also { job = it }
        val sc = scope
        val pat = patterns()
        val est = estimate
        val name = displayName(uri) ?: BackupPlan.defaultName()
        setBusy(true, tr(R.string.backup_backing_up))
        bg.execute {
            val res = try {
                ctx.contentResolver.openOutputStream(uri, "w")?.use { out ->
                    val r = BackupService.backup(ctx, out, sc, pat, j) { p -> ui.post { progress(p, est?.let { (it / 2).coerceAtLeast(p) /* compressed size is about half the raw estimate here; the bar is a guide */ }) } }
                    if (r.ok) {
                        // one file: the manifest (checksum, CLI versions) rides inside the archive as its trailer
                        ui.post { progressTitle.text = tr(R.string.backup_recording_versions); bar.isIndeterminate = true }
                        val comps = mapOf("appVersionCode" to BuildConfig.VERSION_CODE.toString(), "guest" to ProjectPathResolver.DEFAULT_DISTRO, "method" to "proot", "android" to android.os.Build.VERSION.SDK_INT.toString()) +
                            BackupService.cliVersions(ctx) + (if (r.app) mapOf(BackupPlan.COMPONENT_CHAT to "1") else emptyMap())
                        BackupService.appendManifest(out, Manifest(BackupPlan.FORMAT, BuildConfig.VERSION_NAME, BackupPlan.isoNow(), r.sha256, r.bytes, r.files, sc.name, pat, comps))
                    }
                    r
                } ?: com.zenithblue.nativecode.backup.BackupResult(false, "", 0, 0, tr(R.string.backup_could_not_open_the_destination), false)
            } catch (e: Exception) {
                com.zenithblue.nativecode.backup.BackupResult(false, "", 0, 0, e.message ?: e.javaClass.simpleName, j.cancelled)
            }
            if (!res.ok) runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
            ui.post {
                if (!res.ok) {
                    showResult(false, if (res.cancelled) tr(R.string.backup_backup_cancelled) else tr(R.string.backup_backup_failed), (res.message ?: "") + "\nThe partial file was removed.")
                    return@post
                }
                showResult(true, tr(R.string.backup_backup_saved), "$name\n${BackupPlan.formatBytes(res.bytes)}, ${res.files} files\nsha256 ${res.sha256}" + (res.message?.let { "\n$it" } ?: "") +
                    (if (res.app) "\n" + tr(R.string.backup_chat_included) else "") + "\n\n" + tr(R.string.backup_one_file_saved))
            }
        }
    }

    // endregion

    // region restore

    private fun startRestore() {
        if (busy) return
        if (host.aiSessionsOpen()) {
            showResult(false, tr(R.string.backup_close_ai_sessions_first), tr(R.string.backup_a_restore_changes_files_that))
            return
        }
        host.openDocument { uri -> if (uri != null) runVerify(uri) }
    }

    /** Old two-file archives (no embedded manifest): optionally check them against the `.json` saved beside them. */
    private fun askChecksum(actual: String, then: (checked: Boolean, manifest: Manifest?) -> Unit) {
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tr(R.string.backup_verify_checksum))
            .setMessage(tr(R.string.backup_pick_the_json_checksum_file))
            .setPositiveButton(tr(R.string.backup_pick_file)) { _, _ ->
                host.openDocument { s ->
                    if (s == null) return@openDocument
                    val m = try { ctx.contentResolver.openInputStream(s)?.use { BackupPlan.parseManifest(it.readBytes().toString(Charsets.UTF_8)) } } catch (_: Exception) { null }
                    when {
                        m == null -> showResult(false, "Not a checksum file", "That file is not a NativeCode backup checksum (.json).")
                        m.sha256 != actual -> showResult(false, "Checksum mismatch", "The archive does not match its checksum file, so it is corrupt or was modified.\nexpected ${m.sha256}\ngot      $actual\nNothing was changed.")
                        else -> then(true, m)
                    }
                }
            }
            .setNeutralButton(tr(R.string.backup_continue_without)) { _, _ -> then(false, null) }
            .setNegativeButton(tr(R.string.x11_cancel), null)
            .create().showThemed(ctx)
    }

    private fun runVerify(archive: Uri) {
        val size = docSize(archive)
        val free = try { StatFs(ctx.filesDir.absolutePath).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
        if (size != null && free < size * 3 + 100L * 1024 * 1024) {
            showResult(false, tr(R.string.backup_not_enough_free_space), tr(R.string.backup_need_about_free_have, BackupPlan.formatBytes(size * 3 + 100L * 1024 * 1024), BackupPlan.formatBytes(free)))
            return
        }
        val j = BackupJob().also { job = it }
        setBusy(true, tr(R.string.backup_verifying_archive))
        bg.execute {
            val res = try {
                ctx.contentResolver.openInputStream(archive)?.use { ins -> BackupService.verify(ctx, ins, j) { p -> ui.post { progress(p, size) } } }
            } catch (e: Exception) { null }
            ui.post {
                val m = res?.manifest
                when {
                    res == null -> showResult(false, "Could not read the backup", "The file could not be opened.")
                    res.cancelled -> showResult(false, "Restore cancelled", tr(R.string.backup_nothing_was_changed))
                    !res.ok -> showResult(false, tr(R.string.backup_backup_rejected), (res.message ?: "") + "\nNothing was changed.")
                    m != null && m.sha256 != res.sha256 -> showResult(false, "Checksum mismatch", "The archive does not match its embedded checksum, so it is corrupt or was modified.\nexpected ${m.sha256}\ngot      ${res.sha256}\nNothing was changed.")
                    m != null -> { setBusy(false); chooseEntries(archive, size, res.topLevel, res.sha256, true, res.members, m) }
                    else -> { setBusy(false); askChecksum(res.sha256) { checked, sidecar -> chooseEntries(archive, size, res.topLevel, res.sha256, checked, res.members, sidecar) } }
                }
            }
        }
    }

    private fun chooseEntries(archive: Uri, size: Long?, all: List<String>, sha: String, checked: Boolean, members: Long, manifest: Manifest?) {
        val top = all.filter { it != BackupPlan.APP_DIR }
        val hasChat = BackupPlan.APP_DIR in all
        var chatOn = true
        val home = ProjectPathResolver.guestHomeDir(ctx, "proot")
        val modes = HashMap<String, EntryMode>().apply { top.forEach { put(it, EntryMode.MERGE) } }
        val on = HashSet<String>(top)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        list.addView(text(12f, NC.ON_SURF_VAR, mono = true).apply {
            text = (if (checked) "Checksum verified. " else "No checksum file: sha256 ${sha.take(16)}... ") + "$members entries."
            setPadding(0, 0, 0, dp(8))
        })
        if (hasChat) list.addView(CheckBox(ctx).apply {
            text = tr(R.string.backup_chat_entry)
            setTextColor(NC.ON_SURFACE); textSize = 13f
            buttonTintList = ColorStateList.valueOf(NC.PRIMARY)
            isChecked = true; minHeight = dp(44)
            setOnCheckedChangeListener { _, c -> chatOn = c }
        })
        for (n in top) {
            val exists = DirModel.validName(n) && java.nio.file.Files.exists(File(home, n).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val cb = CheckBox(ctx).apply {
                text = n + if (exists) "  (exists)" else ""
                setTextColor(NC.ON_SURFACE); textSize = 13f
                buttonTintList = ColorStateList.valueOf(NC.PRIMARY)
                isChecked = true; minHeight = dp(44)
                setOnCheckedChangeListener { _, c -> if (c) on.add(n) else on.remove(n) }
            }
            val chip = TextView(ctx).apply {
                text = tr(R.string.backup_merge); textSize = 11f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
                setTextColor(NC.PRIMARY); minHeight = dp(40); minWidth = dp(76)
                background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(1), NC.OUTLINE_VAR) }
                contentDescription = "Merge or replace $n"
                setOnClickListener {
                    val next = if (modes[n] == EntryMode.MERGE) EntryMode.REPLACE else EntryMode.MERGE
                    modes[n] = next
                    text = next.name
                    setTextColor(if (next == EntryMode.REPLACE) NC.TERTIARY_CON else NC.PRIMARY)
                }
            }
            row.addView(cb, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(chip)
            list.addView(row)
        }
        val scroll = android.widget.ScrollView(ctx).apply { addView(list) }
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tr(R.string.backup_restore_entries))
            .setView(scroll)
            .setPositiveButton(tr(R.string.backup_restore)) { _, _ ->
                val plans = top.filter { it in on }.map { EntryPlan(it, modes[it]!!) }
                val chat = hasChat && chatOn
                if (plans.isEmpty() && !chat) showResult(false, tr(R.string.backup_nothing_selected), tr(R.string.backup_nothing_was_changed)) else runRestore(archive, size, plans, chat, manifest)
            }
            .setNegativeButton(tr(R.string.x11_cancel), null)
            .create().showThemed(ctx)
    }

    private fun runRestore(archive: Uri, size: Long?, plans: List<EntryPlan>, chat: Boolean, manifest: Manifest?) {
        if (host.aiSessionsOpen()) {
            showResult(false, tr(R.string.backup_close_ai_sessions_first), tr(R.string.backup_nothing_was_changed))
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val home = ProjectPathResolver.guestHomeDir(ctx, "proot")
        val staging = File(home, ".restore-$stamp")
        val j = BackupJob().also { job = it }
        setBusy(true, tr(R.string.backup_restoring))
        bg.execute {
            val ex = try {
                ctx.contentResolver.openInputStream(archive)?.use { ins ->
                    BackupService.extract(ctx, ins, "${BackupPlan.GUEST_HOME}/.restore-$stamp", j) { p -> ui.post { progress(p, size) } }
                }
            } catch (e: Exception) { null }
            if (ex == null || !ex.ok) {
                runCatching { DirModel.deleteTree(home, staging) }
                ui.post {
                    showResult(false, if (ex?.cancelled == true) "Restore cancelled" else "Restore failed", (ex?.message ?: "Could not read the backup.") + "\nNothing was changed.")
                }
                return@execute
            }
            if (j.cancelled) {
                runCatching { DirModel.deleteTree(home, staging) }
                ui.post { showResult(false, "Restore cancelled", tr(R.string.backup_nothing_was_changed)) }
                return@execute
            }
            var threads = 0
            val appStaged = File(staging, BackupPlan.APP_DIR)
            if (chat && appStaged.isDirectory) {
                // chat history first (one transaction, never replaces a local thread); a failure leaves the home untouched
                val latch = java.util.concurrent.CountDownLatch(1)
                var failure: Throwable? = null
                AppDataBackup.restore(ctx, appStaged) { r -> r.onSuccess { threads = it }.onFailure { failure = it }; latch.countDown() }
                latch.await(10, java.util.concurrent.TimeUnit.MINUTES)
                failure?.let { f ->
                    runCatching { DirModel.deleteTree(home, staging) }
                    ui.post { showResult(false, tr(R.string.backup_restore_failed_chat, f.message ?: f.javaClass.simpleName), tr(R.string.backup_nothing_was_changed)) }
                    return@execute
                }
            }
            val prefsJson = AppDataBackup.readPrefs(appStaged) // read now: the swap removes the staging dir
            val sw = RestoreSwap.apply(home, staging, plans, stamp)
            if (sw.ok) AppDataBackup.applyPrefs(ctx, prefsJson) // projects merged, settings overwritten; only after a good swap
            else runCatching { DirModel.deleteTree(home, staging) }
            ui.post {
                if (sw.ok) {
                    showResult(true, tr(R.string.backup_restore_complete), "${sw.moved} items restored, ${sw.kept} existing files kept." +
                        (if (chat) "\n" + tr(R.string.backup_chat_threads_restored, threads) else "") +
                        (sw.replacedDir?.let { "\nReplaced items are in ${BackupPlan.GUEST_HOME}/${it.name}. Delete that folder when you are happy with the result." } ?: ""))
                    offerCliUpdate(manifest)
                } else showResult(false, tr(R.string.backup_restore_rolled_back), "${sw.error}\nYour files are as they were.")
            }
        }
    }

    /**
     * Restored files bring old CLI versions back only if the CLI binaries were in the archive (they are left out by default):
     * compare what is installed now with the versions recorded in the backup and offer the existing CLI updater.
     */
    private fun offerCliUpdate(m: Manifest?) {
        val manifest = m ?: return
        val ids = manifest.components.keys.filter { it.startsWith("cli.") }.map { it.removePrefix("cli.") }
        if (ids.isEmpty() || ctx !is android.app.Activity) return
        bg.execute {
            val installed = ids.associateWith { id -> com.zenithblue.nativecode.cliauth.CliVersionCheck.check(ctx, id, force = true)?.takeIf { it.canUpdate }?.installed }
            val deltas = BackupPlan.outdatedClis(manifest, installed)
            if (deltas.isNotEmpty()) ui.post { askUpdateClis(deltas) }
        }
    }

    private fun askUpdateClis(deltas: List<BackupPlan.CliDelta>) {
        fun name(id: String) = com.zenithblue.nativecode.cliauth.CliVersionCheck.def(id)?.displayName ?: id
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tr(R.string.backup_update_clis_title))
            .setMessage(tr(R.string.backup_update_clis_msg) + "\n\n" + deltas.joinToString("\n") { "${name(it.toolId)}  ${it.installed} -> ${it.backup}" })
            .setPositiveButton(tr(R.string.backup_update_all)) { _, _ -> updateClis(deltas.map { it.toolId }) }
            .setNegativeButton(tr(R.string.backup_later), null)
            .create().showThemed(ctx)
    }

    /** One CLI after the other through [com.zenithblue.nativecode.cliauth.CliVersionCheck.update] (the same runner as Settings > CLI tools > Update all). */
    private fun updateClis(ids: List<String>) {
        val activity = ctx as? android.app.Activity ?: return
        val lines = ArrayList<String>()
        setBusy(true, tr(R.string.backup_updating_clis))
        fun next(i: Int) {
            if (i >= ids.size) { showResult(true, tr(R.string.backup_clis_updated), lines.joinToString("\n")); return }
            com.zenithblue.nativecode.cliauth.CliVersionCheck.update(activity, ids[i]) { st ->
                when (st) {
                    is com.zenithblue.nativecode.cliauth.CliVersionCheck.State.Running -> progressTv.text = st.text
                    is com.zenithblue.nativecode.cliauth.CliVersionCheck.State.Done -> {
                        lines += (com.zenithblue.nativecode.cliauth.CliVersionCheck.def(ids[i])?.displayName ?: ids[i]) + ": " + st.message
                        next(i + 1)
                    }
                }
            }
        }
        next(0)
    }

    // endregion
}
