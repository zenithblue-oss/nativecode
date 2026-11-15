package com.zenithblue.nativecode.terminal.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.util.concurrent.Executors
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

/**
 * Decoupled and cohesive TerminalSessionClient implementation for NativeCode terminals.
 * Encapsulates text updates, session finishing lifecycle, clipboard copy/paste, and cursor styling.
 */
class NativeCodeTerminalSessionClient(
    private val context: Context,
    private val terminalViewRef: () -> TerminalView?,
    private val activeSessionRef: () -> TerminalSession?,
    private val onSessionFinishedCallback: ((TerminalSession) -> Unit)? = null,
    /** A loopback dev-server URL newly printed on screen (plan 12 preview chip). Main thread. */
    private val onPreviewUrl: ((String) -> Unit)? = null,
) : TerminalSessionClient {

    /** Last output time per session (tab strip running/idle dot), shared by every client. Main thread only. */
    val lastOutputAt get() = SHARED_LAST_OUTPUT
    private var lastScanAt = 0L

    override fun onTextChanged(session: TerminalSession) {
        val now = android.os.SystemClock.uptimeMillis()
        lastOutputAt[session] = now
        if (session == activeSessionRef()) {
            terminalViewRef()?.onScreenUpdated()
        }
        // ponytail: scans the visible screen at most every 700 ms; a URL scrolled away within that window is missed.
        if (onPreviewUrl != null && now - lastScanAt > 700) {
            lastScanAt = now
            val emu = session.emulator ?: return
            val text = runCatching { emu.screen.getSelectedText(0, 0, emu.mColumns, emu.mRows - 1) }.getOrNull() ?: return
            if (!text.contains("://")) return
            com.zenithblue.nativecode.preview.PortProbe.findUrls(text).forEach { url ->
                val port = com.zenithblue.nativecode.preview.PortProbe.portOf(url) ?: return@forEach
                if (com.zenithblue.nativecode.preview.PortProbe.remember(port)) onPreviewUrl.invoke(url)
            }
        }
    }

    override fun onTitleChanged(session: TerminalSession) {}

    override fun onSessionFinished(session: TerminalSession) {
        Log.d("Terminal", "Session finished: ${session.exitStatus}")
        onSessionFinishedCallback?.invoke(session)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(tr(R.string.term_terminal_selection), text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        val view = terminalViewRef()
        view?.post { view.setTopRow(0) }
        // Never write on the UI thread: session.write blocks when the 4 KB input queue is full, and
        // the echoed output that would drain it needs the UI thread (deadlock -> ANR).
        // Serial: two quick pastes must not interleave (also inside bracketed-paste markers).
        PASTE.execute { session.emulator?.paste(text) ?: session.write(text) }
    }

    companion object {
        private val PASTE: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor()
        val SHARED_LAST_OUTPUT = java.util.WeakHashMap<TerminalSession, Long>()
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = 1

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "Stacktrace", e) }
}
