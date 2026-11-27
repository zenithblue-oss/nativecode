package com.zenithblue.nativecode

/**
 * True when root / chroot / marketplace features are off: the old Play-policy switch (PLAY_STORE_BUILD) or a build
 * without root features (fdroid). The Android SDK pack gates keep using PLAY_STORE_BUILD directly.
 */
object ProotOnly {
    const val ON = BuildConfig.PLAY_STORE_BUILD || !BuildConfig.ROOT_FEATURES
}
