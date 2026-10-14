package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.VideoOverlayAssetTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoTestImages
import network.lapis.cloud.server.videooverlays.VideoOverlayImagePolicy
import network.lapis.cloud.server.videooverlays.VideoOverlayStorage
import network.lapis.cloud.server.videooverlays.VideoOverlayTestImages
import network.lapis.cloud.server.videooverlays.VideoOverlayTestState
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.VideoOverlayRules
import java.net.URLEncoder
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private fun multipart(
    bytes: ByteArray,
    contentType: String = "image/png",
    fileName: String = "overlay.png",
): MultiPartFormDataContent =
    MultiPartFormDataContent(
        formData {
            append(
                "file",
                bytes,
                Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, contentType)
                },
            )
        },
    )

private fun errorCodeOf(body: String): String? =
    Json
        .parseToJsonElement(body)
        .jsonObject["error"]
        ?.toString()
        ?.trim('"')

private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

/**
 * Welle V1.9.129 "Video-Overlays" -- the ADMIN upload route: CSRF, role, size (declared, chunked, streamed), sniffing (SVG is refused),
 * dimensions, frame rules (PNG, 16:9, real transparency), metadata stripping, the 50-element limit, rate limit and the audit entry.
 */
class VideoOverlayUploadRoutesTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { VideoOverlayTestState.reset() }
        afterSpec {
            VideoOverlayTestState.reset()
            fixtures.cleanup()
        }

        fun newRoot() = MemberPhotoFixtures.freshRoot("video-overlay")

        suspend fun HttpClient.upload(
            actor: Uuid?,
            kind: String,
            name: String,
            body: OutgoingContent,
            extraHeaders: Map<String, String> = emptyMap(),
        ): HttpResponse =
            post("/api/video-overlays/$kind?name=${enc(name)}") {
                if (actor != null) header("X-Member-Id", actor.toString())
                extraHeaders.forEach { (k, v) -> header(k, v) }
                setBody(body)
            }

        fun Routing.install(
            storage: VideoOverlayStorage,
            uploadLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
        ) {
            registerVideoOverlayRoutes(
                storage = storage,
                baseUrl = "https://lapis.example.org",
                uploadRateLimiter = uploadLimiter,
                readRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            )
        }

        fun withApp(
            root: java.io.File,
            uploadLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
        ) = testApplication {
            application {
                install(ContentNegotiation) { json() }
                routing { install(VideoOverlayStorage(root), uploadLimiter) }
            }
            block()
        }

        test("a logo PNG keeps its alpha, is stored under its UUID, the row and the audit carry no token or hash") {
            val root = newRoot()
            withApp(root) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val response = client.upload(admin, "LOGO", "Vereinslogo", multipart(bytes = VideoOverlayTestImages.logoPng()))
                response.status shouldBe HttpStatusCode.Created
                response.headers[HttpHeaders.CacheControl] shouldBe null
                val rows = VideoOverlayTestState.rows()
                rows.size shouldBe 1
                val row = rows.single()
                row[VideoOverlayAssetTable.kind] shouldBe "LOGO"
                row[VideoOverlayAssetTable.name] shouldBe "Vereinslogo"
                row[VideoOverlayAssetTable.format] shouldBe "png"
                row[VideoOverlayAssetTable.accessToken].length shouldBe 43
                val files = MemberPhotoFixtures.filesIn(root)
                files.map { it.name } shouldBe listOf("${row[VideoOverlayAssetTable.id]}.png")
                val stored = ImageIO.read(files.single())
                stored.colorModel.hasAlpha() shouldBe true
                (stored.getRGB(10, 10) ushr 24) shouldBe 0
                (stored.getRGB(500, 10) ushr 24) shouldBe 128
                // The answer names the item and its same-origin path, but the audit never carries token, hash or bytes.
                response.bodyAsText() shouldContain "/api/video-overlays/${row[VideoOverlayAssetTable.accessToken]}"
                val audit = VideoOverlayTestState.auditSnapshots()
                audit.size shouldBe 1
                audit.single() shouldContain "\"kind\":\"LOGO\""
                audit.single() shouldContain "\"name\":\"Vereinslogo\""
                audit.forEach {
                    it shouldNotContain row[VideoOverlayAssetTable.accessToken]
                    it shouldNotContain row[VideoOverlayAssetTable.sha256]
                }
            }
        }

        test("a logo JPEG is stored as a JPEG; a wide wordmark (long edge 300, short edge 64) passes, 63 px short edge is TOO_SMALL") {
            val root = newRoot()
            withApp(root) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                client
                    .upload(
                        admin,
                        "LOGO",
                        "Foto",
                        multipart(bytes = VideoOverlayTestImages.logoJpeg(), contentType = "image/jpeg", fileName = "a.jpg"),
                    ).status shouldBe
                    HttpStatusCode.Created
                VideoOverlayTestState.rows().single()[VideoOverlayAssetTable.format] shouldBe "jpg"
                client
                    .upload(
                        admin,
                        "LOGO",
                        "Wortmarke",
                        multipart(bytes = VideoOverlayTestImages.logoPng(width = 300, height = 64)),
                    ).status shouldBe
                    HttpStatusCode.Created
                val small =
                    client.upload(
                        admin,
                        "LOGO",
                        "Zu klein",
                        multipart(bytes = VideoOverlayTestImages.logoPng(width = 300, height = 63)),
                    )
                small.status shouldBe HttpStatusCode.UnprocessableEntity
                errorCodeOf(small.bodyAsText()) shouldBe "TOO_SMALL"
                val tiny =
                    client.upload(
                        admin,
                        "LOGO",
                        "Winzig",
                        multipart(bytes = VideoOverlayTestImages.logoPng(width = 200, height = 200)),
                    )
                errorCodeOf(tiny.bodyAsText()) shouldBe "TOO_SMALL"
                VideoOverlayTestState.rows().size shouldBe 2
            }
        }

        test("a larger logo is scaled down to a long edge of 1024 px, never up") {
            val root = newRoot()
            withApp(root) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                client
                    .upload(
                        admin,
                        "LOGO",
                        "Gross",
                        multipart(bytes = VideoOverlayTestImages.logoPng(width = 2000, height = 1000)),
                    ).status shouldBe
                    HttpStatusCode.Created
                val row = VideoOverlayTestState.rows().single()
                row[VideoOverlayAssetTable.width] shouldBe VideoOverlayRules.LOGO_TARGET_LONG_EDGE_PX
                row[VideoOverlayAssetTable.height] shouldBe 512
            }
        }

        test("a frame: 1920x1080 with transparency passes, 1280x720 stays 1280x720") {
            val root = newRoot()
            withApp(root) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                client.upload(admin, "FRAME", "Rahmen", multipart(bytes = VideoOverlayTestImages.framePng())).status shouldBe
                    HttpStatusCode.Created
                client
                    .upload(
                        admin,
                        "FRAME",
                        "Klein",
                        multipart(bytes = VideoOverlayTestImages.framePng(width = 1280, height = 720)),
                    ).status shouldBe
                    HttpStatusCode.Created
                val sizes = VideoOverlayTestState.rows().map { it[VideoOverlayAssetTable.width] to it[VideoOverlayAssetTable.height] }
                sizes.toSet() shouldBe setOf(1920 to 1080, 1280 to 720)
            }
        }

        test("frame refusals: no alpha, an opaque alpha channel, JPEG, 4:3, too small") {
            val root = newRoot()
            withApp(root) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val noAlpha = client.upload(admin, "FRAME", "A", multipart(bytes = VideoOverlayTestImages.rgbPng()))
                noAlpha.status shouldBe HttpStatusCode.UnprocessableEntity
                errorCodeOf(noAlpha.bodyAsText()) shouldBe "FRAME_NEEDS_ALPHA"
                val opaque = client.upload(admin, "FRAME", "B", multipart(bytes = VideoOverlayTestImages.opaqueArgbPng()))
                opaque.status shouldBe HttpStatusCode.UnprocessableEntity
                errorCodeOf(opaque.bodyAsText()) shouldBe "FRAME_NEEDS_ALPHA"
                val jpeg =
                    client.upload(
                        admin,
                        "FRAME",
                        "C",
                        multipart(
                            bytes = VideoOverlayTestImages.logoJpeg(width = 1920, height = 1080),
                            contentType = "image/jpeg",
                            fileName = "f.jpg",
                        ),
                    )
                jpeg.status shouldBe HttpStatusCode.UnsupportedMediaType
                errorCodeOf(jpeg.bodyAsText()) shouldBe "FRAME_NOT_PNG"
                val fourThree =
                    client.upload(
                        admin,
                        "FRAME",
                        "D",
                        multipart(bytes = VideoOverlayTestImages.framePng(width = 1600, height = 1200)),
                    )
                fourThree.status shouldBe HttpStatusCode.UnprocessableEntity
                errorCodeOf(fourThree.bodyAsText()) shouldBe "FRAME_WRONG_ASPECT"
                val small =
                    client.upload(
                        admin,
                        "FRAME",
                        "E",
                        multipart(bytes = VideoOverlayTestImages.framePng(width = 1000, height = 562)),
                    )
                small.status shouldBe HttpStatusCode.UnprocessableEntity
                errorCodeOf(small.bodyAsText()) shouldBe "TOO_SMALL"
                VideoOverlayTestState.rows().size shouldBe 0
                MemberPhotoFixtures.filesIn(root).size shouldBe 0
            }
        }

        test("rejected formats: SVG, GIF, PDF, text and a lie about the content type never store anything") {
            val root = newRoot()
            withApp(root) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"400\" height=\"300\"><circle r=\"50\"/></svg>".toByteArray()
                val gif = "GIF89a".toByteArray() + ByteArray(64)
                val pdf = "%PDF-1.4\n".toByteArray() + ByteArray(64)
                val text = "just some text, definitely no image".toByteArray()
                for ((name, bytes, type) in listOf(
                    Triple("svg", svg, "image/svg+xml"),
                    Triple("svg-as-png", svg, "image/png"),
                    Triple("gif", gif, "image/gif"),
                    Triple("pdf", pdf, "application/pdf"),
                    Triple("text", text, "image/png"),
                )) {
                    val response = client.upload(admin, "LOGO", name, multipart(bytes = bytes, contentType = type, fileName = "x.$name"))
                    response.status shouldBe HttpStatusCode.UnsupportedMediaType
                    errorCodeOf(response.bodyAsText()) shouldBe "UNSUPPORTED_FORMAT"
                }
                val fake =
                    client.upload(
                        admin,
                        "LOGO",
                        "fake",
                        multipart(bytes = MemberPhotoTestImages.pngHeaderOnly(width = 400, height = 300)),
                    )
                errorCodeOf(fake.bodyAsText()) shouldBe "UNDECODABLE"
                val bomb =
                    client.upload(
                        admin,
                        "LOGO",
                        "bomb",
                        multipart(bytes = MemberPhotoTestImages.pngHeaderOnly(width = 9000, height = 9000)),
                    )
                bomb.status shouldBe HttpStatusCode.UnprocessableEntity
                errorCodeOf(bomb.bodyAsText()) shouldBe "DIMENSIONS_TOO_LARGE"
                VideoOverlayTestState.rows().size shouldBe 0
                MemberPhotoFixtures.filesIn(root).size shouldBe 0
            }
        }

        test("metadata and polyglot payloads do not survive the fresh re-encode") {
            val root = newRoot()
            withApp(root) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val withMeta = MemberPhotoTestImages.pngWithMetadata(png = VideoOverlayTestImages.logoPng(), secret = "GEHEIM-GPS-50.1N")
                val polyglot = withMeta + "<html><script>alert('x')</script></html>".toByteArray()
                client.upload(admin, "LOGO", "Meta", multipart(bytes = polyglot)).status shouldBe HttpStatusCode.Created
                val stored = MemberPhotoFixtures.filesIn(root).single().readBytes()
                MemberPhotoTestImages.contains(haystack = stored, needle = "GEHEIM-GPS") shouldBe false
                MemberPhotoTestImages.contains(haystack = stored, needle = "<script>") shouldBe false
                (stored.contentEquals(polyglot)) shouldBe false
            }
        }

        test("size: a declared Content-Length above the cap, a body without Content-Length and a lying small header are 413") {
            val root = newRoot()
            withApp(root) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val huge = VideoOverlayTestImages.logoPng() + ByteArray(2 * 1024 * 1024 + 200_000) { 1 }
                val declared = client.upload(admin, "LOGO", "Gross", multipart(bytes = huge))
                declared.status shouldBe HttpStatusCode.PayloadTooLarge
                errorCodeOf(declared.bodyAsText()) shouldBe "FILE_TOO_LARGE"
                // Within the multipart allowance but above the file cap: stopped while streaming.
                val slightlyOver = VideoOverlayTestImages.logoPng() + ByteArray(2 * 1024 * 1024 + 10_000) { 1 }
                val streamed = client.upload(admin, "LOGO", "Knapp", multipart(bytes = slightlyOver))
                streamed.status shouldBe HttpStatusCode.PayloadTooLarge
                // No Content-Length at all (chunked): refused before the body is read.
                val chunked =
                    object : OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.parse("multipart/form-data; boundary=xyz")

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            channel.writeFully("--xyz--\r\n".toByteArray())
                        }
                    }
                val noLength = client.upload(admin, "LOGO", "Chunked", chunked)
                noLength.status shouldBe HttpStatusCode.PayloadTooLarge
                errorCodeOf(noLength.bodyAsText()) shouldBe "FILE_TOO_LARGE"
                // A frame's cap is 4 MB, a logo's 2 MB.
                VideoOverlayTestState.rows().size shouldBe 0
                MemberPhotoFixtures.filesIn(root).size shouldBe 0
            }
        }

        test("two file parts and a missing file are 400") {
            val root = newRoot()
            withApp(root) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val two =
                    MultiPartFormDataContent(
                        formData {
                            for (n in 1..2) {
                                append(
                                    "file$n",
                                    VideoOverlayTestImages.logoPng(),
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, "filename=\"o$n.png\"")
                                        append(HttpHeaders.ContentType, "image/png")
                                    },
                                )
                            }
                        },
                    )
                val multi = client.upload(admin, "LOGO", "Zwei", two)
                multi.status shouldBe HttpStatusCode.BadRequest
                errorCodeOf(multi.bodyAsText()) shouldBe "INVALID_REQUEST"
                val none = client.upload(admin, "LOGO", "Keine", MultiPartFormDataContent(formData { append("note", "hello") }))
                none.status shouldBe HttpStatusCode.BadRequest
                VideoOverlayTestState.rows().size shouldBe 0
            }
        }

        test("names: empty, blank, 61 characters and control characters are INVALID_NAME; 60 characters and umlauts pass") {
            val root = newRoot()
            withApp(root) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                for (bad in listOf("", "   ", "a".repeat(61), "Logo\u0007", "Logo\u200Bx")) {
                    val response = client.upload(admin, "LOGO", bad, multipart(bytes = VideoOverlayTestImages.logoPng()))
                    response.status shouldBe HttpStatusCode.BadRequest
                    errorCodeOf(response.bodyAsText()) shouldBe "INVALID_NAME"
                }
                client
                    .upload(
                        admin,
                        "LOGO",
                        "  " + "ä".repeat(60) + " ",
                        multipart(bytes = VideoOverlayTestImages.logoPng()),
                    ).status shouldBe
                    HttpStatusCode.Created
                VideoOverlayTestState.rows().single()[VideoOverlayAssetTable.name] shouldBe "ä".repeat(60)
                val badKind = client.upload(admin, "BANNER", "x", multipart(bytes = VideoOverlayTestImages.logoPng()))
                badKind.status shouldBe HttpStatusCode.BadRequest
                errorCodeOf(badKind.bodyAsText()) shouldBe "INVALID_REQUEST"
            }
        }

        test("roles: BOARD, TREASURER and MEMBER are 403, no session is 401, and nothing is stored") {
            val root = newRoot()
            withApp(root) {
                for (role in listOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.MEMBER)) {
                    val actor = fixtures.newMember(role = role)
                    val response = client.upload(actor, "LOGO", "x", multipart(bytes = VideoOverlayTestImages.logoPng()))
                    response.status shouldBe HttpStatusCode.Forbidden
                    errorCodeOf(response.bodyAsText()) shouldBe "FORBIDDEN"
                }
                client.upload(null, "LOGO", "x", multipart(bytes = VideoOverlayTestImages.logoPng())).status shouldBe
                    HttpStatusCode.Unauthorized
                VideoOverlayTestState.rows().size shouldBe 0
                MemberPhotoFixtures.filesIn(root).size shouldBe 0
            }
        }

        test("CSRF: a cross-origin, cross-site or null-origin request is 403 and stores nothing; same-origin passes") {
            val root = newRoot()
            withApp(root) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                for (headers in listOf(
                    mapOf(HttpHeaders.Origin to "https://evil.example"),
                    mapOf("Sec-Fetch-Site" to "cross-site"),
                    mapOf(HttpHeaders.Origin to "null"),
                )) {
                    val response = client.upload(admin, "LOGO", "x", multipart(bytes = VideoOverlayTestImages.logoPng()), headers)
                    response.status shouldBe HttpStatusCode.Forbidden
                    errorCodeOf(response.bodyAsText()) shouldBe "INVALID_REQUEST"
                }
                VideoOverlayTestState.rows().size shouldBe 0
                val same =
                    client.upload(
                        admin,
                        "LOGO",
                        "x",
                        multipart(bytes = VideoOverlayTestImages.logoPng()),
                        mapOf(HttpHeaders.Origin to "https://lapis.example.org", "Sec-Fetch-Site" to "same-origin"),
                    )
                same.status shouldBe HttpStatusCode.Created
            }
        }

        test("the per-actor rate limit answers 429 with Retry-After") {
            val root = newRoot()
            withApp(root, FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                client.upload(admin, "LOGO", "a", multipart(bytes = VideoOverlayTestImages.logoPng())).status shouldBe
                    HttpStatusCode.Created
                val second = client.upload(admin, "LOGO", "b", multipart(bytes = VideoOverlayTestImages.logoPng()))
                second.status shouldBe HttpStatusCode.TooManyRequests
                second.headers[HttpHeaders.RetryAfter] shouldNotBe null
                errorCodeOf(second.bodyAsText()) shouldBe "RATE_LIMITED"
            }
        }

        test("the 51st element is 409 LIBRARY_FULL and its file is cleaned up") {
            val root = newRoot()
            withApp(root) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val storage = VideoOverlayStorage(root)
                repeat(VideoOverlayRules.MAX_ITEMS) { VideoOverlayTestState.seed(storage = storage, name = "Logo $it") }
                MemberPhotoFixtures.filesIn(root).size shouldBe VideoOverlayRules.MAX_ITEMS
                val response = client.upload(admin, "LOGO", "Zuviel", multipart(bytes = VideoOverlayTestImages.logoPng()))
                response.status shouldBe HttpStatusCode.Conflict
                errorCodeOf(response.bodyAsText()) shouldBe "LIBRARY_FULL"
                VideoOverlayTestState.rows().size shouldBe VideoOverlayRules.MAX_ITEMS
                MemberPhotoFixtures.filesIn(root).size shouldBe VideoOverlayRules.MAX_ITEMS
            }
        }

        test("the re-encoder always writes an ARGB PNG, which is why the alpha test of a frame runs on the UPLOAD and on pixels") {
            // pngHasAlpha(re-encoded bytes) would be vacuously true for every PNG -- documented here so nobody moves the check.
            val rgb = VideoOverlayTestImages.rgbPng(width = 400, height = 300)
            VideoOverlayImagePolicy.pngHasAlpha(rgb) shouldBe false
            val recoded =
                kotlinx.coroutines.runBlocking {
                    network.lapis.cloud.server.events.EventCoverImageProcessor.process(
                        bytes = rgb,
                        format = network.lapis.cloud.server.events.CoverImageFormat.PNG,
                        limits = network.lapis.cloud.server.events.CoverImageLimits.VIDEO_OVERLAY_LOGO,
                    ) as network.lapis.cloud.server.events.CoverProcessingResult.Ok
                }
            VideoOverlayImagePolicy.pngHasAlpha(recoded.bytes) shouldBe true
            VideoOverlayImagePolicy.hasTransparentPixel(recoded.bytes) shouldBe false
        }
    })
