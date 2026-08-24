package com.zenithblue.nativecode.terminal

/** POSIX single-quoted argument for a host shell command. */
object ShellQuoting {
    fun singleQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
