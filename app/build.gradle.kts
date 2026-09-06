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


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zenithblue.nativecode"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zenithblue.nativecode"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "1.5"
        buildConfigField("String", "GIT_COMMIT", "\"${getGitCommitHash()}\"")
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
        }
    }

    // Keep large rootfs/bootstrap archives stored uncompressed in the APK
    // NOTE: 'gz' must be listed or AGP/aapt2 gunzips *.tar.gz assets to *.tar at package time.
    androidResources {
        noCompress += listOf("xz", "tar", "gz", "zst")
    }

    assetPacks += listOf(":nativecode_runtime")

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Compose dependencies
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Termux App and its shared lib
    implementation("com.github.termux:termux-app:v0.118.0")
    // Fix listenable future conflict
    implementation("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")

    // Termux X11 module
    implementation(project(":termux-x11"))

    // Play Asset Delivery
    implementation("com.google.android.play:asset-delivery-ktx:2.3.0")
}
