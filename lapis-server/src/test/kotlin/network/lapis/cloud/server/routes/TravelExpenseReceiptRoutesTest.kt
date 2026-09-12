package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
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
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReceiptTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.TEST_RECEIPT_STORAGE_ROOT
import network.lapis.cloud.server.rpc.installTravelExpenseExceptionHandlers
import network.lapis.cloud.server.rpc.registerTravelExpenseTestRoutes
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"

private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34) // "%PDF-1.4"
private val PNG_MAGIC =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)

/**
 * Welle V1.4.11 -- security checklist coverage for [registerTravelExpenseReceiptRoutes]: MIME
 * sniffing (magic bytes override the declared Content-Type), the DoS size cap, path-traversal
 * safety, filename sanitization, and IDOR on download. Mirrors [BankStatementRoutesTest]'s house
 * style (own throwaway routing, multipart bytes built by hand).
 */
class TravelExpenseReceiptRoutesTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            TEST_RECEIPT_STORAGE_ROOT.mkdirs()
        }

        afterSpec {
            transaction {
                val reportIds =
                    if (createdMemberIds.isEmpty()) {
                        emptyList()
                    } else {
                        TravelExpenseReportTable
                            .selectAll()
                            .where { TravelExpenseReportTable.subjectMemberId inList createdMemberIds }
                            .map { it[TravelExpenseReportTable.id] }
                    }
                val lineIds =
                    if (reportIds.isEmpty()) {
                        emptyList()
                    } else {
                        TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.reportId inList reportIds }.map {
                            it[TravelExpenseLineTable.id]
                        }
                    }
                if (lineIds.isNotEmpty()) TravelExpenseReceiptTable.deleteWhere { TravelExpenseReceiptTable.lineId inList lineIds }
                TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId inList reportIds }
                TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList reportIds }
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                    it[actorMemberId] = null
                }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
            TEST_RECEIPT_STORAGE_ROOT.deleteRecursively()
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Receipt Testmitglied"
                    it[email] = "travel-receipt-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
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

        suspend fun HttpClient.draftLineOf(subjectId: Uuid): String {
            val draftId =
                post("/test/travelexpense/create?subjectMemberId=$subjectId&purpose=Reise&from=2026-01-05&to=2026-01-05") {
                    header("X-Member-Id", subjectId.toString())
                }.bodyAsText().split("|")[0]
            val afterLine =
                post("/test/travelexpense/addline?reportId=$draftId&kind=RECEIPTED&description=Beleg&amount=10.00") {
                    header("X-Member-Id", subjectId.toString())
                }.bodyAsText()
            return transaction {
                TravelExpenseLineTable
                    .selectAll()
                    .where { TravelExpenseLineTable.reportId eq Uuid.parse(draftId) }
                    .single()[
                    TravelExpenseLineTable.id,
                ].toString()
            }.also { check(afterLine.isNotBlank()) }
        }

        fun multipartBody(
            bytes: ByteArray,
            fileName: String,
            declaredContentType: String,
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

        // Review MAJOR fix regression coverage: a request carrying TWO file parts must be rejected
        // wholesale, never silently blend part 1's magic bytes with part 2's on-disk bytes.
        fun multipartBodyTwoFileParts(
            firstBytes: ByteArray,
            secondBytes: ByteArray,
        ): MultiPartFormDataContent =
            MultiPartFormDataContent(
                formData {
                    append(
                        "file",
                        firstBytes,
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"first.pdf\"")
                            append(HttpHeaders.ContentType, "application/pdf")
                        },
                    )
                    append(
                        "file2",
                        secondBytes,
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"second.html\"")
                            append(HttpHeaders.ContentType, "text/html")
                        },
                    )
                },
            )

        test("magic bytes win over the declared Content-Type: a PNG body declared as application/pdf is stored as image/png") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)

                val response =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(PNG_MAGIC, "beleg.pdf", "application/pdf"))
                    }
                response.status shouldBe HttpStatusCode.Created
                val receiptId =
                    Json
                        .parseToJsonElement(response.bodyAsText())
                        .jsonObject["receiptId"]!!
                        .jsonPrimitive.content
                transaction {
                    TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.id eq Uuid.parse(receiptId) }.single()[
                        TravelExpenseReceiptTable.mimeType,
                    ]
                } shouldBe "image/png"
            }
        }

        test("an SVG body (regardless of declared type) is rejected with 415 -- no image/svg+xml in the allowlist") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)
                val svgBytes = "<svg xmlns='http://www.w3.org/2000/svg'></svg>".toByteArray()

                val response =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(svgBytes, "beleg.svg", "image/svg+xml"))
                    }
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
                transaction {
                    TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.lineId eq Uuid.parse(lineId) }.count()
                } shouldBe 0L
            }
        }

        test("plain text/html body is rejected with 415") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)
                val response =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody("<html></html>".toByteArray(), "beleg.html", "text/html"))
                    }
                response.status shouldBe HttpStatusCode.UnsupportedMediaType
            }
        }

        test("upload over MAX_RECEIPT_BYTES (10 MiB) is rejected with 413 while streaming, no DB row, no leftover file") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)
                val reportId =
                    transaction {
                        TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.id eq Uuid.parse(lineId) }.single()[
                            TravelExpenseLineTable.reportId,
                        ]
                    }
                val oversized = ByteArray((10L * 1024 * 1024 + 1).toInt()) { PDF_MAGIC.getOrElse(it) { 0 } }

                val response =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(oversized, "beleg.pdf", "application/pdf"))
                    }
                response.status shouldBe HttpStatusCode.PayloadTooLarge
                transaction {
                    TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.lineId eq Uuid.parse(lineId) }.count()
                } shouldBe 0L
                // Security-Audit regression coverage (2026-09-12): the title claimed "no leftover
                // file" but nothing actually checked the filesystem before this fix.
                val reportDir = TEST_RECEIPT_STORAGE_ROOT.resolve("travel-expenses/$reportId")
                (!reportDir.exists() || reportDir.listFiles().isNullOrEmpty()) shouldBe true
            }
        }

        test("path-traversal filename is neutralized: storage_key stays server-generated, original_filename contains no separator") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)
                val response =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(PDF_MAGIC, "../../../etc/passwd", "application/pdf"))
                    }
                response.status shouldBe HttpStatusCode.Created
                val receiptId =
                    Json
                        .parseToJsonElement(response.bodyAsText())
                        .jsonObject["receiptId"]!!
                        .jsonPrimitive.content
                val row =
                    transaction {
                        TravelExpenseReceiptTable
                            .selectAll()
                            .where {
                                TravelExpenseReceiptTable.id eq
                                    Uuid.parse(
                                        receiptId,
                                    )
                            }.single()
                    }
                val storageKey = row[TravelExpenseReceiptTable.storageKey]
                storageKey.startsWith("travel-expenses/") shouldBe true
                storageKey.contains("..") shouldBe false
                row[TravelExpenseReceiptTable.originalFilename].contains("/") shouldBe false
                val resolved = TEST_RECEIPT_STORAGE_ROOT.resolve(storageKey).canonicalFile
                resolved.path.startsWith(TEST_RECEIPT_STORAGE_ROOT.canonicalFile.path) shouldBe true
            }
        }

        test("IDOR on download (submitted report): a foreign MEMBER gets 403, the subject gets 200, BOARD gets 200, TREASURER gets 403") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val stranger = newMember()
                val lineId = client.draftLineOf(subject)
                val reportId =
                    transaction {
                        TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.id eq Uuid.parse(lineId) }.single()[
                            TravelExpenseLineTable.reportId,
                        ]
                    }
                val uploadResponse =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(PDF_MAGIC, "beleg.pdf", "application/pdf"))
                    }
                val receiptId =
                    Json
                        .parseToJsonElement(uploadResponse.bodyAsText())
                        .jsonObject["receiptId"]!!
                        .jsonPrimitive.content
                // BOARD's privileged bypass only applies once the report has left DRAFT -- see the
                // dedicated "a DRAFT is private" test above for the DRAFT case itself.
                client.post("/test/travelexpense/submit?id=$reportId") { header("X-Member-Id", subject.toString()) }.status shouldBe
                    HttpStatusCode.OK

                client
                    .get(
                        "/api/travel-expenses/receipts/$receiptId/download",
                    ) { header("X-Member-Id", stranger.toString()) }
                    .status shouldBe
                    HttpStatusCode.Forbidden
                client
                    .get(
                        "/api/travel-expenses/receipts/$receiptId/download",
                    ) { header("X-Member-Id", subject.toString()) }
                    .status shouldBe
                    HttpStatusCode.OK
                client.get("/api/travel-expenses/receipts/$receiptId/download") { header("X-Member-Id", BOARD_ID) }.status shouldBe
                    HttpStatusCode.OK
                client.get("/api/travel-expenses/receipts/$receiptId/download") { header("X-Member-Id", TREASURER_ID) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test(
            "LOW hardening (2026-09-12 security audit): a DRAFT is private -- BOARD/ADMIN's privileged " +
                "download bypass does NOT apply to a still-private, never-submitted draft's receipt",
        ) {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject) // report stays DRAFT -- never submitted
                val uploadResponse =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(PDF_MAGIC, "beleg.pdf", "application/pdf"))
                    }
                val receiptId =
                    Json
                        .parseToJsonElement(uploadResponse.bodyAsText())
                        .jsonObject["receiptId"]!!
                        .jsonPrimitive.content

                // BOARD would normally pass the privileged bypass -- but the report is still DRAFT.
                client.get("/api/travel-expenses/receipts/$receiptId/download") { header("X-Member-Id", BOARD_ID) }.status shouldBe
                    HttpStatusCode.Forbidden

                // The subject may of course still download their own draft's receipt.
                client
                    .get("/api/travel-expenses/receipts/$receiptId/download") { header("X-Member-Id", subject.toString()) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test("download response carries Content-Disposition: attachment and X-Content-Type-Options: nosniff") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)
                val uploadResponse =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(PDF_MAGIC, "beleg.pdf", "application/pdf"))
                    }
                val receiptId =
                    Json
                        .parseToJsonElement(uploadResponse.bodyAsText())
                        .jsonObject["receiptId"]!!
                        .jsonPrimitive.content

                val download = client.get("/api/travel-expenses/receipts/$receiptId/download") { header("X-Member-Id", subject.toString()) }
                download.headers[HttpHeaders.ContentDisposition]?.contains("attachment") shouldBe true
                download.headers["X-Content-Type-Options"] shouldBe "nosniff"
            }
        }

        test("6th receipt on the same line -> 409") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)
                repeat(5) {
                    client
                        .post("/api/travel-expenses/lines/$lineId/receipts") {
                            header("X-Member-Id", subject.toString())
                            setBody(multipartBody(PDF_MAGIC, "beleg$it.pdf", "application/pdf"))
                        }.status shouldBe HttpStatusCode.Created
                }
                client
                    .post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(PDF_MAGIC, "beleg6.pdf", "application/pdf"))
                    }.status shouldBe HttpStatusCode.Conflict
            }
        }

        test(
            "review MAJOR fix: a request with TWO file parts is rejected with 400, no DB row, no leftover file, " +
                "on-disk bytes never blend part 1 with part 2",
        ) {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)
                val reportId =
                    transaction {
                        TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.id eq Uuid.parse(lineId) }.single()[
                            TravelExpenseLineTable.reportId,
                        ]
                    }

                val response =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBodyTwoFileParts(PNG_MAGIC, "<script>alert(1)</script>".toByteArray()))
                    }
                response.status shouldBe HttpStatusCode.BadRequest
                transaction {
                    TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.lineId eq Uuid.parse(lineId) }.count()
                } shouldBe 0L
                // Security-Audit regression coverage (2026-09-12): the title above claimed "no
                // leftover file" but nothing actually checked the filesystem -- this exercises BOTH
                // the pre-existing `fileItemCount > 1` cleanup AND the new early-abort-on-second-
                // file-part path (`MultipleFilePartsRejected`) that now stops `forEachPart` instead
                // of draining every remaining part first.
                val reportDir = TEST_RECEIPT_STORAGE_ROOT.resolve("travel-expenses/$reportId")
                (!reportDir.exists() || reportDir.listFiles().isNullOrEmpty()) shouldBe true
            }
        }

        test("review MAJOR fix: concurrent uploads never push a line past MAX_RECEIPTS_PER_LINE (5)") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)
                // 4 receipts already present -- exactly one slot left. Firing several uploads
                // concurrently must let AT MOST one through; before the review fix, the gate-only
                // check let every concurrent request pass, so more than one could squeeze past.
                repeat(4) {
                    client
                        .post("/api/travel-expenses/lines/$lineId/receipts") {
                            header("X-Member-Id", subject.toString())
                            setBody(multipartBody(PDF_MAGIC, "beleg$it.pdf", "application/pdf"))
                        }.status shouldBe HttpStatusCode.Created
                }

                val results =
                    coroutineScope {
                        (1..4)
                            .map {
                                async {
                                    client
                                        .post("/api/travel-expenses/lines/$lineId/receipts") {
                                            header("X-Member-Id", subject.toString())
                                            setBody(multipartBody(PDF_MAGIC, "race$it.pdf", "application/pdf"))
                                        }.status
                                }
                            }.awaitAll()
                    }

                results.count { it == HttpStatusCode.Created } shouldBe 1
                results.count { it == HttpStatusCode.Conflict } shouldBe 3
                transaction {
                    TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.lineId eq Uuid.parse(lineId) }.count()
                } shouldBe 5L
            }
        }

        test("Security-Audit fix (2026-09-12, MAJOR \"kein Rate-Limit\"): the upload route now enforces one") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        // A deliberately tiny budget -- this test only proves the gate fires, not
                        // the production threshold (see Application.kt for the real 30/min value).
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 1, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)

                client
                    .post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(PDF_MAGIC, "beleg0.pdf", "application/pdf"))
                    }.status shouldBe HttpStatusCode.Created
                client
                    .post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(PDF_MAGIC, "beleg1.pdf", "application/pdf"))
                    }.status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("upload on a fremd DRAFT (not subject/requester) -> 403") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val stranger = newMember()
                val lineId = client.draftLineOf(subject)
                client
                    .post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", stranger.toString())
                        setBody(multipartBody(PDF_MAGIC, "beleg.pdf", "application/pdf"))
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "Security-Audit fix (2026-09-12, MAJOR \"orphaned/unbounded receipt storage\"): withdrawing a " +
                "SUBMITTED report reclaims its receipt FILE bytes, but keeps the DB row as an audit trail",
        ) {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)
                val reportId =
                    transaction {
                        TravelExpenseLineTable.selectAll().where { TravelExpenseLineTable.id eq Uuid.parse(lineId) }.single()[
                            TravelExpenseLineTable.reportId,
                        ]
                    }
                val uploadResponse =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(PDF_MAGIC, "beleg.pdf", "application/pdf"))
                    }
                val receiptId =
                    Json
                        .parseToJsonElement(uploadResponse.bodyAsText())
                        .jsonObject["receiptId"]!!
                        .jsonPrimitive.content
                val storageKey =
                    transaction {
                        TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.id eq Uuid.parse(receiptId) }.single()[
                            TravelExpenseReceiptTable.storageKey,
                        ]
                    }
                TEST_RECEIPT_STORAGE_ROOT.resolve(storageKey).exists() shouldBe true

                client.post("/test/travelexpense/submit?id=$reportId") { header("X-Member-Id", subject.toString()) }.status shouldBe
                    HttpStatusCode.OK
                client.post("/test/travelexpense/withdraw?id=$reportId") { header("X-Member-Id", subject.toString()) }.status shouldBe
                    HttpStatusCode.OK

                // Before this fix, NOTHING ever deleted this file for a withdrawn-after-submit
                // report -- repeating submit+withdraw was an unbounded disk-growth loop.
                TEST_RECEIPT_STORAGE_ROOT.resolve(storageKey).exists() shouldBe false
                // The metadata row is deliberately KEPT (audit trail; the download route already
                // handles the now-missing file gracefully with 404 "Stored file missing").
                transaction {
                    TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.id eq Uuid.parse(receiptId) }.count()
                } shouldBe 1L
            }
        }

        test("removeLine deletes its receipt rows AND files") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)
                val uploadResponse =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(PDF_MAGIC, "beleg.pdf", "application/pdf"))
                    }
                val receiptId =
                    Json
                        .parseToJsonElement(uploadResponse.bodyAsText())
                        .jsonObject["receiptId"]!!
                        .jsonPrimitive.content
                val storageKey =
                    transaction {
                        TravelExpenseReceiptTable
                            .selectAll()
                            .where {
                                TravelExpenseReceiptTable.id eq Uuid.parse(receiptId)
                            }.single()[TravelExpenseReceiptTable.storageKey]
                    }
                TEST_RECEIPT_STORAGE_ROOT.resolve(storageKey).exists() shouldBe true

                client.post("/test/travelexpense/removeline?lineId=$lineId") { header("X-Member-Id", subject.toString()) }

                transaction {
                    TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.id eq Uuid.parse(receiptId) }.count()
                } shouldBe 0L
                TEST_RECEIPT_STORAGE_ROOT.resolve(storageKey).exists() shouldBe false
            }
        }

        test("DELETE /receipts/{receiptId} removes the row and the file") {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)
                val uploadResponse =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(PDF_MAGIC, "beleg.pdf", "application/pdf"))
                    }
                val receiptId =
                    Json
                        .parseToJsonElement(uploadResponse.bodyAsText())
                        .jsonObject["receiptId"]!!
                        .jsonPrimitive.content
                val storageKey =
                    transaction {
                        TravelExpenseReceiptTable
                            .selectAll()
                            .where { TravelExpenseReceiptTable.id eq Uuid.parse(receiptId) }
                            .single()[TravelExpenseReceiptTable.storageKey]
                    }

                client
                    .delete("/api/travel-expenses/receipts/$receiptId") {
                        header("X-Member-Id", subject.toString())
                    }.status shouldBe HttpStatusCode.NoContent

                transaction {
                    TravelExpenseReceiptTable.selectAll().where { TravelExpenseReceiptTable.id eq Uuid.parse(receiptId) }.count()
                } shouldBe 0L
                TEST_RECEIPT_STORAGE_ROOT.resolve(storageKey).exists() shouldBe false
            }
        }

        test("review MINOR fix: concurrent receipt deletion racing submitReport never leaves an unbacked RECEIPTED line") {
            // Before the fix, the DELETE route read the report row with a plain (unlocked)
            // SELECT, so it never serialized against submitReport's forUpdate() lock on the
            // identical row -- a delete could commit its removal AFTER submitReport had already
            // read a receipt count of 1 and committed the report as REQUESTED, leaving a
            // submitted report whose RECEIPTED line has zero receipts. With forUpdate() on both
            // sides, the two requests must serialize: whichever runs first is fully visible to
            // the other, so exactly one of {delete succeeds, submit succeeds} can happen -- never
            // both.
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing {
                        registerTravelExpenseTestRoutes()
                        registerTravelExpenseReceiptRoutes(
                            storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                            rateLimiter = FederationInboxRateLimiter(maxRequests = 30, window = 1.minutes),
                        )
                    }
                }
                val subject = newMember()
                val lineId = client.draftLineOf(subject)
                val reportId =
                    transaction {
                        TravelExpenseLineTable
                            .selectAll()
                            .where { TravelExpenseLineTable.id eq Uuid.parse(lineId) }
                            .single()[TravelExpenseLineTable.reportId]
                    }.toString()
                val uploadResponse =
                    client.post("/api/travel-expenses/lines/$lineId/receipts") {
                        header("X-Member-Id", subject.toString())
                        setBody(multipartBody(PDF_MAGIC, "beleg.pdf", "application/pdf"))
                    }
                val receiptId =
                    Json
                        .parseToJsonElement(uploadResponse.bodyAsText())
                        .jsonObject["receiptId"]!!
                        .jsonPrimitive.content

                val (deleteStatus, submitStatus) =
                    coroutineScope {
                        val deleteJob =
                            async {
                                client
                                    .delete("/api/travel-expenses/receipts/$receiptId") {
                                        header("X-Member-Id", subject.toString())
                                    }.status
                            }
                        val submitJob =
                            async {
                                client
                                    .post("/test/travelexpense/submit?id=$reportId") {
                                        header("X-Member-Id", subject.toString())
                                    }.status
                            }
                        deleteJob.await() to submitJob.await()
                    }

                val bothSucceeded = deleteStatus == HttpStatusCode.NoContent && submitStatus == HttpStatusCode.OK
                bothSucceeded shouldBe false

                val (finalStatus, receiptCount) =
                    transaction {
                        val status =
                            TravelExpenseReportTable
                                .selectAll()
                                .where { TravelExpenseReportTable.id eq Uuid.parse(reportId) }
                                .single()[TravelExpenseReportTable.status]
                        val count =
                            TravelExpenseReceiptTable
                                .selectAll()
                                .where { TravelExpenseReceiptTable.lineId eq Uuid.parse(lineId) }
                                .count()
                        status to count
                    }
                // The invariant that actually matters: a submitted report never has a RECEIPTED
                // line with zero receipts, regardless of which side won the race.
                if (finalStatus != TravelExpenseReportStatus.DRAFT) {
                    receiptCount shouldBe 1L
                }
            }
        }
    })
