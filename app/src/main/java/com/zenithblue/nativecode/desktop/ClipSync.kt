package com.zenithblue.nativecode.desktop

import android.content.Context
import com.zenithblue.nativecode.marketplace.PackageInstallRunner
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import com.zenithblue.nativecode.terminal.ShellCommandRunner
import com.zenithblue.nativecode.terminal.ShellJob
import java.io.File

/**
 * Phosh (Wayland) <-> Android clipboard bridge. phoc on the X11 backend never forwards Wayland selections to Termux:X11,
 * so a guest daemon ([ASSET], python3 + py3-gobject3) mirrors wlr-data-control <-> X11 CLIPBOARD; Termux:X11 does the
 * X11 <-> Android half. start_gui.sh launches the daemon when it is deployed and the [OFF_MARKER] is absent.
 */
object ClipSync {
    const val PACKAGES = "py3-gobject3"
    private const val ASSET = "scripts/nativecode-clipsync"
    private const val DAEMON_REL = "usr/local/bin/nativecode-clipsync"
    private const val OFF_REL = "etc/nativecode/clipsync_off"

    enum class Status { NOT_INSTALLED, ON, OFF }

    /** Pure mapping: the daemon can only run once the Python GObject bindings are in the guest. */
    fun status(hasGi: Boolean, disabled: Boolean): Status = when {
        !hasGi -> Status.NOT_INSTALLED
        disabled -> Status.OFF
        else -> Status.ON
    }

    fun hasGi(rootfs: File): Boolean {
        if (!File(rootfs, "usr/bin/python3").exists()) return false
        val libs = File(rootfs, "usr/lib").listFiles { f -> f.name.startsWith("python3") } ?: return false
        return libs.any { File(it, "site-packages/gi/__init__.py").exists() }
    }

    fun status(ctx: Context): Status {
        val root = ProjectPathResolver.prootRootfsDir(ctx)
        return status(hasGi(root), File(root, OFF_REL).exists())
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        val marker = File(ProjectPathResolver.prootRootfsDir(ctx), OFF_REL)
        if (enabled) marker.delete() else {
            marker.parentFile?.mkdirs()
            marker.writeText("off\n")
        }
    }

    /** Writes the daemon into the guest rootfs (no guest-side migration script); refreshed on every desktop start. */
    fun deploy(ctx: Context) {
        val dst = File(ProjectPathResolver.prootRootfsDir(ctx), DAEMON_REL)
        dst.parentFile?.mkdirs()
        ctx.assets.open(ASSET).use { i -> dst.outputStream().use { i.copyTo(it) } }
        dst.setReadable(true, false)
        dst.setExecutable(true, false)
    }

    /** Installs [PACKAGES] as guest root, then deploys the daemon. Callbacks run on the main thread. */
    fun install(ctx: Context, onLine: (String) -> Unit, onDone: (Boolean) -> Unit): ShellJob {
        val app = ctx.applicationContext
        val (args, env) = PackageInstallRunner.buildRootExec(app, "apk update && apk add --no-cache $PACKAGES", "proot")
        return ShellCommandRunner.runStreamedCancelable(app, args, env, onLine) { code ->
            val ok = code == 0 && hasGi(ProjectPathResolver.prootRootfsDir(app))
            if (ok) runCatching { deploy(app) }
            onDone(ok)
        }
    }
}
