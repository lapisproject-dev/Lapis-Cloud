package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files

/**
 * V1.4.23 Videokonferenz-Hintergrundeffekte -- die zwei `Cache-Control`-Routen fuer WASM/Modell/Bilder
 * ([registerClientAssetRoutes]). Ohne sie laedt jeder Kamera-Toggle die 19 MB MediaPipe-WASM neu (der
 * Catch-all `staticFiles("/")` setzt kein `Cache-Control`). Bootet nur die Route gegen ein temporaeres
 * Verzeichnis, unabhaengig davon, ob ein Client-Build existiert.
 */
class ClientAssetRoutesTest :
    FunSpec({
        fun withAssetTree(block: (File) -> Unit) {
            val root = Files.createTempDirectory("lapis-client-assets").toFile()
            try {
                val wasmDir = File(root, "assets/mediapipe/tasks-vision-$MEDIAPIPE_TASKS_VISION_VERSION/wasm").apply { mkdirs() }
                File(wasmDir, "vision_wasm_internal.wasm").writeBytes(byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0))
                File(wasmDir, "vision_wasm_internal.js").writeText("// glue")
                File(root, "assets/mediapipe/selfie_segmenter.tflite").apply { parentFile.mkdirs() }.writeBytes(ByteArray(16))
                File(root, "assets/video-backgrounds/bg-sage.webp").apply { parentFile.mkdirs() }.writeBytes(ByteArray(16))
                block(root)
            } finally {
                root.deleteRecursively()
            }
        }

        test("die versionierten WASM-Dateien kommen als application/wasm mit einem Jahr max-age, public und immutable") {
            withAssetTree { root ->
                testApplication {
                    application { routing { registerClientAssetRoutes(clientDistRoot = root) } }
                    val response =
                        client.get("/assets/mediapipe/tasks-vision-$MEDIAPIPE_TASKS_VISION_VERSION/wasm/vision_wasm_internal.wasm")
                    response.status shouldBe HttpStatusCode.OK
                    (response.headers[HttpHeaders.ContentType] ?: "") shouldContain "application/wasm"
                    // Audit-Befund N2: GENAU ein Header, und er enthaelt `immutable` -- sonst revalidiert der
                    // Browser die 9,4-MB-Datei bei jedem F5.
                    response.headers.getAll(HttpHeaders.CacheControl)?.size shouldBe 1
                    response.headers[HttpHeaders.CacheControl] shouldBe CLIENT_ASSETS_IMMUTABLE_CACHE_CONTROL
                    (response.headers[HttpHeaders.CacheControl] ?: "") shouldContain "max-age=31536000"
                    (response.headers[HttpHeaders.CacheControl] ?: "") shouldContain "immutable"
                    (response.headers[HttpHeaders.CacheControl] ?: "") shouldContain "public"
                }
            }
        }

        test("die WASM-Glue-JS-Datei bekommt dasselbe Jahres-Caching") {
            withAssetTree { root ->
                testApplication {
                    application { routing { registerClientAssetRoutes(clientDistRoot = root) } }
                    val response =
                        client.get("/assets/mediapipe/tasks-vision-$MEDIAPIPE_TASKS_VISION_VERSION/wasm/vision_wasm_internal.js")
                    response.status shouldBe HttpStatusCode.OK
                    response.headers[HttpHeaders.CacheControl] shouldBe CLIENT_ASSETS_IMMUTABLE_CACHE_CONTROL
                }
            }
        }

        test("Modell und Hintergrundbilder bekommen nur einen Tag max-age und NIE immutable (unversionierter Praefix)") {
            withAssetTree { root ->
                testApplication {
                    application { routing { registerClientAssetRoutes(clientDistRoot = root) } }
                    val model = client.get("/assets/mediapipe/selfie_segmenter.tflite")
                    model.status shouldBe HttpStatusCode.OK
                    (model.headers[HttpHeaders.CacheControl] ?: "") shouldContain "max-age=86400"
                    (model.headers[HttpHeaders.CacheControl] ?: "") shouldNotContain "immutable"
                    val image = client.get("/assets/video-backgrounds/bg-sage.webp")
                    image.status shouldBe HttpStatusCode.OK
                    (image.headers[HttpHeaders.ContentType] ?: "") shouldContain "image/webp"
                    (image.headers[HttpHeaders.CacheControl] ?: "") shouldContain "max-age=86400"
                    (image.headers[HttpHeaders.CacheControl] ?: "") shouldNotContain "immutable"
                }
            }
        }

        test("eine nicht vorhandene Datei liefert 404, ein Verzeichnis kein Listing") {
            withAssetTree { root ->
                testApplication {
                    application { routing { registerClientAssetRoutes(clientDistRoot = root) } }
                    client.get("/assets/video-backgrounds/does-not-exist.webp").status shouldBe HttpStatusCode.NotFound
                    client.get("/assets/video-backgrounds/").status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("Path-Traversal aus dem assets-Verzeichnis heraus liefert nie eine Datei") {
            withAssetTree { root ->
                File(root, "secret.txt").writeText("not for the browser")
                testApplication {
                    application { routing { registerClientAssetRoutes(clientDistRoot = root) } }
                    listOf(
                        "/assets/%2e%2e/secret.txt",
                        "/assets/%2e%2e%2fsecret.txt",
                        "/assets/video-backgrounds/%2e%2e/%2e%2e/secret.txt",
                        "/assets/..%2fsecret.txt",
                    ).forEach { path ->
                        val response = client.get(path)
                        (response.status == HttpStatusCode.OK) shouldBe false
                    }
                }
            }
        }
    })
