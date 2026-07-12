plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kilua.rpc)
}

// Packaging tasks (jarWithJs, *Archive, ...) are switched off via
// gradle.properties (dev.kilua.rpc.plugin.enableGradleTasks=false) — see
// that file for why it can't be done here via the extension DSL. Only the
// plugin's KSP wiring (expect/actual generation for getService /
// RpcServiceManager) is used in this module.

kotlin {
    // Kilua RPC's published jars (kilua-rpc-ktor, kilua-rpc-ksp-processor)
    // are compiled for JVM 25 bytecode (class file version 69). The
    // runtime loading them therefore needs to be JVM 25+; this machine has
    // JDK 26 installed (no local JDK 25), so we target that rather than
    // request an exact "25" toolchain Gradle would otherwise try to
    // auto-provision/download.
    jvmToolchain(26)

    jvm()

    js {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: lapis-server/lapis-client consume
            // Kilua RPC runtime classes directly (initRpc, applyRoutes,
            // getService, ...) from lapis-shared's compiled jvm/js output.
            api(libs.kilua.rpc.ktor)
            api(libs.kvision.common.remote)
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The Kilua RPC KSP processor writes generated jvm/js/commonMain-metadata
// bindings into build/generated/ksp/**, which the Kotlin MPP plugin then
// registers as extra source directories on the very same source sets that
// ktlint's Gradle plugin lints. Without an explicit task dependency, Gradle
// flags this as an "implicit dependency" (ktlint could run before KSP
// generates its output). Declare it explicitly.
tasks.matching { it.name.contains("Ktlint") }.configureEach {
    mustRunAfter(tasks.matching { it.name.startsWith("ksp") })
}
