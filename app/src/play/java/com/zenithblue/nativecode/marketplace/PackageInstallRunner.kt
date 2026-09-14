package com.zenithblue.nativecode.marketplace

import android.content.Context
import com.zenithblue.nativecode.terminal.LinuxCommandBuilder

/** Google Play stub. It cannot stage or execute marketplace scripts. */
object PackageInstallRunner {
    data class PreparedRun(
        val title: String,
        val method: String,
        val uninstall: Boolean,
        val packageIds: List<String>,
        val packages: List<MpPackage>,
        val guestCmd: String,
        val args: Array<String>,
        val envMap: HashMap<String, String>
    )

    private fun unavailable() = UnsupportedOperationException("Remote marketplace is unavailable in the Google Play build")
    fun resolveOrder(catalog: MpCatalog, id: String): List<MpPackage> = throw unavailable()
    fun prepareInstall(ctx: Context, catalog: MpCatalog, id: String, method: String = LinuxCommandBuilder.currentMethod): Result<PreparedRun> = Result.failure(unavailable())
    fun prepareUninstall(ctx: Context, catalog: MpCatalog, id: String, method: String = LinuxCommandBuilder.currentMethod): Result<PreparedRun> = Result.failure(unavailable())
    fun applyRegistryAfterRun(ctx: Context, run: PreparedRun, success: Boolean) = Unit
    fun install(ctx: Context, catalog: MpCatalog, id: String, method: String = LinuxCommandBuilder.currentMethod, onEvent: (InstallEvent) -> Unit) = fail(onEvent)
    fun uninstall(ctx: Context, catalog: MpCatalog, id: String, method: String = LinuxCommandBuilder.currentMethod, onEvent: (InstallEvent) -> Unit) = fail(onEvent)
    fun launchApp(ctx: Context, entry: RegistryEntry, method: String = LinuxCommandBuilder.currentMethod, onEvent: (InstallEvent) -> Unit) = fail(onEvent)
    fun buildRootExec(ctx: Context, guestCmd: String, method: String = LinuxCommandBuilder.currentMethod): Pair<Array<String>, HashMap<String, String>> = throw unavailable()

    private fun fail(onEvent: (InstallEvent) -> Unit) {
        onEvent(InstallEvent.Log("Marketplace unavailable in this build"))
        onEvent(InstallEvent.Finished(false, 1, 0, emptyList()))
    }
}
