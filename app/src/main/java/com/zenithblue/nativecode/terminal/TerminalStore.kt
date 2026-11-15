package com.zenithblue.nativecode.terminal

import com.termux.terminal.TerminalSession
import com.zenithblue.nativecode.terminal.ui.NativeCodeTerminalSessionClient

/**
 * Terminal tab state that outlives MainActivity when it is recreated (in-app language change). The Activity owns the
 * views; sessions, tab names and their clients live here so a recreate re-attaches instead of killing shells.
 * A real finish (back, swipe-away) ends the sessions and calls [clear].
 */
internal object TerminalStore {
    val sessions = ArrayList<TerminalSession>()
    val sessionTypes = ArrayList<String>()
    val titles = HashMap<TerminalSession, String>()
    var activeIndex = -1
    val projectSessions = HashMap<String, ArrayList<TerminalSession>>()
    val projectTabNames = HashMap<String, ArrayList<String>>()
    val projectActiveTab = HashMap<String, Int>()
    var appClient: NativeCodeTerminalSessionClient? = null
    /** One client per workspace session; weak so a closed tab's client can go. */
    val wsClients: MutableSet<NativeCodeTerminalSessionClient> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    /** Page to show again after a recreate (Settings / Terminal only). 0 = none. */
    var restorePage = 0

    fun clear() {
        sessions.clear(); sessionTypes.clear(); titles.clear(); activeIndex = -1
        projectSessions.clear(); projectTabNames.clear(); projectActiveTab.clear()
        appClient = null; wsClients.clear(); restorePage = 0
    }
}
