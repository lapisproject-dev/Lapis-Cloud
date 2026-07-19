plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    // Kept in lockstep with lapis-shared — see the comment there. Needed
    // to load Kilua RPC's JVM-25-compiled classes at runtime.
    jvmToolchain(25)
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
    implementation(libs.pdfbox)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.h2)

    // kUML MDA persistence pipeline (ADR-0016) — see gradle/libs.versions.toml for why these
    // are test-scoped only. Drives SchemaDriftTest: evaluates src/main/kuml/*.kuml.kts via
    // KumlScriptHost, runs UmlToErmTransformer -> ErmToExposedTransformer / ErmSqlDdlGenerator,
    // and diffs the result against the real H2-migrated schema and the hand-written Table
    // objects (verification-only — the hand-written Table objects remain the compiled/runtime
    // artifact; see docs/architecture/domain-model.adoc "MDA-Pipeline / ADR-0016").
    testImplementation(libs.kuml.core.model)
    testImplementation(libs.kuml.core.dsl)
    testImplementation(libs.kuml.core.script)
    testImplementation(libs.kuml.metamodel.uml)
    testImplementation(libs.kuml.metamodel.erm)
    testImplementation(libs.kuml.profile.api)
    testImplementation(libs.kuml.profile.erm)
    testImplementation(libs.kuml.codegen.api)
    testImplementation(libs.kuml.codegen.m2m)
    testImplementation(libs.kuml.transform.uml.to.erm)
    testImplementation(libs.kuml.codegen.m2m.exposed)
    testImplementation(libs.kuml.gen.sql)
    testImplementation(libs.kotlin.scripting.jvm.host)
    testImplementation(libs.kotlin.scripting.common)
    testImplementation(libs.kotlin.scripting.jvm)
}

tasks.test {
    useJUnitPlatform()
}
