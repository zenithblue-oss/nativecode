dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "termux-lib"
include(":app")
include(":stub")
include(":termux-x11")
include(":nativecode_runtime")
include(":nativecode_android_dev")
include(":nativecode_update_core")
include(":terminal-view")
project(":terminal-view").projectDir = file("termux-app/terminal-view")
