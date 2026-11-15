package com.zenithblue.nativecode.backup

import com.zenithblue.nativecode.workspace.DirModel
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

enum class EntryMode { MERGE, REPLACE }

data class EntryPlan(val name: String, val mode: EntryMode)

data class SwapResult(val ok: Boolean, val error: String?, val replacedDir: File?, val kept: Int, val moved: Int)

/**
 * Moves extracted top-level entries from a staging folder into the guest home. Everything is a rename inside one
 * filesystem. Any failure undoes every step already taken (reverse order), so the home is left as it was.
 *  - REPLACE: an existing entry moves to `.replaced-<ts>/<name>`, then the restored one takes its place.
 *  - MERGE: files that already exist are kept; only missing files and folders are moved in.
 */
object RestoreSwap {
    private fun exists(f: File) = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun realDir(f: File) = Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS)
    private fun move(a: File, b: File) {
        b.parentFile?.mkdirs()
        Files.move(a.toPath(), b.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    fun apply(home: File, staging: File, plans: List<EntryPlan>, stamp: String): SwapResult {
        val replaced = File(home, ".replaced-$stamp")
        val undo = ArrayList<() -> Unit>()
        var kept = 0
        var moved = 0

        fun merge(src: File, dst: File) {
            for (c in src.listFiles() ?: emptyArray()) {
                val d = File(dst, c.name)
                when {
                    !exists(d) -> { move(c, d); undo.add { move(d, c) }; moved++ }
                    realDir(c) && realDir(d) -> merge(c, d)
                    else -> kept++
                }
            }
        }

        try {
            for (p in plans) {
                require(DirModel.validName(p.name)) { "bad entry name ${p.name}" }
                val src = File(staging, p.name)
                if (!exists(src)) continue
                val dst = File(home, p.name)
                if (!exists(dst)) {
                    move(src, dst); undo.add { move(dst, src) }; moved++
                } else if (p.mode == EntryMode.REPLACE) {
                    val old = File(replaced, p.name)
                    move(dst, old); undo.add { move(old, dst) }
                    move(src, dst); undo.add { move(dst, src) }; moved++
                } else if (realDir(src) && realDir(dst)) {
                    merge(src, dst)
                } else kept++
            }
        } catch (e: Exception) {
            for (u in undo.asReversed()) runCatching(u)
            runCatching { if (replaced.isDirectory && replaced.list().isNullOrEmpty()) replaced.delete() }
            return SwapResult(false, e.message ?: e.javaClass.simpleName, null, 0, 0)
        }
        runCatching { DirModel.deleteTree(home, staging) }
        return SwapResult(true, null, replaced.takeIf { it.isDirectory }, kept, moved)
    }
}
