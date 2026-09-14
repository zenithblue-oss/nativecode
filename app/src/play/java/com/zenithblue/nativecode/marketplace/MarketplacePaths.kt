package com.zenithblue.nativecode.marketplace

import android.content.Context
import java.io.File

/** Local registry paths only; the Play build has no remote marketplace endpoint. */
object MarketplacePaths {
    const val OWNER = "disabled"
    const val REPO = "disabled"
    const val REF = "disabled"
    const val RAW_BASE = ""
    fun catalogUrl(): String = ""
    fun rawUrl(relativePath: String): String = ""
    fun root(ctx: Context): File = File(ctx.filesDir, "marketplace").also { it.mkdirs() }
    fun cacheDir(ctx: Context): File = File(root(ctx), "cache").also { it.mkdirs() }
    fun catalogCache(ctx: Context): File = File(cacheDir(ctx), "catalog.json")
    fun packageCacheDir(ctx: Context, id: String): File = File(cacheDir(ctx), "packages/$id").also { it.mkdirs() }
    fun libCacheDir(ctx: Context): File = File(cacheDir(ctx), "lib").also { it.mkdirs() }
    fun registryFile(ctx: Context): File = File(root(ctx), "registry.json")
    fun inventoryCache(ctx: Context, env: String): File = File(root(ctx), "inventory-$env.json")
}
