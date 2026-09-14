package com.zenithblue.nativecode.marketplace

import android.content.Context
import java.io.File

/** Google Play stub. No catalog, executable, or script is downloaded. */
object MarketplaceClient {
    private fun unavailable() = UnsupportedOperationException("Remote marketplace is unavailable in the Google Play build")
    fun loadCatalog(ctx: Context, forceRefresh: Boolean = false): Result<MpCatalog> = Result.failure(unavailable())
    fun lastSyncMs(ctx: Context): Long = 0L
    fun ensurePackageScripts(ctx: Context, pkg: MpPackage): Result<File> = Result.failure(unavailable())
    fun parseCatalog(json: String): Result<MpCatalog> = Result.failure(unavailable())
}
