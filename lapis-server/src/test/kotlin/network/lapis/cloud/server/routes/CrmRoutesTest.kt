package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.crm.CrmContactStore
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.DsgvoAuditLogTable
import network.lapis.cloud.shared.domain.CrmContactInput
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import network.lapis.cloud.shared.domain.DsgvoSubjectKind
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val MEMBER_ID = "00000000-0000-0000-0000-000000000004"

/** Structurally 1:1 to [network.lapis.cloud.server.rpc.DsgvoServiceTest]'s own HTTP-export exercising, for `GET /api/dsgvo/crm-contacts/{id}/export`. */
class CrmRoutesTest :
    FunSpec({
        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        suspend fun testApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application { routing { registerCrmRoutes() } }
                block()
            }
        }

        fun createContact(): Uuid =
            transaction {
                Uuid.parse(
                    CrmContactStore
                        .create(
                            input =
                                CrmContactInput(
                                    displayName = "Routen-Testkontakt",
                                    email = null,
                                    phone = null,
                                    street = null,
                                    postalCode = null,
                                    city = null,
                                    country = null,
                                    contactType = CrmContactType.INTERESSENT,
                                    lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
                                    consentSource = null,
                                    consentGivenAt = null,
                                    externalDonorId = null,
                                    memberId = null,
                                ),
                            createdBy = Uuid.parse(ADMIN_ID),
                        ).id,
                )
            }

        test("no session at all -> 401") {
            testApp {
                val id = createContact()
                client.get("/api/dsgvo/crm-contacts/$id/export").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("a plain MEMBER -> 403") {
            testApp {
                val id = createContact()
                client.get("/api/dsgvo/crm-contacts/$id/export") { header("X-Member-Id", MEMBER_ID) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("an invalid UUID -> 400") {
            testApp {
                client.get("/api/dsgvo/crm-contacts/not-a-uuid/export") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        test("an unknown contact id -> 404") {
            testApp {
                client
                    .get("/api/dsgvo/crm-contacts/${Uuid.random()}/export") {
                        header("X-Member-Id", ADMIN_ID)
                    }.status shouldBe HttpStatusCode.NotFound
            }
        }

        test("BOARD -> 200 and writes exactly one new dsgvo_audit_log row with subject_kind = CRM_CONTACT and outcome_summary IS NULL") {
            testApp {
                val id = createContact()
                val before =
                    transaction {
                        DsgvoAuditLogTable.selectAll().where { DsgvoAuditLogTable.subjectMemberId eq id }.count()
                    }

                client.get("/api/dsgvo/crm-contacts/$id/export") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK

                val newRows =
                    transaction {
                        DsgvoAuditLogTable
                            .selectAll()
                            .where {
                                (DsgvoAuditLogTable.subjectMemberId eq id) and
                                    (DsgvoAuditLogTable.subjectKind eq DsgvoSubjectKind.CRM_CONTACT)
                            }.toList()
                    }
                newRows.size.toLong() shouldBe before + 1
                newRows.last()[DsgvoAuditLogTable.outcomeSummary] shouldBe null
            }
        }
    })
