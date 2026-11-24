package com.zenithblue.nativecode.cliauth
import com.zenithblue.nativecode.agent.chat.core.ChatFailures
/**
 * The version gate in front of a chat session and a sign-in: [go] now when [installed] meets [min] (an unknown version,
 * e.g. offline or the Free-gate shim, never blocks), else [ask] the user to update and [go] only if that worked, [stop] if not.
 * [ask] gets the callback the update popup answers with (true = updated).
 */
object CliGate {
    fun run(installed: String?, min: String?, ask: ((Boolean) -> Unit) -> Unit, go: () -> Unit, stop: () -> Unit) {
        if (!ChatFailures.versionTooOld(installed, min)) go() else ask { if (it) go() else stop() }
    }
}
