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
            name = "Anthropic agent skills",
            component = "Bundled skills: frontend-design, theme-factory (anthropics/skills, commit e4bb5d9)",
            licenseType = "Apache-2.0",
            copyright = "Copyright Anthropic, PBC and contributors",
            url = "https://github.com/anthropics/skills",
            licenseSummary = "Apache License 2.0. Text used unchanged; each skill folder keeps its LICENSE.txt and a SOURCE.md."
        ),
        OssLicenseItem(
            name = "Google Stitch skills",
            component = "Bundled skill: taste-design (google-labs-code/stitch-skills, commit 75f4696)",
            licenseType = "Apache-2.0",
            copyright = "Copyright the stitch-skills authors",
            url = "https://github.com/google-labs-code/stitch-skills",
            licenseSummary = "Apache License 2.0. Text used unchanged; the skill folder keeps the repository LICENSE and a SOURCE.md."
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
            name = "Sora Editor",
            component = "Built-in code editor (editor + language-textmate 0.24.7, with tm4e, joni, jcodings, snakeyaml-engine, Gson)",
            licenseType = "LGPL-2.1",
            copyright = "Copyright (C) 2020-2026 Rosemoe and Sora Editor contributors",
            url = "https://github.com/Rosemoe/sora-editor",
            licenseSummary = "GNU Lesser General Public License v2.1. Used unmodified as separate Maven libraries (dynamically linked); you may replace them with a modified build. Full text: https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt"
        ),
        OssLicenseItem(
            name = "TextMate grammars",
            component = "Syntax grammars for the editor (Kotlin, Java, JS/TS, Python, JSON, YAML, Markdown, shell, HTML/CSS, C/C++, Go, Rust, Dockerfile, INI)",
            licenseType = "MIT",
            copyright = "Copyright (C) Microsoft Corporation and the upstream TextMate bundle authors",
            url = "https://github.com/microsoft/vscode/tree/main/extensions",
            licenseSummary = "MIT License. Grammars taken from the Visual Studio Code built-in extensions and the Sora Editor sample assets."
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
