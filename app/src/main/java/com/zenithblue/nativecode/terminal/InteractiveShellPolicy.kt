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

    fun resolve(marker: String?, isExecutable: (String) -> Boolean = { true }): String = BASH

    fun resolveForUser(user: String, marker: String?, isExecutable: (String) -> Boolean = { true }): String = BASH
}
