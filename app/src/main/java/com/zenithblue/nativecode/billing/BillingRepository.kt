package com.zenithblue.nativecode.billing

import com.zenithblue.nativecode.telemetry.Telemetry
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.zenithblue.nativecode.entitlements.EntitlementStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Play Billing (PBL 9). Two subscription products (monthly, yearly) + one non-consumable INAPP (lifetime).
 * isPro = any active sub OR owned lifetime, cached in [EntitlementStore] (7-day offline TTL).
 *
 * ponytail: client-side verification only (Play-signed purchase state, no server). A rooted/patched
 * client can fake Pro. Upgrade path: send purchaseToken to a backend that verifies via the Play
 * Developer API (purchases.subscriptionsv2 / products) and listens to RTDN for renew/cancel/refund.
 */
object BillingRepository : PurchasesUpdatedListener {
    const val SKU_MONTHLY = "nativecode_pro_monthly"
    const val SKU_YEARLY = "nativecode_pro_yearly"
    const val SKU_LIFETIME = "nativecode_pro_lifetime"
    private val SUBS = listOf(SKU_MONTHLY, SKU_YEARLY)
    private val ALL = SUBS + SKU_LIFETIME
    private const val TAG = "NCBilling"

    private lateinit var app: Context
    private var client: BillingClient? = null
    private val main = Handler(Looper.getMainLooper())
    val products = ConcurrentHashMap<String, ProductDetails>()

    /** Active subscription SKU (for the manage deep link); null when none / lifetime. */
    @Volatile var activeSubSku: String? = null
        private set

    /** UI refresh hook; always invoked on the main thread. */
    var onChanged: (() -> Unit)? = null

    private fun changed() = main.post { onChanged?.invoke() }

    fun init(ctx: Context) {
        if (client != null) return
        app = ctx.applicationContext
        client = BillingClient.newBuilder(app)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
        client?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                Log.i(TAG, "setup code=${result.responseCode} msg=${result.debugMessage}")
                if (result.responseCode == BillingResponseCode.OK) refresh() // also loads products
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "service disconnected (auto-reconnect on next call)")
            }
        })
    }

    private fun queryProducts() {
        query(ProductType.SUBS, SUBS)
        query(ProductType.INAPP, listOf(SKU_LIFETIME))
    }

    private fun query(type: String, ids: List<String>) {
        val c = client ?: return
        val params = QueryProductDetailsParams.newBuilder().setProductList(ids.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build()
        }).build()
        c.queryProductDetailsAsync(params) { r, res ->
            Log.i(
                TAG,
                "queryProductDetails type=$type code=${r.responseCode} msg=${r.debugMessage} " +
                    "found=${res.productDetailsList.map { it.productId }} unfetched=${res.unfetchedProductList.map { "${it.productId}:status=${it.statusCode}" }}"
            )
            if (r.responseCode == BillingResponseCode.OK) {
                res.productDetailsList.forEach { products[it.productId] = it }
                if (type == ProductType.SUBS) res.productDetailsList.forEach { pd ->
                    val chosen = subOffer(pd)
                    Log.i(
                        TAG,
                        "offers ${pd.productId}: " + subOffers(pd).joinToString(" ") { o ->
                            "[id=${o.offerId} base=${o.basePlanId} tags=${o.tags} phases=" +
                                o.phases.joinToString(",") { "${it.formattedPrice}/${it.priceMicros}/${it.period}" } + "]"
                        } + " chosen=${chosen?.offerId} trial=${chosen?.isTrial} label=\"${planLabel("PLAN", chosen, "/period", "fallback")}\""
                    )
                }
                changed()
            }
        }
    }

    /** Restore / app start / resume: re-derive Pro from Play's owned purchases (expired subs drop out). */
    fun refresh(onDone: ((ok: Boolean) -> Unit)? = null) {
        val c = client ?: return
        // init's startConnection still in flight: its setup callback calls refresh(). Querying now would
        // kick a second connect ("already in the process of connecting", code 5). Disconnected → auto-reconnect.
        if (c.connectionState == BillingClient.ConnectionState.CONNECTING) { onDone?.let { main.post { it(false) } }; return }
        if (products.isEmpty() && c.isReady) queryProducts()
        c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.SUBS).build()) { r1, subs ->
            c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()) { r2, inapp ->
                val ok = r1.responseCode == BillingResponseCode.OK && r2.responseCode == BillingResponseCode.OK
                Log.i(TAG, "queryPurchases subs=${r1.responseCode}/${subs.size} inapp=${r2.responseCode}/${inapp.size}")
                if (ok) {
                    val owned = (subs + inapp).filter(::isOwned)
                    owned.forEach(::acknowledge)
                    activeSubSku = owned.flatMap { it.products }.firstOrNull { it in SUBS }
                    // Offline / error → keep the cached value (7-day TTL in EntitlementStore).
                    EntitlementStore.setPro(app, owned.isNotEmpty())
                    changed()
                }
                main.post { onDone?.invoke(ok) }
            }
        }
    }

    /** PENDING never grants Pro; only PURCHASED for one of our SKUs. */
    private fun isOwned(p: Purchase) =
        p.purchaseState == Purchase.PurchaseState.PURCHASED && p.products.any { it in ALL }

    /** Acknowledge within 3 days or Play refunds. Never consume: lifetime is non-consumable. */
    private fun acknowledge(p: Purchase) {
        if (p.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
        client?.acknowledgePurchase(params) { r -> Log.i(TAG, "ack ${p.products} code=${r.responseCode}") }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        Log.i(TAG, "purchasesUpdated code=${result.responseCode} n=${purchases?.size ?: 0}")
        val trialSku = pendingTrialSku
        pendingTrialSku = null
        if (result.responseCode != BillingResponseCode.OK && result.responseCode != BillingResponseCode.ITEM_ALREADY_OWNED) return
        purchases.orEmpty().forEach {
            Log.i(TAG, "purchase ${it.products} state=${it.purchaseState} trial=${trialSku in it.products}")
            if (isOwned(it)) {
                if (!it.isAcknowledged) {
                    val sku = Telemetry.Param.SKU to it.products.firstOrNull()
                    if (trialSku in it.products) Telemetry.log(app, Telemetry.Event.TRIAL_START, sku)
                    Telemetry.log(app, Telemetry.Event.PRO_PURCHASE, sku)
                }
                acknowledge(it)
            }
        }
        refresh()
    }

    /** Localized price from ProductDetails, or null until Play Console products exist. */
    fun price(sku: String): String? {
        val pd = products[sku] ?: return null
        return if (sku == SKU_LIFETIME) {
            pd.oneTimePurchaseOfferDetailsList?.firstOrNull()?.formattedPrice
        } else {
            subOffer(pd)?.recurringPrice
        }
    }

    /** Paywall button text; subscriptions with an eligible free trial say so ("7-day free trial, then …/mo"). */
    fun label(label: String, sku: String, unit: String, fallback: String): String =
        if (sku == SKU_LIFETIME) "$label · ${price(sku) ?: fallback}"
        else planLabel(label, products[sku]?.let(::subOffer), unit, fallback)

    /** True when Play returned a free-trial offer for a subscription (i.e. this account is eligible). */
    fun hasTrial(): Boolean = SUBS.any { products[it]?.let(::subOffer)?.trialPhase != null }

    private fun subOffers(pd: ProductDetails) = pd.subscriptionOfferDetails.orEmpty().map { o ->
        SubOffer(
            o.offerId, o.basePlanId, o.offerTags,
            o.pricingPhases.pricingPhaseList.map { OfferPhase(it.priceAmountMicros, it.formattedPrice, it.billingPeriod) },
            o.offerToken
        )
    }

    /** Eligible free-trial offer first, else the base plan (see [pickOffer]). */
    private fun subOffer(pd: ProductDetails) = pickOffer(subOffers(pd))

    // ponytail: in-memory only; process death during the Play sheet loses trial_start (pro_purchase
    // is still logged). Persist to prefs if the funnel numbers ever look short.
    @Volatile private var pendingTrialSku: String? = null

    /** Returns an error message, or null if the Play purchase sheet launched. */
    fun launch(activity: Activity, sku: String): String? {
        val c = client ?: return "Billing not ready"
        val pd = products[sku] ?: return "Product unavailable — try again later"
        val token = if (sku == SKU_LIFETIME) {
            pd.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
        } else {
            val offer = subOffer(pd) ?: return "No subscription offer available"
            pendingTrialSku = sku.takeIf { offer.isTrial }
            offer.token
        }
        if (sku == SKU_LIFETIME) pendingTrialSku = null
        val pdp = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd)
        token?.let { pdp.setOfferToken(it) }
        val r = c.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(pdp.build())).build())
        Log.i(TAG, "launchBillingFlow $sku code=${r.responseCode} msg=${r.debugMessage}")
        return if (r.responseCode == BillingResponseCode.OK) null else "Purchase failed (${r.responseCode})"
    }

    fun manageUrl(ctx: Context): String {
        val sku = activeSubSku ?: return "https://play.google.com/store/account/subscriptions"
        return "https://play.google.com/store/account/subscriptions?sku=$sku&package=${ctx.packageName}"
    }
}
