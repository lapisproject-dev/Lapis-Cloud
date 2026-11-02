package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.conference.FfmpegProcessRunner
import network.lapis.cloud.server.conference.pausefeed.FfprobeAudioProber
import network.lapis.cloud.server.conference.pausefeed.PauseMediaDb
import network.lapis.cloud.server.conference.pausefeed.PauseMediaStore
import network.lapis.cloud.server.conference.pausefeed.PauseMusicProcessor
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberbio.PublicProfilesFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoTestImages
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.toOrganizationSettingsDto
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.awt.Color
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private fun multipart(
    bytes: ByteArray,
    contentType: String = "image/png",
    fileName: String = "x.png",
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

private fun picture(
    w: Int,
    h: Int,
) = MemberPhotoTestImages.png(MemberPhotoTestImages.solid(width = w, height = h, color = Color.ORANGE))

private val FFMPEG: Boolean =
    runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false)

private fun tone(seconds: Int): ByteArray {
    val f = Files.createTempFile("tone", ".wav").toFile()
    ProcessBuilder(
        "ffmpeg",
        "-nostdin",
        "-v",
        "error",
        "-y",
        "-f",
        "lavfi",
        "-i",
        "sine=frequency=440:duration=$seconds",
        f.path,
    ).start().waitFor()
    return f.readBytes().also { f.delete() }
}

/**
 * V1.9.255 -- the pause picture / pause music routes: ADMIN only on every method, same-origin on every write, size, sniff, dimension and
 * duration rules, the fixed-vocabulary answers, the stored file names, the key swap with the deferred deletion of the old file, the audit.
 */
class PauseMediaRoutesTest :
    FunSpec({
        val fixtures = PublicProfilesFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest {
            transaction {
                PauseMediaDb.setImage(key = null, now = null)
                PauseMediaDb.setMusic(key = null, label = null, durationSeconds = null, now = null)
            }
        }
        afterSpec { fixtures.cleanup() }

        fun withApp(
            root: File,
            feedsRunning: () -> Boolean = { false },
            ffmpegAvailable: Boolean = FFMPEG,
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.(PauseMediaStore) -> Unit,
        ) = testApplication {
            val store = PauseMediaStore(root = root, feedsRunning = feedsRunning)
            application {
                install(ContentNegotiation) { json() }
                routing {
                    registerPauseMediaRoutes(
                        store = store,
                        musicProcessor =
                            PauseMusicProcessor(
                                runner = FfmpegProcessRunner("ffmpeg"),
                                prober = FfprobeAudioProber("ffprobe"),
                            ),
                        ffmpegAvailable = ffmpegAvailable,
                        baseUrl = "https://lapis.example.org",
                        uploadRateLimiter = limiter,
                        readRateLimiter = FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes),
                    )
                }
            }
            block(store)
        }

        fun root() = MemberPhotoFixtures.freshRoot("pause-media")

        fun storedFiles(root: File) =
            MemberPhotoFixtures.filesIn(root).map { it.name }.filter { Regex("[0-9a-f-]{36}\\.(png|m4a)").matches(it) }

        suspend fun HttpClient.postImage(
            actor: Uuid?,
            body: OutgoingContent,
            vararg headers: Pair<String, String>,
        ): HttpResponse =
            post("/api/conference/pause-media/image") {
                if (actor != null) header("X-Member-Id", actor.toString())
                headers.forEach { (k, v) -> header(k, v) }
                setBody(body)
            }

        test("an ADMIN stores a picture: 1280x720 PNG under its UUID, the settings row holds the key, one audit entry without names") {
            val dir = root()
            withApp(dir) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val response = client.postImage(admin, multipart(bytes = picture(w = 1024, h = 768)))
                response.status shouldBe HttpStatusCode.Created
                val body = response.bodyAsText()
                body shouldContain "\"imageCustom\":true"
                val key = transaction { PauseMediaDb.load().imageKey }!!
                storedFiles(dir) shouldBe listOf("$key.png")
                val stored = ImageIO.read(File(dir, "$key.png"))
                (stored.width to stored.height) shouldBe (1280 to 720)
                val audits =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                AuditLogEntryTable.entityType eq AuditEntityType.ORGANIZATION_SETTINGS
                            }.mapNotNull { it[AuditLogEntryTable.afterSnapshot] }
                    }
                audits.contains("""{"pauseImage":"set"}""") shouldBe true
                // GET: the stored file, fixed headers, never cached
                val get = client.get("/api/conference/pause-media/image?v=1") { header("X-Member-Id", admin.toString()) }
                get.status shouldBe HttpStatusCode.OK
                get.headers[HttpHeaders.ContentType] shouldBe "image/png"
                get.headers["X-Content-Type-Options"] shouldBe "nosniff"
                get.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
                get.headers[HttpHeaders.ContentDisposition]!!.startsWith("inline") shouldBe true
            }
        }

        test("everybody but an ADMIN gets the same 403 on every method; no session is 401; a cross-origin write is 403") {
            withApp(root()) {
                val member = fixtures.newMember(role = AccountRole.MEMBER)
                val board = fixtures.newMember(role = AccountRole.BOARD)
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                listOf(member, board).forEach { who ->
                    client.postImage(who, multipart(bytes = picture(w = 800, h = 450))).status shouldBe HttpStatusCode.Forbidden
                    client.delete("/api/conference/pause-media/image") { header("X-Member-Id", who.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                    client.get("/api/conference/pause-media/image") { header("X-Member-Id", who.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                    client
                        .post("/api/conference/pause-media/music") {
                            header("X-Member-Id", who.toString())
                            setBody(multipart(bytes = ByteArray(10), contentType = "audio/mpeg", fileName = "a.mp3"))
                        }.status shouldBe
                        HttpStatusCode.Forbidden
                    client.delete("/api/conference/pause-media/music") { header("X-Member-Id", who.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                    client.get("/api/conference/pause-media/music") { header("X-Member-Id", who.toString()) }.status shouldBe
                        HttpStatusCode.Forbidden
                }
                client.postImage(null, multipart(bytes = picture(w = 800, h = 450))).status shouldBe HttpStatusCode.Unauthorized
                client.postImage(admin, multipart(bytes = picture(w = 800, h = 450)), "Sec-Fetch-Site" to "cross-site").status shouldBe
                    HttpStatusCode.Forbidden
                client
                    .postImage(
                        admin,
                        multipart(bytes = picture(w = 800, h = 450)),
                        HttpHeaders.Origin to "https://evil.example.net",
                    ).status shouldBe
                    HttpStatusCode.Forbidden
                client
                    .delete("/api/conference/pause-media/image") {
                        header("X-Member-Id", admin.toString())
                        header("Sec-Fetch-Site", "cross-site")
                    }.status shouldBe
                    HttpStatusCode.Forbidden
                transaction { PauseMediaDb.load().imageKey } shouldBe null
            }
        }

        test("a file that is not a picture (HTML named .png), a declared-too-large body and a body without a length are refused") {
            val dir = root()
            withApp(dir) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val html =
                    client.postImage(
                        admin,
                        multipart(
                            bytes = "<html><script>alert(1)</script></html>".toByteArray(),
                            contentType = "image/png",
                            fileName = "evil.png",
                        ),
                    )
                html.status shouldBe HttpStatusCode.UnsupportedMediaType
                errorCodeOf(html.bodyAsText()) shouldBe "UNSUPPORTED_FORMAT"
                val gif = client.postImage(admin, multipart(bytes = "GIF89a....".toByteArray()))
                gif.status shouldBe HttpStatusCode.UnsupportedMediaType
                val tooBig = client.postImage(admin, multipart(bytes = ByteArray(10 * 1024 * 1024 + 200_000) { 1 }))
                tooBig.status shouldBe HttpStatusCode.PayloadTooLarge
                errorCodeOf(tooBig.bodyAsText()) shouldBe "FILE_TOO_LARGE"
                val chunked =
                    object : OutgoingContent.WriteChannelContent() {
                        override val contentType = ContentType.parse("multipart/form-data; boundary=xyz")

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            channel.writeFully("--xyz--\r\n".toByteArray())
                        }
                    }
                client.postImage(admin, chunked).status shouldBe HttpStatusCode.PayloadTooLarge
                storedFiles(dir).size shouldBe 0
            }
        }

        test("dimension rules: 639x360 is TOO_SMALL, header-only 9000x9000 and 7000x7000 are DIMENSIONS_TOO_LARGE without decoding") {
            withApp(root()) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                errorCodeOf(
                    client
                        .postImage(admin, multipart(bytes = picture(w = 639, h = 360)))
                        .also {
                            it.status shouldBe
                                HttpStatusCode.UnprocessableEntity
                        }.bodyAsText(),
                ) shouldBe
                    "TOO_SMALL"
                listOf(9000 to 9000, 7000 to 7000).forEach { (w, h) ->
                    val r = client.postImage(admin, multipart(bytes = MemberPhotoTestImages.pngHeaderOnly(width = w, height = h)))
                    r.status shouldBe HttpStatusCode.UnprocessableEntity
                    errorCodeOf(r.bodyAsText()) shouldBe "DIMENSIONS_TOO_LARGE"
                }
                errorCodeOf(
                    client
                        .postImage(
                            admin,
                            multipart(
                                bytes =
                                    ByteArray(64) { 7 }.let {
                                        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
                                            it
                                    },
                            ),
                        ).bodyAsText(),
                ) shouldBe
                    "UNDECODABLE"
            }
        }

        test(
            "replacing a picture swaps the key; the OLD file stays while a feed runs and is removed once none runs; DELETE returns to the default picture",
        ) {
            val dir = root()
            var running = true
            withApp(dir, feedsRunning = { running }) { store ->
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                client.postImage(admin, multipart(bytes = picture(w = 900, h = 600))).status shouldBe HttpStatusCode.Created
                val first = transaction { PauseMediaDb.load().imageKey }!!
                client.postImage(admin, multipart(bytes = picture(w = 1000, h = 700))).status shouldBe HttpStatusCode.Created
                val second = transaction { PauseMediaDb.load().imageKey }!!
                (first != second) shouldBe true
                storedFiles(dir).toSet() shouldBe setOf("$first.png", "$second.png") // the feed may still read the first
                running = false
                store.sweep()
                storedFiles(dir) shouldBe listOf("$second.png")
                client.delete("/api/conference/pause-media/image") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
                transaction { PauseMediaDb.load().imageKey } shouldBe null
                storedFiles(dir).size shouldBe 0
                val get = client.get("/api/conference/pause-media/image") { header("X-Member-Id", admin.toString()) }
                get.status shouldBe HttpStatusCode.OK
                (ImageIO.read(get.bodyAsBytes().inputStream()).width) shouldBe 1280
            }
        }

        test("the generic organization update path never writes the pause columns (they are in no input and in no update statement)") {
            val columns =
                listOf(
                    "pauseImageKey",
                    "pauseImageUpdatedAt",
                    "pauseMusicKey",
                    "pauseMusicLabel",
                    "pauseMusicDurationSeconds",
                    "pauseMusicUpdatedAt",
                )
            val input =
                network.lapis.cloud.shared.domain.OrganizationSettingsInput
                    .serializer()
                    .descriptor
            val inputNames = (0 until input.elementsCount).map { input.getElementName(it) }
            columns.forEach { inputNames.contains(it) shouldBe false }
            val service =
                File("src/main/kotlin/network/lapis/cloud/server/rpc/OrganizationSettingsService.kt").let {
                    if (it.exists()) {
                        it
                    } else {
                        File(
                            "lapis-server/src/main/kotlin/network/lapis/cloud/server/rpc/OrganizationSettingsService.kt",
                        )
                    }
                }
            val code = service.readLines().filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") }.joinToString("\n")
            columns.forEach { code.contains("it[$it]") shouldBe false }
        }

        test("music: a WAV becomes an M4A under its UUID, the label is a sanitized base name, GET serves audio/mp4, DELETE removes it") {
            if (!FFMPEG) return@test
            val dir = root()
            withApp(dir) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val name = URLEncoder.encode("../../etc/passwd.mp3", Charsets.UTF_8)
                val response =
                    client.post("/api/conference/pause-media/music?name=$name") {
                        header("X-Member-Id", admin.toString())
                        setBody(multipart(bytes = tone(8), contentType = "audio/mpeg", fileName = "../../etc/passwd.mp3"))
                    }
                response.status shouldBe HttpStatusCode.Created
                val row = transaction { PauseMediaDb.load() }
                row.musicLabel shouldBe "passwd.mp3"
                (row.musicDurationSeconds in 7..9) shouldBe true
                storedFiles(dir) shouldBe listOf("${row.musicKey}.m4a")
                MemberPhotoFixtures.filesIn(dir).none { it.name.contains("passwd") } shouldBe true
                File(dir, "tmp").listFiles().orEmpty().size shouldBe 0 // the scratch files are gone
                val get = client.get("/api/conference/pause-media/music") { header("X-Member-Id", admin.toString()) }
                get.status shouldBe HttpStatusCode.OK
                get.headers[HttpHeaders.ContentType] shouldBe "audio/mp4"
                get.headers["X-Content-Type-Options"] shouldBe "nosniff"
                client.delete("/api/conference/pause-media/music") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.OK
                transaction { PauseMediaDb.load().musicKey } shouldBe null
                client.get("/api/conference/pause-media/music") { header("X-Member-Id", admin.toString()) }.status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        test("music refusals: too short is TOO_SHORT, HTML is UNSUPPORTED_FORMAT, no ffmpeg is 503 PROCESSING_UNAVAILABLE") {
            val dir = root()
            if (FFMPEG) {
                withApp(dir) {
                    val admin = fixtures.newMember(role = AccountRole.ADMIN)
                    val short =
                        client.post("/api/conference/pause-media/music") {
                            header("X-Member-Id", admin.toString())
                            setBody(multipart(bytes = tone(3), contentType = "audio/wav", fileName = "a.wav"))
                        }
                    short.status shouldBe HttpStatusCode.UnprocessableEntity
                    errorCodeOf(short.bodyAsText()) shouldBe "TOO_SHORT"
                    val html =
                        client.post("/api/conference/pause-media/music") {
                            header("X-Member-Id", admin.toString())
                            setBody(multipart(bytes = "<html></html>".toByteArray(), contentType = "audio/mpeg", fileName = "a.mp3"))
                        }
                    html.status shouldBe HttpStatusCode.UnsupportedMediaType
                    storedFiles(dir).size shouldBe 0
                    File(dir, "tmp").listFiles().orEmpty().size shouldBe 0
                }
            }
            withApp(root(), ffmpegAvailable = false) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                val r =
                    client.post("/api/conference/pause-media/music") {
                        header("X-Member-Id", admin.toString())
                        setBody(multipart(bytes = ByteArray(100), contentType = "audio/wav", fileName = "a.wav"))
                    }
                r.status shouldBe HttpStatusCode.ServiceUnavailable
                errorCodeOf(r.bodyAsText()) shouldBe "PROCESSING_UNAVAILABLE"
            }
        }

        test("the upload rate limit answers 429 with Retry-After and a fixed code") {
            withApp(root(), limiter = FederationInboxRateLimiter(maxRequests = 2, window = 10.minutes)) {
                val admin = fixtures.newMember(role = AccountRole.ADMIN)
                repeat(2) { client.postImage(admin, multipart(bytes = "nope".toByteArray())) }
                val limited = client.postImage(admin, multipart(bytes = "nope".toByteArray()))
                limited.status shouldBe HttpStatusCode.TooManyRequests
                (limited.headers[HttpHeaders.RetryAfter] != null) shouldBe true
                errorCodeOf(limited.bodyAsText()) shouldBe "RATE_LIMITED"
                limited.bodyAsText() shouldNotContain "nope"
            }
        }

        test("the settings DTO exposes the picture and music state without any path") {
            transaction {
                PauseMediaDb.setImage(key = Uuid.parse("aaaaaaaa-1111-2222-3333-444444444444"), now = null)
                PauseMediaDb.setMusic(
                    key = Uuid.parse("bbbbbbbb-1111-2222-3333-444444444444"),
                    label = "Lied.mp3",
                    durationSeconds = 90,
                    now = null,
                )
            }
            val dto =
                transaction {
                    OrganizationSettingsTable
                        .selectAll()
                        .where {
                            OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID
                        }.single()
                        .toOrganizationSettingsDto()
                }
            dto.pauseImageCustom shouldBe true
            dto.pauseImageVersion shouldBe "aaaaaaaa"
            dto.pauseMusicLabel shouldBe "Lied.mp3"
            dto.pauseMusicDurationSeconds shouldBe 90
            dto.pauseMusicVersion shouldBe "bbbbbbbb"
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[pauseImageKey] =
                        null
                }
            }
        }
    })
