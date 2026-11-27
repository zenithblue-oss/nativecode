plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.11.0")
    // Prebuilt 16k aar (play) when present; the public source-only repo builds terminal-emulator from source.
    val prebuiltEmulator = file("${rootProject.projectDir}/app/libs/terminal-emulator-v0.118.0-16k.aar")
    if (prebuiltEmulator.exists()) compileOnly(files(prebuiltEmulator)) else compileOnly(project(":terminal-emulator"))
}
