package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.events.EventCoverStorage
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoTestImages
import network.lapis.cloud.shared.domain.AccountRole
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private fun multipart(
    bytes: ByteArray,
    contentType: String = "image/png",
    fileName: String = "crest.png",
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

/** A 128x128 ARGB PNG whose left half is fully transparent and whose right half is half-transparent red. */
private fun alphaPng(): ByteArray {
    val img =
        MemberPhotoTestImages.image(width = 128, height = 128, type = BufferedImage.TYPE_INT_ARGB) { g ->
            g.color = Color(255, 0, 0, 128)
            g.fillRect(64, 0, 64, 128)
        }
    return MemberPhotoTestImages.png(img)
}

private fun solidPng(
    width: Int = 200,
    height: Int = 200,
): ByteArray = MemberPhotoTestImages.png(MemberPhotoTestImages.solid(width = width, height = height))

private fun solidJpeg(
    width: Int = 200,
    height: Int = 200,
): ByteArray = MemberPhotoTestImages.jpeg(MemberPhotoTestImages.solid(width = width, height = height))

/**
 * Welle V1.9.20 "Öffentliche Seiten" -- the chapter crest routes: upload security (CSRF, role, size,
 * sniffing, polyglots, metadata, alpha, minimum size, rate limit, IDOR-free) and the public delivery
 * (headers, no CORS, one identical 404 for every miss, per-IP limit before any lookup).
 */
class ChapterCrestRoutesTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup() }

        fun newRoot() = MemberPhotoFixtures.freshRoot("chapter-crest")

        fun rowOf(chapter: Uuid) = transaction { RegionalChapterTable.selectAll().where { RegionalChapterTable.id eq chapter }.single() }

        suspend fun HttpClient.upload(
            actor: Uuid?,
            chapter: String,
            body: MultiPartFormDataContent,
            extraHeaders: Map<String, String> = emptyMap(),
        ): HttpResponse =
            post("/api/regional-chapters/$chapter/crest") {
                if (actor != null) header("X-Member-Id", actor.toString())
                extraHeaders.forEach { (k, v) -> header(k, v) }
                setBody(body)
            }

        fun io.ktor.server.routing.Routing.install(
            storage: EventCoverStorage,
            uploadLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            readLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
        ) {
            registerChapterCrestRoutes(
                storage = storage,
                baseUrl = "https://lapis.example.org",
                uploadRateLimiter = uploadLimiter,
                publicReadRateLimiter = readLimiter,
            )
        }

        // ── upload ────────────────────────────────────────────────────────────────

        test(
            "happy path: a PNG with alpha stays a PNG WITH its alpha, the row carries id/token/type together, the audit records a boolean",
        ) {
            val root = newRoot()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(EventCoverStorage(root)) }
                }
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val chapter = fixtures.newChapter(name = "LV Alpha")
                val response = client.upload(board, chapter.toString(), multipart(bytes = alphaPng()))
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"

                val row = rowOf(chapter)
                row[RegionalChapterTable.crestContentType] shouldBe "image/png"
                (row[RegionalChapterTable.crestImageId] != null) shouldBe true
                row[RegionalChapterTable.crestPublicToken]!!.length shouldBe 43
                val files = MemberPhotoFixtures.filesIn(root)
                files.map { it.name } shouldBe listOf("${row[RegionalChapterTable.crestImageId]}.png")
                val stored = ImageIO.read(files.single())
                stored.colorModel.hasAlpha() shouldBe true
                (stored.getRGB(10, 10) ushr 24) shouldBe 0 // transparent half stays transparent
                (stored.getRGB(100, 10) ushr 24) shouldBe 128 // half-transparent red stays half-transparent

                val audit = fixtures.chapterAuditAfterSnapshots(chapter)
                audit.last() shouldContain "\"crestPresent\":true"
                audit.forEach { it shouldNotContain row[RegionalChapterTable.crestPublicToken]!! }
            }
        }

        test("a JPEG is stored as a JPEG; ADMIN may upload too") {
            val root = newRoot()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(EventCoverStorage(root)) }
                }
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val chapter = fixtures.newChapter()
                client
                    .upload(
                        admin,
                        chapter.toString(),
                        multipart(bytes = solidJpeg(), contentType = "image/jpeg", fileName = "c.jpg"),
                    ).status shouldBe
                    HttpStatusCode.OK
                rowOf(chapter)[RegionalChapterTable.crestContentType] shouldBe "image/jpeg"
                MemberPhotoFixtures
                    .filesIn(root)
                    .single()
                    .name
                    .endsWith(".jpg") shouldBe true
            }
        }

        test("a small logo is accepted: exactly 64 x 64 passes, 63 px is TOO_SMALL (an event cover would need 800 x 600)") {
            val root = newRoot()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(EventCoverStorage(root)) }
                }
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val chapter = fixtures.newChapter()
                client.upload(board, chapter.toString(), multipart(bytes = solidPng(width = 64, height = 64))).status shouldBe
                    HttpStatusCode.OK
                val small = client.upload(board, chapter.toString(), multipart(bytes = solidPng(width = 63, height = 300)))
                small.status shouldBe HttpStatusCode.UnprocessableEntity
                errorCodeOf(small.bodyAsText()) shouldBe "TOO_SMALL"
            }
        }

        test("replacing mints a NEW token, the old token answers 404, the old file is deleted, exactly one file remains") {
            val root = newRoot()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(EventCoverStorage(root)) }
                }
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val chapter = fixtures.newChapter()
                client.upload(board, chapter.toString(), multipart(bytes = solidPng())).status shouldBe HttpStatusCode.OK
                val oldToken = rowOf(chapter)[RegionalChapterTable.crestPublicToken]!!
                client.get("/public/chapter-crests/$oldToken").status shouldBe HttpStatusCode.OK

                client.upload(board, chapter.toString(), multipart(bytes = solidPng(width = 300, height = 300))).status shouldBe
                    HttpStatusCode.OK
                val newToken = rowOf(chapter)[RegionalChapterTable.crestPublicToken]!!
                (newToken == oldToken) shouldBe false
                client.get("/public/chapter-crests/$oldToken").status shouldBe HttpStatusCode.NotFound
                client.get("/public/chapter-crests/$newToken").status shouldBe HttpStatusCode.OK
                MemberPhotoFixtures.filesIn(root).size shouldBe 1
            }
        }

        test("rejected formats: SVG, GIF, WebP and garbage with a PNG signature never store anything") {
            val root = newRoot()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(EventCoverStorage(root)) }
                }
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val chapter = fixtures.newChapter()
                val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".toByteArray()
                val gif = "GIF89a".toByteArray() + ByteArray(64)
                val webp = "RIFF".toByteArray() + byteArrayOf(0x24, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(64)
                for (
                (name, bytes, contentType) in
                listOf(
                    Triple("svg", svg, "image/svg+xml"),
                    Triple("gif", gif, "image/gif"),
                    Triple("webp", webp, "image/webp"),
                    // The client-declared type is never consulted -- a lie about it changes nothing.
                    Triple("svg-as-png", svg, "image/png"),
                )
                ) {
                    val response =
                        client.upload(
                            board,
                            chapter.toString(),
                            multipart(bytes = bytes, contentType = contentType, fileName = "x.$name"),
                        )
                    response.status shouldBe HttpStatusCode.UnsupportedMediaType
                    errorCodeOf(response.bodyAsText()) shouldBe "UNSUPPORTED_FORMAT"
                }
                val fake = MemberPhotoTestImages.pngHeaderOnly(width = 100, height = 100)
                val undecodable = client.upload(board, chapter.toString(), multipart(bytes = fake))
                undecodable.status shouldBe HttpStatusCode.UnprocessableEntity
                errorCodeOf(undecodable.bodyAsText()) shouldBe "UNDECODABLE"
                MemberPhotoFixtures.filesIn(root).size shouldBe 0
                rowOf(chapter)[RegionalChapterTable.crestImageId] shouldBe null
            }
        }

        test("a decompression bomb header (9000 x 9000) is refused BEFORE any pixel is decoded") {
            val root = newRoot()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(EventCoverStorage(root)) }
                }
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val chapter = fixtures.newChapter()
                val response =
                    client.upload(
                        board,
                        chapter.toString(),
                        multipart(bytes = MemberPhotoTestImages.pngHeaderOnly(width = 9000, height = 9000)),
                    )
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                errorCodeOf(response.bodyAsText()) shouldBe "DIMENSIONS_TOO_LARGE"
                MemberPhotoFixtures.filesIn(root).size shouldBe 0
            }
        }

        test("polyglot and metadata: trailing HTML and tEXt/eXIf payloads do not survive the fresh re-encode") {
            val root = newRoot()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(EventCoverStorage(root)) }
                }
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val chapter = fixtures.newChapter()
                val base = solidPng()
                val polyglot = base + "<html><script>alert('polyglot')</script></html>".toByteArray()
                client.upload(board, chapter.toString(), multipart(bytes = polyglot)).status shouldBe HttpStatusCode.OK
                var stored = MemberPhotoFixtures.filesIn(root).single().readBytes()
                MemberPhotoTestImages.contains(haystack = stored, needle = "<script>") shouldBe false

                val withMeta = MemberPhotoTestImages.pngWithMetadata(png = solidPng(), secret = "GEHEIM-GPS-50.1N")
                client.upload(board, chapter.toString(), multipart(bytes = withMeta)).status shouldBe HttpStatusCode.OK
                stored = MemberPhotoFixtures.filesIn(root).single().readBytes()
                MemberPhotoTestImages.contains(haystack = stored, needle = "GEHEIM-GPS") shouldBe false

                val exifJpeg = MemberPhotoTestImages.jpegWithExif(jpeg = solidJpeg(), orientation = 1, secret = "GEHEIM-EXIF-ORT")
                client.upload(board, chapter.toString(), multipart(bytes = exifJpeg, contentType = "image/jpeg")).status shouldBe
                    HttpStatusCode.OK
                stored = MemberPhotoFixtures.filesIn(root).single().readBytes()
                MemberPhotoTestImages.contains(haystack = stored, needle = "GEHEIM-EXIF") shouldBe false
            }
        }

        test("size caps: a declared Content-Length above 2 MB + framing is refused with FILE_TOO_LARGE before the body is read") {
            val root = newRoot()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(EventCoverStorage(root)) }
                }
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val chapter = fixtures.newChapter()
                val huge = solidPng() + ByteArray(2 * 1024 * 1024 + 200_000) { 1 }
                val response = client.upload(board, chapter.toString(), multipart(bytes = huge))
                response.status shouldBe HttpStatusCode.PayloadTooLarge
                errorCodeOf(response.bodyAsText()) shouldBe "FILE_TOO_LARGE"
                MemberPhotoFixtures.filesIn(root).size shouldBe 0
            }
        }

        test("CSRF: a cross-origin or cross-site request is refused with 403 and stores nothing; same-origin passes") {
            val root = newRoot()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(EventCoverStorage(root)) }
                }
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val chapter = fixtures.newChapter()
                val crossOrigin =
                    client.upload(
                        board,
                        chapter.toString(),
                        multipart(bytes = solidPng()),
                        mapOf(
                            HttpHeaders.Origin to "https://evil.example",
                        ),
                    )
                crossOrigin.status shouldBe HttpStatusCode.Forbidden
                val crossSite =
                    client.upload(
                        board,
                        chapter.toString(),
                        multipart(bytes = solidPng()),
                        mapOf(
                            "Sec-Fetch-Site" to "cross-site",
                        ),
                    )
                crossSite.status shouldBe HttpStatusCode.Forbidden
                val nullOrigin =
                    client.upload(
                        board,
                        chapter.toString(),
                        multipart(bytes = solidPng()),
                        mapOf(HttpHeaders.Origin to "null"),
                    )
                nullOrigin.status shouldBe HttpStatusCode.Forbidden
                MemberPhotoFixtures.filesIn(root).size shouldBe 0

                val same =
                    client.upload(
                        board,
                        chapter.toString(),
                        multipart(bytes = solidPng()),
                        mapOf(HttpHeaders.Origin to "https://lapis.example.org", "Sec-Fetch-Site" to "same-origin"),
                    )
                same.status shouldBe HttpStatusCode.OK
            }
        }

        test("roles: MEMBER and TREASURER are 403, no session is 401, unknown or malformed chapter ids are 404 and leave no file") {
            val root = newRoot()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(EventCoverStorage(root)) }
                }
                val chapter = fixtures.newChapter()
                val member = fixtures.newMember(role = AccountRole.MEMBER)
                val treasurer = fixtures.newMember(role = AccountRole.TREASURER)
                val board = fixtures.newMember(role = AccountRole.BOARD)

                val asMember = client.upload(member, chapter.toString(), multipart(bytes = solidPng()))
                asMember.status shouldBe HttpStatusCode.Forbidden
                errorCodeOf(asMember.bodyAsText()) shouldBe "FORBIDDEN"
                client.upload(treasurer, chapter.toString(), multipart(bytes = solidPng())).status shouldBe HttpStatusCode.Forbidden
                client.upload(null, chapter.toString(), multipart(bytes = solidPng())).status shouldBe HttpStatusCode.Unauthorized

                // The status for an unknown chapter is only reachable AFTER the role check.
                client.upload(member, Uuid.random().toString(), multipart(bytes = solidPng())).status shouldBe HttpStatusCode.Forbidden
                client.upload(board, Uuid.random().toString(), multipart(bytes = solidPng())).status shouldBe HttpStatusCode.NotFound
                client.upload(board, "not-a-uuid", multipart(bytes = solidPng())).status shouldBe HttpStatusCode.NotFound
                MemberPhotoFixtures.filesIn(root).size shouldBe 0
                rowOf(chapter)[RegionalChapterTable.crestImageId] shouldBe null
            }
        }

        test("the per-actor upload rate limit answers 429 with Retry-After") {
            val root = newRoot()
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        install(EventCoverStorage(root), uploadLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes))
                    }
                }
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val chapter = fixtures.newChapter()
                client.upload(board, chapter.toString(), multipart(bytes = solidPng())).status shouldBe HttpStatusCode.OK
                val second = client.upload(board, chapter.toString(), multipart(bytes = solidPng()))
                second.status shouldBe HttpStatusCode.TooManyRequests
                second.headers[HttpHeaders.RetryAfter] shouldNotBe null
                errorCodeOf(second.bodyAsText()) shouldBe "RATE_LIMITED"
            }
        }

        // ── public delivery ───────────────────────────────────────────────────────

        test("delivery: 200 with the exact defensive headers, the stored content type, Cache-Control exactly once -- and NO CORS") {
            val root = newRoot()
            val storage = EventCoverStorage(root)
            testApplication {
                application { routing { install(storage) } }
                val chapter = fixtures.newChapter()
                val token = fixtures.seedCrest(storage = storage, chapterId = chapter)
                val response = client.get("/public/chapter-crests/$token") { header(HttpHeaders.Origin, "https://partei.example") }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.ContentType] shouldBe "image/png"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers[HttpHeaders.ContentDisposition] shouldBe "inline"
                response.headers["Content-Security-Policy"] shouldBe "default-src 'none'; sandbox"
                response.headers["Cross-Origin-Resource-Policy"] shouldBe "cross-origin"
                response.headers["Referrer-Policy"] shouldBe "no-referrer"
                response.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("public, max-age=300")
                response.headers[HttpHeaders.AccessControlAllowOrigin] shouldBe null
                ImageIO.read(response.bodyAsBytes().inputStream()).width shouldBe 128
            }
        }

        test("delivery: every kind of miss is the SAME bare 404 with the same defensive headers") {
            val root = newRoot()
            val storage = EventCoverStorage(root)
            testApplication {
                application { routing { install(storage) } }
                val chapter = fixtures.newChapter()
                val token = fixtures.seedCrest(storage = storage, chapterId = chapter)
                val gone = fixtures.newChapter()
                val goneToken = fixtures.seedCrest(storage = storage, chapterId = gone)
                // The row stays, the FILE is missing (DB/filesystem drift).
                MemberPhotoFixtures.filesIn(root).first { it.name != "${rowOf(chapter)[RegionalChapterTable.crestImageId]}.png" }.delete()
                val unknown = "A".repeat(43)
                val misses =
                    listOf(
                        unknown,
                        "abc",
                        "A".repeat(42),
                        "A".repeat(44),
                        "${"A".repeat(42)}!",
                        "..%2F..%2Fetc%2Fpasswd",
                        goneToken,
                    )
                val shapes =
                    misses.map { candidate ->
                        val response = client.get("/public/chapter-crests/$candidate")
                        response.status shouldBe HttpStatusCode.NotFound
                        response.bodyAsText() shouldBe ""
                        response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                        response.headers["Content-Security-Policy"] shouldBe "default-src 'none'; sandbox"
                        response.headers["Cross-Origin-Resource-Policy"] shouldBe "cross-origin"
                        response.headers[HttpHeaders.CacheControl] shouldBe null
                        response.headers.names().toSet()
                    }
                shapes.toSet().size shouldBe 1
                client.get("/public/chapter-crests/$token").status shouldBe HttpStatusCode.OK
            }
        }

        test("delivery: the crest is gone the moment it is removed (row cleared -> 404 for the old token)") {
            val root = newRoot()
            val storage = EventCoverStorage(root)
            testApplication {
                application { routing { install(storage) } }
                val chapter = fixtures.newChapter()
                val token = fixtures.seedCrest(storage = storage, chapterId = chapter)
                client.get("/public/chapter-crests/$token").status shouldBe HttpStatusCode.OK
                transaction {
                    network.lapis.cloud.server.chapters.ChapterCrestStore
                        .clearCrest(chapter)
                }
                client.get("/public/chapter-crests/$token").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("delivery: the per-IP rate limit applies BEFORE the token is even examined") {
            val root = newRoot()
            testApplication {
                application {
                    routing {
                        install(EventCoverStorage(root), readLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes))
                    }
                }
                client.get("/public/chapter-crests/garbage").status shouldBe HttpStatusCode.NotFound
                client.get("/public/chapter-crests/garbage").status shouldBe HttpStatusCode.TooManyRequests
            }
        }
    })
