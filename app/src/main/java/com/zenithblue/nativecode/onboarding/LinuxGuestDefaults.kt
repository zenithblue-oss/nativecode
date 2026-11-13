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
            AgentConfigInstaller.install(context)
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

    /**
     * Login shells reset PATH in /etc/profile, so CLI self-update targets are re-applied here.
     * One managed file sourced by bash (/etc/profile) and zsh (/etc/zsh/zshenv); user files stay untouched.
     * POSIX only and no `$(...)`: zsh 5.9 in the 1.7.x rootfs hangs on command substitution.
     */
    internal val cliPathProfile = listOf(
        "# Managed by NativeCode: CLI self-updates must land where PATH looks first.",
        "export NPM_CONFIG_PREFIX=\"\$HOME/.local\"",
        "export NPM_CONFIG_ALLOW_SCRIPTS=opencode-ai",
        "case \":\$PATH:\" in",
        "  \":\$HOME/.opencode/bin:\$HOME/.local/bin:\"*) ;;",
        "  \":\$HOME/.local/bin:\"*) PATH=\"\$HOME/.opencode/bin:\$PATH\" ;;",
        "  *) PATH=\"\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\" ;;",
        "esac",
        "export PATH",
    ).joinToString("\n") + "\n"

    private const val CLI_PROFILE = "/etc/profile.d/zz-nativecode-cli.sh"
    private const val OLD_CLI_ENV_PATH =
        "export PATH=\"\$HOME/.local/bin:\$HOME/.grok/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\""
    private val AGY_BACKUP = Regex("^agy\\.\\d+\\.old$")

    /**
     * Guest config repairs for an app update. Called by [AppUpgrade] when versionCode changes and once
     * after the rootfs is installed. Kotlin file I/O only: never runs a shell or guest command.
     * Each step checks the file first and edits only what is wrong; user lines are never rewritten.
     */
    fun applyOnUpdate(context: Context): Boolean {
        val rootfs = ProjectPathResolver.prootRootfsDir(context)
        if (!rootfs.isDirectory) return false
        return runCatching { applyToRootfs(rootfs); true }
            .onFailure { Log.w(TAG, "Could not apply guest config update: ${it.message}", it) }
            .getOrDefault(false)
    }

    internal fun applyToRootfs(rootfs: File) {
        installCliPaths(rootfs)
        installZshStub(rootfs)
        pruneAgyBackups(rootfs)
    }

    private fun installCliPaths(rootfs: File) {
        writeIfChanged(File(rootfs, CLI_PROFILE.removePrefix("/")), cliPathProfile)

        val source = "[ -r $CLI_PROFILE ] && . $CLI_PROFILE"
        val zshenv = File(rootfs, "etc/zsh/zshenv")
        if (File(rootfs, "usr/bin/zsh").exists() &&
            !(zshenv.isFile && zshenv.readText().contains(source))
        ) {
            zshenv.parentFile?.let { it.mkdirs(); it.setReadable(true, false); it.setExecutable(true, false) }
            zshenv.appendText((if (zshenv.isFile && !zshenv.readText().endsWith("\n")) "\n" else "") + source + "\n")
            zshenv.setReadable(true, false)
        }

        // Rootfs-baked env file re-prepends ~/.local/bin; swap only that exact managed line.
        val cliEnv = File(rootfs, "home/flux/.config/fluxlinux/cli-tools.env")
        if (cliEnv.isFile) {
            val text = cliEnv.readText()
            if (text.contains(OLD_CLI_ENV_PATH)) {
                cliEnv.writeText(text.replace(OLD_CLI_ENV_PATH, OLD_CLI_ENV_PATH.replace("=\"\$HOME/.local", "=\"\$HOME/.opencode/bin:\$HOME/.local")))
            }
        }
    }

    /** zsh runs its first-run wizard when none of these exist in $HOME; a comment-only ~/.zshrc stops it. */
    private fun installZshStub(rootfs: File) {
        val home = File(rootfs, "home/flux")
        if (!File(rootfs, "usr/bin/zsh").exists() || !home.isDirectory) return
        if (listOf(".zshrc", ".zshenv", ".zprofile", ".zlogin").any { File(home, it).exists() }) return
        File(home, ".zshrc").apply {
            writeText("# NativeCode: placeholder so zsh skips its first-run wizard. Edit freely.\n")
            setReadable(true, false)
        }
    }

    /** `agy update` keeps a ~200 MB agy.<n>.old copy of the previous binary every time. */
    private fun pruneAgyBackups(rootfs: File) {
        File(rootfs, "home/flux/.local/bin").listFiles { f -> AGY_BACKUP.matches(f.name) && f.isFile }
            ?.forEach { if (!it.delete()) Log.w(TAG, "Could not remove ${it.path}") }
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
