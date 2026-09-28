package network.lapis.cloud.server.routes

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.sync.Semaphore
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.ConferenceBackgroundImageTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"

private fun fixtureBytes(name: String): ByteArray =
    requireNotNull(
        Thread.currentThread().contextClassLoader.getResourceAsStream("conference-backgrounds/$name"),
    ) { "Fixture not found: conference-backgrounds/$name" }
        .use { it.readBytes() }

private val TEST_STORAGE_ROOT = File("build/test-conference-background-storage")

/**
 * Welle V1.9.4 "private Hintergrundbild-Uploads für Videokonferenzen" -- security checklist
 * coverage for [registerConferenceBackgroundRoutes]. Mirrors [TravelExpenseReceiptRoutesTest]'s
 * house style (own throwaway routing, multipart bytes built by hand), simplified where the
 * status-quote unauthenticated exception is caught directly rather than via `StatusPages` (this
 * route's GET handler already does its own catch, see that route's own code).
 */
class ConferenceBackgroundRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TEST_STORAGE_ROOT.mkdirs()
        }

        afterSpec {
            transaction {
                ConferenceBackgroundImageTable.deleteWhere { ConferenceBackgroundImageTable.memberId inList createdMemberIds }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
            TEST_STORAGE_ROOT.deleteRecursively()
        }

        fun newMember(status: MemberStatus = MemberStatus.ACTIVE): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Background Testmitglied"
                    it[email] = "conference-background-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun multipartBody(
            bytes: ByteArray,
            fileName: String = "photo.jpg",
            declaredContentType: String = "image/jpeg",
        ): MultiPartFormDataContent =
            MultiPartFormDataContent(
                formData {
                    append(
                        "file",
                        bytes,
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            append(HttpHeaders.ContentType, declaredContentType)
                        },
                    )
                },
            )

        fun twoFilePartsBody(): MultiPartFormDataContent =
            MultiPartFormDataContent(
                formData {
                    append(
                        "file",
                        fixtureBytes("landscape.jpg"),
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"")
                            append(HttpHeaders.ContentType, "image/jpeg")
                        },
                    )
                    append(
                        "file2",
                        fixtureBytes("landscape.jpg"),
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"b.jpg\"")
                            append(HttpHeaders.ContentType, "image/jpeg")
                        },
                    )
                },
            )

        fun io.ktor.server.routing.Routing.installRoutes(
            rateLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
            decodeSemaphore: Semaphore = Semaphore(permits = 2),
        ) {
            registerConferenceBackgroundRoutes(
                storageRoot = TEST_STORAGE_ROOT,
                rateLimiter = rateLimiter,
                decodeSemaphore = decodeSemaphore,
            )
        }

        test("happy path: upload -> 201, GET image/thumb -> 200 with headers, If-None-Match -> 304, DELETE -> 204, GET -> 404") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()

                val uploadResponse =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(bytes = fixtureBytes("landscape.jpg")))
                    }
                uploadResponse.status shouldBe HttpStatusCode.Created
                val json = Json.parseToJsonElement(uploadResponse.bodyAsText()).jsonObject
                val imageId = json["id"]!!.jsonPrimitive.content
                json["width"]!!.jsonPrimitive.content shouldBe "1920"

                val imageResponse =
                    client.get("/api/conference-backgrounds/$imageId/image") {
                        header("X-Member-Id", member.toString())
                    }
                imageResponse.status shouldBe HttpStatusCode.OK
                imageResponse.headers[HttpHeaders.ContentType] shouldBe "image/jpeg"
                imageResponse.headers["X-Content-Type-Options"] shouldBe "nosniff"
                imageResponse.headers["Cross-Origin-Resource-Policy"] shouldBe "same-origin"
                imageResponse.headers[HttpHeaders.CacheControl] shouldBe "private, no-cache"
                val etag = imageResponse.headers[HttpHeaders.ETag]
                etag shouldBe "\"${etag!!.trim('"')}\""

                val thumbResponse =
                    client.get("/api/conference-backgrounds/$imageId/thumb") {
                        header("X-Member-Id", member.toString())
                    }
                thumbResponse.status shouldBe HttpStatusCode.OK
                thumbResponse.headers[HttpHeaders.ETag] shouldBe "\"${etag.trim('"')}-t\""

                val notModified =
                    client.get("/api/conference-backgrounds/$imageId/image") {
                        header("X-Member-Id", member.toString())
                        header(HttpHeaders.IfNoneMatch, etag)
                    }
                notModified.status shouldBe HttpStatusCode.NotModified

                val deleteResponse =
                    client.delete("/api/conference-backgrounds/$imageId") {
                        header("X-Member-Id", member.toString())
                    }
                deleteResponse.status shouldBe HttpStatusCode.NoContent

                val afterDelete =
                    client.get("/api/conference-backgrounds/$imageId/image") {
                        header("X-Member-Id", member.toString())
                    }
                afterDelete.status shouldBe HttpStatusCode.NotFound

                transaction {
                    ConferenceBackgroundImageTable.selectAll().where { ConferenceBackgroundImageTable.id eq Uuid.parse(imageId) }.count()
                } shouldBe 0L
            }
        }

        test("PDF magic bytes -> 415, no row created") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                val pdfMagic = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34)
                val response =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(bytes = pdfMagic, fileName = "x.pdf", declaredContentType = "application/pdf"))
                    }
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
                transaction {
                    ConferenceBackgroundImageTable.selectAll().where { ConferenceBackgroundImageTable.memberId eq member }.count()
                } shouldBe 0L
            }
        }

        test("WebP magic bytes -> 415 (not in the allowlist despite the client accept attribute offering it)") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                val webpMagic = "RIFF____WEBP".toByteArray()
                val response =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(bytes = webpMagic, fileName = "x.webp", declaredContentType = "image/webp"))
                    }
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
            }
        }

        test("garbage bytes -> 415") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                val response =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(bytes = fixtureBytes("random-bytes.bin"), fileName = "x.jpg"))
                    }
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
            }
        }

        test("too-small image -> 422 (UnprocessableEntity)") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                val response =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(bytes = fixtureBytes("too-small.png"), fileName = "x.png", declaredContentType = "image/png"))
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test("decompression-bomb PNG -> 422, rejected on dimensions before any decode") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                val response =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(bytes = fixtureBytes("bomb.png"), fileName = "x.png", declaredContentType = "image/png"))
                    }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
            }
        }

        test(
            "Security-Audit unit (2026-09-27): declaredUploadTooLarge -- null (no Content-Length, " +
                "e.g. chunked encoding) and over-ceiling both reject; at-ceiling and under do not",
        ) {
            val ceiling = 4L * 1024 * 1024 + 64 * 1024 // ConferenceBackgroundRules.MAX_UPLOAD_BYTES + MULTIPART_OVERHEAD_BYTES
            declaredUploadTooLarge(contentLength = null) shouldBe true
            declaredUploadTooLarge(contentLength = ceiling + 1) shouldBe true
            declaredUploadTooLarge(contentLength = ceiling) shouldBe false
            declaredUploadTooLarge(contentLength = 100L) shouldBe false
        }

        test("upload over MAX_UPLOAD_BYTES -> 413, no row created") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                val oversized = ByteArray((4L * 1024 * 1024 + 1).toInt())
                fixtureBytes("landscape.jpg").copyInto(oversized, 0, 0, minOf(8, oversized.size))
                val response =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(bytes = oversized, fileName = "x.jpg"))
                    }
                response.status shouldBe HttpStatusCode.PayloadTooLarge
                transaction {
                    ConferenceBackgroundImageTable.selectAll().where { ConferenceBackgroundImageTable.memberId eq member }.count()
                } shouldBe 0L
            }
        }

        test("two file parts in one request -> 400, no row created") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                val response =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(twoFilePartsBody())
                    }
                response.status shouldBe HttpStatusCode.BadRequest
                transaction {
                    ConferenceBackgroundImageTable.selectAll().where { ConferenceBackgroundImageTable.memberId eq member }.count()
                } shouldBe 0L
            }
        }

        test("no file part -> 400") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                val response =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(MultiPartFormDataContent(formData { append("notAFile", "hello") }))
                    }
                response.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("not authenticated -> 401 on all four routes") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) {
                        exception<UnauthenticatedException> { call, _ -> call.respond(HttpStatusCode.Unauthorized) }
                    }
                    routing { installRoutes() }
                }
                client.post("/api/conference-backgrounds") { setBody(multipartBody(bytes = fixtureBytes("landscape.jpg"))) }.status shouldBe
                    HttpStatusCode.Unauthorized
                client.get("/api/conference-backgrounds/${Uuid.random()}/image").status shouldBe HttpStatusCode.Unauthorized
                client.get("/api/conference-backgrounds/${Uuid.random()}/thumb").status shouldBe HttpStatusCode.Unauthorized
                client.delete("/api/conference-backgrounds/${Uuid.random()}").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("invalid id (not a UUID) -> 400 on GET and DELETE") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                client.get("/api/conference-backgrounds/not-a-uuid/image") { header("X-Member-Id", member.toString()) }.status shouldBe
                    HttpStatusCode.BadRequest
                client.delete("/api/conference-backgrounds/not-a-uuid") { header("X-Member-Id", member.toString()) }.status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        test("unknown (but valid) id -> 404") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                val unknown = Uuid.random().toString()
                client.get("/api/conference-backgrounds/$unknown/image") { header("X-Member-Id", member.toString()) }.status shouldBe
                    HttpStatusCode.NotFound
                client.delete("/api/conference-backgrounds/$unknown") { header("X-Member-Id", member.toString()) }.status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        test("IDOR: another member (including BOARD/ADMIN) gets 404 on GET/thumb/DELETE of someone else's image, and it survives") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val owner = newMember()
                val uploadResponse =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", owner.toString())
                        setBody(multipartBody(bytes = fixtureBytes("landscape.jpg")))
                    }
                val imageId =
                    Json
                        .parseToJsonElement(uploadResponse.bodyAsText())
                        .jsonObject["id"]!!
                        .jsonPrimitive.content

                val otherMember = newMember()
                for (callerId in listOf(otherMember.toString(), BOARD_ID, ADMIN_ID)) {
                    client.get("/api/conference-backgrounds/$imageId/image") { header("X-Member-Id", callerId) }.status shouldBe
                        HttpStatusCode.NotFound
                    client.get("/api/conference-backgrounds/$imageId/thumb") { header("X-Member-Id", callerId) }.status shouldBe
                        HttpStatusCode.NotFound
                    client.delete("/api/conference-backgrounds/$imageId") { header("X-Member-Id", callerId) }.status shouldBe
                        HttpStatusCode.NotFound
                }

                // The image must still be there -- none of the above deleted it.
                client.get("/api/conference-backgrounds/$imageId/image") { header("X-Member-Id", owner.toString()) }.status shouldBe
                    HttpStatusCode.OK
            }
        }

        test("F1: FRIEND and GUEST cannot upload (403), but can still list/view/delete their OWN pre-existing images") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val friend = newMember(status = MemberStatus.FRIEND)
                val uploadAttempt =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", friend.toString())
                        setBody(multipartBody(bytes = fixtureBytes("landscape.jpg")))
                    }
                uploadAttempt.status shouldBe HttpStatusCode.Forbidden

                // Simulate a pre-existing image (e.g. uploaded while still ACTIVE) directly via the DB.
                val imageId = Uuid.random()
                transaction {
                    ConferenceBackgroundImageTable.insert {
                        it[id] = imageId
                        it[memberId] = friend
                        it[storageKey] = "conference-backgrounds/$friend/$imageId.jpg"
                        it[thumbStorageKey] = "conference-backgrounds/$friend/$imageId.thumb.jpg"
                        it[width] = 100
                        it[height] = 100
                        it[sizeBytes] = 10L
                        it[sha256] = "0".repeat(64)
                        it[createdAt] =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime()
                    }
                }
                TEST_STORAGE_ROOT.resolve("conference-backgrounds/$friend").mkdirs()
                TEST_STORAGE_ROOT.resolve("conference-backgrounds/$friend/$imageId.jpg").writeBytes(byteArrayOf(1))

                client.get("/api/conference-backgrounds/$imageId/image") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.delete("/api/conference-backgrounds/$imageId") { header("X-Member-Id", friend.toString()) }.status shouldBe
                    HttpStatusCode.NoContent
            }
        }

        test("MAX_PER_MEMBER limit: the 4th upload is rejected with 409, only 3 rows remain") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                repeat(3) {
                    val r =
                        client.post("/api/conference-backgrounds") {
                            header("X-Member-Id", member.toString())
                            setBody(multipartBody(bytes = fixtureBytes("landscape.jpg")))
                        }
                    r.status shouldBe HttpStatusCode.Created
                }
                val fourth =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(bytes = fixtureBytes("portrait.png"), fileName = "x.png", declaredContentType = "image/png"))
                    }
                fourth.status shouldBe HttpStatusCode.Conflict
                transaction {
                    ConferenceBackgroundImageTable.selectAll().where { ConferenceBackgroundImageTable.memberId eq member }.count()
                } shouldBe 3L
            }
        }

        test("rate limiter: the 2nd upload within the window is rejected with 429") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes(rateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes)) }
                }
                val member = newMember()
                val first =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(bytes = fixtureBytes("landscape.jpg")))
                    }
                first.status shouldBe HttpStatusCode.Created
                val second =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(bytes = fixtureBytes("portrait.png"), fileName = "x.png", declaredContentType = "image/png"))
                    }
                second.status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("decode semaphore exhausted -> 503, no row created") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes(decodeSemaphore = Semaphore(permits = 1).also { it.tryAcquire() }) }
                }
                val member = newMember()
                val response =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", member.toString())
                        setBody(multipartBody(bytes = fixtureBytes("landscape.jpg")))
                    }
                response.status shouldBe HttpStatusCode.ServiceUnavailable
                transaction {
                    ConferenceBackgroundImageTable.selectAll().where { ConferenceBackgroundImageTable.memberId eq member }.count()
                } shouldBe 0L
            }
        }

        test("path traversal: a manipulated storage_key pointing outside the storage root never escapes it -- 404, nothing read") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                val imageId = Uuid.random()
                transaction {
                    ConferenceBackgroundImageTable.insert {
                        it[id] = imageId
                        it[memberId] = member
                        it[storageKey] = "../../../../etc/passwd"
                        it[thumbStorageKey] = "../../../../etc/passwd"
                        it[width] = 100
                        it[height] = 100
                        it[sizeBytes] = 10L
                        it[sha256] = "0".repeat(64)
                        it[createdAt] =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime()
                    }
                }
                client.get("/api/conference-backgrounds/$imageId/image") { header("X-Member-Id", member.toString()) }.status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        test(
            "writeAtomically I/O failure (read-only target directory) -> 500, no row created",
        ) {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val member = newMember()
                val memberDir = TEST_STORAGE_ROOT.resolve("conference-backgrounds/$member")
                memberDir.mkdirs()
                // `mainFile.parentFile.mkdirs()` (route code) is a no-op on an already-existing dir --
                // permissions survive. With no write permission on the target directory, writeAtomically's
                // `part.writeBytes(bytes)` fails before a single byte lands on disk -- so this only exercises
                // the route's error handling (500, no DB row), NOT the `.part`-cleanup path of writeAtomically's
                // own catch block (writing never gets far enough to create a `.part` file here in the first
                // place). The actual cleanup-after-a-half-written-`.part` scenario needs `part.writeBytes` to
                // SUCCEED and the subsequent `Files.move` to fail -- covered directly, without going through
                // the route/multipart machinery, by the `writeAtomically(...)` unit test right below.
                check(memberDir.setWritable(false)) { "test setup: could not make $memberDir read-only" }
                try {
                    val response =
                        client.post("/api/conference-backgrounds") {
                            header("X-Member-Id", member.toString())
                            setBody(multipartBody(bytes = fixtureBytes("landscape.jpg")))
                        }
                    response.status shouldBe HttpStatusCode.InternalServerError
                    transaction {
                        ConferenceBackgroundImageTable.selectAll().where { ConferenceBackgroundImageTable.memberId eq member }.count()
                    } shouldBe 0L
                } finally {
                    // Restore write permission BEFORE any assertion that walks the directory, and
                    // regardless of the outcome above, so afterSpec's deleteRecursively() can clean up.
                    memberDir.setWritable(true)
                }
            }
        }

        test(
            "writeAtomically: .part is written, the move then fails, and the .part is still cleaned up (Review-Befund)",
        ) {
            // Unlike the route-level test above, this drives `writeAtomically` (now `internal`, see
            // ConferenceBackgroundRoutes.kt) directly, so `part.writeBytes(bytes)` genuinely SUCCEEDS first --
            // the target itself is an already-existing, non-empty directory, so `part.writeBytes` (a sibling
            // file next to it) is unaffected, but the subsequent `Files.move(part, target, ATOMIC_MOVE)`
            // cannot replace an existing non-empty directory and throws. This is the scenario the finding
            // asked for: a genuinely half-written `.part` file that only the catch block's `part.delete()`
            // removes.
            val tmp = Files.createTempDirectory("write-atomically-unit").toFile()
            val target = File(tmp, "x.jpg")
            check(target.mkdirs()) { "test setup: could not create target directory $target" }
            check(File(target, "occupant").createNewFile()) { "test setup: could not create occupant file inside $target" }
            val part = File(target.path + ".part")

            shouldThrow<IOException> {
                writeAtomically(target = target, bytes = "irrelevant".toByteArray())
            }

            part.exists() shouldBe false
        }

        test("If-None-Match with the owner's own etag from a DIFFERENT member's id never returns 304") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { installRoutes() }
                }
                val ownerA = newMember()
                val uploadA =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", ownerA.toString())
                        setBody(multipartBody(bytes = fixtureBytes("landscape.jpg")))
                    }
                val etagA =
                    client
                        .get(
                            "/api/conference-backgrounds/${Json.parseToJsonElement(
                                uploadA.bodyAsText(),
                            ).jsonObject["id"]!!.jsonPrimitive.content}/image",
                        ) {
                            header("X-Member-Id", ownerA.toString())
                        }.headers[HttpHeaders.ETag]!!

                val ownerB = newMember()
                val uploadB =
                    client.post("/api/conference-backgrounds") {
                        header("X-Member-Id", ownerB.toString())
                        setBody(multipartBody(bytes = fixtureBytes("portrait.png"), fileName = "x.png", declaredContentType = "image/png"))
                    }
                val imageIdB =
                    Json
                        .parseToJsonElement(uploadB.bodyAsText())
                        .jsonObject["id"]!!
                        .jsonPrimitive.content

                val crossResponse =
                    client.get("/api/conference-backgrounds/$imageIdB/image") {
                        header("X-Member-Id", ownerB.toString())
                        header(HttpHeaders.IfNoneMatch, etagA)
                    }
                crossResponse.status shouldBe HttpStatusCode.OK
            }
        }
    })
