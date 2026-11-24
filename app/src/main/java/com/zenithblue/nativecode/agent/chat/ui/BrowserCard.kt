package com.zenithblue.nativecode.agent.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zenithblue.nativecode.R
import com.zenithblue.nativecode.agent.chat.tools.AgentBrowser
import com.zenithblue.nativecode.tr

private val PANEL_H = 260.dp

/**
 * Feature 2: the agent browser in the chat. Shows (for this thread only) the consent card the first time an agent
 * calls a browser tool, then the live panel: URL, an "agent in control" indicator, collapse and close. The user can
 * touch the page; Close stops the agent and withdraws its consent.
 */
@Composable
internal fun BrowserCard(threadId: String, approvalPending: Boolean = false) {
    val b = AgentBrowser.ui
    val c = b.consent
    if (c != null && c.threadId == threadId) {
        PendingShell(Ic.Globe, tr(R.string.chat_browser), tr(R.string.browser_consent_title, c.agent), null) {
            Column(Modifier.padding(top = S.m), verticalArrangement = Arrangement.spacedBy(S.m)) {
                Text(tr(R.string.browser_consent_body), color = C.onVar, style = T.bodySm)
                if (!b.isolated) Text(tr(R.string.browser_not_isolated), color = C.tertiary, style = T.bodySm)
                Row(horizontalArrangement = Arrangement.spacedBy(S.s)) {
                    NcButton(tr(R.string.browser_allow), primary = true, icon = Ic.Check) { AgentBrowser.answer(true) }
                    NcButton(tr(R.string.browser_deny), danger = true, icon = Ic.Close) { AgentBrowser.answer(false) }
                }
            }
        }
    } else if (b.owner == threadId) {
        Column(Modifier.fillMaxWidth().padding(start = S.m, end = S.m, top = S.s)) {
            Column(Modifier.fillMaxWidth().card(C.low, if (b.controlling) C.primary else C.border)) {
                Row(Modifier.fillMaxWidth().padding(start = S.m), verticalAlignment = Alignment.CenterVertically) {
                    NcIcon(Ic.Globe, null, tint = C.primary, size = 16.dp)
                    Label(b.url.ifBlank { tr(R.string.chat_browser) }, Modifier.weight(1f).padding(horizontal = S.s), color = C.on)
                    if (b.controlling) {
                        StatusDot(C.primary, pulse = true)
                        Label(tr(R.string.browser_agent_in_control), Modifier.padding(start = S.s), color = C.primary)
                    }
                    IconBtn(if (b.collapsed) Ic.ChevronDown else Ic.ChevronRight, tr(if (b.collapsed) R.string.browser_expand else R.string.browser_collapse),
                        box = 40.dp, size = 16.dp) { b.collapsed = !b.collapsed }
                    IconBtn(Ic.Close, tr(R.string.browser_close), box = 40.dp, size = 16.dp) { AgentBrowser.userClose() }
                }
                // Collapsed = not composed: AgentBrowser parks the WebView in the window (alpha ~0), so it keeps rendering and
                // keeps its viewport; a clipped-to-nothing child would stop Chromium drawing.
                // An approval card pending = panel parked too, so APPROVE / DENY never get pushed off screen.
                if (!b.collapsed && !approvalPending) Column(Modifier.fillMaxWidth().height(PANEL_H).clipToBounds()) {
                    AndroidView(
                        factory = { AgentBrowser.attach(it) },
                        modifier = Modifier.fillMaxWidth().requiredHeight(PANEL_H),
                        onRelease = { AgentBrowser.detach(it) },
                    )
                }
            }
        }
    }
}
