plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kvision) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kilua.rpc) apply false
}

allprojects {
    group = "network.lapis.cloud"
    version = "0.1.5"
}

// Apply ktlint to every subproject that carries a Kotlin JVM or Kotlin
// Multiplatform plugin (mirrors the kuml-dev/kUML root build convention).
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }

    // Never lint generated sources (e.g. Kilua RPC's KSP-generated
    // jvm/js/commonMain bindings under build/generated/ksp/**) — they are
    // not hand-written, are regenerated on every build, and ktlint's
    // implicit-dependency validation otherwise races the KSP task that
    // produces them.
    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            filter {
                exclude { entry -> entry.file.path.contains("${java.io.File.separator}generated${java.io.File.separator}") }
            }
        }
    }
}
