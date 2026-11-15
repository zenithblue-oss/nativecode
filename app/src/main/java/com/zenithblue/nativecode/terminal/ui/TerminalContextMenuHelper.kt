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
import com.zenithblue.nativecode.tr
import com.zenithblue.nativecode.R

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
            menu.add(Menu.NONE, MENU_COPY_SELECTION, Menu.NONE, tr(R.string.term_copy_selection))
        }
        menu.add(Menu.NONE, MENU_PASTE, Menu.NONE, tr(R.string.term_paste)).isEnabled = clipboard.hasPrimaryClip()
        menu.add(Menu.NONE, MENU_COPY_TRANSCRIPT, Menu.NONE, tr(R.string.term_copy_all_transcript))
        menu.add(Menu.NONE, MENU_SELECT_ALL, Menu.NONE, tr(R.string.term_select_all))
        menu.add(Menu.NONE, MENU_OPEN_SCROLLVIEW, Menu.NONE, tr(R.string.term_open_in_scrollview))
        menu.add(Menu.NONE, MENU_RESET, Menu.NONE, tr(R.string.term_reset_terminal))
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
                    clipboard.setPrimaryClip(ClipData.newPlainText(tr(R.string.term_terminal_selection), text))
                    Toast.makeText(context, tr(R.string.term_copied_selection_chars, text.length), Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, tr(R.string.term_terminal_reset), Toast.LENGTH_SHORT).show()
                true
            }
            else -> false
        }
    }
}
