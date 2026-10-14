plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(libs.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
