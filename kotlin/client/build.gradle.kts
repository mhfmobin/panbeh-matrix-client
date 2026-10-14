plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":core"))
    api(libs.trixnity.client)
    api(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

tasks.test { useJUnitPlatform() }
