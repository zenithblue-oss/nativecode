buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.5.1")
        // Pins AGP's built-in Kotlin; Firebase BoM 35.x needs a Kotlin 2.4 compiler.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.4.21")
        // Applied in app/build.gradle.kts only when app/google-services.json exists.
        classpath("com.google.gms:google-services:4.6.0")
        classpath("com.google.firebase:firebase-crashlytics-gradle:3.0.8")
    }
}
