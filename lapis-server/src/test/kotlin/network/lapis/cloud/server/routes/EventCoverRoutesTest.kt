package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.events.CoverImageFormat
import network.lapis.cloud.server.events.EventCoverImageProcessor
import network.lapis.cloud.server.module
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EventCoverResultDto
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import javax.imageio.ImageIO
import kotlin.uuid.Uuid

private const val TEST_ORIGIN = "http://localhost:8080"

private fun uploadFormData(bytes: ByteArray) =
    formData {
        append(
            "file",
            bytes,
            Headers.build {
                append(HttpHeaders.ContentDisposition, "filename=\"cover.jpg\"")
            },
        )
    }

private fun renderJpegBytes(
    width: Int = 1000,
    height: Int = 800,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.WHITE
    g.fillRect(0, 0, width, height)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "jpeg", out)
    return out.toByteArray()
}

private fun renderPngBytes(
    width: Int = 1000,
    height: Int = 800,
): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.BLUE
    g.fillRect(0, 0, width, height)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    return out.toByteArray()
}

/**
 * Welle "Veranstaltungs-Titelbild" (Event Cover Image) -- exercises [registerEventCoverRoutes]
 * through the REAL `application { module() }` (same idiom [MemberCardRoutesTest] establishes), so
 * every layer this feature actually touches in production is exercised together: StatusPages,
 * `resolveCurrentMember`/`requireRole`, `EventCoverImageProcessor`, `EventCoverStorage`, and
 * `EventStore.setCoverImageId`.
 */
class EventCoverRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdEventIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }

        afterSpec {
            transaction {
                if (createdEventIds.isNotEmpty()) {
                    // Clean up any cover files this spec's uploads wrote to disk.
                    val coverIds =
                        EventTable
                            .selectAll()
                            .where { EventTable.id inList createdEventIds }
                            .mapNotNull { it[EventTable.coverImageId] }
                    EventTable.deleteWhere { id inList createdEventIds }
                    val root = java.io.File("build/document-storage/event-covers")
                    coverIds.forEach { id ->
                        root.resolve("$id.jpg").delete()
                        root.resolve("$id.png").delete()
                    }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { memberId inList createdMemberIds }
                    MemberTable.deleteWhere { id inList createdMemberIds }
                }
            }
        }

        fun createMember(
            email: String,
            role: AccountRole,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "EventCoverRoutesTest Mitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun createEvent(
            visibility: EventVisibility = EventVisibility.MEMBERS_ONLY,
            status: EventStatus = EventStatus.DRAFT,
        ): Pair<Uuid, String> {
            val organizer = createMember("event-cover-organizer-${Uuid.random()}@example.org", AccountRole.BOARD)
            val id = Uuid.random()
            val slug = "event-cover-test-$id"
            transaction {
                EventTable.insert {
                    it[EventTable.id] = id
                    it[EventTable.slug] = slug
                    it[title] = "Event-Cover-Test"
                    it[description] = "test"
                    it[locationText] = "Testort"
                    it[onlineUrl] = null
                    it[startsAt] = LocalDateTime(2030, 1, 1, 18, 0)
                    it[endsAt] = LocalDateTime(2030, 1, 1, 22, 0)
                    it[capacity] = null
                    it[feeAmount] = BigDecimal.ZERO
                    it[feeCurrency] = "EUR"
                    it[EventTable.status] = status
                    it[EventTable.visibility] = visibility
                    it[registrationClosesAt] = null
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[createdBy] = organizer
                    it[cancelledAt] = null
                }
            }
            createdEventIds += id
            return id to slug
        }

        fun decodeResult(text: String): EventCoverResultDto = Json.decodeFromString(EventCoverResultDto.serializer(), text)

        test("upload without session -> 401") {
            testApplication {
                application { module() }
                val (_, slug) = createEvent()
                val response =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                response.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("upload as MEMBER -> 403") {
            testApplication {
                application { module() }
                val (_, slug) = createEvent()
                val member = createMember("event-cover-member-${Uuid.random()}@example.org", AccountRole.MEMBER)
                val response =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", member.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("upload as TREASURER -> 403 (EVENT_MANAGE_ROLES is BOARD/ADMIN only)") {
            testApplication {
                application { module() }
                val (_, slug) = createEvent()
                val treasurer = createMember("event-cover-treasurer-${Uuid.random()}@example.org", AccountRole.TREASURER)
                val response =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", treasurer.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("upload as BOARD with a cross-site Origin -> 403") {
            testApplication {
                application { module() }
                val (_, slug) = createEvent()
                val board = createMember("event-cover-board-origin-${Uuid.random()}@example.org", AccountRole.BOARD)
                val response =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, "https://evil.example")
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("upload as BOARD with Origin: null -> 403") {
            testApplication {
                application { module() }
                val (_, slug) = createEvent()
                val board = createMember("event-cover-board-null-${Uuid.random()}@example.org", AccountRole.BOARD)
                val response =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, "null")
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                response.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("upload as BOARD, same origin, valid JPEG -> 200 with a coverImageUrl, event.cover_image_id set") {
            testApplication {
                application { module() }
                val (eventId, slug) = createEvent()
                val board = createMember("event-cover-board-ok-${Uuid.random()}@example.org", AccountRole.BOARD)
                val response =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                response.status shouldBe HttpStatusCode.OK
                val dto = decodeResult(response.bodyAsText())
                dto.coverImageUrl.shouldNotBeNull()
                dto.coverImageUrl!! shouldContain "/veranstaltung/$slug/bild?v="

                val storedId = transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single()[EventTable.coverImageId] }
                storedId.shouldNotBeNull()
            }
        }

        test("upload as ADMIN (also permitted) with a PNG -> 200") {
            testApplication {
                application { module() }
                val (_, slug) = createEvent()
                val admin = createMember("event-cover-admin-${Uuid.random()}@example.org", AccountRole.ADMIN)
                val response =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", admin.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderPngBytes())))
                    }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("upload: unsupported content (GIF magic bytes) -> 415") {
            testApplication {
                application { module() }
                val (_, slug) = createEvent()
                val board = createMember("event-cover-board-gif-${Uuid.random()}@example.org", AccountRole.BOARD)
                val response =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData("GIF89a".toByteArray() + ByteArray(20))))
                    }
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
            }
        }

        test("upload: a perfectly valid SVG is still 415 (V1.9.21: SVG is accepted for the chapter crest ONLY)") {
            testApplication {
                application { module() }
                val (_, slug) = createEvent()
                val board = createMember("event-cover-board-svg-${Uuid.random()}@example.org", AccountRole.BOARD)
                val response =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(
                            MultiPartFormDataContent(
                                uploadFormData(
                                    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><circle cx=\"5\" cy=\"5\" r=\"4\" fill=\"#c00\"/></svg>"
                                        .toByteArray(),
                                ),
                            ),
                        )
                    }
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
            }
        }

        test("upload: image below the minimum size (799x600) -> 422") {
            testApplication {
                application { module() }
                val (_, slug) = createEvent()
                val board = createMember("event-cover-board-small-${Uuid.random()}@example.org", AccountRole.BOARD)
                val response =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes(width = 799, height = 600))))
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("upload: portrait 600x800 (long edge 800, short edge 600) -> 200, orientation-independent minimum") {
            testApplication {
                application { module() }
                val (_, slug) = createEvent()
                val board = createMember("event-cover-board-portrait-${Uuid.random()}@example.org", AccountRole.BOARD)
                val response =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes(width = 600, height = 800))))
                    }
                response.status shouldBe HttpStatusCode.OK
            }
        }

        test("upload: request body exceeding Content-Length pre-check -> 413 without reading the body") {
            testApplication {
                application { module() }
                val (_, slug) = createEvent()
                val board = createMember("event-cover-board-big-${Uuid.random()}@example.org", AccountRole.BOARD)
                val oversized = ByteArray(6 * 1024 * 1024)
                val response =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(oversized)))
                    }
                response.status shouldBe HttpStatusCode.PayloadTooLarge
            }
        }

        test("upload: unknown slug -> 404") {
            testApplication {
                application { module() }
                val board = createMember("event-cover-board-unknown-${Uuid.random()}@example.org", AccountRole.BOARD)
                val response =
                    client.post("/api/embed/v1/event/does-not-exist-${Uuid.random()}/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("upload: a path-traversal-shaped slug -> 404") {
            testApplication {
                application { module() }
                val board = createMember("event-cover-board-traversal-${Uuid.random()}@example.org", AccountRole.BOARD)
                val response =
                    client.post("/api/embed/v1/event/..%2F..%2Fetc/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                response.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("upload then replace: previous file is gone, new one exists, cover_image_id updated") {
            testApplication {
                application { module() }
                val (eventId, slug) = createEvent()
                val board = createMember("event-cover-board-replace-${Uuid.random()}@example.org", AccountRole.BOARD)

                val first =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                first.status shouldBe HttpStatusCode.OK
                val firstId = transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single()[EventTable.coverImageId] }!!
                val firstFile = java.io.File("build/document-storage/event-covers/$firstId.jpg")
                firstFile.exists() shouldBe true

                val second =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderPngBytes())))
                    }
                second.status shouldBe HttpStatusCode.OK
                val secondId = transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single()[EventTable.coverImageId] }!!
                secondId shouldNotBe firstId
                firstFile.exists() shouldBe false
                java.io.File("build/document-storage/event-covers/$secondId.png").exists() shouldBe true
            }
        }

        test("DELETE removes the cover, is idempotent, and requires BOARD/ADMIN + same origin") {
            testApplication {
                application { module() }
                val (eventId, slug) = createEvent()
                val board = createMember("event-cover-board-delete-${Uuid.random()}@example.org", AccountRole.BOARD)

                client
                    .post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }.status shouldBe HttpStatusCode.OK

                val forbiddenOrigin =
                    client.delete("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, "https://evil.example")
                        header("X-Member-Id", board.toString())
                    }
                forbiddenOrigin.status shouldBe HttpStatusCode.Forbidden

                val firstDelete =
                    client.delete("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                    }
                firstDelete.status shouldBe HttpStatusCode.OK
                decodeResult(firstDelete.bodyAsText()).coverImageUrl shouldBe null
                transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single()[EventTable.coverImageId] } shouldBe null

                // Second DELETE with no cover set -- still 200, idempotent.
                val secondDelete =
                    client.delete("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                    }
                secondDelete.status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "GET /veranstaltung/{slug}/bild: PUBLIC+PUBLISHED event with a cover -> 200, immutable when v matches, max-age=300 otherwise",
        ) {
            testApplication {
                application { module() }
                val (_, slug) = createEvent(visibility = EventVisibility.PUBLIC, status = EventStatus.PUBLISHED)
                val board = createMember("event-cover-board-public-${Uuid.random()}@example.org", AccountRole.BOARD)
                val upload =
                    client.post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }
                val url = decodeResult(upload.bodyAsText()).coverImageUrl!!
                val path = url.removePrefix(TEST_ORIGIN)

                val withVersion = client.get(path)
                withVersion.status shouldBe HttpStatusCode.OK
                withVersion.headers[HttpHeaders.CacheControl] shouldContain "immutable"
                withVersion.bodyAsBytes().size shouldBeGreaterThan 0

                val withoutVersion = client.get("/veranstaltung/$slug/bild")
                withoutVersion.status shouldBe HttpStatusCode.OK
                withoutVersion.headers[HttpHeaders.CacheControl] shouldBe "public, max-age=300"

                withVersion.headers["X-Content-Type-Options"] shouldBe "nosniff"
                withVersion.headers["Cross-Origin-Resource-Policy"] shouldBe "cross-origin"
            }
        }

        test("GET /veranstaltung/{slug}/bild: PUBLIC+PUBLISHED event with no cover -> 404") {
            testApplication {
                application { module() }
                val (_, slug) = createEvent(visibility = EventVisibility.PUBLIC, status = EventStatus.PUBLISHED)
                client.get("/veranstaltung/$slug/bild").status shouldBe HttpStatusCode.NotFound
            }
        }

        test("GET /veranstaltung/{slug}/bild: unknown slug -> 404, identical to a private/no-cover event") {
            testApplication {
                application { module() }
                val unknown = client.get("/veranstaltung/does-not-exist-${Uuid.random()}/bild")
                unknown.status shouldBe HttpStatusCode.NotFound

                val (_, privateSlug) = createEvent(visibility = EventVisibility.MEMBERS_ONLY, status = EventStatus.PUBLISHED)
                val privateNoCover = client.get("/veranstaltung/$privateSlug/bild")
                privateNoCover.status shouldBe HttpStatusCode.NotFound
                unknown.bodyAsText() shouldBe privateNoCover.bodyAsText()
            }
        }

        test(
            "GET /veranstaltung/{slug}/bild: MEMBERS_ONLY event with a cover -> 404 for anonymous, 200 private for BOARD, 404 for a plain MEMBER",
        ) {
            testApplication {
                application { module() }
                val (_, slug) = createEvent(visibility = EventVisibility.MEMBERS_ONLY, status = EventStatus.PUBLISHED)
                val board = createMember("event-cover-board-private-${Uuid.random()}@example.org", AccountRole.BOARD)
                client
                    .post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }.status shouldBe HttpStatusCode.OK

                val anonymous = client.get("/veranstaltung/$slug/bild")
                anonymous.status shouldBe HttpStatusCode.NotFound

                val member = createMember("event-cover-member-private-${Uuid.random()}@example.org", AccountRole.MEMBER)
                val asMember = client.get("/veranstaltung/$slug/bild") { header("X-Member-Id", member.toString()) }
                asMember.status shouldBe HttpStatusCode.NotFound

                val asBoard = client.get("/veranstaltung/$slug/bild") { header("X-Member-Id", board.toString()) }
                asBoard.status shouldBe HttpStatusCode.OK
                asBoard.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
            }
        }

        test("EventCoverImageProcessor.sniff sanity check reused by the route (defensive, in case of a future refactor)") {
            EventCoverImageProcessor.sniff(renderJpegBytes().copyOfRange(0, 8)) shouldBe CoverImageFormat.JPEG
        }

        test("regression: EventStore.updateEvent never touches cover_image_id") {
            testApplication {
                application { module() }
                val (eventId, slug) = createEvent()
                val board = createMember("event-cover-board-regression-${Uuid.random()}@example.org", AccountRole.BOARD)
                client
                    .post("/api/embed/v1/event/$slug/cover") {
                        header(HttpHeaders.Origin, TEST_ORIGIN)
                        header("X-Member-Id", board.toString())
                        setBody(MultiPartFormDataContent(uploadFormData(renderJpegBytes())))
                    }.status shouldBe HttpStatusCode.OK
                val coverIdBefore =
                    transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single()[EventTable.coverImageId] }
                coverIdBefore.shouldNotBeNull()

                transaction {
                    network.lapis.cloud.server.events.EventStore.updateEvent(
                        id = eventId,
                        title = "Updated title",
                        description = "Updated description",
                        locationText = "Updated location",
                        onlineUrl = null,
                        startsAt = LocalDateTime(2031, 1, 1, 18, 0),
                        endsAt = LocalDateTime(2031, 1, 1, 22, 0),
                        capacity = null,
                        feeAmount = null,
                        feeCurrency = null,
                        visibility = EventVisibility.MEMBERS_ONLY,
                        registrationClosesAt = null,
                        summary = null,
                        coverImageAlt = null,
                        onlineUrlPublic = false,
                    )
                }
                val coverIdAfter =
                    transaction { EventTable.selectAll().where { EventTable.id eq eventId }.single()[EventTable.coverImageId] }
                coverIdAfter shouldBe coverIdBefore
            }
        }
    })
