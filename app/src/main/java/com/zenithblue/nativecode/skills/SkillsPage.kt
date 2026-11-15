package com.zenithblue.nativecode.skills

import android.content.ClipData
import android.content.ClipboardManager
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
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.tr

private fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

private fun Context.brutalistBg(
    fillColor: Int,
    strokeColor: Int = NC.OUTLINE_VAR,
    shadowColor: Int = NC.SHADOW_DARK,
    offsetDp: Int = 6,
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

/**
 * Settings → SKILLS.
 * Categories: Inbuilt, Project/Utility, Install Programs. Search filters the list.
 * Each skill can be viewed in full or copied as a markdown prompt for a CLI agent.
 */
class SkillsPage(
    private val ctx: Context,
    private val onBack: () -> Unit,
) {
    val view: FrameLayout = FrameLayout(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        visibility = View.GONE
        setBackgroundColor(NC.BG)
    }

    private val listScroll = ScrollView(ctx).apply {
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        clipToPadding = false
        setPadding(0, 0, 0, ctx.dp(80))
        isVerticalScrollBarEnabled = true
    }

    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
    }

    private var category: SkillCategory = SkillCategory.INBUILT
    private var query: String = ""
    private var viewing: Skill? = null
    private val listHost = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val detailHost = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        setBackgroundColor(NC.BG)
        visibility = View.GONE
    }

    init {
        listScroll.addView(body)
        view.addView(listScroll)
        view.addView(detailHost)
        body.addView(header())
        body.addView(categoryRow())
        body.addView(searchBar())
        body.addView(listHost)
        renderList()
    }

    fun refresh() {
        if (viewing != null) renderDetail(viewing!!) else renderList()
    }

    /** @return true when a skill detail was closed and the list is showing again. */
    fun handleBack(): Boolean {
        if (viewing == null) return false
        showList()
        return true
    }

    private fun header(): LinearLayout {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ctx.dp(12))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.PRIMARY)
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
            layoutParams = LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)).apply { rightMargin = ctx.dp(8) }
            contentDescription = "Back"
            setOnClickListener { if (!handleBack()) onBack() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(ctx.dp(1), NC.OUTLINE_VAR)
            }
        })
        row.addView(TextView(ctx).apply {
            text = tr(R.string.skills_skills)
            textSize = 20f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        row.addView(badge(tr(R.string.skills_cli_agents)))
        col.addView(row)
        col.addView(TextView(ctx).apply {
            text = "// PASTE INTO CLAUDE, CODEX, OR ANY CLI AGENT"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, ctx.dp(4), 0, 0)
        })
        return col
    }

    private fun categoryRow(): HorizontalScrollView {
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(14) }
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        SkillCategory.entries.forEach { cat ->
            row.addView(TextView(ctx).apply {
                text = cat.title
                textSize = 11f
                typeface = Typeface.MONOSPACE
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = ctx.dp(8) }
                tag = cat
                setOnClickListener {
                    category = cat
                    showList()
                }
                setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(60).start()
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    }
                    false
                }
            })
        }
        scroll.addView(row)
        scroll.tag = "cats"
        return scroll
    }

    private fun paintCategories() {
        val scroll = body.findViewWithTag<HorizontalScrollView>("cats") ?: return
        val row = scroll.getChildAt(0) as LinearLayout
        for (i in 0 until row.childCount) {
            val tv = row.getChildAt(i) as TextView
            val on = tv.tag == category && viewing == null
            tv.setTextColor(if (on) Color.parseColor("#0A0A0A") else NC.ON_SURF_VAR)
            tv.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(if (on) NC.PRIMARY else NC.SURFACE_LOW)
                setStroke(ctx.dp(1), if (on) NC.PRIMARY else NC.OUTLINE_VAR)
            }
        }
    }

    private fun showList() {
        viewing = null
        detailHost.visibility = View.GONE
        listScroll.visibility = View.VISIBLE
        renderList()
        listScroll.scrollTo(0, 0)
    }

    private fun searchBar(): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_LOWEST)
                setStroke(ctx.dp(1), NC.OUTLINE_VAR)
            }
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(14) }
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(18), ctx.dp(18)).apply { rightMargin = ctx.dp(8) }
        })
        row.addView(EditText(ctx).apply {
            hint = tr(R.string.skills_search_skills)
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

    private fun renderList() {
        paintCategories()
        listHost.removeAllViews()
        val q = query.trim()
        val pool = if (q.isEmpty()) SkillsCatalog.skills(category) else SkillsCatalog.all()
        val skills = SkillsCatalog.matching(q, pool)
        listHost.addView(TextView(ctx).apply {
            text = if (q.isEmpty()) category.subtitle else "${skills.size} MATCHES"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, ctx.dp(10))
        })
        if (skills.isEmpty()) {
            listHost.addView(emptyCard())
            return
        }
        skills.forEach { skill ->
            listHost.addView(skillCard(skill))
            listHost.addView(gap(12))
        }
    }

    private fun skillCard(skill: Skill): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.brutalistBg(NC.SURFACE_LOW)
            setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)).apply { rightMargin = ctx.dp(10) }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    SkillIcons.bind(ctx, this, skill.id)
                })
                addView(TextView(ctx).apply {
                    text = skill.title
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                })
            })
            addView(TextView(ctx).apply {
                text = skill.summary
                textSize = 12f
                setTextColor(NC.ON_SURF_VAR)
                setPadding(0, ctx.dp(8), 0, ctx.dp(12))
            })
            val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(actionBtn(tr(R.string.skills_view)) {
                viewing = skill
                renderDetail(skill)
            }.also {
                it.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = ctx.dp(8) }
            })
            actions.addView(actionBtn(tr(R.string.skills_copy)) { copy(skill) }.also {
                it.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(actions)
        }
    }

    private fun emptyCard(): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ctx.brutalistBg(NC.SURFACE_LOW, offsetDp = 4)
            setPadding(ctx.dp(16), ctx.dp(20), ctx.dp(16), ctx.dp(20))
            addView(TextView(ctx).apply {
                text = tr(R.string.skills_empty)
                textSize = 13f
                setTextColor(NC.PRIMARY)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(ctx).apply {
                text = if (query.isBlank()) {
                    tr(R.string.skills_no_skills_in_this_category)
                } else {
                    tr(R.string.skills_no_skills_match_that_search)
                }
                textSize = 12f
                setTextColor(NC.ON_SURF_VAR)
                setPadding(0, ctx.dp(6), 0, 0)
            })
        }
    }

    private fun renderDetail(skill: Skill) {
        listScroll.visibility = View.GONE
        detailHost.visibility = View.VISIBLE
        detailHost.removeAllViews()
        detailHost.setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(12))
        detailHost.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_arrow_back)
                setColorFilter(NC.PRIMARY)
                setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
                layoutParams = LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)).apply { rightMargin = ctx.dp(8) }
                contentDescription = "Back"
                setOnClickListener { handleBack() }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NC.SURFACE_CONTAINER)
                    setStroke(ctx.dp(1), NC.OUTLINE_VAR)
                }
            })
            addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28)).apply { rightMargin = ctx.dp(8) }
                scaleType = ImageView.ScaleType.FIT_CENTER
                SkillIcons.bind(ctx, this, skill.id)
            })
            addView(TextView(ctx).apply {
                text = skill.title
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
        })
        detailHost.addView(TextView(ctx).apply {
            text = skill.summary
            textSize = 12f
            setTextColor(NC.ON_SURF_VAR)
            setPadding(0, ctx.dp(8), 0, ctx.dp(12))
        })
        detailHost.addView(actionBtn(tr(R.string.skills_copy_skill)) { copy(skill) }.also {
            it.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = ctx.dp(12) }
        })
        detailHost.addView(WebView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            setBackgroundColor(NC.SURFACE_CONTAINER)
            settings.javaScriptEnabled = false
            isVerticalScrollBarEnabled = true
            loadDataWithBaseURL(null, SkillMarkdown.html(skill.body), "text/html", "utf-8", null)
        })
    }

    private fun copy(skill: Skill) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(skill.title, skill.body))
        Toast.makeText(ctx, tr(R.string.skills_skill_copied), Toast.LENGTH_SHORT).show()
    }

    private fun actionBtn(label: String, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#0A0A0A"))
            typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            background = ctx.brutalistBg(
                fillColor = NC.PRIMARY,
                strokeColor = NC.PRIMARY,
                offsetDp = 4,
            )
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            setOnClickListener { onClick() }
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().translationX(ctx.dp(3).toFloat()).translationY(ctx.dp(3).toFloat()).setDuration(60).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().translationX(0f).translationY(0f).setDuration(80).start()
                }
                false
            }
        }
    }

    private fun badge(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 10f
        setTextColor(NC.PRIMARY)
        typeface = Typeface.MONOSPACE
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(NC.SURFACE_HIGHEST)
            setStroke(ctx.dp(1), NC.PRIMARY)
        }
        setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
    }

    private fun gap(dp: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, ctx.dp(dp))
    }
}
