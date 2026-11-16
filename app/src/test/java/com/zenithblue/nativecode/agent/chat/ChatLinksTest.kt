package com.zenithblue.nativecode.agent.chat

import com.zenithblue.nativecode.agent.chat.ui.ChatLink
import com.zenithblue.nativecode.agent.chat.ui.ChatLinks
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatLinksTest {
    private val cwd = "/home/flux/repos/app"
    private fun c(s: String) = ChatLinks.classify(s, cwd)

    @Test fun httpGoesToBrowser() {
        assertEquals(ChatLink.Web("https://example.com/a?b=1"), c("https://example.com/a?b=1"))
        assertEquals(ChatLink.Web("http://192.168.1.5:3000"), c(" http://192.168.1.5:3000 "))
    }

    @Test fun loopbackGoesToPreview() {
        assertEquals(ChatLink.Preview("http://localhost:5173/"), c("http://localhost:5173/"))
        assertEquals(ChatLink.Preview("http://127.0.0.1:8080"), c("http://127.0.0.1:8080"))
    }

    @Test fun fileUriBecomesGuestFile() {
        assertEquals(ChatLink.GuestFile("/home/flux/repos/app/NOTES.md"), c("file:///home/flux/repos/app/NOTES.md"))
        assertEquals(ChatLink.GuestFile("/home/flux/my notes.md"), c("file://localhost/home/flux/my%20notes.md"))
        assertEquals(ChatLink.GuestFile("/home/flux/a.md"), c("file:/home/flux/a.md#section"))
    }

    @Test fun fileUriNeverLeavesHomeOrHost() {
        assertEquals(ChatLink.Rejected, c("file:///etc/passwd"))
        assertEquals(ChatLink.Rejected, c("file:///sdcard/Download/x.md"))
        assertEquals(ChatLink.Rejected, c("file://evil.example/home/flux/a.md"))
        assertEquals(ChatLink.Rejected, c("file://nopath"))
    }

    @Test fun relativePathsResolveAgainstCwd() {
        assertEquals(ChatLink.GuestFile("/home/flux/repos/app/docs/plan.md"), c("docs/plan.md"))
        assertEquals(ChatLink.GuestFile("/home/flux/repos/app/README.md"), c("./README.md"))
        assertEquals(ChatLink.GuestFile("/home/flux/repos/other/x.md"), c("../other/x.md"))
        assertEquals(ChatLink.GuestFile("/home/flux/repos/app/src/Main.kt"), c("src/Main.kt:42:7"))
        assertEquals(ChatLink.GuestFile("/home/flux/notes.md"), c("~/notes.md"))
        assertEquals(ChatLink.GuestFile("/home/flux/x.md"), c("/home/flux/x.md"))
    }

    @Test fun pathEscapeRejected() {
        assertEquals(ChatLink.Rejected, c("../../../../etc/passwd"))
        assertEquals(ChatLink.Rejected, c("../../../../../../../../etc/passwd"))
        assertEquals(ChatLink.Rejected, c("/etc/passwd"))
        assertEquals(ChatLink.Rejected, c("/home/flux/../other/x"))
        assertEquals(ChatLink.Rejected, c("/home/flux"))
        assertEquals(ChatLink.Rejected, c("/home/fluxy/a.md"))
    }

    @Test fun unknownSchemeIsOther() {
        assertEquals(ChatLink.Other("mailto:a@b.c"), c("mailto:a@b.c"))
        assertEquals(ChatLink.Other("bogus://thing"), c("bogus://thing"))
        assertEquals(ChatLink.Other("content://media/x"), c("content://media/x"))
    }

    @Test fun blankRejected() {
        assertEquals(ChatLink.Rejected, c(""))
        assertEquals(ChatLink.Rejected, c("   "))
        assertEquals(ChatLink.Rejected, c("#anchor"))
    }
}
