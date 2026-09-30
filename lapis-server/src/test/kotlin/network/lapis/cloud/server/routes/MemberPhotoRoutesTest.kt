package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.MemberPhotoTable
import network.lapis.cloud.server.embed.EmbedConfig
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.memberphoto.MemberPhotoFixtures
import network.lapis.cloud.server.memberphoto.MemberPhotoStorage
import network.lapis.cloud.server.memberphoto.MemberPhotoTestImages
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private fun jpegBytes(
    width: Int = 1200,
    height: Int = 900,
): ByteArray = MemberPhotoTestImages.jpeg(MemberPhotoTestImages.solid(width = width, height = height))

private fun multipart(
    bytes: ByteArray,
    fileName: String = "photo.jpg",
    partName: String = "file",
): MultiPartFormDataContent =
    MultiPartFormDataContent(
        formData {
            append(
                partName,
                bytes,
                Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    append(HttpHeaders.ContentType, "image/jpeg")
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

/**
 * Welle V1.9.19 "Mitglieder-Foto" -- the upload route and the private preview route
 * ([registerMemberPhotoRoutes]): security checklist coverage (CSRF, IDOR, DoS caps, sniffing,
 * polyglots, metadata, rate limit, eligibility) plus the replace/concurrency invariants.
 */
class MemberPhotoRoutesTest :
    FunSpec({
        val fixtures = MemberPhotoFixtures()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fixtures.cleanup() }

        fun Routing.install(
            storage: MemberPhotoStorage,
            uploadLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
        ) {
            registerMemberPhotoRoutes(
                storage = storage,
                baseUrl = "https://lapis.example.org",
                embedConfig = EmbedConfig.DISABLED,
                uploadRateLimiter = uploadLimiter,
                ownReadRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                publicReadRateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
            )
        }

        suspend fun HttpClient.upload(
            member: Uuid?,
            body: MultiPartFormDataContent,
            query: String = "",
            extraHeaders: Map<String, String> = emptyMap(),
        ): HttpResponse =
            post("/api/member-photo$query") {
                if (member != null) header("X-Member-Id", member.toString())
                extraHeaders.forEach { (k, v) -> header(k, v) }
                setBody(body)
            }

        test(
            "happy path: upload stores an 800x800 JPEG, the row is PRIVATE without token/consent, and /own serves it with the private headers",
        ) {
            val root = MemberPhotoFixtures.freshRoot("happy")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(MemberPhotoStorage(root)) }
                }
                val member = fixtures.newMember()
                val response = client.upload(member = member, body = multipart(bytes = jpegBytes()))
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"

                val row = transaction { MemberPhotoTable.selectAll().where { MemberPhotoTable.memberId eq member }.single() }
                row[MemberPhotoTable.visibility] shouldBe MemberPhotoVisibility.PRIVATE
                row[MemberPhotoTable.publicToken] shouldBe null
                row[MemberPhotoTable.consentGrantedAt] shouldBe null
                row[MemberPhotoTable.consentTextVersion] shouldBe null
                row[MemberPhotoTable.widthPx] shouldBe 800
                row[MemberPhotoTable.contentType] shouldBe "image/jpeg"
                MemberPhotoFixtures.filesIn(root).map { it.name } shouldBe listOf(row[MemberPhotoTable.storageKey])

                val own = client.get("/api/member-photo/own") { header("X-Member-Id", member.toString()) }
                own.status shouldBe HttpStatusCode.OK
                own.headers[HttpHeaders.ContentType] shouldBe "image/jpeg"
                own.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
                own.headers["X-Content-Type-Options"] shouldBe "nosniff"
                own.headers["Cross-Origin-Resource-Policy"] shouldBe "same-origin"
                own.headers["Content-Security-Policy"] shouldBe "default-src 'none'; sandbox"
                MemberPhotoTestImages.dimensions(own.bodyAsBytes()) shouldBe (800 to 800)
            }
        }

        test("/own: 401 without a session, 404 without a photo") {
            val root = MemberPhotoFixtures.freshRoot("own")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(MemberPhotoStorage(root)) }
                }
                client.get("/api/member-photo/own").status shouldBe HttpStatusCode.Unauthorized
                val member = fixtures.newMember()
                client.get("/api/member-photo/own") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("replacing deletes the OLD file physically, resets PUBLIC to PRIVATE, and the old public token then yields 404 material") {
            val root = MemberPhotoFixtures.freshRoot("replace")
            val storage = MemberPhotoStorage(root)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(storage) }
                }
                val member = fixtures.newMember()
                val oldToken = fixtures.seedPhoto(storage = storage, memberId = member, publish = true)
                (oldToken != null) shouldBe true
                val oldKey =
                    transaction {
                        MemberPhotoTable.selectAll().where { MemberPhotoTable.memberId eq member }.single()[MemberPhotoTable.storageKey]
                    }

                client.upload(member = member, body = multipart(bytes = jpegBytes())).status shouldBe HttpStatusCode.OK

                val row = transaction { MemberPhotoTable.selectAll().where { MemberPhotoTable.memberId eq member }.single() }
                row[MemberPhotoTable.visibility] shouldBe MemberPhotoVisibility.PRIVATE
                row[MemberPhotoTable.publicToken] shouldBe null
                row[MemberPhotoTable.consentTextVersion] shouldBe null
                (row[MemberPhotoTable.storageKey] == oldKey) shouldBe false
                MemberPhotoFixtures.filesIn(root).map { it.name } shouldBe listOf(row[MemberPhotoTable.storageKey])
                fixtures.memberAuditAfterSnapshots(member).any { it.contains("UNPUBLISHED_BY_REPLACEMENT") } shouldBe true
            }
        }

        test("two parallel uploads for one member leave exactly one row and exactly one file") {
            val root = MemberPhotoFixtures.freshRoot("parallel")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(MemberPhotoStorage(root)) }
                }
                val member = fixtures.newMember()
                val results =
                    coroutineScope {
                        listOf(
                            async {
                                client
                                    .upload(
                                        member = member,
                                        body = multipart(bytes = jpegBytes(width = 1000, height = 1000)),
                                    ).status
                            },
                            async {
                                client
                                    .upload(
                                        member = member,
                                        body = multipart(bytes = jpegBytes(width = 1100, height = 1100)),
                                    ).status
                            },
                        ).awaitAll()
                    }
                results.all { it == HttpStatusCode.OK } shouldBe true
                fixtures.rowCount(member) shouldBe 1
                MemberPhotoFixtures.filesIn(root).size shouldBe 1
            }
        }

        test("CSRF: a foreign Origin or Sec-Fetch-Site cross-site is rejected with 403 and nothing is stored") {
            val root = MemberPhotoFixtures.freshRoot("csrf")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(MemberPhotoStorage(root)) }
                }
                val member = fixtures.newMember()
                client
                    .upload(
                        member = member,
                        body = multipart(bytes = jpegBytes()),
                        extraHeaders = mapOf("Origin" to "https://evil.example"),
                    ).status shouldBe HttpStatusCode.Forbidden
                client
                    .upload(member = member, body = multipart(bytes = jpegBytes()), extraHeaders = mapOf("Sec-Fetch-Site" to "cross-site"))
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .upload(
                        member = member,
                        body = multipart(bytes = jpegBytes()),
                        extraHeaders =
                            mapOf(
                                "Origin" to "https://lapis.example.org",
                            ),
                    ).status shouldBe HttpStatusCode.OK
                fixtures.rowCount(member) shouldBe 1
            }
        }

        test("unauthenticated upload is 401") {
            val root = MemberPhotoFixtures.freshRoot("unauth")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(MemberPhotoStorage(root)) }
                }
                client.upload(member = null, body = multipart(bytes = jpegBytes())).status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("IDOR: a memberId query parameter is ignored -- the photo lands on the caller") {
            val root = MemberPhotoFixtures.freshRoot("idor")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(MemberPhotoStorage(root)) }
                }
                val caller = fixtures.newMember()
                val victim = fixtures.newMember()
                client.upload(member = caller, body = multipart(bytes = jpegBytes()), query = "?memberId=$victim").status shouldBe
                    HttpStatusCode.OK
                fixtures.rowCount(caller) shouldBe 1
                fixtures.rowCount(victim) shouldBe 0
            }
        }

        test("size caps: declared length far above the cap and a streamed file one byte over the cap are both 413 FILE_TOO_LARGE") {
            val root = MemberPhotoFixtures.freshRoot("size")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(MemberPhotoStorage(root)) }
                }
                val member = fixtures.newMember()
                val jpegMagic = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
                val huge = jpegMagic + ByteArray(10 * 1024 * 1024 + 70 * 1024)
                val response = client.upload(member = member, body = multipart(bytes = huge))
                response.status shouldBe HttpStatusCode.PayloadTooLarge
                errorCodeOf(response.bodyAsText()) shouldBe "FILE_TOO_LARGE"

                val oneOver = jpegMagic + ByteArray(10 * 1024 * 1024 + 1 - jpegMagic.size)
                val streamed = client.upload(member = member, body = multipart(bytes = oneOver))
                streamed.status shouldBe HttpStatusCode.PayloadTooLarge
                errorCodeOf(streamed.bodyAsText()) shouldBe "FILE_TOO_LARGE"
                fixtures.rowCount(member) shouldBe 0
            }
        }

        test("two file parts and a body without any file part are 400 INVALID_REQUEST") {
            val root = MemberPhotoFixtures.freshRoot("parts")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(MemberPhotoStorage(root)) }
                }
                val member = fixtures.newMember()
                val two =
                    MultiPartFormDataContent(
                        formData {
                            listOf("a", "b").forEach { name ->
                                append(
                                    name,
                                    jpegBytes(),
                                    Headers.build {
                                        append(HttpHeaders.ContentDisposition, "filename=\"$name.jpg\"")
                                        append(HttpHeaders.ContentType, "image/jpeg")
                                    },
                                )
                            }
                        },
                    )
                val twoResponse = client.upload(member = member, body = two)
                twoResponse.status shouldBe HttpStatusCode.BadRequest
                errorCodeOf(twoResponse.bodyAsText()) shouldBe "INVALID_REQUEST"

                val none = client.upload(member = member, body = MultiPartFormDataContent(formData { append("note", "no file here") }))
                none.status shouldBe HttpStatusCode.BadRequest
                errorCodeOf(none.bodyAsText()) shouldBe "INVALID_REQUEST"
                fixtures.rowCount(member) shouldBe 0
            }
        }

        test("SVG, GIF, WebP and HEIC-like bytes are 422 UNSUPPORTED_FORMAT (sniffed, not trusted from the declared type)") {
            val root = MemberPhotoFixtures.freshRoot("formats")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(MemberPhotoStorage(root)) }
                }
                val member = fixtures.newMember()
                val samples =
                    listOf(
                        "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".toByteArray(),
                        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><circle cx=\"5\" cy=\"5\" r=\"4\" fill=\"#c00\"/></svg>"
                            .toByteArray(),
                        "GIF89a".toByteArray() + ByteArray(64),
                        "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(32),
                        byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(32),
                    )
                samples.forEach { bytes ->
                    val response = client.upload(member = member, body = multipart(bytes = bytes, fileName = "photo.jpg"))
                    response.status shouldBe HttpStatusCode.UnprocessableEntity
                    errorCodeOf(response.bodyAsText()) shouldBe "UNSUPPORTED_FORMAT"
                }
                fixtures.rowCount(member) shouldBe 0
            }
        }

        test("TOO_SMALL and UNDECODABLE are 422 with their codes; error bodies carry only the enum code") {
            val root = MemberPhotoFixtures.freshRoot("small")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(MemberPhotoStorage(root)) }
                }
                val member = fixtures.newMember()
                val small = client.upload(member = member, body = multipart(bytes = jpegBytes(width = 300, height = 300)))
                small.status shouldBe HttpStatusCode.UnprocessableEntity
                errorCodeOf(small.bodyAsText()) shouldBe "TOO_SMALL"
                Json.parseToJsonElement(small.bodyAsText()).jsonObject.keys shouldBe setOf("error")

                val garbage = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(300) { (it * 13).toByte() }
                val undecodable = client.upload(member = member, body = multipart(bytes = garbage))
                undecodable.status shouldBe HttpStatusCode.UnprocessableEntity
                errorCodeOf(undecodable.bodyAsText()) shouldBe "UNDECODABLE"
            }
        }

        test("polyglot: a JPEG with an HTML/ZIP tail is stored WITHOUT the tail (pixels only are re-encoded)") {
            val root = MemberPhotoFixtures.freshRoot("polyglot")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(MemberPhotoStorage(root)) }
                }
                val member = fixtures.newMember()
                val tail = "<html><script>POLYGLOT-PAYLOAD</script></html>PK"
                client.upload(member = member, body = multipart(bytes = jpegBytes() + tail.toByteArray())).status shouldBe HttpStatusCode.OK
                val stored = MemberPhotoFixtures.filesIn(root).single().readBytes()
                MemberPhotoTestImages.contains(haystack = stored, needle = "POLYGLOT-PAYLOAD") shouldBe false
                MemberPhotoTestImages.contains(haystack = stored, needle = "<html>") shouldBe false
            }
        }

        test("rate limit: the third upload with a limit of two is 429 with Retry-After") {
            val root = MemberPhotoFixtures.freshRoot("rate")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        install(MemberPhotoStorage(root), uploadLimiter = FederationInboxRateLimiter(maxRequests = 2, window = 60.minutes))
                    }
                }
                val member = fixtures.newMember()
                client.upload(member = member, body = multipart(bytes = jpegBytes())).status shouldBe HttpStatusCode.OK
                client.upload(member = member, body = multipart(bytes = jpegBytes())).status shouldBe HttpStatusCode.OK
                val limited = client.upload(member = member, body = multipart(bytes = jpegBytes()))
                limited.status shouldBe HttpStatusCode.TooManyRequests
                ((limited.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: 0L) > 0L) shouldBe true
                errorCodeOf(limited.bodyAsText()) shouldBe "RATE_LIMITED"
            }
        }

        test("eligibility: a FRIEND and a GUEST are 403 NOT_ELIGIBLE and nothing is stored") {
            val root = MemberPhotoFixtures.freshRoot("eligible")
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(MemberPhotoStorage(root)) }
                }
                listOf(MemberStatus.FRIEND, MemberStatus.GUEST, MemberStatus.WITHDRAWN).forEach { status ->
                    val member = fixtures.newMember(status = status)
                    val response = client.upload(member = member, body = multipart(bytes = jpegBytes()))
                    response.status shouldBe HttpStatusCode.Forbidden
                    errorCodeOf(response.bodyAsText()) shouldBe "NOT_ELIGIBLE"
                    fixtures.rowCount(member) shouldBe 0
                }
                MemberPhotoFixtures.filesIn(root).size shouldBe 0
            }
        }

        test("Uuid paths never escape: a stored row whose key was tampered to a traversal string is not served by /own") {
            val root = MemberPhotoFixtures.freshRoot("traversal")
            val storage = MemberPhotoStorage(root)
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { install(storage) }
                }
                val member = fixtures.newMember()
                fixtures.seedPhoto(storage = storage, memberId = member)
                transaction {
                    MemberPhotoTable.update({ MemberPhotoTable.memberId eq member }) {
                        it[storageKey] = "../../etc/passwd"
                    }
                }
                client.get("/api/member-photo/own") { header("X-Member-Id", member.toString()) }.status shouldBe HttpStatusCode.NotFound
            }
        }
    })
