package com.zenithblue.nativecode.telemetry

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.ui.BrutalistToggle

/**
 * The one consent UI: a themed sheet for the one-time prompt (existing users) and Settings, and the same
 * content as an onboarding page ([content]). Two independent toggles, both off until the user opts in.
 * Changes go through [Telemetry.setConsent], which also wipes queued data when a toggle is turned off.
 */
object ConsentSheet {
    private const val PRIVACY_URL = "https://zenithblue-dev.github.io/nativecode-ai/privacy/"

    /** Toggles start at the stored choice. [onDone] runs after "Allow selected" or "Not now". */
    fun show(activity: Activity, onDone: () -> Unit = {}) {
        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(Color.parseColor("#B3000000"))
            setPadding(px(16), px(24), px(16), px(24))
            setOnClickListener { dialog.cancel() }
        }
        val card = ScrollView(activity).apply {
            background = GradientDrawable().apply { setColor(NC.SURFACE_LOW); setStroke(px(1), NC.PRIMARY) }
            isClickable = true // swallow taps so the scrim does not dismiss
            addView(content(activity) { dialog.dismiss() })
        }
        scrim.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        dialog.setContentView(scrim)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // Back / outside tap = "Not now": keep the stored choice, stop re-asking.
        dialog.setOnDismissListener {
            markAsked(activity)
            onDone()
        }
        dialog.show()
    }

    /** Existing users see the sheet once after upgrading; new users answer in onboarding. */
    fun showOnceIfUnasked(activity: Activity) {
        if (!Telemetry.asked(activity)) show(activity)
    }

    private fun markAsked(ctx: Context) {
        if (!Telemetry.asked(ctx)) Telemetry.setConsent(ctx, Telemetry.crashConsent(ctx), Telemetry.usageConsent(ctx))
    }

    /** The consent content (icon, title, plain-language text, toggles, privacy link, buttons). [onChoice] after either button. */
    fun content(activity: Activity, onChoice: () -> Unit): View {
        val dm = activity.resources.displayMetrics
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm).toInt()
        fun text(res: Int, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            text = tr(res); textSize = size; setTextColor(color); typeface = Typeface.MONOSPACE
            if (bold) paint.isFakeBoldText = true
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }

        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(16)) }

        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_lucide_shield_check); setColorFilter(NC.PRIMARY)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
        head.addView(text(R.string.cons_title, 17f, NC.PRIMARY, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head)

        root.addView(text(R.string.cons_intro, 13f, NC.ON_SURFACE).apply { setPadding(0, dp(12), 0, dp(10)) })
        root.addView(text(R.string.cons_collected, 12f, NC.ON_SURF_VAR).apply { setPadding(0, 0, 0, dp(8)) })
        root.addView(text(R.string.cons_never, 12f, NC.ON_SURF_VAR).apply { setPadding(0, 0, 0, dp(14)) })

        fun toggleRow(icon: Int, title: Int, sub: Int, initial: Boolean): Pair<View, BrutalistToggle> {
            val toggle = BrutalistToggle(activity, initial)
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { setColor(NC.SURFACE_LOWEST); setStroke(dp(1), NC.OUTLINE_VAR) }
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener { toggle.performClick() }
            }
            row.addView(ImageView(activity).apply {
                setImageResource(icon); setColorFilter(NC.PRIMARY)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
            val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(title, 14f, NC.ON_SURFACE, bold = true))
            col.addView(text(sub, 11f, NC.OUTLINE))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
            toggle.isClickable = false // the row handles the tap
            toggle.contentDescription = tr(title)
            row.addView(toggle)
            return row to toggle
        }
        val (crashRow, crashToggle) = toggleRow(R.drawable.ic_lucide_bug, R.string.telemetry_send_crash_reports, R.string.cons_crash_sub, Telemetry.crashConsent(activity))
        val (usageRow, usageToggle) = toggleRow(R.drawable.ic_lucide_chart, R.string.telemetry_share_anonymous_usage_statistics, R.string.cons_usage_sub, Telemetry.usageConsent(activity))
        root.addView(crashRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        root.addView(usageRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(text(R.string.cons_footer, 11f, NC.OUTLINE).apply { setPadding(0, dp(12), 0, dp(6)) })
        root.addView(text(R.string.cons_privacy, 13f, NC.PRIMARY).apply {
            paint.isUnderlineText = true
            minHeight = dp(44)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener { openUrl(activity, PRIVACY_URL) }
        })

        fun button(label: Int, primary: Boolean, onClick: () -> Unit) = TextView(activity).apply {
            text = tr(label); textSize = 13f; typeface = Typeface.MONOSPACE; paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(if (primary) Color.parseColor("#0A0A0A") else NC.PRIMARY)
            background = GradientDrawable().apply {
                if (primary) setColor(NC.PRIMARY_CON) else { setColor(NC.SURFACE_LOWEST); setStroke(dp(1), NC.OUTLINE_VAR) }
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(button(R.string.cons_not_now, false) {
            markAsked(activity) // keeps the stored choice
            onChoice()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })
        buttons.addView(button(R.string.cons_allow, true) {
            Telemetry.setConsent(activity, crashToggle.isChecked, usageToggle.isChecked)
            onChoice()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        root.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        return root
    }

    private fun openUrl(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(ctx, tr(R.string.main_no_browser_available_to_open), Toast.LENGTH_SHORT).show()
        }
    }
}
