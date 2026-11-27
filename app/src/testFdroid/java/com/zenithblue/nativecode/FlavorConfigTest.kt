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
    }

    @Test fun rootedShellStaysButChrootStaysOff() {
        assertEquals("root", com.zenithblue.nativecode.terminal.LinuxCommandBuilder.sessionUserForType("shell-root"))
        assertEquals("flux", com.zenithblue.nativecode.terminal.LinuxCommandBuilder.sessionUserForType("shell"))
        assertTrue(ProotOnly.ON) // chroot gate untouched
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
        assertEquals("https://github.com/zenithblue-oss/nativecode-runtime/releases/download/runtime-1.8.0/alpaquita_rootfs.tar.zst", rootfs.url)
        val gpu = ReleasePins.pinFor(PlayAssetDeliveryManager.PACK_GPU_DRIVER, ReleasePins.BASE_URL)
        assertEquals("063bcf0cacd6605458f01530e7fc4f4de43cda7d7a995d9518e1a6e38f5d0db6", gpu.sha256) // = panvk libvulkan_panfrost_kbase.sha256
        assertEquals(33_917_432L, gpu.sizeBytes)
        val tn = ReleasePins.pinFor(PlayAssetDeliveryManager.PACK_TURNIP_DRIVER, ReleasePins.BASE_URL)
        assertEquals("33b4acfbc04205d34e57178301b32f06df1539568fcb1e9b37688a3e777163ab", tn.sha256) // = turnip libvulkan_freedreno.sha256
        assertEquals(16_912_208L, tn.sizeBytes)
        assertTrue(listOf(rootfs, update, dev, gpu, tn).all { it.sizeBytes > 1_000_000L && it.url.startsWith("https://") })
    }
}
