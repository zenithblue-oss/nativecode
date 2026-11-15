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
import com.zenithblue.nativecode.tr

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

    // Conditionally visible rows depending on selected options
    private var displayScaleRow: View? = null
    private var displayExactRow: View? = null
    private var displayCustomRow: View? = null
    private var adjustResolutionRow: View? = null
    private var displayStretchRow: View? = null

    private var scaleTouchpadRow: View? = null
    private var mouseHelperRow: View? = null
    private var tapToMoveRow: View? = null
    private var stylusButtonModRow: View? = null
    private var transformPointerRow: View? = null
    private var pointerSpeedRow: View? = null

    private var adjustHeightEkRow: View? = null
    private var termuxEkBehaviourRow: View? = null
    private var opacityEkRow: View? = null

    init {
        view.addView(body)
        body.addView(header())
        body.addView(outputCard())
        body.addView(windowCard())
        body.addView(touchCard())
        body.addView(keyboardCard())
        body.addView(gesturesCard())
        updateVisibility()
    }

    fun refresh() {
        refreshRows(body)
        updateVisibility()
    }

    private fun updateVisibility() {
        val mode = X11Prefs.getString(ctx, X11Prefs.DISPLAY_MODE)
        displayScaleRow?.visibility = if (mode == "scaled") View.VISIBLE else View.GONE
        displayExactRow?.visibility = if (mode == "exact") View.VISIBLE else View.GONE
        displayCustomRow?.visibility = if (mode == "custom") View.VISIBLE else View.GONE
        val stretchAllowed = mode == "exact" || mode == "custom"
        adjustResolutionRow?.visibility = if (stretchAllowed) View.VISIBLE else View.GONE
        displayStretchRow?.visibility = if (stretchAllowed) View.VISIBLE else View.GONE

        val touch = X11Prefs.getString(ctx, X11Prefs.TOUCH_MODE)
        val isTrackpad = touch == "1"
        scaleTouchpadRow?.visibility = if (isTrackpad && mode != "native") View.VISIBLE else View.GONE
        mouseHelperRow?.visibility = if (isTrackpad) View.VISIBLE else View.GONE
        tapToMoveRow?.visibility = if (isTrackpad) View.VISIBLE else View.GONE

        val stylusOverride = X11Prefs.getBool(ctx, X11Prefs.STYLUS_CLICK_OVERRIDE)
        stylusButtonModRow?.visibility = if (stylusOverride) View.VISIBLE else View.GONE

        val pointerCapture = X11Prefs.getBool(ctx, X11Prefs.POINTER_CAPTURE)
        transformPointerRow?.visibility = if (pointerCapture) View.VISIBLE else View.GONE
        pointerSpeedRow?.visibility = if (pointerCapture) View.VISIBLE else View.GONE

        val showEk = X11Prefs.getBool(ctx, X11Prefs.SHOW_ADDITIONAL_KBD)
        adjustHeightEkRow?.visibility = if (showEk) View.VISIBLE else View.GONE
        termuxEkBehaviourRow?.visibility = if (showEk) View.VISIBLE else View.GONE
        opacityEkRow?.visibility = if (showEk) View.VISIBLE else View.GONE
    }

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
            text = tr(R.string.x11_x11_settings)
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
        addView(ctx.sectionHeader(tr(R.string.x11_output)))
        val c = ctx
        addView(c.listRow(
            title = tr(R.string.x11_display_resolution_mode),
            getSummary = { X11Prefs.getString(c, X11Prefs.DISPLAY_MODE) },
            entries = X11Prefs.DISPLAY_MODES,
            currentValue = { X11Prefs.getString(c, X11Prefs.DISPLAY_MODE) },
            valueOf = { X11Prefs.DISPLAY_MODES[it] },
            indexOf = { X11Prefs.DISPLAY_MODES.indexOf(it) },
            set = {
                X11Prefs.put(c, X11Prefs.DISPLAY_MODE, it)
                updateVisibility()
            }
        ))
        displayScaleRow = c.seekRow(
            title = tr(R.string.x11_display_scale),
            min = 30, max = 300, step = 10,
            get = { X11Prefs.getInt(c, X11Prefs.DISPLAY_SCALE) },
            set = { X11Prefs.putInt(c, X11Prefs.DISPLAY_SCALE, it) },
            format = { "$it" }
        ).also { addView(it) }
        displayExactRow = c.listRow(
            title = tr(R.string.x11_display_resolution_exact),
            getSummary = { X11Prefs.getString(c, X11Prefs.DISPLAY_EXACT) },
            entries = X11Prefs.EXACT_RESOLUTIONS,
            currentValue = { X11Prefs.getString(c, X11Prefs.DISPLAY_EXACT) },
            valueOf = { X11Prefs.EXACT_RESOLUTIONS[it] },
            indexOf = { X11Prefs.EXACT_RESOLUTIONS.indexOf(it) },
            set = { X11Prefs.put(c, X11Prefs.DISPLAY_EXACT, it) }
        ).also { addView(it) }
        displayCustomRow = c.textRow(
            title = tr(R.string.x11_display_resolution_custom),
            summary = X11Prefs.getString(c, X11Prefs.DISPLAY_CUSTOM),
            get = { X11Prefs.getString(c, X11Prefs.DISPLAY_CUSTOM) },
            validate = { X11Prefs.isCustomResolutionValid(it) },
            set = { X11Prefs.put(c, X11Prefs.DISPLAY_CUSTOM, it) },
            errorText = tr(R.string.x11_use_widthxheight_e_g_1280x1024)
        ).also { addView(it) }
        addView(c.listRow(
            title = tr(R.string.x11_display_filtering_mode),
            getSummary = { X11Prefs.getString(c, X11Prefs.DISPLAY_FILTERING) },
            entries = X11Prefs.FILTERING_MODES,
            currentValue = { X11Prefs.getString(c, X11Prefs.DISPLAY_FILTERING) },
            valueOf = { X11Prefs.FILTERING_MODES[it] },
            indexOf = { X11Prefs.FILTERING_MODES.indexOf(it) },
            set = { X11Prefs.put(c, X11Prefs.DISPLAY_FILTERING, it) }
        ))
        adjustResolutionRow = c.switchRow(
            title = tr(R.string.x11_adjust_the_set_resolution_to),
            summary = tr(R.string.x11_requires_display_resolution_mode_to),
            get = { X11Prefs.getBool(c, X11Prefs.ADJUST_RESOLUTION) },
            set = { X11Prefs.putBool(c, X11Prefs.ADJUST_RESOLUTION, it) }
        ).also { addView(it) }
        displayStretchRow = c.switchRow(
            title = tr(R.string.x11_stretch_to_fit_display),
            summary = tr(R.string.x11_requires_display_resolution_mode_to),
            get = { X11Prefs.getBool(c, X11Prefs.DISPLAY_STRETCH) },
            set = { X11Prefs.putBool(c, X11Prefs.DISPLAY_STRETCH, it) }
        ).also { addView(it) }
    }

    private fun windowCard(): LinearLayout = ctx.sectionCard().apply {
        addView(ctx.sectionHeader(tr(R.string.x11_window)))
        val c = ctx
        addView(c.switchRow(
            title = tr(R.string.x11_reseed_screen_while_soft_keyboard),
            summary = tr(R.string.x11_the_screen_size_will_be),
            get = { X11Prefs.getBool(c, X11Prefs.RESEED) },
            set = { X11Prefs.putBool(c, X11Prefs.RESEED, it) }
        ))
        addView(c.switchRow(
            title = tr(R.string.x11_pip_mode),
            summary = tr(R.string.x11_show_app_in_picture_in),
            get = { X11Prefs.getBool(c, X11Prefs.PIP) },
            set = { X11Prefs.putBool(c, X11Prefs.PIP, it) }
        ))
        addView(c.switchRow(
            title = tr(R.string.x11_fullscreen),
            summary = tr(R.string.x11_toggle_immersive_mode_in_your),
            get = { X11Prefs.getBool(c, X11Prefs.FULLSCREEN) },
            set = { X11Prefs.putBool(c, X11Prefs.FULLSCREEN, it) }
        ))
        addView(c.listRow(
            title = tr(R.string.x11_screen_orientation),
            getSummary = { X11Prefs.getString(c, X11Prefs.FORCE_ORIENTATION) },
            entries = X11Prefs.ORIENTATIONS,
            currentValue = { X11Prefs.getString(c, X11Prefs.FORCE_ORIENTATION) },
            valueOf = { X11Prefs.ORIENTATIONS[it] },
            indexOf = { X11Prefs.ORIENTATIONS.indexOf(it) },
            set = { X11Prefs.put(c, X11Prefs.FORCE_ORIENTATION, it) }
        ))
        addView(c.switchRow(
            title = tr(R.string.x11_hide_display_cutout),
            summary = tr(R.string.x11_hide_the_display_cutout_notch),
            get = { X11Prefs.getBool(c, X11Prefs.HIDE_CUTOUT) },
            set = { X11Prefs.putBool(c, X11Prefs.HIDE_CUTOUT, it) }
        ))
        addView(c.switchRow(
            title = tr(R.string.x11_keep_screen_on),
            summary = tr(R.string.x11_prevent_the_display_from_sleeping),
            get = { X11Prefs.getBool(c, X11Prefs.KEEP_SCREEN_ON) },
            set = { X11Prefs.putBool(c, X11Prefs.KEEP_SCREEN_ON, it) }
        ))
    }

    private fun touchCard(): LinearLayout = ctx.sectionCard().apply {
        addView(ctx.sectionHeader(tr(R.string.x11_touch_pointer)))
        val c = ctx
        addView(c.listRow(
            title = tr(R.string.x11_touchscreen_input_mode),
            getSummary = { X11Prefs.touchLabel(X11Prefs.getString(c, X11Prefs.TOUCH_MODE)) },
            entries = X11Prefs.TOUCH_ENTRIES,
            currentValue = { X11Prefs.getString(c, X11Prefs.TOUCH_MODE) },
            valueOf = { X11Prefs.TOUCH_VALUES[it] },
            indexOf = { X11Prefs.TOUCH_VALUES.indexOf(it) },
            set = {
                X11Prefs.put(c, X11Prefs.TOUCH_MODE, it)
                updateVisibility()
            }
        ))
        scaleTouchpadRow = c.switchRow(
            title = tr(R.string.x11_apply_display_scale_factor_to),
            summary = tr(R.string.x11_scale_touchpad_motion_with_the),
            get = { X11Prefs.getBool(c, X11Prefs.SCALE_TOUCHPAD) },
            set = { X11Prefs.putBool(c, X11Prefs.SCALE_TOUCHPAD, it) }
        ).also { addView(it) }
        addView(c.switchRow(
            title = tr(R.string.x11_show_stylus_click_options),
            summary = tr(R.string.x11_stylus_touch_override_left_middle),
            get = { X11Prefs.getBool(c, X11Prefs.STYLUS_CLICK_OVERRIDE) },
            set = {
                X11Prefs.putBool(c, X11Prefs.STYLUS_CLICK_OVERRIDE, it)
                updateVisibility()
            }
        ))
        addView(c.switchRow(
            title = tr(R.string.x11_enable_stylus_mouse_mode),
            summary = tr(R.string.x11_stylus_moves_the_cursor_and),
            get = { X11Prefs.getBool(c, X11Prefs.STYLUS_IS_MOUSE) },
            set = { X11Prefs.putBool(c, X11Prefs.STYLUS_IS_MOUSE, it) }
        ))
        stylusButtonModRow = c.switchRow(
            title = tr(R.string.x11_stylus_button_contact_modifier),
            summary = tr(R.string.x11_send_right_middle_click_only),
            get = { X11Prefs.getBool(c, X11Prefs.STYLUS_BUTTON_MOD) },
            set = { X11Prefs.putBool(c, X11Prefs.STYLUS_BUTTON_MOD, it) }
        ).also { addView(it) }
        mouseHelperRow = c.switchRow(
            title = tr(R.string.x11_show_mouse_click_helper_overlay),
            summary = tr(R.string.x11_onscreen_mouse_buttons_usable_with),
            get = { X11Prefs.getBool(c, X11Prefs.SHOW_MOUSE_HELPER) },
            set = { X11Prefs.putBool(c, X11Prefs.SHOW_MOUSE_HELPER, it) }
        ).also { addView(it) }
        addView(c.switchRow(
            title = tr(R.string.x11_capture_external_pointer_devices),
            summary = tr(R.string.x11_intercept_all_hardware_pointer_event),
            get = { X11Prefs.getBool(c, X11Prefs.POINTER_CAPTURE) },
            set = {
                X11Prefs.putBool(c, X11Prefs.POINTER_CAPTURE, it)
                updateVisibility()
            }
        ))
        transformPointerRow = c.listRow(
            title = tr(R.string.x11_transform_captured_pointer_movements),
            getSummary = { X11Prefs.transformLabel(X11Prefs.getString(c, X11Prefs.TRANSFORM_POINTER)) },
            entries = X11Prefs.TRANSFORM_ENTRIES,
            currentValue = { X11Prefs.getString(c, X11Prefs.TRANSFORM_POINTER) },
            valueOf = { X11Prefs.TRANSFORM_VALUES[it] },
            indexOf = { X11Prefs.TRANSFORM_VALUES.indexOf(it) },
            set = { X11Prefs.put(c, X11Prefs.TRANSFORM_POINTER, it) }
        ).also { addView(it) }
        pointerSpeedRow = c.seekRow(
            title = tr(R.string.x11_captured_pointer_speed_factor),
            min = 1, max = 300, step = 1,
            get = { X11Prefs.getInt(c, X11Prefs.POINTER_SPEED) },
            set = { X11Prefs.putInt(c, X11Prefs.POINTER_SPEED, it) },
            format = { "$it" }
        ).also { addView(it) }
        tapToMoveRow = c.switchRow(
            title = tr(R.string.x11_enable_tap_to_move_for),
            summary = tr(R.string.x11_tap_gestures_move_the_pointer),
            get = { X11Prefs.getBool(c, X11Prefs.TAP_TO_MOVE) },
            set = { X11Prefs.putBool(c, X11Prefs.TAP_TO_MOVE, it) }
        ).also { addView(it) }
        addView(c.switchRow(
            title = tr(R.string.x11_ignore_gamepad_input_events),
            summary = tr(R.string.x11_prevent_gamepad_and_joystick_events),
            get = { X11Prefs.getBool(c, X11Prefs.IGNORE_GAMEPAD) },
            set = { X11Prefs.putBool(c, X11Prefs.IGNORE_GAMEPAD, it) }
        ))
    }

    private fun keyboardCard(): LinearLayout = ctx.sectionCard().apply {
        addView(ctx.sectionHeader(tr(R.string.x11_keyboard_extra_keys)))
        val c = ctx
        addView(c.switchRow(
            title = tr(R.string.x11_show_additional_keyboard),
            summary = tr(R.string.x11_show_keyboard_with_additional_keys),
            get = { X11Prefs.getBool(c, X11Prefs.SHOW_ADDITIONAL_KBD) },
            set = {
                X11Prefs.putBool(c, X11Prefs.SHOW_ADDITIONAL_KBD, it)
                updateVisibility()
            }
        ))
        addView(c.switchRow(
            title = tr(R.string.x11_show_ime_with_external_keyboard),
            summary = tr(R.string.x11_show_software_keyboard_while_hardwar),
            get = { X11Prefs.getBool(c, X11Prefs.SHOW_IME_EXTERNAL) },
            set = { X11Prefs.putBool(c, X11Prefs.SHOW_IME_EXTERNAL, it) }
        ))
        addView(c.switchRow(
            title = tr(R.string.x11_prefer_scancodes_when_possible),
            summary = tr(R.string.x11_let_the_x_server_handle),
            get = { X11Prefs.getBool(c, X11Prefs.PREFER_SCANCODES) },
            set = { X11Prefs.putBool(c, X11Prefs.PREFER_SCANCODES, it) }
        ))
        addView(c.switchRow(
            title = tr(R.string.x11_hardware_keyboard_scancodes_workarou),
            summary = tr(R.string.x11_fixes_scancodes_on_some_devices),
            get = { X11Prefs.getBool(c, X11Prefs.HW_KBD_WORKAROUND) },
            set = { X11Prefs.putBool(c, X11Prefs.HW_KBD_WORKAROUND, it) }
        ))
        addView(c.switchRow(
            title = tr(R.string.x11_intercept_system_shortcuts_samsung_d),
            summary = tr(R.string.x11_allows_interception_of_alt_f4),
            get = { X11Prefs.getBool(c, X11Prefs.DEX_META_CAPTURE) },
            set = { X11Prefs.putBool(c, X11Prefs.DEX_META_CAPTURE, it) }
        ))
        addView(c.switchRow(
            title = tr(R.string.x11_enforce_char_based_input),
            summary = tr(R.string.x11_suppresses_suggestions_predictive_ty),
            get = { X11Prefs.getBool(c, X11Prefs.ENFORCE_CHAR_INPUT) },
            set = { X11Prefs.putBool(c, X11Prefs.ENFORCE_CHAR_INPUT, it) }
        ))
        addView(c.switchRow(
            title = tr(R.string.x11_clipboard_sharing),
            summary = tr(R.string.x11_share_the_clipboard_between_android),
            get = { X11Prefs.getBool(c, X11Prefs.CLIPBOARD) },
            set = { X11Prefs.putBool(c, X11Prefs.CLIPBOARD, it) }
        ))
        adjustHeightEkRow = c.switchRow(
            title = tr(R.string.x11_adjust_display_height_for_extra),
            summary = tr(R.string.x11_may_cause_screen_flickering_during),
            get = { X11Prefs.getBool(c, X11Prefs.ADJUST_HEIGHT_EK) },
            set = { X11Prefs.putBool(c, X11Prefs.ADJUST_HEIGHT_EK, it) }
        ).also { addView(it) }
        termuxEkBehaviourRow = c.switchRow(
            title = tr(R.string.x11_deactivate_special_keys_after_each),
            summary = tr(R.string.x11_use_long_tap_to_lock),
            get = { X11Prefs.getBool(c, X11Prefs.TERMUX_EK_BEHAVIOUR) },
            set = { X11Prefs.putBool(c, X11Prefs.TERMUX_EK_BEHAVIOUR, it) }
        ).also { addView(it) }
        opacityEkRow = c.seekRow(
            title = tr(R.string.x11_opacity_of_extra_keys_bar),
            min = 1, max = 100, step = 1,
            get = { X11Prefs.getInt(c, X11Prefs.OPACITY_EK_BAR) },
            set = { X11Prefs.putInt(c, X11Prefs.OPACITY_EK_BAR, it) },
            format = { "$it" }
        ).also { addView(it) }
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
        addView(ctx.sectionHeader(tr(R.string.x11_gestures_actions)))
        addView(actionRow(X11Prefs.SWIPE_UP, tr(R.string.x11_three_finger_swipe_up), tr(R.string.x11_action_triggered_by_swiping_up)))
        addView(actionRow(X11Prefs.SWIPE_DOWN, tr(R.string.x11_three_finger_swipe_down), tr(R.string.x11_action_triggered_by_swiping_down)))
        addView(actionRow(X11Prefs.VOLUME_UP, tr(R.string.x11_volume_up), tr(R.string.x11_action_for_the_volume_up), X11Prefs.VOLUME_UP_ACTIONS))
        addView(actionRow(X11Prefs.VOLUME_DOWN, tr(R.string.x11_volume_down), tr(R.string.x11_action_for_the_volume_down), X11Prefs.VOLUME_DOWN_ACTIONS))
        addView(actionRow(X11Prefs.BACK_BUTTON, tr(R.string.x11_back_button), tr(R.string.x11_response_to_the_system_back)))
        addView(actionRow(X11Prefs.MEDIA_KEYS, tr(R.string.x11_media_keys), tr(R.string.x11_response_to_media_key_events), X11Prefs.MEDIA_KEY_ACTIONS))
        addView(actionRow(X11Prefs.NOTIF_TAP, tr(R.string.x11_notification_tap), tr(R.string.x11_action_when_the_session_notification)))
        addView(actionRow(X11Prefs.NOTIF_BTN0, tr(R.string.x11_notification_first_button), tr(R.string.x11_action_for_the_first_notification)))
        addView(actionRow(X11Prefs.NOTIF_BTN1, tr(R.string.x11_notification_second_button), tr(R.string.x11_action_for_the_second_notification)))
    }
}
