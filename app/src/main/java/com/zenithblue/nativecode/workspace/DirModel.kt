package com.zenithblue.nativecode.workspace

import com.zenithblue.nativecode.agent.chat.ui.FileKinds
import com.zenithblue.nativecode.agent.chat.ui.FileType
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

enum class DirSort { NAME, MODIFIED, SIZE }

enum class FileKind { FOLDER, CODE, TEXT, IMAGE, ARCHIVE, SCRIPT, OTHER }

data class DirEntry(val file: File, val isDir: Boolean, val size: Long, val modified: Long) {
    val name: String get() = file.name
}

data class Crumb(val label: String, val dir: File)

/** Pure logic of the workspace Directory page (listing, sorting, search, breadcrumbs, git markers, safe delete). */
object DirModel {
    /** Never descended into by the recursive search: huge or machine-owned. */
    private val SKIP_SEARCH = setOf(".git", "node_modules", ".gradle", "build", "target", ".venv", ".cache")

    private val CODE = setOf(
        "kt", "kts", "java", "js", "jsx", "ts", "tsx", "mjs", "py", "go", "rs", "c", "h", "cpp", "hpp", "cc",
        "json", "yml", "yaml", "toml", "xml", "html", "htm", "css", "scss", "sql", "gradle", "rb", "php", "swift", "lua", "cs",
    )
    private val TEXT = setOf("md", "markdown", "txt", "log", "csv", "rst", "ini", "conf", "cfg", "properties", "env", "lock", "diff", "patch")
    private val ARCHIVE = setOf("zip", "tar", "gz", "tgz", "xz", "zst", "bz2", "7z", "rar", "apk", "jar", "aar")
    private val SCRIPT = setOf("sh", "bash", "zsh", "fish", "bat", "cmd")

    fun kindOf(name: String, isDir: Boolean): FileKind {
        if (isDir) return FileKind.FOLDER
        val lower = name.lowercase()
        if (lower == "dockerfile" || lower == "makefile") return FileKind.SCRIPT
        val ext = lower.substringAfterLast('.', "")
        return when {
            ext in SCRIPT -> FileKind.SCRIPT
            ext in CODE -> FileKind.CODE
            FileKinds.kindOf(lower).let { it == FileType.IMAGE || it == FileType.SVG } -> FileKind.IMAGE
            ext in ARCHIVE -> FileKind.ARCHIVE
            ext in TEXT -> FileKind.TEXT
            else -> FileKind.OTHER
        }
    }

    private fun entry(f: File): DirEntry {
        val dir = f.isDirectory
        return DirEntry(f, dir, if (dir) 0L else f.length(), f.lastModified())
    }

    /** Folders first, then [sort]. NAME is case-insensitive ascending; MODIFIED newest first; SIZE largest first. */
    fun sorted(entries: List<DirEntry>, sort: DirSort): List<DirEntry> {
        val byName = compareBy<DirEntry>({ !it.isDir }, { it.name.lowercase() })
        val cmp: Comparator<DirEntry> = when (sort) {
            DirSort.NAME -> byName
            DirSort.MODIFIED -> compareBy<DirEntry> { !it.isDir }.thenByDescending { it.modified }.then(byName)
            DirSort.SIZE -> compareBy<DirEntry> { !it.isDir }.thenByDescending { it.size }.then(byName)
        }
        return entries.sortedWith(cmp)
    }

    fun list(dir: File, showHidden: Boolean, filter: String, sort: DirSort): List<DirEntry> {
        val files = dir.listFiles() ?: return emptyList()
        val f = filter.trim()
        return sorted(
            files.filter { (showHidden || !it.name.startsWith(".")) && (f.isEmpty() || it.name.contains(f, ignoreCase = true)) }
                .map { entry(it) },
            sort,
        )
    }

    /** Recursive name search below [root]; at most [limit] hits; skips [SKIP_SEARCH] directories and symlinked directories. */
    fun search(root: File, query: String, showHidden: Boolean, sort: DirSort, limit: Int = 500): List<DirEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<DirEntry>()
        fun walk(dir: File) {
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (out.size >= limit) return
                if (!showHidden && f.name.startsWith(".")) continue
                if (f.name.contains(q, ignoreCase = true)) out.add(entry(f))
                if (f.isDirectory && f.name !in SKIP_SEARCH && !Files.isSymbolicLink(f.toPath())) walk(f)
            }
        }
        walk(root)
        return sorted(out, sort)
    }

    /** Root-first path segments from [root] down to [cur] (cur must be root or inside it). */
    fun breadcrumbs(root: File, cur: File, rootLabel: String = "project"): List<Crumb> {
        val out = arrayListOf(Crumb(rootLabel, root))
        val rel = cur.absolutePath.removePrefix(root.absolutePath).trim('/')
        if (rel.isEmpty()) return out
        var acc = root
        for (seg in rel.split('/')) {
            acc = File(acc, seg)
            out.add(Crumb(seg, acc))
        }
        return out
    }

    fun isInside(root: File, f: File): Boolean {
        val r = root.absolutePath.trimEnd('/')
        val p = f.absolutePath
        return p == r || p.startsWith("$r/")
    }

    fun relativePath(root: File, f: File): String =
        if (isInside(root, f)) f.absolutePath.removePrefix(root.absolutePath).trimStart('/') else f.name

    /** A single path segment that can be created or renamed to. */
    fun validName(name: String): Boolean {
        val n = name.trim()
        return n.isNotEmpty() && n != "." && n != ".." && '/' !in n && '\u0000' !in n && n.length <= 255
    }

    /**
     * Git markers keyed by project-relative path. Files get their status letter (M A D R U for untracked, ! conflict);
     * every ancestor directory of a changed file gets '*'.
     */
    fun markers(changes: List<Pair<String, Char>>): Map<String, Char> {
        val out = HashMap<String, Char>()
        for ((path, status) in changes) {
            out[path] = status
            var i = path.lastIndexOf('/')
            while (i > 0) {
                out.putIfAbsent(path.substring(0, i), '*')
                i = path.lastIndexOf('/', i - 1)
            }
        }
        return out
    }

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    /**
     * Delete a file, symlink or directory tree. Symlinks are removed, never followed, so a link to a directory
     * can not make the walk delete the target's contents. Refuses [root] itself and anything outside it.
     */
    @Throws(IOException::class)
    fun deleteTree(root: File, target: File) {
        if (!isInside(root, target) || target.absolutePath == root.absolutePath) throw IOException("refusing to delete ${target.path}")
        val start: Path = target.toPath()
        if (!Files.exists(start, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
}
