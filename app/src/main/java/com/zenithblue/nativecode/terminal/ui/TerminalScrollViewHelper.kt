package com.zenithblue.nativecode.terminal.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.onboarding.OnboardingUiKit
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/**
 * Decoupled helper providing a dedicated native Android ScrollView & transcript viewer
 * for terminal sessions, CLI tools, and TUIs.
 *
 * Solves the mobile touch constraint where text selection and scrolling in an emulated
 * terminal grid are bounded by visible rows. In this ScrollView, users enjoy full inertial
 * scrolling, unrestricted native Android text selection across any number of lines,
 * and one-tap "COPY ALL".
 */
object TerminalScrollViewHelper {

    /** Extract the full transcript (scrollback history + active screen) of a session. */
    fun transcriptOf(session: TerminalSession?): String =
        try {
            session?.emulator?.screen?.getTranscriptText()?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }

    /** Copy the entire terminal transcript to the clipboard and alert the user. */
    fun copyTranscript(context: Context, session: TerminalSession?, label: String = "Terminal Transcript"): Boolean {
        val text = transcriptOf(session)
        if (text.isEmpty()) {
            Toast.makeText(context, tr(R.string.term_nothing_to_copy_yet), Toast.LENGTH_SHORT).show()
            return false
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, tr(R.string.term_copied_transcript_chars, text.length), Toast.LENGTH_SHORT).show()
        return true
    }

    /**
     * Shows a full-screen or modal dialog with a native Android ScrollView containing the
     * session's complete transcript, allowing unrestrained scrolling and native text selection.
     */
    fun showScrollViewDialog(
        context: Context,
        session: TerminalSession?,
        customTypeface: Typeface? = null,
        title: String = tr(R.string.term_transcript_read_only)
    ) {
        val transcript = transcriptOf(session)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(NC.BG)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top Navigation Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(NC.SURFACE)
        }

        val titleTv = TextView(context).apply {
            text = title
            setTextColor(NC.PRIMARY)
            textSize = 14f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val copyBtn = TextView(context).apply {
            text = tr(R.string.term_copy_all)
            setTextColor(Color.BLACK)
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = OnboardingUiKit.cyberBrutalistBg(
                context,
                fillColor = NC.PRIMARY,
                strokeColor = NC.BORDER,
                shadowColor = NC.SHADOW_GREEN,
                offsetDp = 3,
                cornerRadiusDp = 0
            )
            setOnClickListener {
                copyTranscript(context, session, title)
            }
        }

        val closeBtn = TextView(context).apply {
            text = tr(R.string.term_close)
            setTextColor(NC.ON_SURF_VAR)
            textSize = 12f
            paint.isFakeBoldText = true
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = OnboardingUiKit.cyberBrutalistBg(
                context,
                fillColor = NC.SURFACE_HIGH,
                strokeColor = NC.OUTLINE_VAR,
                shadowColor = NC.SHADOW_DARK,
                offsetDp = 3,
                cornerRadiusDp = 0
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
            setOnClickListener { dialog.dismiss() }
        }

        header.addView(titleTv)
        header.addView(copyBtn)
        header.addView(closeBtn)
        root.addView(header)

        // Accent Divider
        root.addView(View(context).apply {
            setBackgroundColor(NC.BORDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        // Native ScrollView + Selectable TextView
        val sv = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            isVerticalScrollBarEnabled = true
        }

        val contentTv = TextView(context).apply {
            text = if (transcript.isEmpty()) "(empty transcript)" else transcript
            setTextIsSelectable(true)
            textSize = 12f
            setTextColor(Color.parseColor("#E0E0E0"))
            typeface = customTypeface ?: Typeface.MONOSPACE
            setPadding(dp(16), dp(12), dp(16), dp(32))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        sv.addView(contentTv)
        root.addView(sv)

        dialog.setContentView(root)
        dialog.show()

        // Jump to bottom initially so latest output is immediately visible
        sv.post {
            sv.fullScroll(View.FOCUS_DOWN)
        }
    }
}
