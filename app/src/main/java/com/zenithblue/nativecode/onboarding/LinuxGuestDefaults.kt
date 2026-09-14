package com.zenithblue.nativecode.onboarding

import android.content.Context
import android.util.Log
import com.zenithblue.nativecode.terminal.ProjectPathResolver
import java.io.File
import java.io.FileOutputStream

/** Keeps user-visible Linux defaults aligned with NativeCode after rootfs extraction. */
object LinuxGuestDefaults {

    private const val TAG = "LinuxGuestDefaults"
    private const val FONT_ASSET = "fonts/font.ttf"
    private const val FONT_NAME = "JetBrainsMonoNerdFont-Regular.ttf"

    private val fontConfig = """
        <?xml version="1.0"?>
        <!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
        <fontconfig>
          <alias binding="strong">
            <family>monospace</family>
            <prefer><family>JetBrainsMono Nerd Font</family></prefer>
          </alias>
          <match target="pattern">
            <test name="family" qual="any"><string>monospace</string></test>
            <edit name="family" mode="prepend" binding="strong">
              <string>JetBrainsMono Nerd Font</string>
            </edit>
          </match>
        </fontconfig>
    """.trimIndent() + "\n"

    /** Idempotent; returns false only when no installed proot rootfs exists yet. */
    fun apply(context: Context): Boolean {
        val rootfs = ProjectPathResolver.prootRootfsDir(context)
        if (!rootfs.isDirectory) return false

        return runCatching {
            installFont(context, rootfs)
            installFontAlias(rootfs)
            renameLegacyHostProfile(rootfs)
            pinXfceTerminalFont(rootfs)
            true
        }.onFailure {
            Log.w(TAG, "Could not apply Linux guest defaults: ${it.message}", it)
        }.getOrDefault(false)
    }

    private fun installFont(context: Context, rootfs: File) {
        val fontDir = File(rootfs, "usr/share/fonts/nativecode").also { it.mkdirs() }
        val target = File(fontDir, FONT_NAME)
        if (target.isFile && target.length() > 0L) return

        val temp = File(fontDir, ".$FONT_NAME.tmp")
        context.assets.open(FONT_ASSET).use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }
        check(temp.length() > 0L) { "Bundled terminal font is empty" }
        temp.copyTo(target, overwrite = true)
        temp.delete()
        target.setReadable(true, false)
    }

    private fun installFontAlias(rootfs: File) {
        val configDir = File(rootfs, "etc/fonts/conf.d").also { it.mkdirs() }
        writeIfChanged(File(configDir, "40-nativecode-monospace.conf"), fontConfig)
    }

    private fun renameLegacyHostProfile(rootfs: File) {
        val profileDir = File(rootfs, "etc/profile.d")
        val legacy = File(profileDir, "termux-profile.sh")
        if (!legacy.isFile) return

        val nativeCode = File(profileDir, "nativecode-host.sh")
        writeIfChanged(nativeCode, legacy.readText())
        if (!legacy.delete()) Log.w(TAG, "Could not remove legacy profile name: ${legacy.path}")
    }

    private fun pinXfceTerminalFont(rootfs: File) {
        val terminalConfig = File(rootfs, "home/flux/.config/xfce4/terminal/terminalrc")
        if (!terminalConfig.isFile) return

        var text = terminalConfig.readText()
        text = if (Regex("(?m)^FontUseSystem=.*$").containsMatchIn(text)) {
            text.replace(Regex("(?m)^FontUseSystem=.*$"), "FontUseSystem=FALSE")
        } else {
            text.trimEnd() + "\nFontUseSystem=FALSE\n"
        }
        text = if (Regex("(?m)^FontName=.*$").containsMatchIn(text)) {
            text.replace(Regex("(?m)^FontName=.*$"), "FontName=JetBrainsMono Nerd Font 12")
        } else {
            text.trimEnd() + "\nFontName=JetBrainsMono Nerd Font 12\n"
        }
        writeIfChanged(terminalConfig, text)
    }

    private fun writeIfChanged(file: File, content: String) {
        if (file.isFile && file.readText() == content) return
        file.parentFile?.mkdirs()
        file.writeText(content)
        file.setReadable(true, false)
    }
}
