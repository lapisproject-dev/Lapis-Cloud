plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kvision)
    // Compliance UI wave, screen 2 of 5 (`BackupScreen.kt`/`BackupHttp.kt`): the first `@Serializable`
    // class defined directly in this module (`RestoreSuccessResult`, mirroring the server's raw-HTTP
    // `/api/backup/restore` response shape -- deliberately not an RPC DTO, so it does not belong in
    // `lapis-shared`). Every other `@Serializable` type this module already decodes (audit-log
    // snapshots, RPC DTOs, ...) is compiled inside `lapis-shared` (which already applies this plugin,
    // see that module's own `build.gradle.kts`) and only *consumed* here via the generated
    // `kotlinx-serialization-json` runtime -- so this plugin was never needed in this module before.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    js {
        browser {
            commonWebpackConfig {
                outputFileName = "main.bundle.js"
            }
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        named("jsMain") {
            dependencies {
                implementation(project(":lapis-shared"))
                implementation(libs.kvision.core)
                implementation(libs.kvision.bootstrap)
                // V0.7.3 Basis-Mehrseiten-UI: hash-based multi-screen routing (login/register/
                // dashboard/members/contributions/documents/communication) -- see
                // io.kvision.routing.Routing KDoc.
                implementation(libs.kvision.routing.navigo.ng)
            }
        }
        // V0.7.3 Basis-Mehrseiten-UI: this module had no jsTest source set at all before this wave
        // (only build/tmp artifacts existed) -- see CHANGELOG V0.7.3 entry "Testing approach" for
        // what is and isn't covered. Runs under the Karma+ChromeHeadless testTask already
        // configured above; kotlin.test is the only new test dependency.
        named("jsTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
