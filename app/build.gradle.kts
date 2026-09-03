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

    signingConfigs {
        create("release") {
            storeFile = file("/home/user/repos/keys/nativecode-release.jks")
            storePassword = "REDACTED"
            keyAlias = "nativecode"
            keyPassword = "REDACTED"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
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
    androidResources {
        noCompress += listOf("xz", "tar")
    }

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
}
