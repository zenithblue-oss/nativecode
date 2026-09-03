package com.zenithblue.nativecode.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class InteractiveShellPolicyTest {
    @Test
    fun defaultsToBash() {
        assertEquals(InteractiveShellPolicy.BASH, InteractiveShellPolicy.resolve(null))
        assertEquals(InteractiveShellPolicy.BASH, InteractiveShellPolicy.resolve("/bin/fish"))
        assertEquals(InteractiveShellPolicy.BASH, InteractiveShellPolicy.resolve("/bin/zsh"))
        assertEquals(InteractiveShellPolicy.BASH, InteractiveShellPolicy.resolveForUser("flux", "/bin/zsh"))
        assertEquals(InteractiveShellPolicy.BASH, InteractiveShellPolicy.resolveForUser("root", "/bin/zsh"))
    }

    @Test
    fun workdirQuotePreservesShellMetacharacters() {
        assertEquals("'/home/flux/foo'\\''bar \$literal'", ShellQuoting.singleQuote("/home/flux/foo'bar \$literal"))
    }
}
