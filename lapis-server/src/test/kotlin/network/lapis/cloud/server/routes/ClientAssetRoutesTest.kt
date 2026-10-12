package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * V1.4.23 Videokonferenz-Hintergrundeffekte -- die zwei `Cache-Control`-Routen fuer WASM/Modell/Bilder
 * ([registerClientAssetRoutes]). Ohne sie laedt jeder Kamera-Toggle die 19 MB MediaPipe-WASM neu (der
 * Catch-all `staticFiles("/")` setzt kein `Cache-Control`). Bootet nur die Route gegen ein temporaeres
 * Verzeichnis, unabhaengig davon, ob ein Client-Build existiert.
 */
private val SOUND_FILES = listOf("call-bell.mp3", "blessing-bell.mp3")
private val SOUNDS_SOURCE: File =
    File("../lapis-client/src/jsMain/webAssets/encounter-sounds-v1")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/webAssets/encounter-sounds-v1") }

private val RNNOISE_FILES = listOf("rnnoise-worklet.js", "rnnoise.wasm", "rnnoise_simd.wasm", "LICENSES.txt")
private val RNNOISE_SOURCE: File =
    File("../lapis-client/src/jsMain/webAssets/rnnoise-v1")
        .let { if (it.exists()) it else File("lapis-client/src/jsMain/webAssets/rnnoise-v1") }

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
                // V1.9.97: the REAL recordings, so the test also proves that the served bytes are the committed ones
                SOUND_FILES.forEach { name ->
                    File(root, "assets/$ENCOUNTER_SOUNDS_ASSET_DIR/$name")
                        .apply { parentFile.mkdirs() }
                        .writeBytes(File(SOUNDS_SOURCE, name).readBytes())
                }
                // V1.9.114: the REAL RNNoise files (worklet, both WebAssembly builds, licences)
                RNNOISE_FILES.forEach { name ->
                    File(root, "assets/$RNNOISE_ASSET_DIR/$name")
                        .apply { parentFile.mkdirs() }
                        .writeBytes(File(RNNOISE_SOURCE, name).readBytes())
                }
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

        // ── V1.9.97: the two recorded bells ────────────────────────────────────────────────────────────────────────

        test(
            "V1.9.97: the recorded bells are served as audio/mpeg, immutable for a year, without a cookie, and byte for byte the committed files",
        ) {
            withAssetTree { root ->
                testApplication {
                    application { routing { registerClientAssetRoutes(clientDistRoot = root) } }
                    SOUND_FILES.forEach { name ->
                        val response = client.get("/assets/$ENCOUNTER_SOUNDS_ASSET_DIR/$name")
                        response.status shouldBe HttpStatusCode.OK
                        (response.headers[HttpHeaders.ContentType] ?: "") shouldContain "audio/mpeg"
                        response.headers.getAll(HttpHeaders.CacheControl)?.size shouldBe 1
                        response.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=31536000, immutable"
                        response.headers[HttpHeaders.SetCookie] shouldBe null
                        val served = response.readRawBytes()
                        val source = File(SOUNDS_SOURCE, name).readBytes()
                        MessageDigest.getInstance("SHA-256").digest(served).toList() shouldBe
                            MessageDigest.getInstance("SHA-256").digest(source).toList()
                    }
                }
            }
        }

        test("V1.9.97: an unknown name under the sound prefix is 404, there is no listing, and path traversal never yields a file") {
            withAssetTree { root ->
                File(root, "secret.txt").writeText("not for the browser")
                testApplication {
                    application { routing { registerClientAssetRoutes(clientDistRoot = root) } }
                    client.get("/assets/$ENCOUNTER_SOUNDS_ASSET_DIR/other.mp3").status shouldBe HttpStatusCode.NotFound
                    client.get("/assets/$ENCOUNTER_SOUNDS_ASSET_DIR/").status shouldBe HttpStatusCode.NotFound
                    listOf(
                        "/assets/$ENCOUNTER_SOUNDS_ASSET_DIR/%2e%2e/%2e%2e/secret.txt",
                        "/assets/$ENCOUNTER_SOUNDS_ASSET_DIR/..%2f..%2fsecret.txt",
                        "/assets/$ENCOUNTER_SOUNDS_ASSET_DIR/%2e%2e/call-bell.mp3x",
                    ).forEach { path -> (client.get(path).status == HttpStatusCode.OK) shouldBe false }
                }
            }
        }

        test("V1.9.97: the older assets keep one day without immutable (regression guard for the new, more specific route)") {
            withAssetTree { root ->
                testApplication {
                    application { routing { registerClientAssetRoutes(clientDistRoot = root) } }
                    val model = client.get("/assets/mediapipe/selfie_segmenter.tflite")
                    (model.headers[HttpHeaders.CacheControl] ?: "") shouldContain "max-age=86400"
                    (model.headers[HttpHeaders.CacheControl] ?: "") shouldNotContain "immutable"
                    val image = client.get("/assets/video-backgrounds/bg-sage.webp")
                    (image.headers[HttpHeaders.CacheControl] ?: "") shouldContain "max-age=86400"
                    (image.headers[HttpHeaders.CacheControl] ?: "") shouldNotContain "immutable"
                }
            }
        }

        // ── V1.9.114: the RNNoise worklet and its WebAssembly ────────────────────────────────────────────────────

        test(
            "V1.9.114: the worklet is text/javascript, both WebAssembly builds application/wasm, licences text/plain; all immutable for a year, no cookie",
        ) {
            withAssetTree { root ->
                testApplication {
                    application { routing { registerClientAssetRoutes(clientDistRoot = root) } }
                    val expectedTypes =
                        mapOf(
                            "rnnoise-worklet.js" to "text/javascript",
                            "rnnoise.wasm" to "application/wasm",
                            "rnnoise_simd.wasm" to "application/wasm",
                            "LICENSES.txt" to "text/plain",
                        )
                    expectedTypes.forEach { (name, type) ->
                        val response = client.get("/assets/$RNNOISE_ASSET_DIR/$name")
                        response.status shouldBe HttpStatusCode.OK
                        (response.headers[HttpHeaders.ContentType] ?: "") shouldContain type
                        response.headers.getAll(HttpHeaders.CacheControl)?.size shouldBe 1
                        response.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=31536000, immutable"
                        response.headers[HttpHeaders.SetCookie] shouldBe null
                        MessageDigest.getInstance("SHA-256").digest(response.readRawBytes()).toList() shouldBe
                            MessageDigest.getInstance("SHA-256").digest(File(RNNOISE_SOURCE, name).readBytes()).toList()
                    }
                }
            }
        }

        test("V1.9.114: an unknown name under the RNNoise prefix is 404, there is no listing, and path traversal never yields a file") {
            withAssetTree { root ->
                File(root, "secret.txt").writeText("not for the browser")
                testApplication {
                    application { routing { registerClientAssetRoutes(clientDistRoot = root) } }
                    client.get("/assets/$RNNOISE_ASSET_DIR/other.wasm").status shouldBe HttpStatusCode.NotFound
                    client.get("/assets/$RNNOISE_ASSET_DIR/").status shouldBe HttpStatusCode.NotFound
                    listOf(
                        "/assets/$RNNOISE_ASSET_DIR/%2e%2e/%2e%2e/secret.txt",
                        "/assets/$RNNOISE_ASSET_DIR/..%2f..%2fsecret.txt",
                    ).forEach { path -> (client.get(path).status == HttpStatusCode.OK) shouldBe false }
                }
            }
        }

        test("V1.9.114: the asset routes set no Content-Security-Policy (there is no global CSP; the KDoc names what one would need)") {
            withAssetTree { root ->
                testApplication {
                    application { routing { registerClientAssetRoutes(clientDistRoot = root) } }
                    client.get("/assets/$RNNOISE_ASSET_DIR/rnnoise.wasm").headers["Content-Security-Policy"] shouldBe null
                }
            }
            val routeSource =
                File("src/main/kotlin/network/lapis/cloud/server/routes/ClientAssetRoutes.kt")
                    .let {
                        if (it.exists()) {
                            it
                        } else {
                            File(
                                "lapis-server/src/main/kotlin/network/lapis/cloud/server/routes/ClientAssetRoutes.kt",
                            )
                        }
                    }.readText()
            // whoever introduces a CSP must update the documented needs of the worklet (wasm-unsafe-eval, same-origin module).
            // V1.9.121: the ONE exception is the flag route -- a CSP on a directly opened flag SVG (no global CSP, no other route).
            Regex("\"Content-Security-Policy\"").findAll(routeSource).count() shouldBe 1
            routeSource shouldContain "FLAG_ICONS_CONTENT_SECURITY_POLICY"
            routeSource shouldContain "wasm-unsafe-eval"
        }
    })
