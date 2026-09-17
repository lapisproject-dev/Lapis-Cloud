// ── Detekt gate: intentionally EXCLUDED ──────────────────────────────────────
// lapis-client has no jvm() target (js-only KMP module below), so the detekt
// Gradle plugin registers no type-resolution compilation task for it (verified
// 2026-08-15: only jvm/androidJvm compilations get a detekt<Compilation>-with-
// classpath task — same finding kuml-dev/kUML documented for its
// kuml-wasm-playground module). The RequireNamedArguments rule is
// `RequiresAnalysisApi` and is therefore SILENTLY SKIPPED here — it would
// report zero findings and go green without inspecting a line. This module is
// listed in `lapisDetektExemptModules` in the root build script so
// `verifyDetektCoverage` fails if a NEW module ever ends up unanalysed by
// accident. Its source files must be kept named-argument-clean by review; if
// this module ever grows a jvm() target, remove this exemption.
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
                cssSupport {
                    enabled.set(true)
                }
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
                // UI/UX-Design-Team-Review 2026-08-14: Font Awesome webfont+CSS for the regrouped
                // navbar's icons -- see libs.versions.toml entry KDoc.
                implementation(libs.kvision.fontawesome)
                // V0.7.3 Basis-Mehrseiten-UI: hash-based multi-screen routing (login/register/
                // dashboard/members/contributions/documents/communication) -- see
                // io.kvision.routing.Routing KDoc.
                implementation(libs.kvision.routing.navigo.ng)
                // V1.0 Videokonferenzen (Kleinsitzung), Wave 1: the FIRST hand-declared `npm()`
                // dependency in this codebase -- every other npm package this module's webpack
                // bundle pulls in (bootstrap, navigo, snabbdom, split.js, fecha, @popperjs/core,
                // @js-joda/core) arrives transitively through KVision's own `npm()` declarations.
                // Pinned to an exact version (no `^`), matching how the version catalog pins every
                // JVM dependency -- see `network.lapis.cloud.client.livekit.LiveKitJs` KDoc for the
                // `@JsModule`/`@JsNonModule` externals this compiles against. Adding/upgrading this
                // dependency requires re-running `./gradlew kotlinUpgradeYarnLock` and committing the
                // regenerated `lapis-client/.kotlin-js-store/yarn.lock` -- skipping that makes
                // `./gradlew clean check` fail with an opaque `YarnLockMismatch`, not an obviously
                // related error.
                implementation(npm("livekit-client", "2.21.0"))
                // V1.4.16 UI-Schrift Inter (PdV-Branding, siehe CLAUDE.md "PdV-Branding" -- Inter
                // Bold/SemiBold/Regular/Light-Italic). Variable-Font-Paket, nicht die statische
                // `@fontsource/inter`-Variante -- dieselbe Wahl, die die PdV-Webseite selbst trifft
                // (`parteidervernunft.de-astro/package.json`), ein einziges Woff2 pro Unicode-Range
                // deckt den gesamten 100-900-Gewichtsbereich ab statt einer separaten Datei pro
                // Schnitt. Requires re-running `./gradlew kotlinUpgradeYarnLock` after adding, siehe
                // die `livekit-client`-Zeile oben für die Begründung.
                implementation(npm("@fontsource-variable/inter", "5.3.0"))
                // V0.6.x Price-Oracle Kursverlauf-Diagramm: zweite hand-deklarierte npm()-Abhängigkeit nach
                // livekit-client. Design-Team-Entscheidung (Steve Jobs, Abschluss-Review): chart.js 4.x statt
                // kvision-chart:9.6.0 (existiert auf Maven Central, passt zur gepinnten KVision-Version 9.6.0),
                // uPlot oder ECharts/ApexCharts. Begruendung:
                // - kvision-chart: typisierter Wrapper, würde bei einem eigenen Tick-Formatierer,
                //   einer abstandsabhängigen Liniensegment-Unterbrechung (spanGaps-Schwelle je Anker) und einem
                //   Tooltip mit wörtlicher Decimal-Zeichenkette (nie über Double geroutet) entweder passen oder
                //   blockieren -- kein Plan, der von "passt hoffentlich" abhängt.
                // - uPlot: kleiner, aber ohne eingebaute Tooltips -- die würden wir selbst bauen.
                // - ECharts/ApexCharts: 3-10x das Bundle für Funktionen, die diese Welle nicht braucht.
                // chart.js/auto ist ~65 KB gzip, deckt Achsen/Tooltip/Retina-Beschriftung fertig ab -- genau der
                // Teil, der in Eigenbau (Alan Kay/Bill Atkinson im Design-Review) vierhundert Zeilen würde und
                // trotzdem schlechter wäre. Externals nach exaktem `LiveKitJs.kt`-Muster in `chart/ChartJs.kt`.
                // Requires re-running `./gradlew :lapis-client:kotlinUpgradeYarnLock` after adding, siehe die
                // `livekit-client`-Zeile oben für die Begründung.
                implementation(npm("chart.js", "4.5.0"))
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
