plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// from CI secrets (see README.md); without them release builds use the debug key
val keystore: String? = System.getenv("ANDROID_KEYSTORE_FILE")

android {
    namespace = "ir.panbeh.flutter"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // ponytail: side by side with the Capacitor app (ir.panbeh.app); switch to that id once this replaces it
        applicationId = "ir.panbeh.flutter"
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        // CI passes the run number and the tag; local builds use pubspec's version
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: flutter.versionCode
        versionName = System.getenv("VERSION_NAME") ?: flutter.versionName
    }

    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
