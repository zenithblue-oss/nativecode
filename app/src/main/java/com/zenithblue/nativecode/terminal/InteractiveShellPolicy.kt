package com.zenithblue.nativecode.terminal

/** NativeCode-only interactive preference. Runtime login shells stay Bash. */
object InteractiveShellPolicy {
    const val BASH = "/bin/bash"
    const val ZSH = "/bin/zsh"
    const val MARKER_PATH = "/home/flux/.config/fluxlinux/interactive-shell"

    private val allowed = setOf(
        "/bin/bash",
        "/usr/bin/bash",
        "/bin/zsh",
        "/usr/bin/zsh"
    )

    fun resolve(marker: String?, isExecutable: (String) -> Boolean = { true }): String {
        val candidate = marker?.trim()
        return if (candidate != null && candidate in allowed && isExecutable(candidate)) {
            candidate
        } else {
            BASH
        }
    }

    fun resolveForUser(user: String, marker: String?, isExecutable: (String) -> Boolean = { true }): String =
        if (user == "root") BASH else resolve(marker, isExecutable)
}
