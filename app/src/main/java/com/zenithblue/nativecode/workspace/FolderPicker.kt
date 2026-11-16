package com.zenithblue.nativecode.workspace

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.applySystemInsets
import com.zenithblue.nativecode.editor.showThemed
import com.zenithblue.nativecode.terminal.ProjectLocation
import com.zenithblue.nativecode.tr
import java.io.File

/**
 * Full-screen folder picker for the guest home. Folders only, breadcrumbs, hidden toggle, New folder, Up.
 * Never leaves /home/flux: every step is validated on the canonical guest path ([ProjectLocation.resolve]),
 * so a symlink that points outside is shown greyed and can not be opened or picked.
 * Listing and breadcrumbs reuse [DirModel] from the Directory page.
 *
 * [validate] gets the canonical guest path of the shown folder and returns an inline error or null.
 */
internal class FolderPicker(
    private val ctx: Context,
    private val rootfs: File,
    startGuest: String,
    private val title: String,
    private val validate: (String) -> String?,
    private val onPick: (String) -> Unit,
) {
    private val home = ProjectLocation.hostFile(rootfs, ProjectLocation.HOME)
    private var cur: File = start(startGuest)
    private var showHidden = false
    private var entries: List<Pair<DirEntry, String?>> = emptyList() // entry + canonical guest path (null = outside home)

    private val dialog = Dialog(ctx, android.R.style.Theme_DeviceDefault_NoActionBar)
    private val crumbRow = LinearLayout(ctx)
    private val crumbScroll = HorizontalScrollView(ctx)
    private val list = RecyclerView(ctx)
    private val emptyTv = TextView(ctx)
    private val errorTv = TextView(ctx)
    private val hiddenBtn = ImageView(ctx)
    private val selectedTv = TextView(ctx)
    private val upBtn = LinearLayout(ctx)

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun start(guest: String): File {
        val g = ProjectLocation.expand(guest)?.let { ProjectLocation.resolve(rootfs, it) } ?: ProjectLocation.HOME
        var f = ProjectLocation.hostFile(rootfs, g)
        while (f != home && !f.isDirectory) f = f.parentFile ?: return home
        return if (f.isDirectory) f else home
    }

    private fun guestOf(dir: File): String =
        ProjectLocation.HOME + dir.absolutePath.removePrefix(home.absolutePath)

    fun show() {
        dialog.setCancelable(true)
        dialog.setContentView(build(), ViewGroup.LayoutParams(-1, -1))
        dialog.window?.apply {
            setLayout(-1, -1)
            setBackgroundDrawable(ColorDrawable(NC.BG))
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        reload()
        dialog.show()
    }

    // region ui

    private fun icon(res: Int, desc: String, tint: Int = NC.ON_SURF_VAR, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(res)
        setColorFilter(tint)
        contentDescription = desc
        val p = dp(10)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    private fun button(label: String, res: Int, primary: Boolean, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        val fg = if (primary) Color.parseColor("#0A0A0A") else NC.PRIMARY
        background = GradientDrawable().apply {
            if (primary) setColor(NC.PRIMARY_CON) else { setColor(NC.SURFACE_LOWEST); setStroke(dp(1), NC.OUTLINE_VAR) }
        }
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isClickable = true
        isFocusable = true
        contentDescription = label
        addView(ImageView(ctx).apply { setImageResource(res); setColorFilter(fg) },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
        addView(TextView(ctx).apply {
            text = label; textSize = 13f; typeface = Typeface.MONOSPACE; paint.isFakeBoldText = true; setTextColor(fg)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        setOnClickListener { onClick() }
    }

    private fun build(): View {
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(NC.BG) }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        bar.addView(icon(R.drawable.ic_lucide_x, tr(R.string.x11_cancel)) { dialog.dismiss() })
        bar.addView(TextView(ctx).apply {
            text = title; textSize = 16f; typeface = Typeface.MONOSPACE; paint.isFakeBoldText = true
            setTextColor(NC.ON_SURFACE); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        hiddenBtn.apply {
            setImageResource(R.drawable.ic_lucide_eye_off)
            setColorFilter(NC.ON_SURF_VAR)
            contentDescription = tr(R.string.loc_show_hidden)
            val p = dp(10); setPadding(p, p, p, p)
            setOnClickListener {
                showHidden = !showHidden
                setImageResource(if (showHidden) R.drawable.ic_lucide_eye else R.drawable.ic_lucide_eye_off)
                setColorFilter(if (showHidden) NC.PRIMARY else NC.ON_SURF_VAR)
                reload()
            }
        }
        bar.addView(hiddenBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(icon(R.drawable.ic_lucide_folder_plus, tr(R.string.ws_new_folder_2), NC.PRIMARY) { promptNewFolder() })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        crumbScroll.isHorizontalScrollBarEnabled = false
        crumbRow.orientation = LinearLayout.HORIZONTAL
        crumbRow.gravity = Gravity.CENTER_VERTICAL
        crumbScroll.addView(crumbRow)
        root.addView(crumbScroll, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(8); marginEnd = dp(8) })
        root.addView(View(ctx).apply { setBackgroundColor(NC.OUTLINE_VAR) }, LinearLayout.LayoutParams(-1, dp(1)))

        val body = FrameLayout(ctx)
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = Adapter()
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        emptyTv.apply {
            text = tr(R.string.loc_no_subfolders); textSize = 13f; setTextColor(NC.OUTLINE)
            gravity = Gravity.CENTER; visibility = View.GONE
        }
        body.addView(emptyTv, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(View(ctx).apply { setBackgroundColor(NC.OUTLINE_VAR) }, LinearLayout.LayoutParams(-1, dp(1)))
        val foot = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(10)) }
        selectedTv.apply { textSize = 12f; typeface = Typeface.MONOSPACE; setTextColor(NC.ON_SURF_VAR); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        errorTv.apply { textSize = 12f; setTextColor(NC.ERROR); visibility = View.GONE; setPadding(0, dp(4), 0, 0) }
        foot.addView(selectedTv)
        foot.addView(errorTv)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val up = button(tr(R.string.loc_up), R.drawable.ic_lucide_arrow_up, false) { goUp() }
        upBtn.addView(up, LinearLayout.LayoutParams(-2, -2))
        row.addView(upBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        row.addView(button(tr(R.string.loc_select_this), R.drawable.ic_lucide_check, true) { select() },
            LinearLayout.LayoutParams(0, -2, 1f))
        foot.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(foot, LinearLayout.LayoutParams(-1, -2))
        applySystemInsets(root)
        return root
    }

    private fun renderCrumbs() {
        crumbRow.removeAllViews()
        val crumbs = DirModel.breadcrumbs(home, cur, "~")
        crumbs.forEachIndexed { i, c ->
            if (i > 0) crumbRow.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_lucide_chevron_right); setColorFilter(NC.OUTLINE)
                scaleX = if (isRtl()) -1f else 1f
            }, LinearLayout.LayoutParams(dp(16), dp(16)))
            val last = i == crumbs.lastIndex
            crumbRow.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                setPadding(dp(6), 0, dp(6), 0)
                if (i == 0) addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_lucide_house); setColorFilter(if (last) NC.PRIMARY else NC.ON_SURF_VAR)
                }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
                addView(TextView(ctx).apply {
                    text = c.label; textSize = 13f; typeface = Typeface.MONOSPACE
                    setTextColor(if (last) NC.PRIMARY else NC.ON_SURF_VAR)
                    if (last) paint.isFakeBoldText = true
                    maxLines = 1
                })
                isClickable = true
                contentDescription = c.label
                setOnClickListener { if (!last) { cur = c.dir; reload() } }
            })
        }
        crumbScroll.post { crumbScroll.fullScroll(if (isRtl()) View.FOCUS_LEFT else View.FOCUS_RIGHT) }
    }

    private fun isRtl() = ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    // endregion

    // region state

    private fun reload() {
        errorTv.visibility = View.GONE
        val g = guestOf(cur)
        entries = DirModel.list(cur, showHidden, "", DirSort.NAME).filter { it.isDir }
            .map { it to ProjectLocation.resolve(rootfs, "$g/${it.name}") }
        list.adapter?.notifyDataSetChanged()
        emptyTv.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        selectedTv.text = ProjectLocation.display(g)
        val atHome = cur == home
        upBtn.alpha = if (atHome) 0.4f else 1f
        upBtn.isEnabled = !atHome
        renderCrumbs()
    }

    private fun goUp() {
        if (cur == home) return
        cur = cur.parentFile?.takeIf { DirModel.isInside(home, it) } ?: home
        reload()
    }

    private fun select() {
        val g = guestOf(cur)
        val err = validate(g)
        if (err != null) { errorTv.text = err; errorTv.visibility = View.VISIBLE; return }
        dialog.dismiss()
        onPick(g)
    }

    private fun promptNewFolder() {
        val input = EditText(ctx).apply {
            hint = tr(R.string.loc_folder_name)
            setSingleLine(true)
            setTextColor(NC.ON_SURFACE)
            setHintTextColor(NC.OUTLINE)
            typeface = Typeface.MONOSPACE
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val box = FrameLayout(ctx).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tr(R.string.ws_new_folder_2))
            .setView(box)
            .setPositiveButton(tr(R.string.ws_create)) { _, _ -> createFolder(input.text.toString().trim()) }
            .setNegativeButton(tr(R.string.x11_cancel), null)
            .create().also { dlg ->
                input.setOnEditorActionListener { _, _, _ -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); true }
            }.showThemed(ctx)
    }

    private fun createFolder(name: String) {
        if (!DirModel.validName(name)) { toast(tr(R.string.ws_invalid_name)); return }
        val f = File(cur, name)
        if (f.exists()) { toast(tr(R.string.loc_err_exists, name)); return }
        val ok = try { f.mkdirs() } catch (_: Exception) { false }
        val canon = ProjectLocation.resolve(rootfs, "${guestOf(cur)}/$name")
        if (!ok || canon == null) { toast(tr(R.string.loc_err_create)); return }
        cur = ProjectLocation.hostFile(rootfs, canon)
        reload()
    }

    private fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    private inner class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row) {
        val name = TextView(ctx)
        val sub = TextView(ctx)

        init {
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = dp(52)
            row.setPadding(dp(14), dp(6), dp(14), dp(6))
            row.layoutParams = RecyclerView.LayoutParams(-1, -2)
            row.isClickable = true
            row.isFocusable = true
            row.addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_lucide_folder); setColorFilter(NC.PRIMARY) },
                LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) })
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            name.apply { textSize = 14f; setTextColor(NC.ON_SURFACE); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
            sub.apply { textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(NC.ERROR); maxLines = 1 }
            col.addView(name); col.addView(sub)
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(parent.context))
        override fun getItemCount() = entries.size
        override fun onBindViewHolder(h: Holder, position: Int) {
            val (e, canon) = entries[position]
            val ok = canon != null
            h.name.text = e.name
            h.sub.visibility = if (ok) View.GONE else View.VISIBLE
            h.sub.text = tr(R.string.loc_outside_home)
            h.row.alpha = if (ok) 1f else 0.45f
            h.row.isEnabled = ok
            h.row.setOnClickListener {
                if (canon != null) { cur = ProjectLocation.hostFile(rootfs, canon); reload() }
            }
        }
    }

    // endregion
}
