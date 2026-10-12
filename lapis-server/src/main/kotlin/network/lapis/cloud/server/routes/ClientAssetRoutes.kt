package network.lapis.cloud.server.routes

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import java.io.File

/**
 * V1.4.23 -- version of the `@mediapipe/tasks-vision` WASM the client requests from
 * `/assets/mediapipe/tasks-vision-<version>/wasm`. Third occurrence of this value (after
 * `ConferenceBackgroundAssets.MEDIAPIPE_TASKS_VISION_VERSION` in the client and
 * `mediaPipeTasksVisionVersion` in `lapis-client/build.gradle.kts`); the Gradle task
 * `verifyMediaPipeVersion` asserts the installed package matches. Keep all three in step.
 */
internal const val MEDIAPIPE_TASKS_VISION_VERSION = "0.10.14"

/**
 * V1.9.97 -- directory of the two recorded bells (call and blessing) under `/assets`. Third occurrence of this name (after the
 * client constant `ENCOUNTER_SOUNDS_DIR` in `EncounterSoundFetch.kt` and `into("encounter-sounds-v1")` in `lapis-client/build.gradle.kts`);
 * `EncounterSoundAssetsTest` keeps all three together. The path is versioned: if the bytes ever change, the directory becomes `-v2`.
 */
internal const val ENCOUNTER_SOUNDS_ASSET_DIR = "encounter-sounds-v1"

/**
 * V1.9.114 -- directory of the optional microphone noise suppression (RNNoise worklet, two WebAssembly builds, licence notices) under
 * `/assets`. Third occurrence of this name (after `NoiseSuppressionAssets.DIR` in the client's `ConferenceNoiseSuppression.kt` and
 * `into("rnnoise-v1")` in `lapis-client/build.gradle.kts`); `RnnoiseAssetsTest` keeps all three together. Versioned: if the bytes ever
 * change, the directory becomes `-v2`.
 */
internal const val RNNOISE_ASSET_DIR = "rnnoise-v1"

/**
 * V1.9.121 -- directory of the country flags (flag-icons 7.5.0, 4x3 SVG, 250 files) and their licence notice under `/assets`. Third
 * occurrence of this name (after `CountryFlag.DIR` in the client's `CountryFlag.kt` and `into("flag-icons-v7")` in
 * `lapis-client/build.gradle.kts`); `FlagAssetsTest` keeps all three together. Versioned: if the bytes ever change, the directory becomes `-v8`.
 */
internal const val FLAG_ICONS_ASSET_DIR = "flag-icons-v7"

internal const val CLIENT_ASSETS_ONE_YEAR_SECONDS = 31_536_000

/** V1.9.121 -- CSP of a directly opened flag SVG: no script, no subresource, no framing of anything; inline `style` attributes stay allowed. */
internal const val FLAG_ICONS_CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'unsafe-inline'; sandbox"
internal const val CLIENT_ASSETS_ONE_DAY_SECONDS = 86_400

/**
 * `Cache-Control` fuer den VERSIONIERTEN WASM-Pfad (Audit-Befund N2). Als Rohtext, weil Ktors
 * [CacheControl.MaxAge] die `immutable`-Direktive (RFC 8246) nicht ausdruecken kann -- und genau sie ist hier
 * der Punkt: ohne `immutable` schickt der Browser bei jedem Neuladen der Seite (F5) eine
 * Revalidierungs-Anfrage fuer die 9,4-MB-Datei, obwohl der Pfad die Paketversion traegt und sich der Inhalt
 * unter dieser URL niemals aendern kann. `public`, damit auch ein zwischengeschalteter Cache (Caddy, CDN)
 * die Datei halten darf; die WASM ist oeffentlich und enthaelt keine nutzerbezogenen Daten.
 */
internal const val CLIENT_ASSETS_IMMUTABLE_CACHE_CONTROL = "public, max-age=$CLIENT_ASSETS_ONE_YEAR_SECONDS, immutable"

/**
 * V1.4.23 Videokonferenz-Hintergrundeffekte -- die MediaPipe-WASM-Dateien sind 19 MB und werden vom Browser bei
 * JEDEM Prozessor-Restart erneut angefordert (Kamera aus/an, Geraetewechsel, Reconnect -- livekit-client's
 * `setMediaStreamTrack` ruft `processor.restart()`, das den ImageSegmenter samt Fileset neu aufbaut). Der
 * Catch-all `staticFiles("/")` in `Application.kt` setzt WEDER `Cache-Control` NOCH `ETag`/`Last-Modified`
 * (Ktor-Defaults: `cacheControl = { emptyList() }`, `etagExtractor = { null }`), und weder ConditionalHeaders noch
 * CachingHeaders sind installiert -- ohne diese zwei Routen laedt jeder Kamera-Toggle 19 MB neu.
 *
 * Der WASM-Pfad traegt die Paketversion, deshalb ist dort ein Jahres-`max-age` samt `immutable` korrekt
 * ([CLIENT_ASSETS_IMMUTABLE_CACHE_CONTROL]); Modell und
 * Hintergrundbilder liegen unter dem unversionierten `/assets`-Praefix und bekommen nur einen Tag. Reine
 * Routing-Ergaenzung: keine Logik, kein Schema, keine Auth, nur `Cache-Control`. Ktor liefert `.wasm` als
 * `application/wasm` und `.webp` als `image/webp`; `.tflite` ist unbekannt (`application/octet-stream`), fuer
 * `fetch` + `arrayBuffer` unproblematisch.
 *
 * Falls je eine SPA-weite CSP eingefuehrt wird (heute existiert keine globale): dieses Feature braucht
 * `script-src 'wasm-unsafe-eval'`, `worker-src 'self' blob:` und `img-src 'self'`.
 *
 * V1.9.114 (Rauschunterdrueckung): die WebAssembly-Instanziierung laeuft im AudioWorklet-Scope und braucht ebenfalls
 * `script-src 'wasm-unsafe-eval'`; `audioWorklet.addModule` laedt das Modul nach `script-src` (same-origin: `'self'`). Das Worklet wird
 * deshalb von `/assets/rnnoise-v1/` geladen und nie als `blob:`. Dieser Server setzt bewusst KEINEN globalen CSP-Header
 * (`ClientAssetRoutesTest` haelt das fest); wer einen einfuehrt, muss `docs/architecture/video-background-effects.adoc` mitziehen.
 *
 * Muss VOR dem Catch-all `staticFiles("/", ...)` registriert werden (das spezifischere Praefix gewinnt im
 * Routing-Baum ohnehin, die Reihenfolge dokumentiert die Absicht).
 */
internal fun Route.registerClientAssetRoutes(clientDistRoot: File) {
    val clientAssetsRoot = File(clientDistRoot, "assets")
    staticFiles(
        "/assets/mediapipe/tasks-vision-$MEDIAPIPE_TASKS_VISION_VERSION",
        File(clientAssetsRoot, "mediapipe/tasks-vision-$MEDIAPIPE_TASKS_VISION_VERSION"),
    ) {
        // Bewusst `modify` statt `cacheControl`: Ktors CacheControl-Typen kennen `immutable` nicht (siehe
        // CLIENT_ASSETS_IMMUTABLE_CACHE_CONTROL). Ktors Default fuer `cacheControl` ist `{ emptyList() }`,
        // deshalb entsteht hier genau EIN Cache-Control-Header, kein doppelter.
        modify { _, call -> call.response.header(HttpHeaders.CacheControl, CLIENT_ASSETS_IMMUTABLE_CACHE_CONTROL) }
    }
    // V1.9.97: the two recorded bells. Versioned path -> immutable, like the WASM; explicit `audio/mpeg` (not left to Ktor's table), no listing.
    // Registered before the generic `/assets` prefix. No logging here (the access path is the only trace, see dsgvo.adoc).
    staticFiles("/assets/$ENCOUNTER_SOUNDS_ASSET_DIR", File(clientAssetsRoot, ENCOUNTER_SOUNDS_ASSET_DIR)) {
        modify { _, call -> call.response.header(HttpHeaders.CacheControl, CLIENT_ASSETS_IMMUTABLE_CACHE_CONTROL) }
        contentType { ContentType.Audio.MPEG }
    }
    // V1.9.114: the RNNoise worklet and its WebAssembly. Versioned path -> immutable; explicit content types (a worklet module must come as
    // JavaScript, `WebAssembly.compile` of a fetched body needs no MIME but `application/wasm` keeps streaming compilation possible).
    // Same-origin on purpose (not a `blob:` URL): a future CSP then needs only 'self' for the worklet. Registered before the generic prefix.
    staticFiles("/assets/$RNNOISE_ASSET_DIR", File(clientAssetsRoot, RNNOISE_ASSET_DIR)) {
        modify { _, call -> call.response.header(HttpHeaders.CacheControl, CLIENT_ASSETS_IMMUTABLE_CACHE_CONTROL) }
        contentType { file ->
            when (file.extension) {
                "js" -> ContentType.Text.JavaScript
                "wasm" -> ContentType("application", "wasm")
                else -> ContentType.Text.Plain
            }
        }
    }
    // V1.9.121: the country flags. Versioned path -> immutable. SVG is active content when somebody opens the URL directly, so the
    // response says exactly what it is (`image/svg+xml`, `nosniff`) and a restrictive CSP that blocks scripts and every subresource. In the
    // pages the flags are plain `<img>` elements, where a browser never runs script in an SVG anyway; the files are also scanned (FlagAssetsTest).
    staticFiles("/assets/$FLAG_ICONS_ASSET_DIR", File(clientAssetsRoot, FLAG_ICONS_ASSET_DIR)) {
        modify { _, call ->
            call.response.header(HttpHeaders.CacheControl, CLIENT_ASSETS_IMMUTABLE_CACHE_CONTROL)
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("Content-Security-Policy", FLAG_ICONS_CONTENT_SECURITY_POLICY)
        }
        contentType { file -> if (file.extension == "svg") ContentType.Image.SVG else ContentType.Text.Plain }
    }
    staticFiles("/assets", clientAssetsRoot) {
        // Modell und Hintergrundbilder liegen unter einem UNVERSIONIERTEN Praefix -- hier waere `immutable`
        // falsch (ein Austausch der Datei muesste sichtbar werden), deshalb nur ein Tag und ohne `immutable`.
        cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = CLIENT_ASSETS_ONE_DAY_SECONDS)) }
    }
}
