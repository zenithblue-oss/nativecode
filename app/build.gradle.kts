import java.util.Properties
import java.io.FileInputStream

fun getGitCommitHash(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootDir)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
    } catch (e: Exception) {
        "unknown"
    }
}

val nativeCodeReportUrl = providers.gradleProperty("nativecode.report.url")
    .orElse("https://api.nativecode.app/v1/report")
    .get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase is live only with app/google-services.json (Firebase console, package
// com.zenithblue.nativecode). Without it the build still succeeds and Telemetry is a no-op.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "com.zenithblue.nativecode"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zenithblue.nativecode"
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        versionName = "1.8.0"
        buildConfigField("boolean", "PLAY_STORE_BUILD", "false")
        // Freemium gates (docs/monetisation/plan.md). On with Play Billing (PR5).
        buildConfigField("boolean", "MONETISATION_ENFORCE", "true")
        buildConfigField("String", "GIT_COMMIT", "\"${getGitCommitHash()}\"")
        buildConfigField("String", "REPORT_ENDPOINT", "\"$nativeCodeReportUrl\"")
        // 16 KB page-size devices (Android 15+): never ship 32-bit or extra-ABI JNI.
        ndk {
            abiFilters += "arm64-v8a"
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
        getByName("debug") {
            assets.srcDirs(
                "../nativecode_android_dev/src/main/assets"
            )
        }
    }

    assetPacks += listOf(":nativecode_runtime", ":nativecode_android_dev", ":nativecode_update_core")

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (android.jar's is a stub): chat store/runtime tests (item 12).
    testImplementation("org.json:json:20240303")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.core:core-splashscreen:1.2.0")

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
    implementation(files("libs/terminal-emulator-v0.118.0-16k.aar"))
    // Fix listenable future conflict
    implementation("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")

    // Termux X11 module
    implementation(project(":termux-x11"))

    // Play Asset Delivery
    implementation("com.google.android.play:asset-delivery-ktx:2.3.0")

    // Play Billing (NativeCode Pro)
    implementation("com.android.billingclient:billing-ktx:9.1.0")

    // Firebase (opt-in; collection off in the manifest until consent). Only telemetry/Telemetry.kt imports it.
    // BoM 35.x ships Kotlin 2.4 metadata: needs the Kotlin 2.4 plugin pinned in the root build.gradle.kts.
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-crashlytics-ndk")
    // Analytics pulls guava 31.1-android over termux-shared's 24.1-jre; the android flavour lacks
    // com.google.common.io.MoreFiles (termux FileUtils.clearDirectory). Keep a -jre flavour on top.
    implementation("com.google.guava:guava:33.8.0-jre")
}

tasks.register<Exec>("checkJni16kbAlignment") {
    workingDir = rootProject.projectDir
    commandLine(
        "python3",
        rootProject.file("scripts/check_elf_16k.py").absolutePath,
        file("src/main/jniLibs").absolutePath,
        file("../termux-x11/src/main/jniLibs/arm64-v8a").absolutePath,
        file("libs/terminal-emulator-v0.118.0-16k.aar").absolutePath
    )
}

tasks.named("preBuild").configure { dependsOn("checkJni16kbAlignment") }
