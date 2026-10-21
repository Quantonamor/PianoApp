rootProject.name = "PianoTiles"

// The Android app module is only included when an Android SDK is available.
// This allows building and testing the pure Kotlin `core` module on machines
// without an Android SDK (e.g. CI containers or quick local checks).
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

include(":core")
if (hasAndroidSdk) include(":app")
