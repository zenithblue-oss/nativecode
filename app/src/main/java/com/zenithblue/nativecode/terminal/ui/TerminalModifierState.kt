package com.zenithblue.nativecode.terminal.ui

/**
 * Encapsulates terminal virtual and hardware modifier key states (Ctrl, Alt, Shift, Fn).
 */
data class TerminalModifierState(
    var ctrlActive: Boolean = false,
    var ctrlLocked: Boolean = false,
    var altActive: Boolean = false,
    var altLocked: Boolean = false,
    var shiftActive: Boolean = false,
    var shiftLocked: Boolean = false,
    var onStateChanged: (() -> Unit)? = null
) {
    fun readCtrl(autoReadSetFalse: Boolean = true): Boolean {
        if (!ctrlActive) return false
        if (autoReadSetFalse && !ctrlLocked) {
            ctrlActive = false
            onStateChanged?.invoke()
        }
        return true
    }

    fun readAlt(autoReadSetFalse: Boolean = true): Boolean {
        if (!altActive) return false
        if (autoReadSetFalse && !altLocked) {
            altActive = false
            onStateChanged?.invoke()
        }
        return true
    }

    fun readShift(autoReadSetFalse: Boolean = true): Boolean {
        if (!shiftActive) return false
        if (autoReadSetFalse && !shiftLocked) {
            shiftActive = false
            onStateChanged?.invoke()
        }
        return true
    }

    fun readFn(autoReadSetFalse: Boolean = true): Boolean = false
}
