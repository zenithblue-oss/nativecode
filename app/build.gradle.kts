import java.util.Properties
import java.io.FileInputStream

// Read when the BuildConfig task runs (lazy provider), never frozen at configuration time.
val gitCommit = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    workingDir = rootDir
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

val nativeCodeReportUrl = providers.gradleProperty("nativecode.report.url")
    .orElse("https://api.nativecode.app/v1/report")
    .get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Store flavors: `play` (Play Billing, Play Asset Delivery, Firebase) and `fdroid` (FOSS: none of those).
// An fdroid-only invocation never touches Firebase/Play: no plugins, no asset packs, no google-services.json.
val requestedTasks = gradle.startParameter.taskNames
val fdroidOnly = requestedTasks.any { it.contains("fdroid", ignoreCase = true) } &&
    requestedTasks.none { it.contains("play", ignoreCase = true) }
val wantsPlay = requestedTasks.any { it.contains("play", ignoreCase = true) }

// Firebase is live only for an explicit play task with app/src/play/google-services.json (Firebase console, package
// com.zenithblue.nativecode). Without it the build still succeeds and Telemetry is a no-op.
if (wantsPlay && file("src/play/google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

androidComponents {
    onVariants { v ->
        v.buildConfigFields?.put("GIT_COMMIT", gitCommit.map {
            com.android.build.api.variant.BuildConfigField("String", "\"$it\"", "git short hash at build time")
        })
    }
}

android {
    namespace = "com.zenithblue.nativecode"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zenithblue.nativecode"
        minSdk = 26
        targetSdk = 37
        versionCode = 27
        versionName = "1.8.6"
        buildConfigField("boolean", "PLAY_STORE_BUILD", "false")
        buildConfigField("String", "REPORT_ENDPOINT", "\"$nativeCodeReportUrl\"")
        // 16 KB page-size devices (Android 15+): never ship 32-bit or extra-ABI JNI.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    flavorDimensions += "store"
    productFlavors {
        create("play") {
            dimension = "store"
            // Freemium gates (docs/monetisation/plan.md). On with Play Billing (PR5).
            buildConfigField("boolean", "MONETISATION_ENFORCE", "true")
            buildConfigField("boolean", "FDROID", "false")
            buildConfigField("boolean", "ROOT_FEATURES", "true")
            buildConfigField("boolean", "TELEMETRY_AVAILABLE", "true")
        }
        create("fdroid") {
            dimension = "store"
            applicationIdSuffix = ".fdroid"
            // FOSS build: Pro is always on (every gate allows), no billing UI, no telemetry, proot only.
            buildConfigField("boolean", "MONETISATION_ENFORCE", "false")
            buildConfigField("boolean", "FDROID", "true")
            buildConfigField("boolean", "ROOT_FEATURES", "false")
            buildConfigField("boolean", "TELEMETRY_AVAILABLE", "false")
        }
    }

    val keystorePropertiesFile = rootProject.file("key.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    val releaseStoreFile = System.getenv("RELEASE_KEYSTORE_PATH")
        ?: keystoreProperties.getProperty("storeFile")
    val releaseStorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
        ?: keystoreProperties.getProperty("storePassword")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
        ?: keystoreProperties.getProperty("keyAlias")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        ?: keystoreProperties.getProperty("keyPassword")

    signingConfigs {
        if (!releaseStoreFile.isNullOrEmpty() && file(releaseStoreFile).exists()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword ?: ""
                keyAlias = releaseKeyAlias ?: ""
                keyPassword = releaseKeyPassword ?: ""
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // 32-bit proot loader is not 16 KB-alignable; keep it out of lib/.
            excludes += listOf("**/libloader32.so", "lib/armeabi-v7a/**", "lib/x86/**", "lib/x86_64/**")
        }
    }

    // Keep large rootfs/bootstrap archives stored uncompressed in the APK
    // NOTE: 'gz' must be listed or AGP/aapt2 gunzips *.tar.gz assets to *.tar at package time.
    androidResources {
        noCompress += listOf("xz", "tar", "gz", "zst")
    }

    sourceSets {
        maybeCreate("playDebug").apply {
            assets.srcDirs(
                "../nativecode_android_dev/src/main/assets"
            )
        }
        // fdroid: src/fdroid/jniLibs (proot, loader, bash, termux-exec, fast_pty_guard, libXlorie) and
        // src/fdroid/assets/bootstrap.tar are built from source by tools/fdroid/build_*.sh --install (gitignored).
    }

    // Play-only on-demand packs (AAB). fdroid downloads the same files from GitHub Releases instead.
    if (!fdroidOnly) {
        assetPacks += listOf(":nativecode_runtime", ":nativecode_android_dev", ":nativecode_update_core")
    }

    // Per-app language: every language ships in the base APK (a Play language split would hide it from the picker).
    bundle {
        language {
            enableSplit = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // Translations must stay complete and format-safe (plan 20).
        error += listOf("MissingTranslation", "ExtraTranslation", "StringFormatMatches", "StringFormatInvalid", "StringFormatCount")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (android.jar's is a stub): chat store/runtime tests (item 12).
    testImplementation("org.json:json:20240303")

    implementation("androidx.core:core-ktx:1.19.1")
    // Agent browser: ProfileStore / WebViewCompat.setProfile isolate its cookies from the app's other WebViews (Apache-2.0).
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.core:core-splashscreen:1.2.0")

    // Built-in code editor (plan §16): Sora Editor, LGPL-2.1, unmodified Maven AARs (dynamic link).
    implementation("io.github.rosemoe:editor:0.24.7")
    implementation("io.github.rosemoe:language-textmate:0.24.7")

    // Compose dependencies
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Keep the upstream app/shared APIs, but replace terminal-emulator and
    // terminal-view with local modules.
    implementation("com.github.termux:termux-app:v0.118.0") {
        exclude(group = "com.github.termux.termux-app", module = "terminal-emulator")
        exclude(group = "com.github.termux.termux-app", module = "terminal-view")
    }
    implementation(project(":terminal-view"))
    // play: prebuilt 16k aar; fdroid: terminal-emulator built from source (termux-app/terminal-emulator).
    "playImplementation"(files("libs/terminal-emulator-v0.118.0-16k.aar"))
    "fdroidImplementation"(project(":terminal-emulator"))
    // Fix listenable future conflict
    implementation("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")

    // Termux X11 module
    implementation(project(":termux-x11"))

    // Non-free: play flavor only (src/play holds the code that imports them; src/fdroid has stubs / a GitHub downloader).
    // Play Asset Delivery
    "playImplementation"("com.google.android.play:asset-delivery-ktx:2.3.0")

    // Play Billing (NativeCode Pro)
    "playImplementation"("com.android.billingclient:billing-ktx:9.1.0")

    // Firebase (opt-in; collection off in the manifest until consent). Only telemetry/Telemetry.kt imports it.
    // BoM 35.x ships Kotlin 2.4 metadata: needs the Kotlin 2.4 plugin pinned in the root build.gradle.kts.
    "playImplementation"(platform("com.google.firebase:firebase-bom:35.2.0"))
    "playImplementation"("com.google.firebase:firebase-analytics")
    "playImplementation"("com.google.firebase:firebase-crashlytics")
    "playImplementation"("com.google.firebase:firebase-crashlytics-ndk")
    // Analytics pulls guava 31.1-android over termux-shared's 24.1-jre; the android flavour lacks
    // com.google.common.io.MoreFiles (termux FileUtils.clearDirectory). Keep a -jre flavour on top.
    implementation("com.google.guava:guava:33.8.0-jre")
}

// Prebuilt play-only inputs are absent in the public (source-only) repo: check whichever exist.
val elfCheckPaths = listOf("src/play/jniLibs", "libs/terminal-emulator-v0.118.0-16k.aar", "src/fdroid/jniLibs")
    .map { file(it) }.filter { it.exists() }.map { it.absolutePath }
tasks.register<Exec>("checkJni16kbAlignment") {
    workingDir = rootProject.projectDir
    onlyIf { elfCheckPaths.isNotEmpty() }
    commandLine(listOf("python3", rootProject.file("scripts/check_elf_16k.py").absolutePath) + elfCheckPaths)
}

tasks.named("preBuild").configure { dependsOn("checkJni16kbAlignment") }
