package com.zenithblue.nativecode.agent.chat.ui

import com.zenithblue.nativecode.preview.PortProbe
import com.zenithblue.nativecode.terminal.ProjectLocation
import java.io.File

/** What a tapped chat link means. Nothing here ever becomes an Intent with a file: URI. */
sealed interface ChatLink {
    /** http(s) to a non-loopback host: external browser. */
    data class Web(val url: String) : ChatLink
    /** http(s) to loopback: in-app preview WebView (item 13 rules). */
    data class Preview(val url: String) : ChatLink
    /** A file inside the guest home (absolute, normalized): in-app editor/viewer. */
    data class GuestFile(val path: String) : ChatLink
    /** mailto:, tel:, market:, ...: try an external app, fall back to copying the link. */
    data class Other(val url: String) : ChatLink
    /** Blank, malformed, or a path that leaves the guest home: "File not found". */
    object Rejected : ChatLink
}

/** Host file behind a chat path or link: the one resolver for the tap handler and the inline preview cards. */
object ChatFiles {
    /** [guest] (absolute, inside the guest home) to an existing regular host file under [rootfs]; symlinks are folded, one leaving the home gives null. */
    fun resolveGuest(rootfs: File, guest: String): File? =
        ProjectLocation.resolve(rootfs, guest)?.let { ProjectLocation.hostFile(rootfs, it) }?.takeIf { it.isFile }

    /** A path / file: URI as written in a message to its host file; null for URLs, paths outside the home, or a missing file. */
    fun resolve(rootfs: File, raw: String, cwd: String): File? =
        (ChatLinks.classify(raw, cwd) as? ChatLink.GuestFile)?.let { resolveGuest(rootfs, it.path) }
}

/** One classifier for every link in assistant markdown (file: URIs, absolute and relative paths, URLs). */
object ChatLinks {
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]{1,}):")
    private val LINE_SUFFIX = Regex("(:\\d+){1,2}$")

    fun classify(raw: String, cwd: String): ChatLink {
        val s = raw.trim()
        if (s.isEmpty() || '\u0000' in s) return ChatLink.Rejected
        val scheme = SCHEME.find(s)?.groupValues?.get(1)?.lowercase()
        return when (scheme) {
            "http", "https" -> if (PortProbe.isLoopback(s)) ChatLink.Preview(s) else ChatLink.Web(s)
            "file" -> filePath(s)?.let { guest(it, cwd) } ?: ChatLink.Rejected
            null -> guest(plainPath(s), cwd)
            else -> ChatLink.Other(s)
        }
    }

    /** `file:///a/b`, `file://localhost/a/b`, `file:/a/b`, `file:rel/b` to the decoded path; null for a remote host. */
    private fun filePath(s: String): String? {
        var rest = s.substring(5)
        if (rest.startsWith("//")) {
            rest = rest.substring(2)
            val slash = rest.indexOf('/')
            if (slash < 0) return null
            val host = rest.substring(0, slash)
            if (host.isNotEmpty() && !host.equals("localhost", ignoreCase = true)) return null
            rest = rest.substring(slash)
        }
        rest = rest.substringBefore('#').substringBefore('?')
        return runCatching { java.net.URLDecoder.decode(rest.replace("+", "%2B"), "UTF-8") }.getOrNull()?.let { plainPath(it) }
    }

    /** Drops `#fragment` and a trailing `:line[:col]`; `~/x` to the guest home. */
    private fun plainPath(p: String): String {
        var r = p.substringBefore('#').replace(LINE_SUFFIX, "")
        if (r == "~") r = ProjectLocation.HOME else if (r.startsWith("~/")) r = ProjectLocation.HOME + r.substring(1)
        return r
    }

    /** Absolute, normalized (`.` and `..` folded) and required to stay inside the guest home. */
    private fun guest(path: String, cwd: String): ChatLink {
        if (path.isBlank()) return ChatLink.Rejected
        val base = if (path.startsWith("/")) path else "${cwd.ifBlank { ProjectLocation.HOME }}/$path"
        val segs = ArrayList<String>()
        for (seg in base.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (segs.isEmpty()) return ChatLink.Rejected else segs.removeAt(segs.lastIndex)
                else -> segs.add(seg)
            }
        }
        val out = "/" + segs.joinToString("/")
        return if (out.startsWith(ProjectLocation.HOME + "/")) ChatLink.GuestFile(out) else ChatLink.Rejected
    }
}
