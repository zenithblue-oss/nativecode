package com.zenithblue.nativecode.proot

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.zenithblue.nativecode.tr

/**
 * In-app file navigation page for PRoot home directory (/home/flux).
 * Fully modular and decoupled from system file managers or document providers.
 */
class ProotFilesPage(
    private val ctx: Context,
    private val onBack: () -> Unit,
    private val onOpenFile: (File) -> Unit
) {
    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private val baseDir: File by lazy {
        ProjectPathResolver.guestHomeDir(ctx, "proot").also {
            if (!it.exists()) it.mkdirs()
        }
    }

    private var currentDir: File = ProjectPathResolver.guestHomeDir(ctx, "proot").also {
        if (!it.exists()) it.mkdirs()
    }
    private val dateFormat = SimpleDateFormat(tr(R.string.proot_mmm_d_yyyy_hh_mm), Locale.US)

    val view: ScrollView = ScrollView(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        visibility = View.GONE
        clipToPadding = false
        setPadding(0, 0, 0, dp(80))
        setBackgroundColor(NC.BG)
    }

    private val pageLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private lateinit var pathSubtitleTv: TextView
    private lateinit var upBtn: ImageView
    private lateinit var filesContainer: LinearLayout

    init {
        view.addView(pageLayout)
        pageLayout.addView(buildHeader())
        pageLayout.addView(buildFilesCard())
    }

    private fun buildHeader(): View {
        val headerCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(14))
        }
        val headerTitleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pageBackBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.PRIMARY)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(8) }
            contentDescription = "Back"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setOnClickListener {
                if (!handleBack()) {
                    onBack()
                }
            }
        }
        val headerIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_folder)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) }
        }
        val titleTv = TextView(ctx).apply {
            text = tr(R.string.proot_home_files)
            textSize = 20f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        upBtn = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_arrow_up)
            setColorFilter(NC.PRIMARY)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            contentDescription = "Up"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(dp(1), NC.OUTLINE_VAR)
            }
            setOnClickListener {
                handleBack()
            }
        }

        headerTitleRow.addView(pageBackBtn)
        headerTitleRow.addView(headerIcon)
        headerTitleRow.addView(titleTv)
        headerTitleRow.addView(upBtn)

        pathSubtitleTv = TextView(ctx).apply {
            text = "// ~"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(8))
        }

        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { bottomMargin = dp(8) }
            setBackgroundColor(NC.OUTLINE_VAR)
        }

        headerCol.addView(headerTitleRow)
        headerCol.addView(pathSubtitleTv)
        headerCol.addView(divider)
        return headerCol
    }

    private fun buildFilesCard(): View {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = brutalistBg(NC.SURFACE_LOW, NC.OUTLINE_VAR, NC.SHADOW_DARK, 6)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        filesContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(filesContainer)
        return card
    }

    /** Returns true if handled navigating up within home directory. */
    fun handleBack(): Boolean {
        if (currentDir.canonicalPath != baseDir.canonicalPath) {
            val parent = currentDir.parentFile
            if (parent != null && parent.canonicalPath.startsWith(baseDir.canonicalPath)) {
                currentDir = parent
            } else {
                currentDir = baseDir
            }
            refresh()
            return true
        }
        return false
    }

    fun refresh() {
        if (!currentDir.exists()) {
            currentDir.mkdirs()
        }
        val isAtRoot = currentDir.canonicalPath == baseDir.canonicalPath
        upBtn.visibility = if (isAtRoot) View.GONE else View.VISIBLE

        val rel = if (isAtRoot) "~" else "~/" + currentDir.canonicalPath.removePrefix(baseDir.canonicalPath).trimStart('/')
        pathSubtitleTv.text = "// $rel"

        filesContainer.removeAllViews()

        val files = currentDir.listFiles()
        val entries: List<File> = if (files != null) {
            files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } else {
            emptyList()
        }

        if (entries.isEmpty()) {
            val emptyTv = TextView(ctx).apply {
                text = tr(R.string.proot_folder_is_empty)
                textSize = 13f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
                setPadding(dp(8), dp(16), dp(8), dp(16))
                gravity = Gravity.CENTER
            }
            filesContainer.addView(emptyTv)
            return
        }

        entries.forEachIndexed { index, file ->
            val isDir = file.isDirectory
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_LOWEST)
                    setStroke(dp(1), NC.OUTLINE_VAR)
                }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    if (index > 0) topMargin = dp(6)
                }
                setOnClickListener {
                    if (isDir) {
                        currentDir = file
                        refresh()
                    } else {
                        onOpenFile(file)
                    }
                }
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> v.alpha = 0.7f
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.alpha = 1f
                    }
                    false
                }
            }

            val iconIv = ImageView(ctx).apply {
                setImageResource(if (isDir) R.drawable.ic_folder else R.drawable.ic_attach_file)
                setColorFilter(if (isDir) NC.PRIMARY else NC.ON_SURF_VAR)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) }
            }

            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }

            val nameTv = TextView(ctx).apply {
                text = file.name
                textSize = 13f
                setTextColor(if (isDir) NC.PRIMARY else Color.WHITE)
                typeface = if (isDir) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
            }

            val subTv = TextView(ctx).apply {
                val subText = if (isDir) {
                    val count = file.listFiles()?.size ?: 0
                    "$count items"
                } else {
                    val sizeStr = formatFileSize(file.length())
                    val dateStr = dateFormat.format(Date(file.lastModified()))
                    "$sizeStr · $dateStr"
                }
                text = subText
                textSize = 10f
                setTextColor(NC.ON_SURF_VAR)
                typeface = Typeface.MONOSPACE
            }

            textCol.addView(nameTv)
            textCol.addView(subTv)

            row.addView(iconIv)
            row.addView(textCol)

            if (isDir) {
                val arrowIv = ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_chevron_right)
                    setColorFilter(NC.PRIMARY)
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
                }
                row.addView(arrowIv)
            }

            filesContainer.addView(row)
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups.coerceAtMost(3)])
    }

    private fun brutalistBg(
        fillColor: Int,
        strokeColor: Int = NC.OUTLINE_VAR,
        shadowColor: Int = NC.SHADOW_DARK,
        offsetDp: Int = 6
    ): LayerDrawable {
        val bottom = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(shadowColor) }
        val right = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(strokeColor) }
        val front = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
        }
        val off = dp(offsetDp)
        return LayerDrawable(arrayOf<Drawable>(bottom, right, front)).apply {
            setLayerInset(0, off, off, 0, 0)
            setLayerInset(1, off, 0, 0, off)
            setLayerInset(2, 0, 0, off, off)
        }
    }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
