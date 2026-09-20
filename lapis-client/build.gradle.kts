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
                // V1.4.23 Videokonferenz-Hintergrundeffekte: dritte hand-deklarierte npm()-Abhängigkeit nach
                // livekit-client und chart.js. Exakt gepinnt (kein `^`), gleiche Disziplin wie dort. Apache-2.0
                // (verifiziert im Paket-package.json), Peer `livekit-client ^2.1.0` -- die hier gepinnte 2.21.0
                // erfüllt das. Zieht GENAU EINE transitive Abhängigkeit: @mediapipe/tasks-vision, dort selbst
                // exakt auf 0.10.14 gepinnt (kein Range) und ebenfalls Apache-2.0.
                // Requires re-running `./gradlew kotlinUpgradeYarnLock` after adding, siehe die
                // `livekit-client`-Zeile oben für die Begründung (sonst opaker YarnLockMismatch).
                implementation(npm("@livekit/track-processors", "0.8.1"))
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

// ── V1.4.23 Videokonferenz-Hintergrundeffekte: Assets (MediaPipe-WASM, Modell, Hintergrundbilder) ─────────────
// Alles wird vom EIGENEN Server ausgeliefert, nie von einem CDN (Datenschutz: kein Dritter erfährt, dass eine
// Konferenz stattfindet). Configuration-Cache-Disziplin: nur `Sync` mit Provider-basierten from/into, keine
// `doLast`-Lambdas, die Build-Script-Objekte einfangen (siehe Root-Build, `VerifyI18nCatalogParity`).

// Muss identisch zu ConferenceBackgroundAssets.MEDIAPIPE_TASKS_VISION_VERSION im Kotlin-Code und zu
// MEDIAPIPE_TASKS_VISION_VERSION in ClientAssetRoutes.kt sein -- verifyMediaPipeVersion unten erzwingt, dass auch das
// tatsächlich installierte Paket passt.
val mediaPipeTasksVisionVersion = "0.10.14"

val nodeModulesDir = rootProject.layout.buildDirectory.dir("js/node_modules")

/**
 * Config-Cache-safe worker that turns `stageVideoEffectAssets` into a real GUARD (audit finding M2). Same
 * `Action<Task>` object idiom as [VerifyMediaPipeVersion] below and `VerifyI18nCatalogParity` in the root
 * build script -- only plain `File`/`String` fields, no build-script object capture.
 *
 * Why it is needed: a Gradle `Sync` whose `from` directory does not exist is SILENTLY empty. If the
 * node_modules layout ever shifts (a KGP upgrade, different hoisting), the staging directory would simply
 * lose the 19 MB of MediaPipe WASM, the Docker image would ship without it, and every user would get a
 * runtime LOAD_FAILED -- with a green `check`, because `verifyMediaPipeVersion` only compares the INSTALLED
 * package's version and the Docker build does not run `check` at all (it runs
 * `:lapis-server:installDist :lapis-client:jsBrowserProductionWebpack`).
 *
 * Used TWICE (follow-up audit, point 8): once on the staging task, and once on
 * `copyVideoEffectAssetsToWebpack` against the real webpack output directory -- the one the Dockerfile
 * copies. Checking only the staging directory would have left the copy itself unverified, and that copy is
 * the step that writes into a directory another task owns.
 */
private class VerifyVideoEffectAssets(
    private val taskName: String,
    private val assetsDir: File,
    private val expectedRelativePaths: List<String>,
) : Action<Task> {
    override fun execute(task: Task) {
        val missing = expectedRelativePaths.filter { !File(assetsDir, it).isFile }
        check(missing.isEmpty()) {
            "$taskName: ${missing.size} Asset(s) fehlen unter ${assetsDir.path} -- " +
                "${missing.joinToString()}. Ein Gradle-`Sync` mit einem nicht existierenden `from`-Verzeichnis " +
                "ist STILL leer; ohne diese Dateien liefert der Server 404 und jeder Hintergrundeffekt " +
                "scheitert zur Laufzeit. Pruefen: kotlinNpmInstall gelaufen? Liegt " +
                "build/js/node_modules/@mediapipe/tasks-vision/wasm noch dort?"
        }
        val empty = expectedRelativePaths.filter { File(assetsDir, it).length() == 0L }
        check(empty.isEmpty()) {
            "$taskName: leere Asset-Datei(en) unter ${assetsDir.path} -- ${empty.joinToString()}."
        }
    }
}

// Die sechs Hintergrundbilder MUESSEN der `BG_*`-Whitelist in ConferenceBackgroundEffect entsprechen
// (VideoBackgroundAssetsTest haelt Quellverzeichnis und Whitelist zusammen, dies hier das Staging-Ergebnis).
val videoEffectBackgroundIds =
    listOf("bg-warm-grey", "bg-cool-blue", "bg-sage", "bg-sandstone", "bg-midnight", "bg-studio")

// Beide WASM-Varianten: die Bibliothek entscheidet zur Laufzeit per SIMD-Probe, welche sie laedt -- ein 404
// auf dem no-SIMD-Zweig waere ein stiller Ausfall auf aelteren Geraeten.
val videoEffectStagedPaths =
    listOf(
        "mediapipe/tasks-vision-$mediaPipeTasksVisionVersion/wasm/vision_wasm_internal.js",
        "mediapipe/tasks-vision-$mediaPipeTasksVisionVersion/wasm/vision_wasm_internal.wasm",
        "mediapipe/tasks-vision-$mediaPipeTasksVisionVersion/wasm/vision_wasm_nosimd_internal.js",
        "mediapipe/tasks-vision-$mediaPipeTasksVisionVersion/wasm/vision_wasm_nosimd_internal.wasm",
        "mediapipe/selfie_segmenter.tflite",
    ) + videoEffectBackgroundIds.map { "video-backgrounds/$it.webp" }

val videoEffectStagingDir = layout.buildDirectory.dir("video-effect-assets")

// Bündelt Eigen-Assets + MediaPipe-WASM in EIN Staging-Verzeichnis, damit nur eine Quelle in die zwei
// Ausgabeverzeichnisse gespiegelt werden muss.
val stageVideoEffectAssets by tasks.registering(Sync::class) {
    dependsOn(rootProject.tasks.named("kotlinNpmInstall")) // node_modules muss existieren
    from(layout.projectDirectory.dir("src/jsMain/webAssets/video-backgrounds")) {
        into("video-backgrounds")
        exclude("generate-backgrounds.py") // Generator wird nicht ausgeliefert
    }
    from(layout.projectDirectory.dir("src/jsMain/webAssets/mediapipe")) {
        into("mediapipe")
        exclude("PROVENANCE.adoc")
    }
    from(nodeModulesDir.map { it.dir("@mediapipe/tasks-vision/wasm") }) {
        into("mediapipe/tasks-vision-$mediaPipeTasksVisionVersion/wasm")
    }
    into(videoEffectStagingDir)
    doLast(
        VerifyVideoEffectAssets(
            taskName = "stageVideoEffectAssets",
            assetsDir = videoEffectStagingDir.get().asFile,
            expectedRelativePaths = videoEffectStagedPaths,
        ),
    )
}

// Produktions-Bundle-Verzeichnis: der Dockerfile kopiert `build/kotlin-webpack/js/productionExecutable`.
// `jsBrowserDistribution` (lokaler Serverstart liest per Default
// `LAPIS_CLIENT_DIST_ROOT=../lapis-client/build/dist/js/productionExecutable`) spiegelt genau dieses Verzeichnis
// nach `build/dist/...` -- deshalb hängt es von `copyVideoEffectAssetsToWebpack` ab (Gradle verlangt die explizite
// Abhängigkeit, sonst schlägt die Task-Validierung fehl) und braucht KEIN eigenes Ziel. `Sync` statt `Copy`, damit
// ein entfernter Asset auch wieder verschwindet.
val videoEffectWebpackAssetsDir = layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable/assets")

val copyVideoEffectAssetsToWebpack by tasks.registering(Sync::class) {
    from(stageVideoEffectAssets)
    into(videoEffectWebpackAssetsDir)
    // Folge-Audit, Punkt 8: dieselbe Pruefung noch einmal auf die ECHTE Webpack-Ausgabe -- das ist das
    // Verzeichnis, das der Dockerfile kopiert. Ein unvollstaendiger oder leerer Kopiervorgang (fremdes Ziel:
    // dieses Verzeichnis gehoert `jsBrowserProductionWebpack`) bricht damit den Build statt die Produktion.
    doLast(
        VerifyVideoEffectAssets(
            taskName = "copyVideoEffectAssetsToWebpack",
            assetsDir = videoEffectWebpackAssetsDir.get().asFile,
            expectedRelativePaths = videoEffectStagedPaths,
        ),
    )
}
tasks.named("jsBrowserProductionWebpack") { finalizedBy(copyVideoEffectAssetsToWebpack) }
tasks.named("jsBrowserDistribution") { dependsOn(copyVideoEffectAssetsToWebpack) }

/**
 * Config-Cache-safe worker for `verifyMediaPipeVersion` -- a real `Action<Task>` object (not a script-level
 * lambda), same idiom as `VerifyI18nCatalogParity` in the root build script. Catches a silent transitive version
 * jump: the served path claims 0.10.14, the installed package would be something else.
 */
private class VerifyMediaPipeVersion(
    private val packageJson: File,
    private val expected: String,
) : Action<Task> {
    override fun execute(task: Task) {
        check(packageJson.exists()) {
            "verifyMediaPipeVersion: ${packageJson.path} fehlt -- kotlinNpmInstall zuerst laufen lassen."
        }
        val actual = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(packageJson.readText())?.groupValues?.get(1)
        check(actual == expected) {
            "verifyMediaPipeVersion: @mediapipe/tasks-vision ist $actual, erwartet $expected. " +
                "Die WASM-Dateien werden unter /assets/mediapipe/tasks-vision-$expected/wasm ausgeliefert und der " +
                "Client fragt genau diesen Pfad ab (ConferenceBackgroundAssets). Beide Stellen gemeinsam anheben."
        }
    }
}

val verifyMediaPipeVersion by tasks.registering {
    group = "verification"
    description = "Fails if the installed @mediapipe/tasks-vision differs from the version the asset paths encode."
    dependsOn(rootProject.tasks.named("kotlinNpmInstall"))
    val packageJson = nodeModulesDir.map { it.file("@mediapipe/tasks-vision/package.json").asFile }
    inputs.file(packageJson).withPropertyName("mediaPipePackageJson")
    val expected = mediaPipeTasksVisionVersion
    doLast(VerifyMediaPipeVersion(packageJson = packageJson.get(), expected = expected))
}

tasks.named("check") { dependsOn(verifyMediaPipeVersion) }
