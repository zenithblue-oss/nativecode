package com.zenithblue.nativecode.x11

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * NativeCode-owned view of the real Termux:X11 preferences.
 *
 * Storage SSOT is the app's default SharedPreferences — the same file that
 * `com.termux.x11.Prefs` (PrefsProto) reads. Defaults below mirror
 * `termux-x11/.../Prefs.java` so the native page never diverges.
 *
 * Writes go through the sanctioned `com.termux.x11.CHANGE_PREFERENCE`
 * broadcast (see `LoriePreferences$Receiver`), which validates, commits,
 * and re-broadcasts `ACTION_PREFERENCES_CHANGED` so a running X11 session
 * and the stock preferences UI stay in sync. No direct SharedPreferences
 * writes — the receiver owns validation (e.g. custom resolution format).
 */
object X11Prefs {

    const val ACTION_CHANGE = "com.termux.x11.CHANGE_PREFERENCE"
    const val ACTION_CHANGED = "com.termux.x11.ACTION_PREFERENCES_CHANGED"

    /** Page id for MainActivity navigation (must match ID_X11_SETTINGS there). */
    const val PAGE_ID = 25
    /** MainActivity settings-hub page id (ID_SETTINGS). */
    const val APP_SETTINGS_PAGE_ID = 5
    /** Intent extra consumed by MainActivity.handleIntent for deep links. */
    const val EXTRA_TARGET_PAGE = "EXTRA_TARGET_PAGE"

    // Output / display
    const val DISPLAY_MODE = "displayResolutionMode" // native|scaled|exact|custom
    const val DISPLAY_SCALE = "displayScale" // 30..300, default 120
    const val DISPLAY_EXACT = "displayResolutionExact"
    const val DISPLAY_CUSTOM = "displayResolutionCustom"
    const val DISPLAY_FILTERING = "displayFilteringMode" // bilinear|nearest
    const val ADJUST_RESOLUTION = "adjustResolution"
    const val DISPLAY_STRETCH = "displayStretch"
    const val RESEED = "Reseed"
    const val PIP = "PIP"
    const val FULLSCREEN = "fullscreen"
    const val FORCE_ORIENTATION = "forceOrientation" // auto|portrait|landscape|...
    const val HIDE_CUTOUT = "hideCutout"
    const val KEEP_SCREEN_ON = "keepScreenOn"

    // Touch & pointer
    const val TOUCH_MODE = "touchMode" // 1|2|3
    const val SCALE_TOUCHPAD = "scaleTouchpad"
    const val STYLUS_CLICK_OVERRIDE = "showStylusClickOverride"
    const val STYLUS_IS_MOUSE = "stylusIsMouse"
    const val STYLUS_BUTTON_MOD = "stylusButtonContactModifierMode"
    const val SHOW_MOUSE_HELPER = "showMouseHelper"
    const val POINTER_CAPTURE = "pointerCapture"
    const val TRANSFORM_POINTER = "transformCapturedPointer" // no|c|cc|ud|at
    const val POINTER_SPEED = "capturedPointerSpeedFactor" // 1..300
    const val TAP_TO_MOVE = "tapToMove"
    const val IGNORE_GAMEPAD = "ignoreGamepadEvents"

    // Keyboard & extra keys
    const val SHOW_ADDITIONAL_KBD = "showAdditionalKbd"
    const val SHOW_IME_EXTERNAL = "showIMEWhileExternalConnected"
    const val PREFER_SCANCODES = "preferScancodes"
    const val HW_KBD_WORKAROUND = "hardwareKbdScancodesWorkaround"
    const val DEX_META_CAPTURE = "dexMetaKeyCapture"
    const val ENFORCE_CHAR_INPUT = "enforceCharBasedInput"
    const val CLIPBOARD = "clipboardEnable"
    const val ADJUST_HEIGHT_EK = "adjustHeightForEK"
    const val TERMUX_EK_BEHAVIOUR = "useTermuxEKBarBehaviour"
    const val OPACITY_EK_BAR = "opacityEKBar" // 1..100

    // Gestures & actions
    const val SWIPE_UP = "swipeUpAction"
    const val SWIPE_DOWN = "swipeDownAction"
    const val VOLUME_UP = "volumeUpAction"
    const val VOLUME_DOWN = "volumeDownAction"
    const val BACK_BUTTON = "backButtonAction"
    const val NOTIF_TAP = "notificationTapAction"
    const val NOTIF_BTN0 = "notificationButton0Action"
    const val NOTIF_BTN1 = "notificationButton1Action"
    const val MEDIA_KEYS = "mediaKeysAction"

    val DISPLAY_MODES = arrayOf("native", "scaled", "exact", "custom")
    val FILTERING_MODES = arrayOf("bilinear", "nearest")
    val ORIENTATIONS = arrayOf("auto", "portrait", "landscape", "reverse portrait", "reverse landscape")
    val TOUCH_ENTRIES = arrayOf("Trackpad", "Simulated touchscreen", "Direct touch")
    val TOUCH_VALUES = arrayOf("1", "2", "3")
    val TRANSFORM_ENTRIES = arrayOf("No", "Clockwise", "Counter clockwise", "Upside down", "Automatic (for touchpad)")
    val TRANSFORM_VALUES = arrayOf("no", "c", "cc", "ud", "at")
    val USER_ACTIONS = arrayOf(
        "no action", "return to app", "toggle soft keyboard",
        "toggle additional key bar", "open preferences",
        "release pointer and keyboard capture", "toggle fullscreen",
        "restart activity", "exit"
    )
    val VOLUME_UP_ACTIONS = USER_ACTIONS + "send volume up"
    val VOLUME_DOWN_ACTIONS = USER_ACTIONS + "send volume down"
    val MEDIA_KEY_ACTIONS = USER_ACTIONS + "send media action"

    val EXACT_RESOLUTIONS = arrayOf(
        "320x200", "320x240", "352x288", "400x240", "640x350", "640x480",
        "800x480", "800x600", "1024x768", "1280x720", "1280x960", "1280x1024",
        "1400x1050", "1600x1024", "1600x1200", "1366x768", "1680x1050",
        "1920x1080", "1920x1200", "2048x1536", "2560x1600", "2560x2048",
        "3840x2160", "7680x4320"
    )

    val BOOL_DEFAULTS = mapOf(
        ADJUST_RESOLUTION to false, DISPLAY_STRETCH to false, RESEED to true,
        PIP to false, FULLSCREEN to true, HIDE_CUTOUT to true, KEEP_SCREEN_ON to true,
        SCALE_TOUCHPAD to true, STYLUS_CLICK_OVERRIDE to false, STYLUS_IS_MOUSE to false,
        STYLUS_BUTTON_MOD to false, SHOW_MOUSE_HELPER to false, POINTER_CAPTURE to false,
        TAP_TO_MOVE to false, IGNORE_GAMEPAD to false, SHOW_ADDITIONAL_KBD to false,
        SHOW_IME_EXTERNAL to true, PREFER_SCANCODES to false, HW_KBD_WORKAROUND to true,
        DEX_META_CAPTURE to false, ENFORCE_CHAR_INPUT to false, CLIPBOARD to true,
        ADJUST_HEIGHT_EK to true, TERMUX_EK_BEHAVIOUR to false
    )

    val STRING_DEFAULTS = mapOf(
        DISPLAY_MODE to "native", DISPLAY_EXACT to "1280x1024",
        DISPLAY_CUSTOM to "1280x1024", DISPLAY_FILTERING to "nearest",
        FORCE_ORIENTATION to "auto", TOUCH_MODE to "3", TRANSFORM_POINTER to "no",
        SWIPE_UP to "no action", SWIPE_DOWN to "toggle additional key bar",
        VOLUME_UP to "no action", VOLUME_DOWN to "no action",
        BACK_BUTTON to "return to app", NOTIF_TAP to "open preferences",
        NOTIF_BTN0 to "open preferences", NOTIF_BTN1 to "exit",
        MEDIA_KEYS to "no action"
    )

    val INT_DEFAULTS = mapOf(
        DISPLAY_SCALE to 120, POINTER_SPEED to 100, OPACITY_EK_BAR to 100
    )

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("${ctx.packageName}_preferences", Context.MODE_PRIVATE)

    fun getBool(ctx: Context, key: String): Boolean {
        val sp = prefs(ctx)
        if (key == FULLSCREEN || key == HIDE_CUTOUT) {
            if (!sp.contains(key)) {
                sp.edit().putBoolean(key, true).apply()
                return true
            }
        }
        return sp.getBoolean(key, BOOL_DEFAULTS[key] ?: false)
    }

    fun getString(ctx: Context, key: String): String {
        val sp = prefs(ctx)
        if (key == DISPLAY_MODE) {
            val v = sp.getString(key, null)
            if (v == null || v == "scaled") {
                sp.edit().putString(key, "native").apply()
                return "native"
            }
        }
        if (key == TOUCH_MODE) {
            val v = sp.getString(key, null)
            if (v == null || v == "1") {
                sp.edit().putString(key, "3").apply()
                return "3"
            }
        }
        return sp.getString(key, STRING_DEFAULTS[key]) ?: (STRING_DEFAULTS[key] ?: "")
    }

    fun getInt(ctx: Context, key: String): Int =
        prefs(ctx).getInt(key, INT_DEFAULTS[key] ?: 0)

    /**
     * Write one preference both synchronously to SharedPreferences and through
     * the X11 broadcast receiver so the UI, disk, and live session stay in sync.
     */
    fun put(ctx: Context, key: String, value: String) {
        val sp = prefs(ctx)
        val editor = sp.edit()
        when (key) {
            in BOOL_DEFAULTS.keys -> editor.putBoolean(key, value.toBoolean())
            in INT_DEFAULTS.keys -> editor.putInt(key, value.toIntOrNull() ?: INT_DEFAULTS[key] ?: 0)
            else -> editor.putString(key, value)
        }
        editor.commit()

        val intent = Intent(ACTION_CHANGE).apply {
            setPackage(ctx.packageName)
            putExtra(key, value)
        }
        ctx.sendBroadcast(intent)
    }

    fun putBool(ctx: Context, key: String, value: Boolean) =
        put(ctx, key, if (value) "true" else "false")

    fun putInt(ctx: Context, key: String, value: Int) =
        put(ctx, key, value.toString())

    fun touchLabel(value: String): String {
        val i = TOUCH_VALUES.indexOf(value)
        return if (i >= 0) TOUCH_ENTRIES[i] else value
    }

    fun transformLabel(value: String): String {
        val i = TRANSFORM_VALUES.indexOf(value)
        return if (i >= 0) TRANSFORM_ENTRIES[i] else value
    }

    fun isCustomResolutionValid(v: String): Boolean {
        val parts = v.split("x")
        if (parts.size != 2) return false
        val w = parts[0].toIntOrNull() ?: return false
        val h = parts[1].toIntOrNull() ?: return false
        return w > 0 && h > 0
    }
}
