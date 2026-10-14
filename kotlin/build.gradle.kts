buildscript {
    // AGP (keep in sync with agp in libs.versions.toml) only where Google's Maven is reachable (see settings.gradle.kts)
    if (System.getenv("PANBEH_NO_ANDROID") == null) {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools.build:gradle:9.3.1") }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
