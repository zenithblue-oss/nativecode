package com.zenithblue.nativecode.billing

import android.app.Activity
import android.content.Context

/**
 * F-Droid flavor: no billing. Pro is always on (MONETISATION_ENFORCE=false, every gate allows) and the
 * purchase UI is hidden, so these exist only to keep callers compiling.
 */
object BillingRepository {
    const val SKU_MONTHLY = "nativecode_pro_monthly"
    const val SKU_YEARLY = "nativecode_pro_yearly"
    const val SKU_LIFETIME = "nativecode_pro_lifetime"

    var onChanged: (() -> Unit)? = null

    @Suppress("UNUSED_PARAMETER") fun init(ctx: Context) = Unit
    @Suppress("UNUSED_PARAMETER") fun refresh(onDone: ((ok: Boolean) -> Unit)? = null) { onDone?.invoke(true) }
    @Suppress("UNUSED_PARAMETER") fun price(sku: String): String? = null
    @Suppress("UNUSED_PARAMETER") fun label(label: String, sku: String, unit: String, fallback: String): String = "$label · $fallback"
    fun hasTrial(): Boolean = false
    @Suppress("UNUSED_PARAMETER") fun launch(activity: Activity, sku: String): String? = "Purchases are not available in this build"
    @Suppress("UNUSED_PARAMETER") fun manageUrl(ctx: Context): String = "https://zenithblue-dev.github.io/nativecode-ai/"
}
