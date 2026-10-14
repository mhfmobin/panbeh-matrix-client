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

dependencies {
    testImplementation(libs.trixnity.vodozemac)
    testImplementation(libs.ktor.okhttp)
    testImplementation(libs.ktor.mock)
}

// integration tests run against PANBEH_TEST_HS (e.g. a local Synapse with open registration); skipped without it
tasks.test { environment("PANBEH_TEST_HS", System.getenv("PANBEH_TEST_HS") ?: "") }
