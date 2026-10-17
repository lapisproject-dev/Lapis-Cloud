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
import java.security.MessageDigest

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
                // V1.9.6 Vorstands-Karte (Client-Hälfte): fünfte und sechste hand-deklarierte npm()-Abhängigkeit
                // nach livekit-client/@fontsource-variable-inter/chart.js/@livekit-track-processors. maplibre-gl
                // 5.24.0 (letzte UMD-Bundle-Version vor dem ESM-only-Wechsel in 6.x, siehe Q4 in
                // docs/architecture/member-map.adoc) und pmtiles 4.5.0 (JS-`pmtiles://`-Protokoll-Handler für
                // MapLibre). Beide BSD-3-Clause (verifiziert im jeweiligen Paket-package.json). Bundle-Zuwachs
                // ~230 KB gzip fuer JEDEN Besucher, nicht nur BOARD/ADMIN (Q5, bereits akzeptierter Trade-off).
                // Externals nach exaktem LiveKitJs.kt-Muster in maplibre/MapLibreJs.kt, maplibre/Protocol.kt
                // bzw. maplibre/ResizeObserver.kt. Requires re-running `./gradlew :lapis-client:kotlinUpgradeYarnLock`
                // after adding, siehe die livekit-client-Zeile oben für die Begründung.
                implementation(npm("maplibre-gl", "5.24.0"))
                implementation(npm("pmtiles", "4.5.0"))
                // V1.9.38 Einheitliche Zeitzonen: kotlinx-datetime on Kotlin/JS knows only UTC and the browser's own
                // zone until the zone database of `@js-joda/timezone` is loaded -- without it
                // `TimeZone.of("Europe/Berlin")` throws in the browser while every JVM test stays green. Loaded by
                // `OrganizationTime.kt` (`@JsModule` external + a reference from every public function there, because
                // Kotlin/JS initializes top-level properties lazily). The FULL data set (`dist/js-joda-timezone.js`),
                // deliberately NOT the `10-year-range`/`1970-2030` variants: those silently compute wrong offsets
                // outside their window. Exactly 2.23.0 (BSD-3-Clause, tzdata for all years, ~4 MB unpacked): the newest
                // 2.x whose peer range (`@js-joda/core >=1.11.0`) accepts the 3.2.0 core KVision pulls in -- 2.24+ and
                // 3.x demand core >=5.7.0, a second core copy in the bundle would break the shared registry.
                // Requires re-running `./gradlew :lapis-client:kotlinUpgradeYarnLock` after adding and committing
                // `.kotlin-js-store/yarn.lock`, siehe die `livekit-client`-Zeile oben für die Begründung.
                implementation(npm("@js-joda/timezone", "2.23.0"))
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
    // V1.9.97: relative path -> expected SHA-256 (hex) of own binary assets that are served `immutable`. Plain strings only (Config-Cache safe).
    private val expectedSha256: Map<String, String> = emptyMap(),
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
        // V1.9.97: a corrupted copy of an `immutable` asset would stay in browsers for a year, so the build breaks instead.
        val corrupt =
            expectedSha256.filter { (path, expected) ->
                // a fresh MessageDigest per call (not thread-safe to share)
                val digest = MessageDigest.getInstance("SHA-256")
                val actual = digest.digest(File(assetsDir, path).readBytes()).joinToString("") { "%02x".format(it) }
                actual != expected
            }
        check(corrupt.isEmpty()) {
            "$taskName: SHA-256 mismatch for ${corrupt.keys.joinToString()} under ${assetsDir.path}. " +
                "These files are served immutable; change the bytes only together with a new directory version (encounter-sounds-v2 or rnnoise-v2)."
        }
    }
}

// Die sechs Hintergrundbilder MUESSEN der `BG_*`-Whitelist in ConferenceBackgroundEffect entsprechen
// (VideoBackgroundAssetsTest haelt Quellverzeichnis und Whitelist zusammen, dies hier das Staging-Ergebnis).
val videoEffectBackgroundIds =
    listOf("bg-warm-grey", "bg-cool-blue", "bg-sage", "bg-sandstone", "bg-midnight", "bg-studio")

// V1.9.121: `sha256  4x3/<code>.svg` per line (sha256sum style), the single source of truth for the shipped flag files.
val flagIconManifest: List<Pair<String, String>> =
    providers
        .fileContents(layout.projectDirectory.file("src/jsMain/webAssets/flag-icons-v7/MANIFEST.sha256"))
        .asText
        .get()
        .lines()
        .filter { it.isNotBlank() }
        .map { line -> line.substringAfter("  ").trim() to line.substringBefore("  ").trim() }

// Beide WASM-Varianten: die Bibliothek entscheidet zur Laufzeit per SIMD-Probe, welche sie laedt -- ein 404
// auf dem no-SIMD-Zweig waere ein stiller Ausfall auf aelteren Geraeten.
val videoEffectStagedPaths =
    listOf(
        "mediapipe/tasks-vision-$mediaPipeTasksVisionVersion/wasm/vision_wasm_internal.js",
        "mediapipe/tasks-vision-$mediaPipeTasksVisionVersion/wasm/vision_wasm_internal.wasm",
        "mediapipe/tasks-vision-$mediaPipeTasksVisionVersion/wasm/vision_wasm_nosimd_internal.js",
        "mediapipe/tasks-vision-$mediaPipeTasksVisionVersion/wasm/vision_wasm_nosimd_internal.wasm",
        "mediapipe/selfie_segmenter.tflite",
        // V1.9.146: the face detector of the camera-filter skin smoothing (checksum below, provenance in mediapipe/PROVENANCE.adoc).
        "mediapipe/blaze_face_short_range.tflite",
    ) + videoEffectBackgroundIds.map { "video-backgrounds/$it.webp" } +
        // V1.9.67 Begegnungsraum: the decorative floor-plan scenes (CSS masks, see theme.css `.lapis-encounter-scene-front` and, since V1.9.153, `.lapis-encounter-block::before`) ride the same staging.
        listOf("church", "hall").flatMap { theme -> listOf("front", "row").map { part -> "encounter-themes/$theme/$part.svg" } } +
        // V1.9.152 Begegnungsraum Stufe 2c: the ten objects of the fixed room templates (served immutable under `encounter-objects-v1`, checksums below).
        // The V1.9.67 `encounter-themes/*` files above stay staged (an old cached client still loads them); the new client no longer references them.
        listOf(
            "altar",
            "podium",
            "plant",
            "counter",
            "whiteboard",
            "table-round",
            "table-long",
            "block-pew",
            "block-chair",
            "block-stool",
        ).map { "encounter-objects-v1/$it.svg" } +
        // V1.9.97: the two recorded bells (served immutable under the versioned path `encounter-sounds-v1`).
        listOf("encounter-sounds-v1/call-bell.mp3", "encounter-sounds-v1/blessing-bell.mp3") +
        // V1.9.114: optional microphone noise suppression (RNNoise): the worklet, BOTH WebAssembly variants (SIMD or not is decided in the
        // browser, a missing one is a silent failure on part of the devices) and the licence notices; served immutable under `rnnoise-v1`.
        listOf("rnnoise-worklet.js", "rnnoise.wasm", "rnnoise_simd.wasm", "LICENSES.txt").map { "rnnoise-v1/$it" } +
        // V1.9.121: the licence notice and all 250 flags of the country selection lists (flag-icons 7.5.0), served immutable under `flag-icons-v7`.
        // The flag list is the checked-in MANIFEST.sha256 (read through a tracked provider, so a change invalidates the configuration cache).
        listOf("flag-icons-v7/LICENSES.txt") + flagIconManifest.map { (path, _) -> "flag-icons-v7/$path" }

// V1.9.97: SHA-256 of the two recorded bells (V1.9.114: and of the RNNoise files); checked on the staging AND on the webpack copy.
val videoEffectExpectedSha256 =
    mapOf(
        "mediapipe/blaze_face_short_range.tflite" to "b4578f35940bf5a1a655214a1cce5cab13eba73c1297cd78e1a04c2380b0152f",
        // V1.9.152: the ten objects of the room templates (a changed drawing needs a new value here).
        "encounter-objects-v1/altar.svg" to "772a94708909aa49023a03909d214acacb2bbe3e700fd6725681d529ef69234b",
        "encounter-objects-v1/podium.svg" to "a013ba1f678ad28a07ffd70fe22764f0d97cc6e62615bf0a6af574dec1b741e0",
        "encounter-objects-v1/plant.svg" to "670579ef15da926daf4c63059451ca4787ae25a77efbd1470b2cf7a5de2b8352",
        "encounter-objects-v1/counter.svg" to "879ba8d690cceefa4a6e556f80f12d6385cc23d9389921f80d30288dcc03ec1e",
        "encounter-objects-v1/whiteboard.svg" to "05e3fad20c266d6460ef2b420e5f628ff9506f7477c9d193bd88ed7e45d7f0a2",
        "encounter-objects-v1/table-round.svg" to "7e0e0e4e4463d2d28fb67155575d1beb35c4bf7919e075e9de1b4720874606d2",
        "encounter-objects-v1/table-long.svg" to "0772f7b3e8bb44d9b49c0d57f2985d65a49378e8e45b62b3473dfd445d7be8b0",
        "encounter-objects-v1/block-pew.svg" to "816e45e3f9b1e130c087e9effcd9a1a88d30f05d6d25cbab91f765147fb6800a",
        "encounter-objects-v1/block-chair.svg" to "b17046387521a049a17d9f1b4876753e859ced6cbc0d64af0324a97269669777",
        "encounter-objects-v1/block-stool.svg" to "31b63849d8d1986c07fc75fe91a5ef2e8eb6836e343fc84f322968f1b2f437b0",
        "encounter-sounds-v1/call-bell.mp3" to "b41a4b3e8a6bc490c815a3398767bf2b9a39ec9048692587f174398430434405",
        "encounter-sounds-v1/blessing-bell.mp3" to "7eebd9ed890b21ea230dfa9c3cec8ace23e728e77c1c8d8438abd22b4d91d267",
        "rnnoise-v1/rnnoise-worklet.js" to "1e06facbb3d7459b89769aa4247cd4b71263e6662e4da0c54763f3bf06f5893b",
        "rnnoise-v1/rnnoise.wasm" to "8b60a2ab88fdae2d1a9f940249d0eb072f28ba8e796f7304347b4e07839c8853",
        "rnnoise-v1/rnnoise_simd.wasm" to "378fd17c294db15ee4e818ba5ef072242a21c8ad7445b483f7f46d7dc4f2c253",
        "rnnoise-v1/LICENSES.txt" to "042d729a8892f4cdb4d3984a8c1f2612ea5d5771a00372a6010bd2d6fcb6e9b2",
        // V1.9.121: the licence notice (flag-icons 7.5.0) and every flag (a corrupted copy would stay in browsers for a year).
        "flag-icons-v7/LICENSES.txt" to "8f1195d55a2fd315a07d812328470ca9ba2abb78c8d317ff19619d5125e00cea",
    ) + flagIconManifest.associate { (path, sha) -> "flag-icons-v7/$path" to sha }

val videoEffectStagingDir = layout.buildDirectory.dir("video-effect-assets")

// Bündelt Eigen-Assets (Hintergrundbilder, V1.9.62 auch die Begegnungsraum-Szene) + MediaPipe-WASM in EIN Staging-Verzeichnis, damit nur eine Quelle in die zwei
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
    // V1.9.62 Begegnungsraum: scene assets (the task keeps its historical name; it stages every own asset of the client).
    from(layout.projectDirectory.dir("src/jsMain/webAssets/encounter-themes")) {
        into("encounter-themes")
        exclude("PROVENANCE.adoc")
    }
    // V1.9.152: the ten objects of the fixed room templates (provenance stays in the repository).
    from(layout.projectDirectory.dir("src/jsMain/webAssets/encounter-objects-v1")) {
        into("encounter-objects-v1")
        exclude("PROVENANCE.adoc")
    }
    // V1.9.97: the recorded bells of the bell and the blessing.
    from(layout.projectDirectory.dir("src/jsMain/webAssets/encounter-sounds-v1")) {
        into("encounter-sounds-v1")
        exclude("PROVENANCE.adoc")
    }
    // V1.9.114: the RNNoise worklet, its two WebAssembly builds and the licence notices.
    from(layout.projectDirectory.dir("src/jsMain/webAssets/rnnoise-v1")) {
        into("rnnoise-v1")
        exclude("PROVENANCE.adoc")
    }
    // V1.9.121: the flags of the country selection lists (4x3 only) and the licence notice; provenance and manifest stay in the repository.
    from(layout.projectDirectory.dir("src/jsMain/webAssets/flag-icons-v7")) {
        into("flag-icons-v7")
        exclude("PROVENANCE.adoc", "MANIFEST.sha256")
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
            expectedSha256 = videoEffectExpectedSha256,
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
            expectedSha256 = videoEffectExpectedSha256,
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

// CI diagnosis (2026-10-03): Gradle's default console format for a failed test is SHORT -- "AssertionError at
// /tmp/_karma_webpack_N/commons.js:NNN", no message. The message (e.g. "timeout: both lists were reloaded exactly once
// (not reached after 15000 ms of polls, ...)" from `awaitUntil`, or Mocha's "Timeout of N ms exceeded") was only in the
// HTML report artifact. FULL prints it in the build log, where a flaky CI run is read first. The browser console of a test
// (`console.error` of `awaitUntil`, of the deadline of `formTest`, of the app itself) reaches Gradle as that test's standard
// output; `showStandardStreams` puts it into the log as well. Cheap: in the CI run of 2026-10-03 only about ten of 3328 tests
// wrote anything to the console at all.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    testLogging {
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = true
    }
}
