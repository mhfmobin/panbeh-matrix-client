pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        maven("https://jitpack.io") { content { includeGroup("com.github.davidliu") } } // LiveKit's audioswitch
    }
}
rootProject.name = "panbeh"
include(":core", ":client")
// PANBEH_NO_ANDROID=1 builds only the JVM modules (for machines without Google's Maven / the Android SDK)
if (System.getenv("PANBEH_NO_ANDROID") == null) include(":app")
