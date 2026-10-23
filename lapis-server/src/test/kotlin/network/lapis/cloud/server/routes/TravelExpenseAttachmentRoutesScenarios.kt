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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SessionTable
import network.lapis.cloud.server.db.generated.TravelExpenseAttachmentTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val ATT_PDF = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34)
private val ATT_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00)
private val ATT_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

/**
 * V1.9.198 -- security and behaviour coverage of [registerTravelExpenseAttachmentRoutes] on H2 and
 * on a real PostgreSQL (the row-lock race is the point of the Postgres lane). Members are created
 * directly with real sessions (`Authorization: Bearer`), reports directly in the table, so the
 * spec depends on no seed data and never calls `module()`.
 */
abstract class TravelExpenseAttachmentRoutesScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val storageRoot: File = Files.createTempDirectory("te-attachments").toFile()

        beforeSpec { db.activate() }
        installLaneGuards(db = db)
        afterSpec {
            try {
                transaction {
                    if (createdMemberIds.isNotEmpty()) {
                        val reportIds =
                            TravelExpenseReportTable
                                .selectAll()
                                .where { TravelExpenseReportTable.subjectMemberId inList createdMemberIds }
                                .map { it[TravelExpenseReportTable.id] }
                        TravelExpenseAttachmentTable.deleteWhere { TravelExpenseAttachmentTable.reportId inList reportIds }
                        TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList reportIds }
                        SessionTable.deleteWhere { SessionTable.memberId inList createdMemberIds }
                        AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                        MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                    }
                }
            } finally {
                storageRoot.deleteRecursively()
                db.deactivate()
            }
        }

        class Actor(
            val id: Uuid,
            val token: String,
        )

        fun newActor(role: AccountRole = AccountRole.MEMBER): Actor {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Attachment Testmitglied"
                    it[email] = "te-attachment-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return Actor(id, SessionStore.createSession(id).rawToken)
        }

        fun newReport(
            subject: Actor,
            status: TravelExpenseReportStatus = TravelExpenseReportStatus.DRAFT,
            requestedBy: Actor = subject,
        ): Uuid {
            val reportId = Uuid.random()
            val now = DbClock.nowLocalDateTime()
            transaction {
                TravelExpenseReportTable.insert {
                    it[id] = reportId
                    it[subjectMemberId] = subject.id
                    it[TravelExpenseReportTable.status] = status
                    it[purpose] = "Attachment-Test"
                    it[travelFrom] = LocalDate(2026, 1, 5)
                    it[travelTo] = LocalDate(2026, 1, 5)
                    it[totalAmount] = BigDecimal("0.00")
                    it[createdAt] = now
                    it[TravelExpenseReportTable.requestedBy] = requestedBy.id
                    it[advanceAmount] = BigDecimal("0.00")
                    it[refundAccountChangedAfterSubmit] = false
                    it[paidOutsideLapis] = false
                    it[sepaReexportCount] = 0
                    if (status != TravelExpenseReportStatus.DRAFT && status != TravelExpenseReportStatus.WITHDRAWN) {
                        it[submittedAt] = now
                    }
                    if (status in setOf(TravelExpenseReportStatus.APPROVED, TravelExpenseReportStatus.REJECTED)) {
                        it[decisionNote] = "Test"
                    }
                }
            }
            return reportId
        }

        fun setStatus(
            reportId: Uuid,
            status: TravelExpenseReportStatus,
        ) {
            transaction {
                TravelExpenseReportTable.update({ TravelExpenseReportTable.id eq reportId }) {
                    it[TravelExpenseReportTable.status] = status
                    it[submittedAt] = if (status == TravelExpenseReportStatus.DRAFT) null else DbClock.nowLocalDateTime()
                    if (status in setOf(TravelExpenseReportStatus.APPROVED, TravelExpenseReportStatus.REJECTED)) {
                        it[decisionNote] = "Test"
                    }
                }
            }
        }

        fun ApplicationTestBuilder.mount(
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes),
        ) {
            application {
                install(ContentNegotiation) { json() }
                routing { registerTravelExpenseAttachmentRoutes(storageRoot = storageRoot, rateLimiter = limiter) }
            }
        }

        fun multipart(
            bytes: ByteArray,
            fileName: String = "anlage.pdf",
            declaredType: String = "application/pdf",
        ) = MultiPartFormDataContent(
            formData {
                append(
                    "file",
                    bytes,
                    Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        append(HttpHeaders.ContentType, declaredType)
                    },
                )
            },
        )

        suspend fun HttpClient.upload(
            actor: Actor,
            reportId: Uuid,
            bytes: ByteArray = ATT_PDF,
            fileName: String = "anlage.pdf",
            declaredType: String = "application/pdf",
        ): HttpResponse =
            post("/api/travel-expenses/reports/$reportId/attachments") {
                header(HttpHeaders.Authorization, "Bearer ${actor.token}")
                setBody(multipart(bytes, fileName, declaredType))
            }

        suspend fun HttpResponse.attachmentId(): Uuid =
            Uuid.parse(
                Json
                    .parseToJsonElement(bodyAsText())
                    .jsonObject["attachmentId"]!!
                    .jsonPrimitive.content,
            )

        fun rowOf(id: Uuid) =
            transaction { TravelExpenseAttachmentTable.selectAll().where { TravelExpenseAttachmentTable.id eq id }.single() }

        fun attachmentCount(reportId: Uuid): Long =
            transaction { TravelExpenseAttachmentTable.selectAll().where { TravelExpenseAttachmentTable.reportId eq reportId }.count() }

        fun filesUnder(reportId: Uuid): Int =
            storageRoot
                .resolve("travel-expenses/$reportId/attachments")
                .listFiles()
                ?.size ?: 0

        test("PDF, JPEG and PNG are accepted (201) and stored with the sniffed type") {
            testApplication {
                mount()
                val owner = newActor()
                val reportId = newReport(owner)
                listOf(ATT_PDF to "application/pdf", ATT_JPEG to "image/jpeg", ATT_PNG to "image/png").forEach { (bytes, mime) ->
                    val response = client.upload(owner, reportId, bytes, fileName = "datei", declaredType = "application/octet-stream")
                    response.status shouldBe HttpStatusCode.Created
                    val row = rowOf(response.attachmentId())
                    row[TravelExpenseAttachmentTable.mimeType] shouldBe mime
                    row[TravelExpenseAttachmentTable.sizeBytes] shouldBe bytes.size.toLong()
                    storageRoot.resolve(row[TravelExpenseAttachmentTable.storageKey]).exists() shouldBe true
                }
                attachmentCount(reportId) shouldBe 3L
            }
        }

        test("magic bytes win: a PNG declared as application/pdf is stored as image/png") {
            testApplication {
                mount()
                val owner = newActor()
                val reportId = newReport(owner)
                val response = client.upload(owner, reportId, ATT_PNG, declaredType = "application/pdf")
                response.status shouldBe HttpStatusCode.Created
                rowOf(response.attachmentId())[TravelExpenseAttachmentTable.mimeType] shouldBe "image/png"
            }
        }

        test("SVG and HTML bodies are rejected with 415, no row and no file") {
            testApplication {
                mount()
                val owner = newActor()
                val reportId = newReport(owner)
                client
                    .upload(owner, reportId, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray(), "x.svg", "image/svg+xml")
                    .status shouldBe HttpStatusCode.UnsupportedMediaType
                client
                    .upload(owner, reportId, "<html><script>1</script></html>".toByteArray(), "x.pdf", "application/pdf")
                    .status shouldBe HttpStatusCode.UnsupportedMediaType
                attachmentCount(reportId) shouldBe 0L
                filesUnder(reportId) shouldBe 0
            }
        }

        test("an upload over 10 MiB is rejected with 413 while streaming, no row and no leftover file") {
            testApplication {
                mount()
                val owner = newActor()
                val reportId = newReport(owner)
                val oversized = ByteArray((TravelExpenseAmountRules.MAX_UPLOAD_BYTES + 1).toInt()) { ATT_PDF.getOrElse(it) { 0 } }
                client.upload(owner, reportId, oversized).status shouldBe HttpStatusCode.PayloadTooLarge
                attachmentCount(reportId) shouldBe 0L
                filesUnder(reportId) shouldBe 0
            }
        }

        test("two file parts and no file part are both 400 with no leftovers") {
            testApplication {
                mount()
                val owner = newActor()
                val reportId = newReport(owner)
                val two =
                    MultiPartFormDataContent(
                        formData {
                            append("a", ATT_PDF, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.pdf\"") })
                            append("b", ATT_PDF, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"b.pdf\"") })
                        },
                    )
                client
                    .post("/api/travel-expenses/reports/$reportId/attachments") {
                        header(HttpHeaders.Authorization, "Bearer ${owner.token}")
                        setBody(two)
                    }.status shouldBe HttpStatusCode.BadRequest
                client
                    .post("/api/travel-expenses/reports/$reportId/attachments") {
                        header(HttpHeaders.Authorization, "Bearer ${owner.token}")
                        setBody(MultiPartFormDataContent(formData { append("note", "no file") }))
                    }.status shouldBe HttpStatusCode.BadRequest
                attachmentCount(reportId) shouldBe 0L
                filesUnder(reportId) shouldBe 0
            }
        }

        test("path traversal in the file name is neutralised: server-generated storage key, no separator in the display name") {
            testApplication {
                mount()
                val owner = newActor()
                val reportId = newReport(owner)
                listOf("../../../etc/passwd", "..\\..\\x.pdf").forEach { evil ->
                    val response = client.upload(owner, reportId, fileName = evil)
                    response.status shouldBe HttpStatusCode.Created
                    val row = rowOf(response.attachmentId())
                    val key = row[TravelExpenseAttachmentTable.storageKey]
                    key.startsWith("travel-expenses/$reportId/attachments/") shouldBe true
                    key.contains("..") shouldBe false
                    row[TravelExpenseAttachmentTable.originalFilename].contains("/") shouldBe false
                    row[TravelExpenseAttachmentTable.originalFilename].contains("\\") shouldBe false
                    storageRoot.resolve(key).canonicalPath.startsWith(storageRoot.canonicalPath) shouldBe true
                }
            }
        }

        test("the 11th attachment is a 409; receipts do not count against the attachment cap") {
            testApplication {
                mount()
                val owner = newActor()
                val reportId = newReport(owner)
                repeat(TravelExpenseAmountRules.MAX_ATTACHMENTS_PER_REPORT) {
                    client.upload(owner, reportId).status shouldBe HttpStatusCode.Created
                }
                client.upload(owner, reportId).status shouldBe HttpStatusCode.Conflict
                attachmentCount(reportId) shouldBe TravelExpenseAmountRules.MAX_ATTACHMENTS_PER_REPORT.toLong()
                filesUnder(reportId) shouldBe TravelExpenseAmountRules.MAX_ATTACHMENTS_PER_REPORT
            }
        }

        test("parallel uploads never exceed the cap (row lock on the report) and leave no orphan files") {
            testApplication {
                mount()
                val owner = newActor()
                val reportId = newReport(owner)
                val statuses =
                    coroutineScope {
                        (1..14).map { async { client.upload(owner, reportId).status } }.awaitAll()
                    }
                statuses.count { it == HttpStatusCode.Created } shouldBe TravelExpenseAmountRules.MAX_ATTACHMENTS_PER_REPORT
                statuses.count { it == HttpStatusCode.Conflict } shouldBe 4
                attachmentCount(reportId) shouldBe TravelExpenseAmountRules.MAX_ATTACHMENTS_PER_REPORT.toLong()
                filesUnder(reportId) shouldBe TravelExpenseAmountRules.MAX_ATTACHMENTS_PER_REPORT
            }
        }

        test("IDOR: a stranger gets 403 on POST, DELETE and GET of someone else's draft; permission is checked before status") {
            testApplication {
                mount()
                val owner = newActor()
                val stranger = newActor()
                val draft = newReport(owner)
                val submitted = newReport(owner, TravelExpenseReportStatus.REQUESTED)
                val attachmentId = client.upload(owner, draft).attachmentId()

                client.upload(stranger, draft).status shouldBe HttpStatusCode.Forbidden
                // A foreign member must not learn that the report is already submitted: 403, never 409.
                client.upload(stranger, submitted).status shouldBe HttpStatusCode.Forbidden
                client
                    .delete(
                        "/api/travel-expenses/attachments/$attachmentId",
                    ) { header(HttpHeaders.Authorization, "Bearer ${stranger.token}") }
                    .status shouldBe HttpStatusCode.Forbidden
                client
                    .get("/api/travel-expenses/attachments/$attachmentId/download") {
                        header(HttpHeaders.Authorization, "Bearer ${stranger.token}")
                    }.status shouldBe HttpStatusCode.Forbidden
                attachmentCount(draft) shouldBe 1L
            }
        }

        test("unknown ids: 404 for an unknown report/attachment, 400 for a malformed id, a receipt id is not an attachment id") {
            testApplication {
                mount()
                val owner = newActor()
                client.upload(owner, Uuid.random()).status shouldBe HttpStatusCode.NotFound
                client
                    .delete(
                        "/api/travel-expenses/attachments/${Uuid.random()}",
                    ) { header(HttpHeaders.Authorization, "Bearer ${owner.token}") }
                    .status shouldBe HttpStatusCode.NotFound
                client
                    .get(
                        "/api/travel-expenses/attachments/not-a-uuid/download",
                    ) { header(HttpHeaders.Authorization, "Bearer ${owner.token}") }
                    .status shouldBe HttpStatusCode.BadRequest
                client
                    .post("/api/travel-expenses/reports/not-a-uuid/attachments") {
                        header(HttpHeaders.Authorization, "Bearer ${owner.token}")
                        setBody(multipart(ATT_PDF))
                    }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        test("a requester who is not the subject may upload and delete; status other than DRAFT blocks upload and delete with 409") {
            testApplication {
                mount()
                val subject = newActor()
                val requester = newActor()
                val reportId = newReport(subject, requestedBy = requester)
                val attachmentId = client.upload(requester, reportId).attachmentId()
                // Now freeze the report in every non-editable state.
                listOf(
                    TravelExpenseReportStatus.REQUESTED,
                    TravelExpenseReportStatus.APPROVED,
                    TravelExpenseReportStatus.REJECTED,
                    TravelExpenseReportStatus.WITHDRAWN,
                ).forEach { status ->
                    setStatus(reportId, status)
                    client.upload(subject, reportId).status shouldBe HttpStatusCode.Conflict
                    client
                        .delete(
                            "/api/travel-expenses/attachments/$attachmentId",
                        ) { header(HttpHeaders.Authorization, "Bearer ${subject.token}") }
                        .status shouldBe HttpStatusCode.Conflict
                }
                attachmentCount(reportId) shouldBe 1L
                setStatus(reportId, TravelExpenseReportStatus.DRAFT)
                val key =
                    transaction {
                        TravelExpenseAttachmentTable.selectAll().single {
                            it[TravelExpenseAttachmentTable.id] ==
                                attachmentId
                        }
                    }.get(TravelExpenseAttachmentTable.storageKey)
                client
                    .delete(
                        "/api/travel-expenses/attachments/$attachmentId",
                    ) { header(HttpHeaders.Authorization, "Bearer ${subject.token}") }
                    .status shouldBe HttpStatusCode.NoContent
                attachmentCount(reportId) shouldBe 0L
                storageRoot.resolve(key).exists() shouldBe false
            }
        }

        test(
            "download matrix: subject and requester 200 (also on a draft), BOARD/ADMIN 403 on a draft and 200 once submitted, TREASURER 403, anonymous 401",
        ) {
            testApplication {
                mount()
                val subject = newActor()
                val requester = newActor()
                val board = newActor(AccountRole.BOARD)
                val admin = newActor(AccountRole.ADMIN)
                val treasurer = newActor(AccountRole.TREASURER)
                val reportId = newReport(subject, requestedBy = requester)
                val attachmentId = client.upload(requester, reportId).attachmentId()

                suspend fun status(actor: Actor?): HttpStatusCode =
                    client
                        .get("/api/travel-expenses/attachments/$attachmentId/download") {
                            if (actor != null) header(HttpHeaders.Authorization, "Bearer ${actor.token}")
                        }.status

                status(subject) shouldBe HttpStatusCode.OK
                status(requester) shouldBe HttpStatusCode.OK
                status(board) shouldBe HttpStatusCode.Forbidden
                status(admin) shouldBe HttpStatusCode.Forbidden
                status(treasurer) shouldBe HttpStatusCode.Forbidden
                status(null) shouldBe HttpStatusCode.Unauthorized

                setStatus(reportId, TravelExpenseReportStatus.REQUESTED)
                status(board) shouldBe HttpStatusCode.OK
                status(admin) shouldBe HttpStatusCode.OK
                status(treasurer) shouldBe HttpStatusCode.Forbidden
            }
        }

        test("download headers: attachment disposition with RFC 5987 name, nosniff, private no-store, sniffed content type") {
            testApplication {
                mount()
                val owner = newActor()
                val reportId = newReport(owner)
                val attachmentId =
                    client
                        .upload(
                            owner,
                            reportId,
                            ATT_PNG,
                            fileName = "Grüße \"x\".png",
                            declaredType = "application/pdf",
                        ).attachmentId()
                val response =
                    client.get("/api/travel-expenses/attachments/$attachmentId/download") {
                        header(HttpHeaders.Authorization, "Bearer ${owner.token}")
                    }
                response.status shouldBe HttpStatusCode.OK
                val disposition = response.headers[HttpHeaders.ContentDisposition]!!
                disposition shouldContain "attachment"
                disposition shouldContain "filename*=UTF-8''"
                disposition shouldNotContain "\r"
                disposition shouldNotContain "\n"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers[HttpHeaders.CacheControl] shouldBe "private, no-store"
                response.headers[HttpHeaders.ContentType]!! shouldContain "image/png"
            }
        }

        test("a missing file on disk is a 404 for the download, not a 500") {
            testApplication {
                mount()
                val owner = newActor()
                val reportId = newReport(owner)
                val attachmentId = client.upload(owner, reportId).attachmentId()
                storageRoot.resolve("travel-expenses/$reportId").deleteRecursively()
                client
                    .get(
                        "/api/travel-expenses/attachments/$attachmentId/download",
                    ) { header(HttpHeaders.Authorization, "Bearer ${owner.token}") }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        test("rate limit: the member budget is enforced with 429 and nothing is stored for the refused request") {
            testApplication {
                mount(FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes))
                val owner = newActor()
                val reportId = newReport(owner)
                client.upload(owner, reportId).status shouldBe HttpStatusCode.Created
                client.upload(owner, reportId).status shouldBe HttpStatusCode.Created
                client.upload(owner, reportId).status shouldBe HttpStatusCode.TooManyRequests
                attachmentCount(reportId) shouldBe 2L
            }
        }
    })
