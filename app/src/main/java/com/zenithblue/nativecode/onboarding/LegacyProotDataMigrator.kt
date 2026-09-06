package com.zenithblue.nativecode.onboarding

import android.content.Context
import java.io.File

/**
 * One-time user data migration from legacy Alpine proot container to Alpaquita.
 * Preserves user repos, gitconfig, and GitHub CLI auth state.
 * Never overwrites existing Alpaquita files; never recursively chowns.
 */
object LegacyProotDataMigrator {
    private const val MARKER = ".nativecode_alpine_to_alpaquita_user_migration_v1"

    fun migrateIfNeeded(ctx: Context): Result<Int> = runCatching {
        val containers = File(ctx.filesDir, "usr/var/lib/proot-distro/containers")
        val oldHome = File(containers, "alpine/rootfs/home/flux")
        val newHome = File(containers, "alpaquita/rootfs/home/flux")
        val marker = File(newHome, MARKER)

        if (marker.exists()) return@runCatching 0
        if (!oldHome.isDirectory || !newHome.isDirectory) return@runCatching 0

        var copied = 0

        fun copyMissing(relative: String) {
            val src = File(oldHome, relative)
            val dst = File(newHome, relative)
            if (!src.exists()) return
            if (!dst.exists()) {
                dst.parentFile?.mkdirs()
                if (src.isDirectory) src.copyRecursively(dst, overwrite = false)
                else src.copyTo(dst, overwrite = false)
                copied++
                return
            }
            if (src.isDirectory && dst.isDirectory) {
                src.listFiles()?.forEach { child ->
                    val childDst = File(dst, child.name)
                    if (!childDst.exists()) {
                        if (child.isDirectory) child.copyRecursively(childDst, overwrite = false)
                        else child.copyTo(childDst, overwrite = false)
                        copied++
                    }
                }
            }
        }

        copyMissing("repos")
        copyMissing(".gitconfig")
        copyMissing(".config/gh")

        marker.writeText("1\n")
        copied
    }
}
