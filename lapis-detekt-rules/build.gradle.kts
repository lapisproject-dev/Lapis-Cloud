plugins {
    alias(libs.plugins.kotlin.jvm)
}

// JDK policy (CLAUDE.md "JDK-Versions-Policy"): Lapis Cloud is a self-operated
// server/library project without end-user distribution, so it targets JDK 25 —
// unlike kUML's own kuml-detekt-rules module, which stays on JDK 21 because the
// kUML CLI it ships alongside is distributed to end users. This module has no
// such constraint.
kotlin { jvmToolchain(25) }

dependencies {
    // compileOnly: the ruleset jar is loaded into detekt-cli's own classloader,
    // which already supplies detekt-api + the Kotlin Analysis API. Bundling them
    // would produce two copies of KaSession and blow up with LinkageError.
    compileOnly(libs.detekt.api)

    // NOTE: deliberately NOT depending on dev.detekt:detekt-test here. Its
    // published Gradle module metadata requests the `detekt-api-test-fixtures`
    // capability, but dev.detekt:detekt-api 2.0.0-alpha.5 only ever published a
    // sources-only variant for that capability (no compiled jar) — resolving
    // detekt-test's testRuntimeClasspath fails with "No matching variant ...
    // requested capability 'dev.detekt:detekt-api-test-fixtures'". This is an
    // alpha packaging gap in detekt-test itself, not something a version bump
    // of our own dependencies fixes. detekt-test-utils (createEnvironment,
    // KotlinAnalysisApiEngine) resolves fine standalone and is all
    // RequireNamedArgumentsSpec needs; the tiny lintWithContext-equivalent
    // helper lives directly in the spec (see TestHarness.kt).
    //
    // Ported verbatim from kuml-dev/kUML's kuml-detekt-rules/build.gradle.kts.
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test.utils)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
