package com.zenithblue.nativecode.terminal

import com.zenithblue.nativecode.workspace.DirModel
import java.io.File
import java.nio.file.Files

/**
 * Where a project may live: any folder inside the guest home (/home/flux), validated on the canonical
 * path so `..` and symlinks can not reach the rootfs root, other users or Android storage.
 * Pure (a rootfs [File] is passed in) so it is unit-testable.
 */
object ProjectLocation {
    const val HOME = "/home/flux"
    const val DEFAULT_PARENT = "$HOME/repos"
    const val DEFAULT_PARENT_DISPLAY = "~/repos"

    enum class Error { EMPTY, TRAVERSAL, OUTSIDE_HOME, NOT_FOUND, NOT_DIR, NOT_WRITABLE, DUPLICATE, NOT_EMPTY, BAD_NAME, IS_HOME }

    /** [path] is the canonical guest path when known; [isHome] flags the home dir itself (warn, do not block). */
    data class Check(val path: String?, val error: Error?, val isHome: Boolean = false) {
        val ok get() = error == null
    }

    /** `~`, `~/x`, absolute and home-relative input to an absolute guest path; null for blank. */
    fun expand(input: String): String? {
        val s = input.trim()
        return when {
            s.isEmpty() -> null
            s == "~" -> HOME
            s.startsWith("~/") -> HOME + s.substring(1)
            s.startsWith("/") -> s
            else -> "$HOME/$s"
        }
    }

    /**
     * Canonical guest path of [guest] with symlinks resolved as the guest sees them (an absolute link target is
     * relative to the rootfs, not the Android root). Null when it lands outside [HOME] or loops.
     */
    fun resolve(rootfs: File, guest: String): String? {
        val segs = ArrayDeque<String>()
        val pending = ArrayDeque(guest.split('/').filter { it.isNotEmpty() && it != "." })
        var hops = 0
        while (pending.isNotEmpty()) {
            val s = pending.removeFirst()
            if (s == "..") {
                if (segs.isEmpty()) return null
                segs.removeLast()
                continue
            }
            val link = File(rootfs, (segs + s).joinToString("/")).toPath()
            if (Files.isSymbolicLink(link)) {
                if (++hops > 40) return null
                val target = try { Files.readSymbolicLink(link).toString() } catch (_: Exception) { return null }
                if (target.startsWith("/")) segs.clear()
                pending.addAll(0, target.split('/').filter { it.isNotEmpty() && it != "." })
            } else {
                segs.addLast(s)
            }
        }
        val out = "/" + segs.joinToString("/")
        return if (out == HOME || out.startsWith("$HOME/")) out else null
    }

    fun hostFile(rootfs: File, guestPath: String): File = File(rootfs, guestPath.removePrefix("/"))

    /**
     * Validates [input] as a project folder. [mustExist] = register an existing folder; otherwise a missing folder
     * is fine when its nearest existing ancestor is a writable directory (it gets created). [known] = registered paths.
     * [checkWrite] is off for a root-owned chroot rootfs the app can not probe.
     */
    fun check(rootfs: File, input: String, known: Set<String>, mustExist: Boolean, checkWrite: Boolean = true): Check {
        val raw = expand(input) ?: return Check(null, Error.EMPTY)
        if ('\u0000' in raw || raw.split('/').any { it == ".." }) return Check(null, Error.TRAVERSAL)
        val path = resolve(rootfs, raw) ?: return Check(null, Error.OUTSIDE_HOME)
        val host = hostFile(rootfs, path)
        if (host.exists()) {
            if (!host.isDirectory) return Check(path, Error.NOT_DIR)
            if (checkWrite && !host.canWrite()) return Check(path, Error.NOT_WRITABLE)
        } else {
            if (mustExist) return Check(path, Error.NOT_FOUND)
            var a = host.parentFile
            while (a != null && !a.exists()) a = a.parentFile
            if (a == null || !a.isDirectory) return Check(path, Error.NOT_DIR)
            if (checkWrite && !a.canWrite()) return Check(path, Error.NOT_WRITABLE)
        }
        if (path in known.map { it.trimEnd('/') }) return Check(path, Error.DUPLICATE)
        return Check(path, null, isHome = path == HOME)
    }

    /** Validates the Location field plus project folder name [leaf]; [requireEmpty] for a clone target. */
    fun checkDest(rootfs: File, parentInput: String, leaf: String, known: Set<String>, requireEmpty: Boolean, checkWrite: Boolean = true): Check {
        val parent = check(rootfs, parentInput, emptySet(), false, checkWrite)
        if (!parent.ok) return parent
        if (!DirModel.validName(leaf)) return Check(null, Error.BAD_NAME)
        val dest = check(rootfs, "${parent.path}/$leaf", known, false, checkWrite)
        if (dest.ok && requireEmpty && hostFile(rootfs, dest.path!!).list()?.isNotEmpty() == true) {
            return Check(dest.path, Error.NOT_EMPTY)
        }
        return dest.copy(isHome = false)
    }

    /** "Open project in this folder": the folder itself is the project. Must exist; the home dir itself is refused. */
    fun checkFolder(rootfs: File, input: String, known: Set<String>, requireEmpty: Boolean, checkWrite: Boolean = true): Check {
        val c = check(rootfs, input, known, true, checkWrite)
        if (!c.ok) return c
        if (c.isHome) return Check(c.path, Error.IS_HOME, true)
        if (requireEmpty && hostFile(rootfs, c.path!!).list()?.isNotEmpty() == true) return Check(c.path, Error.NOT_EMPTY)
        return c
    }

    /** Project name from a folder name: lowercase, spaces and underscores to `-`, unsafe characters dropped, dashes collapsed. */
    fun slug(name: String, fallback: String = "project"): String =
        name.trim().lowercase()
            .replace(Regex("[\\s_]+"), "-")
            .replace(Regex("[^\\p{L}\\p{N}\\p{M}-]+"), "")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
            .ifEmpty { fallback }

    /** `/home/flux/a/b` to `~/a/b` for display. */
    fun display(guest: String): String = if (guest == HOME) "~" else if (guest.startsWith("$HOME/")) "~" + guest.substring(HOME.length) else guest
}
