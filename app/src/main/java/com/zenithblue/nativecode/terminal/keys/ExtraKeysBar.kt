package com.zenithblue.nativecode.terminal.keys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.terminal.ui.TerminalModifierState

/**
 * NativeCode's own terminal extra-keys bar (not Termux ExtraKeysView).
 *
 * Fixed grid, nothing scrolls: 9 keys per row, modifiers left, arrows grouped, BKSP / ENTER on the
 * right edge, one page key in a fixed cell. Sticky modifiers (tap = next key only, tap again or
 * hold = locked, tap again = off), key repeat on hold, haptics, swipe-up / hold alternates shown as
 * a corner glyph. Every key is a real View, so TalkBack and UI tests can reach it.
 */
@SuppressLint("ViewConstructor")
class ExtraKeysBar(
    context: Context,
    private val mods: TerminalModifierState,
    private val host: Host,
) : ViewGroup(context) {

    interface Host {
        /** [key] is a terminal key name (ESC, UP, F5…) or literal text. */
        fun send(key: String, ctrl: Boolean, alt: Boolean, shift: Boolean)
        fun action(id: String)
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private var preset = KeyPreset.STANDARD
    private var pages: List<KeyPage> = emptyList()
    private var page = 0
    private val groupGap = dp(5f).toInt()
    private val rowH: Int get() = dp(if (preset == KeyPreset.COMPACT) 40f else 36f).toInt()

    // Must precede init: reloadPreset() builds KeyViews that read these.
    private val isModifier: (Key) -> Boolean = { it.kind == KeyKind.CTRL || it.kind == KeyKind.ALT || it.kind == KeyKind.SHIFT }
    private val navKeys = setOf("UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PGUP", "PGDN")

    init {
        setBackgroundColor(NC.SURFACE_LOWEST)
        mods.onStateChanged = { post { invalidateKeys() } }
        reloadPreset()
    }

    /** Re-read the preset preference (Settings changed it). */
    fun reloadPreset() {
        preset = KeyPreset.byId(
            context.getSharedPreferences("nativecode_prefs", Context.MODE_PRIVATE).getString(KeyLayouts.PREF_PRESET, null)
        )
        pages = KeyLayouts.pages(preset)
        page = 0
        rebuild()
    }

    private fun rebuild() {
        removeAllViews()
        pages[page].rows.forEach { row -> row.forEach { addView(KeyView(it)) } }
        requestLayout()
    }

    private fun invalidateKeys() {
        for (i in 0 until childCount) getChildAt(i).invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), pages[page].rows.size * rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val p = pages[page]
        val cols = p.rows.first().size
        val cellW = (r - l - groupGap * p.gapsAfter.size).toFloat() / cols
        var i = 0
        p.rows.forEachIndexed { rowIdx, row ->
            var x = 0f
            row.indices.forEach { col ->
                val child = getChildAt(i++)
                val left = Math.round(x)
                val right = Math.round(x + cellW)
                child.measure(
                    MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rowH, MeasureSpec.EXACTLY)
                )
                child.layout(left, rowIdx * rowH, right, (rowIdx + 1) * rowH)
                x += cellW + if (col in p.gapsAfter) groupGap else 0
            }
        }
    }

    private fun active(kind: KeyKind) = when (kind) {
        KeyKind.CTRL -> mods.ctrlActive
        KeyKind.ALT -> mods.altActive
        KeyKind.SHIFT -> mods.shiftActive
        else -> false
    }

    private fun locked(kind: KeyKind) = when (kind) {
        KeyKind.CTRL -> mods.ctrlLocked
        KeyKind.ALT -> mods.altLocked
        KeyKind.SHIFT -> mods.shiftLocked
        else -> false
    }

    private fun setModifier(kind: KeyKind, active: Boolean, locked: Boolean) {
        when (kind) {
            KeyKind.CTRL -> { mods.ctrlActive = active; mods.ctrlLocked = locked }
            KeyKind.ALT -> { mods.altActive = active; mods.altLocked = locked }
            KeyKind.SHIFT -> { mods.shiftActive = active; mods.shiftLocked = locked }
            else -> {}
        }
        invalidateKeys()
    }

    private fun fire(key: Key) {
        when (key.kind) {
            KeyKind.SEND -> {
                host.send(key.send, key.ctrl || mods.ctrlActive, mods.altActive, key.shift || mods.shiftActive)
                // One-shot modifiers are spent by the key they modified; locked ones stay.
                if (!mods.ctrlLocked) mods.ctrlActive = false
                if (!mods.altLocked) mods.altActive = false
                if (!mods.shiftLocked) mods.shiftActive = false
                invalidateKeys()
            }
            KeyKind.CTRL, KeyKind.ALT, KeyKind.SHIFT -> {
                val (a, l) = KeyLayouts.nextModifier(active(key.kind), locked(key.kind))
                setModifier(key.kind, a, l)
            }
            KeyKind.PAGE -> { page = (page + 1) % pages.size; rebuild() }
            KeyKind.ACTION -> host.action(key.send)
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class KeyView(val key: Key) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var down = false
        private var altArmed = false
        private var longFired = false
        private var downY = 0f

        private val repeater = object : Runnable {
            override fun run() {
                fire(key)
                postDelayed(this, KeyLayouts.REPEAT_INTERVAL_MS)
            }
        }
        private val longPress = Runnable {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            when {
                isModifier(key) -> setModifier(key.kind, active = true, locked = true)
                key.kind == KeyKind.PAGE -> { if (page != 0) { page = 0; rebuild() } }
                key.alt != null -> fire(key.alt)
            }
        }

        init {
            isClickable = true
            isFocusable = true
            isHapticFeedbackEnabled = true
            describe()
        }

        private fun describe() {
            contentDescription = when {
                isModifier(key) -> key.name + when {
                    locked(key.kind) -> ", locked"
                    active(key.kind) -> ", on for next key"
                    else -> ", off"
                }
                key.kind == KeyKind.PAGE -> "Key page ${page + 1} of ${pages.size}. Tap for next."
                key.alt != null -> "${key.name}. Swipe up for ${key.alt.name}."
                else -> key.name
            }
        }

        /** Accessibility / keyboard activation: the plain key. */
        override fun performClick(): Boolean {
            super.performClick()
            fire(key)
            return true
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    down = true; altArmed = false; longFired = false; downY = e.y
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (key.repeat) {
                        fire(key)
                        postDelayed(repeater, KeyLayouts.REPEAT_DELAY_MS)
                    } else if (key.alt != null || isModifier(key) || key.kind == KeyKind.PAGE) {
                        postDelayed(longPress, KeyLayouts.REPEAT_DELAY_MS)
                    }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (down && !altArmed && !longFired &&
                    KeyLayouts.pickAlt(key, e.y - downY, height * 0.5f)
                ) {
                    altArmed = true
                    removeCallbacks(repeater); removeCallbacks(longPress)
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(repeater); removeCallbacks(longPress)
                    val wasDown = down
                    down = false
                    // rebuild() (page key) detaches this view; nothing to repaint then.
                    if (wasDown) when {
                        altArmed -> fire(key.alt!!)
                        longFired || key.repeat -> {}
                        else -> fire(key)
                    }
                    altArmed = false
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(repeater); removeCallbacks(longPress)
                    down = false; altArmed = false
                    invalidate()
                }
            }
            return true
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(repeater); removeCallbacks(longPress)
            super.onDetachedFromWindow()
        }

        private fun fitText(text: String, size: Float, maxW: Float) {
            paint.textSize = size
            val w = paint.measureText(text)
            if (w > maxW && w > 0) paint.textSize = size * maxW / w
        }

        override fun onDraw(c: Canvas) {
            describe()
            val on = active(key.kind)
            val lock = locked(key.kind)
            val inset = dp(1f)
            val radius = dp(4f)
            rect.set(inset, inset, width - inset, height - inset)

            val primaryTint = (NC.PRIMARY and 0x00FFFFFF)
            paint.style = Paint.Style.FILL
            paint.color = when {
                lock -> NC.PRIMARY
                on -> primaryTint or 0x40000000
                down -> NC.SURFACE_HIGHEST
                key.send == "ENTER" -> primaryTint or 0x26000000
                key.send in navKeys -> NC.SURFACE_HIGH
                else -> NC.SURFACE_LOW
            }
            c.drawRoundRect(rect, radius, radius, paint)
            if (on || key.send == "ENTER") {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1f)
                paint.color = NC.PRIMARY
                c.drawRoundRect(rect, radius, radius, paint)
                paint.style = Paint.Style.FILL
            }
            if (lock) { // locked = filled + underline, never colour alone
                paint.color = NC.ON_PRIMARY
                c.drawRect(width * 0.25f, height - dp(6f), width * 0.75f, height - dp(4f), paint)
            }

            val shown = if (altArmed) key.alt!! else key
            val fg = when {
                lock -> NC.ON_PRIMARY
                on || altArmed || shown.kind == KeyKind.ACTION -> NC.PRIMARY
                else -> NC.ON_SURFACE
            }
            paint.typeface = Typeface.MONOSPACE
            paint.isFakeBoldText = true
            paint.textAlign = Paint.Align.CENTER
            val cx = width / 2f
            if (key.kind == KeyKind.PAGE) {
                paint.color = NC.PRIMARY
                fitText(KeyLayouts.pageDots(page, pages.size), sp(9f), width - dp(6f))
                c.drawText(KeyLayouts.pageDots(page, pages.size), cx, height / 2f - (paint.ascent() + paint.descent()) / 2, paint)
                return
            }
            paint.color = fg
            val glyph = shown.label.length == 1
            fitText(shown.label, sp(if (glyph) 16f else if (shown.kind == KeyKind.ACTION) 10f else 11f), width - dp(6f))
            c.drawText(shown.label, cx, height / 2f - (paint.ascent() + paint.descent()) / 2 + if (key.alt != null && !altArmed) dp(2f) else 0f, paint)

            if (key.alt != null && !altArmed) { // corner glyph = swipe-up alternate
                paint.isFakeBoldText = false
                paint.color = if (lock) NC.ON_PRIMARY else NC.OUTLINE
                paint.textAlign = Paint.Align.RIGHT
                fitText(key.alt.label, sp(8f), width * 0.5f)
                c.drawText(key.alt.label, width - dp(4f), dp(3f) - paint.ascent(), paint)
            }
        }
    }
}
