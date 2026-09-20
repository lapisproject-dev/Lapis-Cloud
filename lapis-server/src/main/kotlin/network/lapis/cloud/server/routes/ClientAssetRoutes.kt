package network.lapis.cloud.server.routes

import io.ktor.http.CacheControl
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

internal const val CLIENT_ASSETS_ONE_YEAR_SECONDS = 31_536_000
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
    staticFiles("/assets", clientAssetsRoot) {
        // Modell und Hintergrundbilder liegen unter einem UNVERSIONIERTEN Praefix -- hier waere `immutable`
        // falsch (ein Austausch der Datei muesste sichtbar werden), deshalb nur ein Tag und ohne `immutable`.
        cacheControl { listOf(CacheControl.MaxAge(maxAgeSeconds = CLIENT_ASSETS_ONE_DAY_SECONDS)) }
    }
}
