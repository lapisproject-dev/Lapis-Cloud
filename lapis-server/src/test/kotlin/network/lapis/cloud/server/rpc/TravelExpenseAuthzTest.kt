package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.TravelExpenseLineTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"

/**
 * Welle V1.4.11 "Reisekostenabrechnung" -- role gates, IDOR, and the four-eyes principle over
 * BOTH paths (subject and requester) for [TravelExpenseService]. Mirrors
 * [ContributionReliefAuthzTest]'s house style.
 */
class TravelExpenseAuthzTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()

        fun setRates() {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[travelMileageRatePerKm] = BigDecimal("0.3000")
                    it[travelPerDiemRate] = BigDecimal("14.00")
                }
            }
        }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            setRates()
        }

        afterSpec {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[travelMileageRatePerKm] = null
                    it[travelPerDiemRate] = null
                }
                val reportIds =
                    TravelExpenseReportTable
                        .selectAll()
                        .where {
                            (TravelExpenseReportTable.subjectMemberId inList createdMemberIds) or
                                (TravelExpenseReportTable.requestedBy inList createdMemberIds)
                        }.map { it[TravelExpenseReportTable.id] }
                TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId inList reportIds }
                TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList reportIds }
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                    it[actorMemberId] = null
                }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun newMember(status: MemberStatus = MemberStatus.ACTIVE): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Authz Testmitglied"
                    it[email] = "travel-authz-$id@example.org"
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

        suspend fun HttpClient.createAndSubmit(
            subjectId: Uuid,
            actorHeader: String,
        ): String {
            val draftId =
                post("/test/travelexpense/create?subjectMemberId=$subjectId&purpose=Reise&from=2026-01-05&to=2026-01-05") {
                    header("X-Member-Id", actorHeader)
                }.bodyAsText().split("|")[0]
            post("/test/travelexpense/addline?reportId=$draftId&kind=MILEAGE&description=Fahrt&kilometers=10") {
                header("X-Member-Id", actorHeader)
            }
            post("/test/travelexpense/submit?id=$draftId") { header("X-Member-Id", actorHeader) }
            return draftId
        }

        // ── Role gates ─────────────────────────────────────────────────────────────

        test("MEMBER may create/submit for themselves but not listReports/decideReport/retryPosting") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val memberId = newMember()
                val reportId = client.createAndSubmit(memberId, memberId.toString())

                client.get("/test/travelexpense/list") { header("X-Member-Id", memberId.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
                client
                    .post("/test/travelexpense/decide?id=$reportId&approve=false&note=x") { header("X-Member-Id", memberId.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client.post("/test/travelexpense/retry?id=$reportId") { header("X-Member-Id", memberId.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("TREASURER may neither listReports nor decideReport nor retryPosting -- the core role-tier test of this wave") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val memberId = newMember()
                val reportId = client.createAndSubmit(memberId, memberId.toString())

                client.get("/test/travelexpense/list") { header("X-Member-Id", TREASURER_ID) }.status shouldBe HttpStatusCode.Forbidden
                client
                    .post("/test/travelexpense/decide?id=$reportId&approve=false&note=x") { header("X-Member-Id", TREASURER_ID) }
                    .status shouldBe HttpStatusCode.Forbidden
                client.post("/test/travelexpense/retry?id=$reportId") { header("X-Member-Id", TREASURER_ID) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("BOARD and ADMIN may both list") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                client.get("/test/travelexpense/list") { header("X-Member-Id", BOARD_ID) }.status shouldBe HttpStatusCode.OK
                client.get("/test/travelexpense/list") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
            }
        }

        test("updateTravelExpenseRates: BOARD -> 403, ADMIN -> 200; getTravelExpenseRates: plain MEMBER -> 200") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val memberId = newMember()
                client.post("/test/travelexpense/rates?mileage=0.30&perDiem=14.00") { header("X-Member-Id", BOARD_ID) }.status shouldBe
                    HttpStatusCode.Forbidden
                client.post("/test/travelexpense/rates?mileage=0.30&perDiem=14.00") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.OK
                client.get("/test/travelexpense/rates") { header("X-Member-Id", memberId.toString()) }.status shouldBe HttpStatusCode.OK
                setRates()
            }
        }

        // ── IDOR ───────────────────────────────────────────────────────────────────

        test("a MEMBER creating a draft for a FOREIGN member -> ForbiddenException") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val actor = newMember()
                val victim = newMember()
                client
                    .post("/test/travelexpense/create?subjectMemberId=$victim&purpose=Reise&from=2026-01-05&to=2026-01-05") {
                        header("X-Member-Id", actor.toString())
                    }.status shouldBe HttpStatusCode.Forbidden
            }
        }

        test("BOARD creating a draft in the name of a foreign member is ALLOWED, requestedBy differs from subject") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val subject = newMember()
                val dto =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$subject&purpose=Reise&from=2026-01-05&to=2026-01-05") {
                            header("X-Member-Id", BOARD_ID)
                        }.bodyAsText()
                        .split("|")
                dto[1] shouldBe subject.toString() // subjectMemberId
                dto[8] shouldBe BOARD_ID // requestedBy
            }
        }

        test("withdrawReport by ADMIN on a fremd report -> ForbiddenException (no privileged bypass)") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val memberId = newMember()
                val draftId =
                    client
                        .post("/test/travelexpense/create?subjectMemberId=$memberId&purpose=Reise&from=2026-01-05&to=2026-01-05") {
                            header("X-Member-Id", memberId.toString())
                        }.bodyAsText()
                        .split("|")[0]
                client.post("/test/travelexpense/withdraw?id=$draftId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        // ── Four-eyes, both paths ──────────────────────────────────────────────────

        test("Weg 1: a BOARD member cannot decide their own request (subjectMemberId == decider)") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val boardMemberId = Uuid.parse(BOARD_ID)
                val reportId = client.createAndSubmit(boardMemberId, BOARD_ID)

                client
                    .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Selbstgenehmigung") {
                        header("X-Member-Id", BOARD_ID)
                    }.status shouldBe HttpStatusCode.Forbidden

                transaction {
                    TravelExpenseReportTable.selectAll().where { TravelExpenseReportTable.id eq Uuid.parse(reportId) }.single()[
                        TravelExpenseReportTable.status,
                    ]
                } shouldBe TravelExpenseReportStatus.REQUESTED

                transaction {
                    TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId eq Uuid.parse(reportId) }
                    TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id eq Uuid.parse(reportId) }
                }
            }
        }

        test(
            "Weg 2 (the gap decideReliefRequest leaves open): a BOARD member who submitted 'on behalf of' someone else may not decide it",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val subject = newMember()
                val reportId = client.createAndSubmit(subject, BOARD_ID)

                client
                    .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") { header("X-Member-Id", BOARD_ID) }
                    .status shouldBe HttpStatusCode.Forbidden

                // A DIFFERENT board member may decide it just fine.
                client
                    .post("/test/travelexpense/decide?id=$reportId&approve=false&note=Abgelehnt") { header("X-Member-Id", ADMIN_ID) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test(
            "Security-Audit fix (2026-09-12): retryPosting enforces the SAME four-eyes exclusion as decideReport -- " +
                "a BOARD member cannot retry-post their OWN approved-but-failed booking",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val boardMemberId = Uuid.parse(BOARD_ID)
                val reportId = client.createAndSubmit(boardMemberId, BOARD_ID)

                // A DIFFERENT board member (ADMIN) approves it -- no expense/bank account is mapped
                // in this test suite, so TravelExpenseExecution.execute fails and the report lands
                // on APPROVED with an executionError, exactly the precondition retryPosting requires.
                val decided =
                    client
                        .post("/test/travelexpense/decide?id=$reportId&approve=true&note=Genehmigt") {
                            header("X-Member-Id", ADMIN_ID)
                        }.bodyAsText()
                        .split("|")
                decided[2] shouldBe TravelExpenseReportStatus.APPROVED.name
                decided[14] shouldNotBe "-" // executionError set

                // The BOARD member who is the SUBJECT of their own report may not retry-post it,
                // even though they are otherwise BOARD-privileged -- before this fix, this call
                // succeeded and attributed the eventual booking's journal_entry/audit row to the
                // beneficiary themselves.
                client.post("/test/travelexpense/retry?id=$reportId") { header("X-Member-Id", BOARD_ID) }.status shouldBe
                    HttpStatusCode.Forbidden

                // A different privileged member may retry it (the booking may still fail for the
                // same unconfigured-account reason -- retryPosting responds 200 either way, only
                // the four-eyes gate above is under test here).
                client.post("/test/travelexpense/retry?id=$reportId") { header("X-Member-Id", ADMIN_ID) }.status shouldBe
                    HttpStatusCode.OK

                transaction {
                    TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId eq Uuid.parse(reportId) }
                    TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id eq Uuid.parse(reportId) }
                }
            }
        }
    })
