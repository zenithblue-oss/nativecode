package com.zenithblue.nativecode

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.zenithblue.nativecode.terminal.ChrootCommandBuilder
import com.zenithblue.nativecode.terminal.TermuxHostPaths
import com.zenithblue.nativecode.onboarding.LinuxGuestDefaults
import java.io.File
import java.io.FileOutputStream

/**
 * Tracks BuildConfig.VERSION_CODE across installs and upgrades.
 *
 * When the stored code differs from the APK (first run or upgrade):
 * 1. Re-stage all assets/scripts files onto disk
 * 2. Refresh small host helpers (launch_tool, host env)
 * 3. Run version-step migrations (add hooks in runVersionMigrations)
 *
 * Never wipes proot/chroot rootfs or user projects.
 *
 * Bump versionCode in app/build.gradle.kts every release so this fires.
 */
object AppUpgrade {

    private const val TAG = "AppUpgrade"
    const val PREFS = "nativecode_prefs"
    const val KEY_LAST_VERSION_CODE = "last_version_code"
    const val KEY_LAST_VERSION_NAME = "last_version_name"

    /** Mirror of assets under filesDir — whole-tree SSOT for host scripts. */
    const val HOST_SCRIPTS_DIR = "host_scripts"

    /** Flat copy used by RootShellService / on-demand runners. */
    const val STAGED_SCRIPTS_DIR = "staged_scripts"

    /**
     * Call early on every cold start (Splash / Main / Onboarding).
     * Idempotent when KEY_LAST_VERSION_CODE already matches APK.
     */
    fun runIfNeeded(ctx: Context) {
        val app = ctx.applicationContext
        // Rootfs packs are external to the APK version. Repair visible guest
        // defaults on every cold start, including same-version debug installs.
        LinuxGuestDefaults.apply(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getInt(KEY_LAST_VERSION_CODE, 0)
        val now = BuildConfig.VERSION_CODE
        if (last == now) return

        Log.i(TAG, "upgrade path: $last → $now (${BuildConfig.VERSION_NAME})")
        try {
            migrate(app, from = last, to = now)
            prefs.edit()
                .putInt(KEY_LAST_VERSION_CODE, now)
                .putString(KEY_LAST_VERSION_NAME, BuildConfig.VERSION_NAME)
                .apply()
            Log.i(TAG, "upgrade complete → versionCode=$now")
        } catch (e: Exception) {
            // Do not write last_version_code on failure — retry next launch
            Log.e(TAG, "upgrade failed ($last → $now): ${e.message}", e)
        }
    }

    /**
     * Full host refresh plus optional stepwise migrations.
     * from == 0 means first run (install or clear-data).
     */
    fun migrate(ctx: Context, from: Int, to: Int) {
        restageAllHostScripts(ctx)
        ChrootCommandBuilder.ensureLauncherScript(ctx)
        TermuxHostPaths.writeHostEnvFile(ctx.filesDir)
        if (File(ctx.filesDir, "usr/lib/libtermux-exec.so").exists()) {
            TermuxHostPaths.applyPackageToExtractedPrefix(ctx.filesDir, force = true)
        }
        refreshBootstrapProot(ctx)
        // Guest file repairs run only here (versionCode changed): direct file edits, no guest exec.
        val guestOk = LinuxGuestDefaults.applyOnUpdate(ctx)
        runVersionMigrations(from, to)
        // Failure leaves last_version_code unset (see runIfNeeded), so the repair retries next launch.
        check(guestOk) { "guest config update failed" }
    }

    /**
     * The bootstrap's usr/bin/proot is the old build without the pair-affinity patch. Everything the app
     * starts already points PD_PROOT_BIN at the 16 KB aligned libproot.so; this keeps the stale copy
     * (used by older host scripts) identical to it. Same-size copy is skipped; swap is a rename.
     */
    private fun refreshBootstrapProot(ctx: Context) {
        val src = TermuxHostPaths.libProot(ctx)
        val dst = File(ctx.filesDir, "usr/bin/proot")
        if (!src.isFile || !dst.isFile) return
        try {
            if (src.length() == dst.length() && src.readBytes().contentEquals(dst.readBytes())) return
            val tmp = File(dst.parentFile, ".proot.nc-new")
            src.copyTo(tmp, overwrite = true)
            tmp.setReadable(true, false)
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(dst)) { tmp.delete(); Log.w(TAG, "could not replace bootstrap proot") }
        } catch (e: Exception) {
            Log.w(TAG, "bootstrap proot refresh failed: ${e.message}")
        }
    }

    /**
     * Add one block per release that needs extra work beyond full script restage.
     * Guest changes are Kotlin file edits only (see LinuxGuestDefaults.applyOnUpdate): never run a
     * script or command inside the guest (Play policy).
     */
    private fun runVersionMigrations(from: Int, to: Int) {
        if (from == 0) {
            Log.i(TAG, "first run (install) → scripts staged only; no version steps")
            return
        }
        if (from < 5 && to >= 5) {
            migrateProotFluxRuntimeShell()
        }
        Log.i(TAG, "version steps $from→$to: none registered (full restage already done)")
    }

    /** Keep legacy customized zsh as UX preference while restoring Bash for proot-distro runtime. */
    private fun migrateProotFluxRuntimeShell() {
        val rootfs = File(
            TermuxHostPaths.PREFIX,
            "var/lib/proot-distro/containers/alpine/rootfs"
        )
        val passwd = File(rootfs, "etc/passwd")
        if (!passwd.isFile || !passwd.canRead() || !passwd.canWrite()) return

        val original = passwd.readText()
        val lines = original.split("\n").toMutableList()
        val fluxIndex = lines.indexOfFirst { it.startsWith("flux:") }
        if (fluxIndex < 0) return
        val fields = lines[fluxIndex].split(":", limit = 0).toMutableList()
        if (fields.size < 7) return
        val oldShell = fields[6]
        if (oldShell != "/bin/zsh" && oldShell != "/usr/bin/zsh") return

        val marker = File(rootfs, "home/flux/.config/fluxlinux/interactive-shell")
        if (!marker.exists()) {
            marker.parentFile?.mkdirs()
            marker.writeText("$oldShell\n")
            marker.setReadable(true, false)
        }

        fields[6] = "/bin/bash"
        lines[fluxIndex] = fields.joinToString(":")
        val updated = lines.joinToString("\n")
        if (updated == original) return
        val temp = File(passwd.parentFile, ".passwd.nativecode.tmp")
        temp.writeText(updated)
        temp.setReadable(true, false)
        if (!temp.renameTo(passwd)) {
            temp.delete()
            Log.w(TAG, "runtime shell migration could not replace ${passwd.path}")
            return
        }
        Log.i(TAG, "migrated legacy flux zsh login shell to Bash runtime")
    }

    /**
     * Copy every file under assets/scripts into:
     * - HOST_SCRIPTS_DIR/scripts/... (tree mirror)
     * - STAGED_SCRIPTS_DIR/basename (flat, for existing callers)
     */
    fun restageAllHostScripts(ctx: Context): Int {
        val assets = ctx.assets
        val paths = mutableListOf<String>()
        walkAssetFiles(assets, "scripts", paths)

        val mirrorRoot = File(ctx.filesDir, HOST_SCRIPTS_DIR)
        val stagedRoot = File(ctx.filesDir, STAGED_SCRIPTS_DIR)
        mirrorRoot.mkdirs()
        stagedRoot.mkdirs()
        stagedRoot.setExecutable(true, false)
        stagedRoot.setReadable(true, false)

        var count = 0
        for (assetPath in paths) {
            try {
                val mirror = File(mirrorRoot, assetPath)
                mirror.parentFile?.mkdirs()
                assets.open(assetPath).use { input ->
                    FileOutputStream(mirror).use { out -> input.copyTo(out) }
                }
                mirror.setExecutable(true, false)
                mirror.setReadable(true, false)

                val flat = File(stagedRoot, File(assetPath).name)
                assets.open(assetPath).use { input ->
                    FileOutputStream(flat).use { out -> input.copyTo(out) }
                }
                flat.setExecutable(true, false)
                flat.setReadable(true, false)

                count++
            } catch (e: Exception) {
                Log.w(TAG, "restage failed $assetPath: ${e.message}")
            }
        }
        Log.i(TAG, "restaged $count script(s) → $HOST_SCRIPTS_DIR + $STAGED_SCRIPTS_DIR")
        return count
    }

    /** Absolute path to mirrored asset, e.g. scripts/chroot/setup_debian13_chroot.sh */
    fun hostScriptFile(ctx: Context, assetPath: String): File =
        File(File(ctx.filesDir, HOST_SCRIPTS_DIR), assetPath)

    private fun walkAssetFiles(assets: AssetManager, path: String, out: MutableList<String>) {
        val children = try {
            assets.list(path)
        } catch (_: Exception) {
            null
        } ?: return

        if (children.isEmpty()) {
            if (path.isNotEmpty()) out.add(path)
            return
        }
        for (name in children) {
            val child = if (path.isEmpty()) name else "$path/$name"
            val sub = try {
                assets.list(child)
            } catch (_: Exception) {
                null
            }
            if (sub == null || sub.isEmpty()) {
                out.add(child)
            } else {
                walkAssetFiles(assets, child, out)
            }
        }
    }
}
