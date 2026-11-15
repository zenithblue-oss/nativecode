package com.zenithblue.nativecode.legal

import com.zenithblue.nativecode.BuildConfig

/**
 * Open-source license entry metadata.
 */
data class OssLicenseItem(
    val name: String,
    val component: String,
    val licenseType: String,
    val copyright: String,
    val url: String,
    val licenseSummary: String
)

/**
 * Complete catalog of open source licenses used by NativeCode.
 */
object OpenSourceLicenses {

    val ITEMS: List<OssLicenseItem> = listOf(
        OssLicenseItem(
            name = "NativeCode / FluxLinux",
            component = "Core Android Workspace & Orchestrator",
            licenseType = "GPL-3.0-only",
            copyright = "Copyright (C) 2024-2026 Zenith Blue / zenithblue",
            url = "",
            licenseSummary = "GNU General Public License v3.0. Permissions include commercial use, modification, distribution, patent use, and private use, with requirements to disclose source, license and copyright notice, and same license (copyleft)."
        ),
        OssLicenseItem(
            name = "Termux App",
            component = "Terminal emulator core & shell integration (termux-app)",
            licenseType = "GPL-3.0-only",
            copyright = "Copyright (C) 2018-2024 Fredrik Fornwall and contributors",
            url = "https://github.com/termux/termux-app",
            licenseSummary = "GNU General Public License v3.0. Includes terminal session management, process execution, and Android interaction."
        ),
        OssLicenseItem(
            name = "Termux:X11",
            component = "X11 Wayland/Xwayland Display Server & Client (termux-x11)",
            licenseType = "GPL-3.0-only",
            copyright = "Copyright (C) 2021-2024 Alexander Morozov (twaik) and contributors",
            url = "https://github.com/termux/termux-x11",
            licenseSummary = "GNU General Public License v3.0. Provides the embedded graphics canvas, touch controls, input handling, and desktop windowing."
        ),
        OssLicenseItem(
            name = "Android Terminal Emulator",
            component = "terminal-view / terminal-emulator",
            licenseType = "Apache-2.0",
            copyright = "Copyright (C) 2011-2018 Steven Luo, Jack Palevich",
            url = "https://github.com/jackpal/Android-Terminal-Emulator",
            licenseSummary = "Apache License 2.0. Permissive license allowing modification and distribution with attribution and copyright preservation."
        ),
        OssLicenseItem(
            name = "PRoot",
            component = "Rootless user-space chroot/bind mount engine (libproot)",
            licenseType = "GPL-2.0-or-later",
            copyright = "Copyright (C) 2014-2024 Cedric Vincent and PRoot project contributors",
            url = "https://github.com/proot-me/proot",
            licenseSummary = "GNU General Public License v2.0 or later. Allows running a guest rootfs inside user space on Android using ptrace syscall interception."
        ),
        OssLicenseItem(
            name = "Android Jetpack & AndroidX",
            component = "Core KTX, AppCompat, ConstraintLayout, Material, Compose",
            licenseType = "Apache-2.0",
            copyright = "Copyright (C) 2018-2026 The Android Open Source Project",
            url = "https://android.googlesource.com/platform/frameworks/support",
            licenseSummary = "Apache License 2.0. Standard modern Android runtime components, UI libraries, and lifecycle management."
        ),
        OssLicenseItem(
            name = "Kotlin & Kotlin Coroutines",
            component = "Kotlin Standard Library & Language Runtime",
            licenseType = "Apache-2.0",
            copyright = "Copyright (C) 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors",
            url = "https://github.com/JetBrains/kotlin",
            licenseSummary = "Apache License 2.0. First-party programming language and asynchronous concurrency libraries."
        ),
        OssLicenseItem(
            name = "Google Guava",
            component = "ListenableFuture concurrency primitives",
            licenseType = "Apache-2.0",
            copyright = "Copyright (C) 2010-2024 Google LLC",
            url = "https://github.com/google/guava",
            licenseSummary = "Apache License 2.0. Common core Java utilities and future contract implementations."
        ),
        OssLicenseItem(
            name = "Firebase Android SDK",
            component = "Crashlytics (+ NDK) and Google Analytics for Firebase (opt-in only)",
            licenseType = "Apache-2.0 / Firebase Terms of Service",
            copyright = "Copyright (C) Google LLC",
            url = "https://github.com/firebase/firebase-android-sdk",
            licenseSummary = "Open-source SDK parts are Apache License 2.0; the Analytics service is used under the Firebase Terms of Service. Off until the user opts in."
        ),
        OssLicenseItem(
            name = "libsuperuser",
            component = "Root shell process management (StreamGobbler)",
            licenseType = "Apache-2.0",
            copyright = "Copyright (C) 2012-2020 Jorrit 'Chainfire' Jongma",
            url = "https://github.com/Chainfire/libsuperuser",
            licenseSummary = "Apache License 2.0. Secure process stream consumption and root/chroot process wrappers."
        ),
        OssLicenseItem(
            name = "Alpaquita Linux",
            component = "Guest Linux rootfs (Alpaquita Linux)",
            licenseType = "DFSG / Various FOSS",
            copyright = "Copyright (C) BellSoft and Alpaquita Linux package contributors",
            url = "https://bell-sw.com/alpaquita-linux/",
            licenseSummary = "Base distribution packages are distributed under their individual upstream open-source licenses. Package license texts are included in the delivered runtime."
        )
    ).filterNot { BuildConfig.PLAY_STORE_BUILD && it.name == "libsuperuser" }
}
