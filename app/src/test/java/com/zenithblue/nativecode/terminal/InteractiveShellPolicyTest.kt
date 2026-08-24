package com.zenithblue.nativecode.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class InteractiveShellPolicyTest {
    @Test
    fun missingMarkerFallsBackToBash() {
        assertEquals(InteractiveShellPolicy.BASH, InteractiveShellPolicy.resolve(null))
        assertEquals(InteractiveShellPolicy.BASH, InteractiveShellPolicy.resolve("/bin/fish"))
    }

    @Test
    fun executableMarkerSelectsZsh() {
        assertEquals(
            InteractiveShellPolicy.ZSH,
            InteractiveShellPolicy.resolve("/bin/zsh") { it == "/bin/zsh" }
        )
    }

    @Test
    fun missingZshFallsBackToBash() {
        assertEquals(
            InteractiveShellPolicy.BASH,
            InteractiveShellPolicy.resolve("/bin/zsh") { false }
        )
    }

    @Test
    fun rootIsAlwaysBash() {
        assertEquals(
            InteractiveShellPolicy.BASH,
            InteractiveShellPolicy.resolveForUser("root", "/bin/zsh") { true }
        )
    }

    @Test
    fun workdirQuotePreservesShellMetacharacters() {
        assertEquals("'/home/flux/foo'\\''bar \$literal'", ShellQuoting.singleQuote("/home/flux/foo'bar \$literal"))
    }
}
