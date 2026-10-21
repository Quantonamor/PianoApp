buildscript {
    val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        rootProject.file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
    repositories {
        if (hasAndroidSdk) google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        val kotlinVersion = "2.1.20"
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
        if (hasAndroidSdk) {
            classpath("com.android.tools.build:gradle:8.9.1")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
        }
    }
}

allprojects {
    repositories {
        if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
            rootProject.file("local.properties").exists()
        ) google()
        mavenCentral()
    }
}
