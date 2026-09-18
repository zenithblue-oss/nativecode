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
    implementation("androidx.annotation:annotation:1.7.1")
    compileOnly(files("${rootProject.projectDir}/app/libs/terminal-emulator-v0.118.0-16k.aar"))
}
