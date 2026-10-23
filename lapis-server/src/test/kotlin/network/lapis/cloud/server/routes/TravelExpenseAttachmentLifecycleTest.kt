package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.TravelExpenseAttachmentTable
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.TEST_RECEIPT_STORAGE_ROOT
import network.lapis.cloud.server.rpc.installTravelExpenseExceptionHandlers
import network.lapis.cloud.server.rpc.registerTravelExpenseTestRoutes
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.198 -- lifecycle of report attachments through the real service: withdrawing or rejecting a report reclaims the files but keeps
 * the rows (download then answers 404), and the report DTO carries the attachments (loaded for a whole list in one batch query).
 */
class TravelExpenseAttachmentLifecycleTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34)

        beforeSpec {
            DatabaseConfig.connect()
            network.lapis.cloud.server.db.DevSeedData
                .seedIfEmpty(force = true)
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
                if (reportIds.isNotEmpty()) {
                    TravelExpenseAttachmentTable.deleteWhere { TravelExpenseAttachmentTable.reportId inList reportIds }
                    TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId inList reportIds }
                    TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList reportIds }
                }
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Attachment Lifecycle"
                    it[email] = "te-att-life-$id@example.org"
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

        fun ApplicationTestBuilder.mount() {
            application {
                install(ContentNegotiation) { json() }
                install(StatusPages) { installTravelExpenseExceptionHandlers() }
                routing {
                    registerTravelExpenseTestRoutes()
                    registerTravelExpenseAttachmentRoutes(
                        storageRoot = TEST_RECEIPT_STORAGE_ROOT,
                        rateLimiter = FederationInboxRateLimiter(maxRequests = 100, window = 1.minutes),
                    )
                }
            }
        }

        suspend fun ApplicationTestBuilder.draftWithLine(subject: Uuid): String {
            val draftId =
                client
                    .post("/test/travelexpense/create?subjectMemberId=$subject&purpose=Reise&from=2026-01-05&to=2026-01-05") {
                        header("X-Member-Id", subject.toString())
                    }.bodyAsText()
                    .split("|")[0]
            // A submittable report needs one non-day line; OTHER needs a receipt, a PUBLIC_TRANSPORT-free alternative is mileage-less here.
            client.post("/test/travelexpense/addline?reportId=$draftId&kind=OTHER&description=Beleg&amount=10.00") {
                header("X-Member-Id", subject.toString())
            }
            return draftId
        }

        suspend fun ApplicationTestBuilder.uploadAttachment(
            subject: Uuid,
            reportId: String,
        ): Uuid {
            val response =
                client.post("/api/travel-expenses/reports/$reportId/attachments") {
                    header("X-Member-Id", subject.toString())
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("file", pdf, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"plan.pdf\"") })
                            },
                        ),
                    )
                }
            response.status shouldBe HttpStatusCode.Created
            return Uuid.parse(
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["attachmentId"]!!
                    .jsonPrimitive.content,
            )
        }

        fun rowKey(id: Uuid) =
            transaction {
                TravelExpenseAttachmentTable.selectAll().where { TravelExpenseAttachmentTable.id eq id }.single()[
                    TravelExpenseAttachmentTable.storageKey,
                ]
            }

        test("withdrawing a draft reclaims the attachment file but keeps the row; the download answers 404 afterwards") {
            testApplication {
                mount()
                val subject = newMember()
                val reportId = draftWithLine(subject)
                val attachmentId = uploadAttachment(subject, reportId)
                val file = TEST_RECEIPT_STORAGE_ROOT.resolve(rowKey(attachmentId))
                file.exists() shouldBe true

                client.post("/test/travelexpense/withdraw?id=$reportId") { header("X-Member-Id", subject.toString()) }.status shouldBe
                    HttpStatusCode.OK

                file.exists() shouldBe false
                transaction {
                    TravelExpenseAttachmentTable.selectAll().where { TravelExpenseAttachmentTable.id eq attachmentId }.count()
                } shouldBe
                    1L
                client
                    .get("/api/travel-expenses/attachments/$attachmentId/download") { header("X-Member-Id", subject.toString()) }
                    .status shouldBe HttpStatusCode.NotFound
            }
        }

        test("the report DTOs of a list carry exactly their own attachments (batch-loaded)") {
            testApplication {
                mount()
                val subject = newMember()
                val first = draftWithLine(subject)
                uploadAttachment(subject, first)
                uploadAttachment(subject, first)
                // A member may hold several open drafts; a second report with one attachment of its own.
                val second =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$subject&purpose=Zweite&from=2026-02-05&to=2026-02-05") {
                            header("X-Member-Id", subject.toString())
                        }.bodyAsText()
                        .split("|")[0]
                uploadAttachment(subject, second)

                val byReport =
                    client
                        .get("/test/travelexpense/mineattachments") { header("X-Member-Id", subject.toString()) }
                        .bodyAsText()
                        .split(";")
                        .associate { it.substringBefore(":") to it.substringAfter(":") }
                byReport[first] shouldBe "plan.pdf,plan.pdf"
                byReport[second] shouldBe "plan.pdf"
            }
        }
    })
