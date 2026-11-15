package com.zenithblue.nativecode.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import java.util.Locale

/**
 * In-app language (item 20 follow-up). One source of truth for the picker on Settings and on the first onboarding screen.
 * The choice lives in AppCompatDelegate: it persists below API 33 (AppLocalesMetadataHolderService, autoStoreLocales)
 * and mirrors the system per-app language on 33+. Empty tag = system default; a system language we do not ship falls
 * back to English (values/ is English).
 */
internal object LanguagePicker {
    /** Keep in step with res/xml/locales_config.xml and res/values-<lang> (LanguagePickerTest). */
    data class Lang(val tag: String, val nativeName: Int, val englishName: Int)

    val LANGS = listOf(
        Lang("en", R.string.lang_en, R.string.lang_en_english),
        Lang("es", R.string.lang_es, R.string.lang_es_english),
        Lang("pt-BR", R.string.lang_pt_br, R.string.lang_pt_br_english),
        Lang("fr", R.string.lang_fr, R.string.lang_fr_english),
        Lang("de", R.string.lang_de, R.string.lang_de_english),
        Lang("it", R.string.lang_it, R.string.lang_it_english),
        Lang("ru", R.string.lang_ru, R.string.lang_ru_english),
        Lang("id", R.string.lang_id, R.string.lang_id_english),
        Lang("tr", R.string.lang_tr, R.string.lang_tr_english),
        Lang("ja", R.string.lang_ja, R.string.lang_ja_english),
        Lang("ko", R.string.lang_ko, R.string.lang_ko_english),
        Lang("zh-CN", R.string.lang_zh_cn, R.string.lang_zh_cn_english),
        Lang("ar", R.string.lang_ar, R.string.lang_ar_english),
    )

    /** Picker option for a stored language tag ("de-DE" -> "de"), "" when it is not one we ship (= system default). */
    fun match(languageTags: String): String {
        val first = languageTags.split(',').firstOrNull()?.trim().orEmpty()
        if (first.isEmpty()) return ""
        LANGS.firstOrNull { it.tag.equals(first, ignoreCase = true) }?.let { return it.tag }
        val lang = Locale.forLanguageTag(first).language
        return LANGS.firstOrNull { Locale.forLanguageTag(it.tag).language == lang }?.tag.orEmpty()
    }

    fun currentTag(): String = match(AppCompatDelegate.getApplicationLocales().toLanguageTags())

    /** Applies at once: AppCompat recreates the visible activity (or the system does on 33+); others follow on resume. */
    fun set(tag: String) = AppCompatDelegate.setApplicationLocales(
        if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
    )

    /** Name of the current choice, shown as the row value. */
    fun currentName(ctx: Context): String =
        LANGS.firstOrNull { it.tag == currentTag() }?.let { ctx.getString(it.nativeName) } ?: ctx.getString(R.string.language_system_default)

    /** Drop-down under [anchor]: "System default" first, then every language in its own script with the English name below. */
    fun showMenu(ctx: Context, anchor: View) {
        val dm = ctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        val current = currentTag()
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scroll = ScrollView(ctx).apply {
            addView(list)
            background = GradientDrawable().apply { setColor(NC.SURFACE_CONTAINER); setStroke(dp(1), NC.PRIMARY) }
        }
        val popup = PopupWindow(scroll, minOf(dp(280), dm.widthPixels - dp(24)), minOf(dp(420), dm.heightPixels * 6 / 10), true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        fun option(tag: String, title: String, subtitle: String?) {
            list.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                contentDescription = listOfNotNull(title, subtitle).joinToString(", ")
                setBackgroundColor(if (tag == current) NC.SURFACE_HIGH else Color.TRANSPARENT)
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(ctx).apply {
                        text = title; textSize = 15f; setTextColor(if (tag == current) NC.PRIMARY else NC.ON_SURFACE)
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    if (subtitle != null) addView(TextView(ctx).apply {
                        text = subtitle; textSize = 11f; setTextColor(NC.ON_SURF_VAR); typeface = Typeface.MONOSPACE
                    })
                }
                addView(col)
                if (tag == current) addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_lucide_check)
                    setColorFilter(NC.PRIMARY)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) }
                })
                setOnClickListener {
                    popup.dismiss()
                    if (tag != current) set(tag)
                }
            })
        }
        option("", ctx.getString(R.string.language_system_default), null)
        for (l in LANGS) option(l.tag, ctx.getString(l.nativeName), ctx.getString(l.englishName))
        // Align the popup's end edge with the anchor's end edge (right in LTR, left in Arabic).
        popup.showAsDropDown(anchor, 0, dp(4), Gravity.END)
    }

    /** Globe + current name + chevron. Used for the onboarding corner chip; the Settings row builds its own. */
    fun chip(ctx: Context, onClick: (View) -> Unit): View {
        val dm = ctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke(dp(1), NC.OUTLINE_VAR) }
            isClickable = true
            contentDescription = ctx.getString(R.string.language_title)
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_lucide_globe); setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) }
            })
            addView(TextView(ctx).apply {
                text = currentName(ctx); textSize = 13f; setTextColor(NC.ON_SURFACE); typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            })
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_lucide_chevron_down); setColorFilter(NC.PRIMARY)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) }
            })
            setOnClickListener { onClick(this) }
        }
    }
}
