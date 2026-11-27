package com.zenithblue.nativecode

import com.zenithblue.nativecode.billing.BillingRepository
import com.zenithblue.nativecode.onboarding.AndroidDevPackManager
import com.zenithblue.nativecode.onboarding.PlayAssetDeliveryManager
import com.zenithblue.nativecode.onboarding.ReleasePins
import com.zenithblue.nativecode.onboarding.RootfsSourceResolver
import com.zenithblue.nativecode.onboarding.RuntimeUpdatePack
import com.zenithblue.nativecode.terminal.TermuxHostPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlavorConfigTest {
    @Test fun fdroidFlavorIsFossProotOnly() {
        assertEquals("com.zenithblue.nativecode.fdroid", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.FDROID)
        assertFalse(BuildConfig.MONETISATION_ENFORCE) // every FeatureGate allows: Pro is always on
        assertFalse(BuildConfig.ROOT_FEATURES)
        assertTrue(ProotOnly.ON)
        assertFalse(BuildConfig.TELEMETRY_AVAILABLE)
        assertFalse(BuildConfig.DESKTOP_AVAILABLE)
    }

    @Test fun hostPrefixFollowsTheApplicationId() {
        assertEquals("/data/data/com.zenithblue.nativecode.fdroid/files/usr", TermuxHostPaths.PREFIX)
    }

    @Test fun billingIsAStub() {
        assertNull(BillingRepository.price(BillingRepository.SKU_MONTHLY))
        assertFalse(BillingRepository.hasTrial())
    }

    @Test fun releasePinsReuseTheExistingShaPins() {
        val rootfs = ReleasePins.pinFor(PlayAssetDeliveryManager.PACK_RUNTIME, ReleasePins.BASE_URL)
        val update = ReleasePins.pinFor(PlayAssetDeliveryManager.PACK_UPDATE_CORE, ReleasePins.BASE_URL)
        val dev = ReleasePins.pinFor(PlayAssetDeliveryManager.PACK_ANDROID_DEV, ReleasePins.BASE_URL)
        assertEquals(RootfsSourceResolver.PINNED_ALPAQUITA_SHA256, rootfs.sha256)
        assertEquals(RuntimeUpdatePack.PINNED_SHA256, update.sha256)
        assertEquals(AndroidDevPackManager.PINNED_SHA256, dev.sha256)
        assertEquals("https://github.com/ZenithBlue-Dev/nativecode-runtime/releases/download/runtime-1.8.0/alpaquita_rootfs.tar.zst", rootfs.url)
        assertTrue(listOf(rootfs, update, dev).all { it.sizeBytes > 1_000_000L && it.url.startsWith("https://") })
    }
}
