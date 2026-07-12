plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    // Kept in lockstep with lapis-shared — see the comment there. Needed
    // to load Kilua RPC's JVM-25-compiled classes at runtime.
    jvmToolchain(26)
}

application {
    mainClass.set("network.lapis.cloud.server.ApplicationKt")
}

dependencies {
    implementation(project(":lapis-shared"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.server.auto.head.response)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.logback.classic)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.h2)
}

tasks.test {
    useJUnitPlatform()
}
