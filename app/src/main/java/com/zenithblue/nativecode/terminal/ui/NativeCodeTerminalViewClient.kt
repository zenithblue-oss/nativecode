package com.zenithblue.nativecode.terminal.ui

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Decoupled and cohesive TerminalViewClient implementation for NativeCode terminals.
 * Encapsulates zoom/scale, single tap focus & IME show, modifier keys state, and key events.
 */
class NativeCodeTerminalViewClient(
    private val context: Context,
    private val terminalViewRef: () -> TerminalView?,
    private val modState: TerminalModifierState,
    private val onScaleChanged: ((Float) -> Unit)? = null
) : TerminalViewClient {

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            onScaleChanged?.invoke(scale)
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val tv = terminalViewRef() ?: return
        tv.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(active: Boolean) {}

    override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
    override fun onLongPress(e: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = modState.readCtrl(true)
    override fun readAltKey(): Boolean = modState.readAlt(true)
    override fun readShiftKey(): Boolean = modState.readShift(true)
    override fun readFnKey(): Boolean = modState.readFn(true)

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}

    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}

    override fun onCopyAllTextToClipboard(session: TerminalSession) {
        TerminalScrollViewHelper.copyTranscript(context, session)
    }

    override fun onOpenScrollView(session: TerminalSession) {
        val customTf = try { android.graphics.Typeface.createFromAsset(context.assets, "fonts/font.ttf") } catch (_: Exception) { null }
        TerminalScrollViewHelper.showScrollViewDialog(context, session, customTf)
    }
}
