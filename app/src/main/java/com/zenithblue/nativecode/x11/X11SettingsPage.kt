package com.zenithblue.nativecode.x11

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zenithblue.nativecode.NC
import com.zenithblue.nativecode.R

/**
 * Native X11 Settings page. Every control reads the real Termux:X11
 * SharedPreferences and writes through [X11Prefs.put] (CHANGE_PREFERENCE
 * broadcast), so the stock preferences UI and a running X11 session stay
 * in sync. Call [refresh] when the page becomes visible.
 */
class X11SettingsPage(
    private val ctx: Context,
    private val onBack: () -> Unit
) {
    val view: ScrollView = ScrollView(ctx).apply {
        layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, MATCH)
        visibility = View.GONE
        clipToPadding = false
        setPadding(0, 0, 0, ctx.dp(80))
        setBackgroundColor(NC.BG)
    }

    private val body = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
    }

    init {
        view.addView(body)
        body.addView(header())
        body.addView(outputCard())
        body.addView(windowCard())
        body.addView(touchCard())
        body.addView(keyboardCard())
        body.addView(gesturesCard())
    }

    fun refresh() = refreshRows(body)

    // Header

    private fun header(): LinearLayout {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, ctx.dp(16))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_arrow_back)
            setColorFilter(NC.PRIMARY)
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(4))
            layoutParams = LinearLayout.LayoutParams(ctx.dp(36), ctx.dp(36)).apply { rightMargin = ctx.dp(8) }
            contentDescription = "Back"
            setOnClickListener { onBack() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(NC.SURFACE_CONTAINER)
                setStroke(ctx.dp(1), NC.OUTLINE_VAR)
            }
        }
        val icon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_display)
            setColorFilter(NC.PRIMARY)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)).apply { rightMargin = ctx.dp(10) }
        }
        val title = TextView(ctx).apply {
            text = "X11 SETTINGS"
            textSize = 20f
            setTextColor(NC.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        row.addView(back)
        row.addView(icon)
        row.addView(title)
        val sub = TextView(ctx).apply {
            text = "// DISPLAY · INPUT · GESTURES — LIVE TERMUX:X11 CONFIG"
            textSize = 11f
            setTextColor(NC.ON_SURF_VAR)
            typeface = Typeface.MONOSPACE
            setPadding(0, ctx.dp(4), 0, ctx.dp(12))
        }
        val div = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, ctx.dp(1)).apply { bottomMargin = ctx.dp(8) }
            setBackgroundColor(NC.OUTLINE_VAR)
        }
        col.addView(row)
        col.addView(sub)
        col.addView(div)
        return col
    }

    // Section cards

    private fun outputCard(): LinearLayout = ctx.sectionCard().apply {
        addView(ctx.sectionHeader("Output"))
        val c = ctx
        addView(c.listRow(
            title = "Display resolution mode",
            getSummary = { X11Prefs.getString(c, X11Prefs.DISPLAY_MODE) },
            entries = X11Prefs.DISPLAY_MODES,
            currentValue = { X11Prefs.getString(c, X11Prefs.DISPLAY_MODE) },
            valueOf = { X11Prefs.DISPLAY_MODES[it] },
            indexOf = { X11Prefs.DISPLAY_MODES.indexOf(it) },
            set = { X11Prefs.put(c, X11Prefs.DISPLAY_MODE, it) }
        ))
        addView(c.seekRow(
            title = "Display scale, %",
            min = 30, max = 300, step = 10,
            get = { X11Prefs.getInt(c, X11Prefs.DISPLAY_SCALE) },
            set = { X11Prefs.putInt(c, X11Prefs.DISPLAY_SCALE, it) },
            format = { "$it" }
        ))
        addView(c.listRow(
            title = "Display resolution (exact)",
            getSummary = { X11Prefs.getString(c, X11Prefs.DISPLAY_EXACT) },
            entries = X11Prefs.EXACT_RESOLUTIONS,
            currentValue = { X11Prefs.getString(c, X11Prefs.DISPLAY_EXACT) },
            valueOf = { X11Prefs.EXACT_RESOLUTIONS[it] },
            indexOf = { X11Prefs.EXACT_RESOLUTIONS.indexOf(it) },
            set = { X11Prefs.put(c, X11Prefs.DISPLAY_EXACT, it) }
        ))
        addView(c.textRow(
            title = "Display resolution (custom)",
            summary = X11Prefs.getString(c, X11Prefs.DISPLAY_CUSTOM),
            get = { X11Prefs.getString(c, X11Prefs.DISPLAY_CUSTOM) },
            validate = { X11Prefs.isCustomResolutionValid(it) },
            set = { X11Prefs.put(c, X11Prefs.DISPLAY_CUSTOM, it) },
            errorText = "Use WIDTHxHEIGHT, e.g. 1280x1024"
        ))
        addView(c.listRow(
            title = "Display filtering mode",
            getSummary = { X11Prefs.getString(c, X11Prefs.DISPLAY_FILTERING) },
            entries = X11Prefs.FILTERING_MODES,
            currentValue = { X11Prefs.getString(c, X11Prefs.DISPLAY_FILTERING) },
            valueOf = { X11Prefs.FILTERING_MODES[it] },
            indexOf = { X11Prefs.FILTERING_MODES.indexOf(it) },
            set = { X11Prefs.put(c, X11Prefs.DISPLAY_FILTERING, it) }
        ))
        addView(c.switchRow(
            title = "Adjust the set resolution to fit screen orientation",
            summary = "Requires display resolution mode to be exact or custom",
            get = { X11Prefs.getBool(c, X11Prefs.ADJUST_RESOLUTION) },
            set = { X11Prefs.putBool(c, X11Prefs.ADJUST_RESOLUTION, it) }
        ))
        addView(c.switchRow(
            title = "Stretch to fit display",
            summary = "Requires display resolution mode to be exact or custom",
            get = { X11Prefs.getBool(c, X11Prefs.DISPLAY_STRETCH) },
            set = { X11Prefs.putBool(c, X11Prefs.DISPLAY_STRETCH, it) }
        ))
    }

    private fun windowCard(): LinearLayout = ctx.sectionCard().apply {
        addView(ctx.sectionHeader("Window"))
        val c = ctx
        addView(c.switchRow(
            title = "Reseed screen while soft keyboard is open",
            summary = "The screen size will be adjusted while Soft Keyboard is open.",
            get = { X11Prefs.getBool(c, X11Prefs.RESEED) },
            set = { X11Prefs.putBool(c, X11Prefs.RESEED, it) }
        ))
        addView(c.switchRow(
            title = "PIP mode",
            summary = "Show app in picture-in-picture mode when home button or recents button is pressed",
            get = { X11Prefs.getBool(c, X11Prefs.PIP) },
            set = { X11Prefs.putBool(c, X11Prefs.PIP, it) }
        ))
        addView(c.switchRow(
            title = "Fullscreen",
            summary = "Toggle immersive mode in your device display",
            get = { X11Prefs.getBool(c, X11Prefs.FULLSCREEN) },
            set = { X11Prefs.putBool(c, X11Prefs.FULLSCREEN, it) }
        ))
        addView(c.listRow(
            title = "Screen orientation",
            getSummary = { X11Prefs.getString(c, X11Prefs.FORCE_ORIENTATION) },
            entries = X11Prefs.ORIENTATIONS,
            currentValue = { X11Prefs.getString(c, X11Prefs.FORCE_ORIENTATION) },
            valueOf = { X11Prefs.ORIENTATIONS[it] },
            indexOf = { X11Prefs.ORIENTATIONS.indexOf(it) },
            set = { X11Prefs.put(c, X11Prefs.FORCE_ORIENTATION, it) }
        ))
        addView(c.switchRow(
            title = "Hide display cutout",
            summary = "Hide the display cutout (notch) if any",
            get = { X11Prefs.getBool(c, X11Prefs.HIDE_CUTOUT) },
            set = { X11Prefs.putBool(c, X11Prefs.HIDE_CUTOUT, it) }
        ))
        addView(c.switchRow(
            title = "Keep screen on",
            summary = "Prevent the display from sleeping while the session runs",
            get = { X11Prefs.getBool(c, X11Prefs.KEEP_SCREEN_ON) },
            set = { X11Prefs.putBool(c, X11Prefs.KEEP_SCREEN_ON, it) }
        ))
    }

    private fun touchCard(): LinearLayout = ctx.sectionCard().apply {
        addView(ctx.sectionHeader("Touch & pointer"))
        val c = ctx
        addView(c.listRow(
            title = "Touchscreen input mode",
            getSummary = { X11Prefs.touchLabel(X11Prefs.getString(c, X11Prefs.TOUCH_MODE)) },
            entries = X11Prefs.TOUCH_ENTRIES,
            currentValue = { X11Prefs.getString(c, X11Prefs.TOUCH_MODE) },
            valueOf = { X11Prefs.TOUCH_VALUES[it] },
            indexOf = { X11Prefs.TOUCH_VALUES.indexOf(it) },
            set = { X11Prefs.put(c, X11Prefs.TOUCH_MODE, it) }
        ))
        addView(c.switchRow(
            title = "Apply display scale factor to touchpad",
            summary = "Scale touchpad motion with the display scale",
            get = { X11Prefs.getBool(c, X11Prefs.SCALE_TOUCHPAD) },
            set = { X11Prefs.putBool(c, X11Prefs.SCALE_TOUCHPAD, it) }
        ))
        addView(c.switchRow(
            title = "Show stylus click options",
            summary = "Stylus touch override: left, middle or right click",
            get = { X11Prefs.getBool(c, X11Prefs.STYLUS_CLICK_OVERRIDE) },
            set = { X11Prefs.putBool(c, X11Prefs.STYLUS_CLICK_OVERRIDE, it) }
        ))
        addView(c.switchRow(
            title = "Enable stylus mouse mode",
            summary = "Stylus moves the cursor and sends mouse clicks",
            get = { X11Prefs.getBool(c, X11Prefs.STYLUS_IS_MOUSE) },
            set = { X11Prefs.putBool(c, X11Prefs.STYLUS_IS_MOUSE, it) }
        ))
        addView(c.switchRow(
            title = "Stylus button contact modifier",
            summary = "Send right/middle click only while the stylus button is pressed",
            get = { X11Prefs.getBool(c, X11Prefs.STYLUS_BUTTON_MOD) },
            set = { X11Prefs.putBool(c, X11Prefs.STYLUS_BUTTON_MOD, it) }
        ))
        addView(c.switchRow(
            title = "Show mouse click helper overlay",
            summary = "Onscreen mouse buttons usable with touchpad",
            get = { X11Prefs.getBool(c, X11Prefs.SHOW_MOUSE_HELPER) },
            set = { X11Prefs.putBool(c, X11Prefs.SHOW_MOUSE_HELPER, it) }
        ))
        addView(c.switchRow(
            title = "Capture external pointer devices",
            summary = "Intercept all hardware pointer events when possible",
            get = { X11Prefs.getBool(c, X11Prefs.POINTER_CAPTURE) },
            set = { X11Prefs.putBool(c, X11Prefs.POINTER_CAPTURE, it) }
        ))
        addView(c.listRow(
            title = "Transform captured pointer movements",
            getSummary = { X11Prefs.transformLabel(X11Prefs.getString(c, X11Prefs.TRANSFORM_POINTER)) },
            entries = X11Prefs.TRANSFORM_ENTRIES,
            currentValue = { X11Prefs.getString(c, X11Prefs.TRANSFORM_POINTER) },
            valueOf = { X11Prefs.TRANSFORM_VALUES[it] },
            indexOf = { X11Prefs.TRANSFORM_VALUES.indexOf(it) },
            set = { X11Prefs.put(c, X11Prefs.TRANSFORM_POINTER, it) }
        ))
        addView(c.seekRow(
            title = "Captured pointer speed factor, %",
            min = 1, max = 300, step = 1,
            get = { X11Prefs.getInt(c, X11Prefs.POINTER_SPEED) },
            set = { X11Prefs.putInt(c, X11Prefs.POINTER_SPEED, it) },
            format = { "$it" }
        ))
        addView(c.switchRow(
            title = "Enable tap-to-move for touchpads",
            summary = "Tap gestures move the pointer on touchpads",
            get = { X11Prefs.getBool(c, X11Prefs.TAP_TO_MOVE) },
            set = { X11Prefs.putBool(c, X11Prefs.TAP_TO_MOVE, it) }
        ))
        addView(c.switchRow(
            title = "Ignore gamepad input events",
            summary = "Prevent gamepad and joystick events reaching the X server",
            get = { X11Prefs.getBool(c, X11Prefs.IGNORE_GAMEPAD) },
            set = { X11Prefs.putBool(c, X11Prefs.IGNORE_GAMEPAD, it) }
        ))
    }

    private fun keyboardCard(): LinearLayout = ctx.sectionCard().apply {
        addView(ctx.sectionHeader("Keyboard & extra keys"))
        val c = ctx
        addView(c.switchRow(
            title = "Show additional keyboard",
            summary = "Show keyboard with additional keys (CTRL, ALT, ESC, arrows)",
            get = { X11Prefs.getBool(c, X11Prefs.SHOW_ADDITIONAL_KBD) },
            set = { X11Prefs.putBool(c, X11Prefs.SHOW_ADDITIONAL_KBD, it) }
        ))
        addView(c.switchRow(
            title = "Show IME with external keyboard",
            summary = "Show software keyboard while hardware keyboard is connected",
            get = { X11Prefs.getBool(c, X11Prefs.SHOW_IME_EXTERNAL) },
            set = { X11Prefs.putBool(c, X11Prefs.SHOW_IME_EXTERNAL, it) }
        ))
        addView(c.switchRow(
            title = "Prefer scancodes when possible",
            summary = "Let the X server handle the hardware keyboard layout",
            get = { X11Prefs.getBool(c, X11Prefs.PREFER_SCANCODES) },
            set = { X11Prefs.putBool(c, X11Prefs.PREFER_SCANCODES, it) }
        ))
        addView(c.switchRow(
            title = "Hardware keyboard scancodes workaround",
            summary = "Fixes scancodes on some devices",
            get = { X11Prefs.getBool(c, X11Prefs.HW_KBD_WORKAROUND) },
            set = { X11Prefs.putBool(c, X11Prefs.HW_KBD_WORKAROUND, it) }
        ))
        addView(c.switchRow(
            title = "Intercept system shortcuts (Samsung Dex)",
            summary = "Allows interception of Alt+F4, Meta+D, Meta+E, etc.",
            get = { X11Prefs.getBool(c, X11Prefs.DEX_META_CAPTURE) },
            set = { X11Prefs.putBool(c, X11Prefs.DEX_META_CAPTURE, it) }
        ))
        addView(c.switchRow(
            title = "Enforce char based input",
            summary = "Suppresses suggestions, predictive typing and CJK languages",
            get = { X11Prefs.getBool(c, X11Prefs.ENFORCE_CHAR_INPUT) },
            set = { X11Prefs.putBool(c, X11Prefs.ENFORCE_CHAR_INPUT, it) }
        ))
        addView(c.switchRow(
            title = "Clipboard sharing",
            summary = "Share the clipboard between Android and the X session",
            get = { X11Prefs.getBool(c, X11Prefs.CLIPBOARD) },
            set = { X11Prefs.putBool(c, X11Prefs.CLIPBOARD, it) }
        ))
        addView(c.switchRow(
            title = "Adjust display height for extra keys bar",
            summary = "May cause screen flickering during toggling",
            get = { X11Prefs.getBool(c, X11Prefs.ADJUST_HEIGHT_EK) },
            set = { X11Prefs.putBool(c, X11Prefs.ADJUST_HEIGHT_EK, it) }
        ))
        addView(c.switchRow(
            title = "Deactivate special keys after each keypress",
            summary = "Use long-tap to lock special keys",
            get = { X11Prefs.getBool(c, X11Prefs.TERMUX_EK_BEHAVIOUR) },
            set = { X11Prefs.putBool(c, X11Prefs.TERMUX_EK_BEHAVIOUR, it) }
        ))
        addView(c.seekRow(
            title = "Opacity of extra keys bar, %",
            min = 1, max = 100, step = 1,
            get = { X11Prefs.getInt(c, X11Prefs.OPACITY_EK_BAR) },
            set = { X11Prefs.putInt(c, X11Prefs.OPACITY_EK_BAR, it) },
            format = { "$it" }
        ))
    }

    private fun actionRow(
        key: String,
        title: String,
        summary: String,
        options: Array<String> = X11Prefs.USER_ACTIONS
    ): LinearLayout {
        val c = ctx
        return c.listRow(
            title = title,
            getSummary = { X11Prefs.getString(c, key) },
            entries = options,
            currentValue = { X11Prefs.getString(c, key) },
            valueOf = { options[it] },
            indexOf = { options.indexOf(it) },
            set = { X11Prefs.put(c, key, it) }
        )
    }

    private fun gesturesCard(): LinearLayout = ctx.sectionCard().apply {
        addView(ctx.sectionHeader("Gestures & actions"))
        addView(actionRow(X11Prefs.SWIPE_UP, "Three finger swipe up", "Action triggered by swiping up with three fingers"))
        addView(actionRow(X11Prefs.SWIPE_DOWN, "Three finger swipe down", "Action triggered by swiping down with three fingers"))
        addView(actionRow(X11Prefs.VOLUME_UP, "Volume up", "Action for the volume up key", X11Prefs.VOLUME_UP_ACTIONS))
        addView(actionRow(X11Prefs.VOLUME_DOWN, "Volume down", "Action for the volume down key", X11Prefs.VOLUME_DOWN_ACTIONS))
        addView(actionRow(X11Prefs.BACK_BUTTON, "Back button", "Response to the system back button"))
        addView(actionRow(X11Prefs.MEDIA_KEYS, "Media keys", "Response to media key events", X11Prefs.MEDIA_KEY_ACTIONS))
        addView(actionRow(X11Prefs.NOTIF_TAP, "Notification tap", "Action when the session notification is tapped"))
        addView(actionRow(X11Prefs.NOTIF_BTN0, "Notification first button", "Action for the first notification button"))
        addView(actionRow(X11Prefs.NOTIF_BTN1, "Notification second button", "Action for the second notification button"))
    }
}
