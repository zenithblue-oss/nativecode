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
    private var includeCreds = false
    private var estimate: Long? = null
    private var estimateJob: BackupJob? = null
    private var estimateRun = 0

    private var job: BackupJob? = null
    private var busy = false
    private var lastManifest: Manifest? = null
    private var lastArchiveName = ""

    private val scopeRow = LinearLayout(ctx)
    private val exclBox = LinearLayout(ctx)
    private val estimateTv = text(12f, NC.ON_SURF_VAR, mono = true)
    private val backupBtn = button("BACK UP NOW", true)
    private val restoreBtn = button("RESTORE FROM BACKUP", false)
    private val progressCard = LinearLayout(ctx)
    private val progressTitle = text(14f, NC.PRIMARY, bold = true, mono = true)
    private val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
    private val progressTv = text(12f, NC.ON_SURF_VAR, mono = true)
    private val cancelBtn = button("CANCEL", false)
    private val resultCard = LinearLayout(ctx)
    private val resultTitle = text(14f, NC.PRIMARY, bold = true, mono = true)
    private val resultTv = text(12f, NC.ON_SURFACE, mono = true)
    private val sidecarBtn = button("SAVE CHECKSUM FILE", false)

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
        backup.addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = "BACK UP" })
        backup.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = "Saves your guest home to a file you choose (local storage, Drive or any document provider). It is a zstd-compressed tar with a sha256 checksum."
            setPadding(0, dp(4), 0, dp(12))
        })
        scopeRow.orientation = LinearLayout.HORIZONTAL
        exclBox.orientation = LinearLayout.VERTICAL
        backup.addView(scopeRow)
        backup.addView(text(12f, NC.ON_SURF_VAR, mono = true).apply { text = "LEAVE OUT"; setPadding(0, dp(14), 0, dp(2)) })
        backup.addView(exclBox)
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
        cancelBtn.setOnClickListener { job?.cancel(); progressTitle.text = "Cancelling..." }

        // Result
        resultCard.orientation = LinearLayout.VERTICAL
        resultCard.background = GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke(dp(1), NC.OUTLINE_VAR) }
        resultCard.setPadding(dp(16), dp(16), dp(16), dp(16))
        resultCard.visibility = View.GONE
        resultTv.setTextIsSelectable(true)
        resultCard.addView(resultTitle)
        resultCard.addView(resultTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        resultCard.addView(sidecarBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        sidecarBtn.visibility = View.GONE
        content.addView(resultCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        // Restore
        val restore = card()
        restore.addView(text(15f, NC.PRIMARY, bold = true, mono = true).apply { text = "RESTORE" })
        restore.addView(text(12f, NC.ON_SURF_VAR).apply {
            text = "Pick a backup file. It is checked against its checksum, unpacked into a staging folder, then moved into your home. Choose per top-level entry: Merge keeps files you already have; Replace moves them aside to .replaced-<time>. If anything fails the move is undone."
            setPadding(0, dp(4), 0, dp(12))
        })
        restore.addView(restoreBtn, LinearLayout.LayoutParams(-1, -2))
        content.addView(restore)

        backupBtn.setOnClickListener { startBackup() }
        restoreBtn.setOnClickListener { startRestore() }
        sidecarBtn.setOnClickListener { saveSidecar() }
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
                text = if (credBox) "Include " + e.label.lowercase(Locale.ROOT) else e.label
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
            if (credBox) exclBox.addView(text(11f, NC.TERTIARY_CON).apply { text = e.warn ?: ""; setPadding(dp(32), 0, 0, dp(4)) })
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
            working -> "Estimating size..."
            estimate == null -> "Size estimate unavailable."
            else -> "About ${BackupPlan.formatBytes(estimate!!)} before compression."
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
        sidecarBtn.visibility = View.GONE
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
        setBusy(true, "Backing up...")
        bg.execute {
            val res = try {
                ctx.contentResolver.openOutputStream(uri, "w")?.use { out ->
                    BackupService.backup(ctx, out, sc, pat, j) { p -> ui.post { progress(p, est?.let { (it / 2).coerceAtLeast(p) /* compressed size is about half the raw estimate here; the bar is a guide */ }) } }
                } ?: com.zenithblue.nativecode.backup.BackupResult(false, "", 0, 0, "Could not open the destination file.", false)
            } catch (e: Exception) {
                com.zenithblue.nativecode.backup.BackupResult(false, "", 0, 0, e.message ?: e.javaClass.simpleName, j.cancelled)
            }
            if (!res.ok) runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }
            ui.post {
                if (!res.ok) {
                    showResult(false, if (res.cancelled) "Backup cancelled" else "Backup failed", (res.message ?: "") + "\nThe partial file was removed.")
                    return@post
                }
                val m = Manifest(
                    BackupPlan.FORMAT, BuildConfig.VERSION_NAME, BackupPlan.isoNow(), res.sha256, res.bytes, res.files, sc.name, pat,
                    mapOf("appVersionCode" to BuildConfig.VERSION_CODE.toString(), "guest" to ProjectPathResolver.DEFAULT_DISTRO, "method" to "proot", "android" to android.os.Build.VERSION.SDK_INT.toString()),
                )
                lastManifest = m; lastArchiveName = name
                showResult(true, "Backup saved", "$name\n${BackupPlan.formatBytes(res.bytes)}, ${res.files} files\nsha256 ${res.sha256}" + (res.message?.let { "\n$it" } ?: "") + "\n\nSave the checksum file next to the backup so a restore can verify it.")
                sidecarBtn.visibility = View.VISIBLE
                saveSidecar()
            }
        }
    }

    private fun saveSidecar() {
        val m = lastManifest ?: return
        host.createDocument(BackupPlan.sidecarName(lastArchiveName)) { uri ->
            if (uri == null) return@createDocument
            bg.execute {
                val err = try {
                    ctx.contentResolver.openOutputStream(uri, "w")?.use { it.write(BackupPlan.manifestJson(m).toByteArray()) } ?: error("could not open file")
                    null
                } catch (e: Exception) { e.message ?: "failed" }
                ui.post {
                    if (err == null) { sidecarBtn.visibility = View.GONE; resultTv.append("\nChecksum file saved.") }
                    else resultTv.append("\nChecksum file failed: $err")
                }
            }
        }
    }

    // endregion

    // region restore

    private fun startRestore() {
        if (busy) return
        if (host.aiSessionsOpen()) {
            showResult(false, "Close AI sessions first", "A restore changes files that open AI sessions are using. Close every AI chat and terminal tab, then try again.")
            return
        }
        host.openDocument { uri -> if (uri != null) askChecksum(uri) }
    }

    private fun askChecksum(archive: Uri) {
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Verify checksum")
            .setMessage("Pick the .json checksum file saved with this backup to verify it.")
            .setPositiveButton("PICK FILE") { _, _ ->
                host.openDocument { s ->
                    if (s == null) return@openDocument
                    val m = try { ctx.contentResolver.openInputStream(s)?.use { BackupPlan.parseManifest(it.readBytes().toString(Charsets.UTF_8)) } } catch (_: Exception) { null }
                    if (m == null) showResult(false, "Not a checksum file", "That file is not a NativeCode backup checksum (.json).")
                    else runVerify(archive, m.sha256)
                }
            }
            .setNeutralButton("CONTINUE WITHOUT") { _, _ -> runVerify(archive, null) }
            .setNegativeButton("CANCEL", null)
            .create().showThemed(ctx)
    }

    private fun runVerify(archive: Uri, expected: String?) {
        val size = docSize(archive)
        val free = try { StatFs(ctx.filesDir.absolutePath).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
        if (size != null && free < size * 3 + 100L * 1024 * 1024) {
            showResult(false, "Not enough free space", "Need about ${BackupPlan.formatBytes(size * 3 + 100L * 1024 * 1024)} free, have ${BackupPlan.formatBytes(free)}.")
            return
        }
        val j = BackupJob().also { job = it }
        setBusy(true, "Verifying archive...")
        bg.execute {
            val res = try {
                ctx.contentResolver.openInputStream(archive)?.use { ins -> BackupService.verify(ctx, ins, j) { p -> ui.post { progress(p, size) } } }
            } catch (e: Exception) { null }
            ui.post {
                when {
                    res == null -> showResult(false, "Could not read the backup", "The file could not be opened.")
                    res.cancelled -> showResult(false, "Restore cancelled", "Nothing was changed.")
                    !res.ok -> showResult(false, "Backup rejected", (res.message ?: "") + "\nNothing was changed.")
                    expected != null && expected != res.sha256 -> showResult(false, "Checksum mismatch", "The archive does not match its checksum file, so it is corrupt or was modified.\nexpected $expected\ngot      ${res.sha256}\nNothing was changed.")
                    else -> { setBusy(false); chooseEntries(archive, size, res.topLevel, res.sha256, expected != null, res.members) }
                }
            }
        }
    }

    private fun chooseEntries(archive: Uri, size: Long?, top: List<String>, sha: String, checked: Boolean, members: Long) {
        val home = ProjectPathResolver.guestHomeDir(ctx, "proot")
        val modes = HashMap<String, EntryMode>().apply { top.forEach { put(it, EntryMode.MERGE) } }
        val on = HashSet<String>(top)
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        list.addView(text(12f, NC.ON_SURF_VAR, mono = true).apply {
            text = (if (checked) "Checksum verified. " else "No checksum file: sha256 ${sha.take(16)}... ") + "$members entries."
            setPadding(0, 0, 0, dp(8))
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
                text = "MERGE"; textSize = 11f; typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
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
            .setTitle("Restore entries")
            .setView(scroll)
            .setPositiveButton("RESTORE") { _, _ ->
                val plans = top.filter { it in on }.map { EntryPlan(it, modes[it]!!) }
                if (plans.isEmpty()) showResult(false, "Nothing selected", "Nothing was changed.") else runRestore(archive, size, plans)
            }
            .setNegativeButton("CANCEL", null)
            .create().showThemed(ctx)
    }

    private fun runRestore(archive: Uri, size: Long?, plans: List<EntryPlan>) {
        if (host.aiSessionsOpen()) {
            showResult(false, "Close AI sessions first", "Nothing was changed.")
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val home = ProjectPathResolver.guestHomeDir(ctx, "proot")
        val staging = File(home, ".restore-$stamp")
        val j = BackupJob().also { job = it }
        setBusy(true, "Restoring...")
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
                ui.post { showResult(false, "Restore cancelled", "Nothing was changed.") }
                return@execute
            }
            val sw = RestoreSwap.apply(home, staging, plans, stamp)
            if (!sw.ok) runCatching { DirModel.deleteTree(home, staging) }
            ui.post {
                if (sw.ok) {
                    showResult(true, "Restore complete", "${sw.moved} items restored, ${sw.kept} existing files kept." +
                        (sw.replacedDir?.let { "\nReplaced items are in ${BackupPlan.GUEST_HOME}/${it.name}. Delete that folder when you are happy with the result." } ?: ""))
                } else showResult(false, "Restore rolled back", "${sw.error}\nYour files are as they were.")
            }
        }
    }

    // endregion
}
