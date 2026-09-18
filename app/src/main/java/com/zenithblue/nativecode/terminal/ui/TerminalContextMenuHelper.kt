package com.zenithblue.nativecode.terminal.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.ContextMenu
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/**
 * Decoupled context menu helper for NativeCode terminal views.
 * Handles populating menu items and dispatching actions:
 * - Paste
 * - Copy Selection
 * - Copy All (Transcript)
 * - Select All
 * - Open in ScrollView (Dedicated CLI Tools / TUI ScrollView)
 * - Reset Terminal
 */
object TerminalContextMenuHelper {

    const val MENU_PASTE = 101
    const val MENU_COPY_SELECTION = 102
    const val MENU_COPY_TRANSCRIPT = 103
    const val MENU_SELECT_ALL = 104
    const val MENU_OPEN_SCROLLVIEW = 105
    const val MENU_RESET = 106

    fun onCreateContextMenu(
        context: Context,
        menu: ContextMenu,
        v: View
    ) {
        val tv = v as? TerminalView ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val selectedText = tv.selectedText ?: tv.storedSelectedText

        if (!selectedText.isNullOrEmpty()) {
            menu.add(Menu.NONE, MENU_COPY_SELECTION, Menu.NONE, "Copy Selection")
        }
        menu.add(Menu.NONE, MENU_PASTE, Menu.NONE, "Paste").isEnabled = clipboard.hasPrimaryClip()
        menu.add(Menu.NONE, MENU_COPY_TRANSCRIPT, Menu.NONE, "Copy All (Transcript)")
        menu.add(Menu.NONE, MENU_SELECT_ALL, Menu.NONE, "Select All")
        menu.add(Menu.NONE, MENU_OPEN_SCROLLVIEW, Menu.NONE, "Open in ScrollView")
        menu.add(Menu.NONE, MENU_RESET, Menu.NONE, "Reset Terminal")
    }

    fun onContextItemSelected(
        context: Context,
        item: MenuItem,
        terminalView: TerminalView?,
        session: TerminalSession?,
        customTypeface: Typeface? = null
    ): Boolean {
        val tv = terminalView ?: return false
        val s = session ?: tv.currentSession

        return when (item.itemId) {
            MENU_PASTE -> {
                s?.onPasteTextFromClipboard()
                true
            }
            MENU_COPY_SELECTION -> {
                val text = tv.selectedText ?: tv.storedSelectedText
                if (!text.isNullOrEmpty()) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Selection", text))
                    Toast.makeText(context, "Copied selection (${text.length} chars)", Toast.LENGTH_SHORT).show()
                }
                tv.stopTextSelectionMode()
                true
            }
            MENU_COPY_TRANSCRIPT -> {
                TerminalScrollViewHelper.copyTranscript(context, s)
                true
            }
            MENU_SELECT_ALL -> {
                tv.selectAll()
                true
            }
            MENU_OPEN_SCROLLVIEW -> {
                TerminalScrollViewHelper.showScrollViewDialog(context, s, customTypeface)
                true
            }
            MENU_RESET -> {
                s?.reset()
                tv.onScreenUpdated()
                Toast.makeText(context, "Terminal reset", Toast.LENGTH_SHORT).show()
                true
            }
            else -> false
        }
    }
}
