import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MenuFogon"

include(":core")
include(":tools")

// The Android module is only included when an Android SDK is available, so that
// `./gradlew :core:test` works on a machine without the SDK.
fun hasAndroidSdk(): Boolean {
    if (!System.getenv("ANDROID_HOME").isNullOrBlank()) return true
    if (!System.getenv("ANDROID_SDK_ROOT").isNullOrBlank()) return true
    val local = file("local.properties")
    if (!local.exists()) return false
    val props = Properties()
    local.inputStream().use { props.load(it) }
    return !props.getProperty("sdk.dir").isNullOrBlank()
}

if (hasAndroidSdk()) {
    include(":app")
} else {
    println("MenuFogon: Android SDK not found (ANDROID_HOME / local.properties). Module :app is skipped.")
}
