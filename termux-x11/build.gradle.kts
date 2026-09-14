plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.x11"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "COMMIT", "\"1.03.01\"")
        buildConfigField("String", "VERSION_NAME", "\"1.03.01\"")
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.preference:preference:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    // Compile-only stub module
    compileOnly(project(":stub"))
}
