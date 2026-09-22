package com.zenithblue.nativecode.skills

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R

private fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/** Terminal top-sheet: search, categories, COPY. Does not open the Skills page. */
internal class SkillsPickerSheet(
    private val ctx: Context,
    private val host: ViewGroup,
    private val onDismiss: () -> Unit,
) {
    val overlay: FrameLayout = FrameLayout(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        setBackgroundColor(Color.parseColor("#CC0E0E0E"))
        isClickable = true
        isFocusable = true
        setOnClickListener { dismiss() }
    }

    private var category: SkillCategory? = null
    private var query: String = ""
    private val listHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val sheet: LinearLayout

    init {
        sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(ctx.dp(1), NC.PRIMARY)
            }
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.TOP
            }
            isClickable = true
            setOnClickListener { }
        }
        sheet.addView(header())
        sheet.addView(searchBar())
        sheet.addView(categoryRow())
        sheet.addView(ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, ctx.dp(360))
            isVerticalScrollBarEnabled = true
            addView(listHost)
        })
        overlay.addView(sheet)
        renderList()
        sheet.translationY = -ctx.dp(40).toFloat()
        sheet.alpha = 0f
        sheet.animate().translationY(0f).alpha(1f).setDuration(180).start()
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(160).start()
        host.addView(overlay)
    }

    fun dismiss() {
        overlay.animate().alpha(0f).setDuration(120).withEndAction {
            try { host.removeView(overlay) } catch (_: Exception) {}
            onDismiss()
        }.start()
        sheet.animate().translationY(-ctx.dp(32).toFloat()).alpha(0f).setDuration(120).start()
    }

    private fun header(): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, ctx.dp(10))
        }
        row.addView(TextView(ctx).apply {
            text = "COPY SKILL"
            textSize = 16f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(NC.ON_SURFACE)
            contentDescription = "Close"
            setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
            layoutParams = LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32))
            setOnClickListener { dismiss() }
        })
        return row
    }

    private fun searchBar(): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(ctx.dp(1), NC.OUTLINE_VAR)
            }
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(10) }
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)).apply { rightMargin = ctx.dp(8) }
        })
        row.addView(EditText(ctx).apply {
            hint = "SEARCH"
            setHintTextColor(NC.OUTLINE)
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            background = null
            setPadding(0, 0, 0, 0)
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    renderList()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        })
        return row
    }

    private fun categoryRow(): HorizontalScrollView {
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(10) }
            tag = "picker-cats"
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(chip("ALL", null))
        SkillCategory.entries.forEach { cat -> row.addView(chip(cat.title, cat)) }
        scroll.addView(row)
        return scroll
    }

    private fun chip(label: String, cat: SkillCategory?): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 10f
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = ctx.dp(6) }
            tag = cat ?: "ALL"
            setOnClickListener {
                category = cat
                paintChips()
                renderList()
            }
        }
    }

    private fun paintChips() {
        val scroll = sheet.findViewWithTag<HorizontalScrollView>("picker-cats") ?: return
        val row = scroll.getChildAt(0) as LinearLayout
        for (i in 0 until row.childCount) {
            val tv = row.getChildAt(i) as TextView
            val on = if (category == null) tv.tag == "ALL" else tv.tag == category
            tv.setTextColor(if (on) Color.parseColor("#0A0A0A") else NC.ON_SURF_VAR)
            tv.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (on) NC.PRIMARY else NC.SURFACE_LOW)
                setStroke(ctx.dp(1), if (on) NC.PRIMARY else NC.OUTLINE_VAR)
            }
        }
    }

    private fun renderList() {
        paintChips()
        listHost.removeAllViews()
        val pool = if (category == null) SkillsCatalog.all() else SkillsCatalog.skills(category!!)
        val skills = SkillsCatalog.matching(query, pool)
        listHost.addView(TextView(ctx).apply {
            text = "${skills.size} SKILLS"
            textSize = 10f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, ctx.dp(8))
        })
        if (skills.isEmpty()) {
            listHost.addView(TextView(ctx).apply {
                text = "No match."
                textSize = 12f
                setTextColor(NC.ON_SURF_VAR)
                setPadding(0, ctx.dp(12), 0, ctx.dp(12))
            })
            return
        }
        skills.forEach { skill ->
            listHost.addView(row(skill))
            listHost.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH, ctx.dp(8))
            })
        }
    }

    private fun row(skill: Skill): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(ctx.dp(1), NC.OUTLINE_VAR)
            }
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(10), ctx.dp(10))
            addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)).apply { rightMargin = ctx.dp(10) }
                scaleType = ImageView.ScaleType.FIT_CENTER
                SkillIcons.bind(ctx, this, skill.id)
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = ctx.dp(8) }
                addView(TextView(ctx).apply {
                    text = skill.title
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                })
                addView(TextView(ctx).apply {
                    text = skill.summary
                    textSize = 11f
                    setTextColor(NC.ON_SURF_VAR)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, ctx.dp(2), 0, 0)
                })
            })
            addView(TextView(ctx).apply {
                text = "COPY"
                textSize = 11f
                setTextColor(Color.parseColor("#0A0A0A"))
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.PRIMARY)
                }
                setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
                setOnClickListener { copy(skill) }
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(50).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.animate().scaleX(1f).scaleY(1f).setDuration(70).start()
                    }
                    false
                }
            })
        }
    }

    private fun copy(skill: Skill) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(skill.title, skill.body))
        Toast.makeText(ctx, "Copied ${skill.title}", Toast.LENGTH_SHORT).show()
        dismiss()
    }
}
