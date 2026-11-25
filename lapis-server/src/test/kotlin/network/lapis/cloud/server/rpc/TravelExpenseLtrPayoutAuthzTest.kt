package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import network.lapis.cloud.server.testdb.legacyAccountRole
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val ADMIN_ID = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ID = "00000000-0000-0000-0000-000000000002"
private const val TREASURER_ID = "00000000-0000-0000-0000-000000000003"

/**
 * V1.9.325 -- the role gates of the LTR refund service methods (TREASURER/BOARD/ADMIN for five, ADMIN only for the switch) and the guards that
 * keep the switch and its accounts consistent (`LTR_ACCOUNTS_REQUIRED`, the LTR choice only while the switch is on).
 */
class TravelExpenseLtrPayoutAuthzTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val someId = Uuid.random().toString()

        fun setSwitch(enabled: Boolean) =
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[travelLtrPayoutEnabled] = enabled
                    it[travelLtrPayableAccountId] = null
                    it[travelLtrSettlementAccountId] = null
                }
            }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "LTR Authz Testmitglied"
                    it[email] = "ltr-authz-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it.legacyAccountRole(role = AccountRole.MEMBER)
                }
            }
            createdMemberIds += id
            return id
        }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            setSwitch(enabled = false)
            transaction {
                val reportIds =
                    TravelExpenseReportTable
                        .selectAll()
                        .where {
                            (TravelExpenseReportTable.subjectMemberId inList createdMemberIds) or
                                (TravelExpenseReportTable.requestedBy inList createdMemberIds)
                        }.map { it[TravelExpenseReportTable.id] }
                TravelExpenseLineTable.deleteWhere { TravelExpenseLineTable.reportId inList reportIds }
                TravelExpenseReportTable.deleteWhere { TravelExpenseReportTable.id inList reportIds }
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) { it[actorMemberId] = null }
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        val gated =
            listOf(
                "/test/ltr/candidates",
                "/test/ltr/preview?id=$someId",
                "/test/ltr/confirm?id=$someId&price=1",
                "/test/ltr/execute?id=$someId",
                "/test/ltr/reverse?id=$someId&payout=$someId",
            )

        test("a plain MEMBER is refused on all five role-gated LTR methods and on the switch") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseLtrTestRoutes() }
                }
                val member = newMember().toString()
                (gated + "/test/ltr/enable?enabled=true").forEach { url ->
                    client.post(url) { header("X-Member-Id", member) }.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        test("TREASURER and BOARD pass the gate of the five methods but never the switch") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseLtrTestRoutes() }
                }
                listOf(TREASURER_ID, BOARD_ID, ADMIN_ID).forEach { actor ->
                    client.post("/test/ltr/candidates") { header("X-Member-Id", actor) }.status shouldBe HttpStatusCode.OK
                    // past the gate: the unknown report is a 404, not a 403
                    client.post("/test/ltr/preview?id=$someId") { header("X-Member-Id", actor) }.status shouldBe HttpStatusCode.NotFound
                }
                listOf(TREASURER_ID, BOARD_ID).forEach { actor ->
                    client.post("/test/ltr/enable?enabled=true") { header("X-Member-Id", actor) }.status shouldBe HttpStatusCode.Forbidden
                    client.post("/test/ltr/enable?enabled=false") { header("X-Member-Id", actor) }.status shouldBe HttpStatusCode.Forbidden
                }
                client.post("/test/ltr/enable?enabled=false") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
            }
        }

        test("saving the settings while the switch is on refuses to clear the LTR accounts") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                setSwitch(enabled = true)
                val response = client.post("/test/travelexpense/settings") { header("X-Member-Id", ADMIN_ID) }
                response.status shouldBe HttpStatusCode.Conflict
                response.bodyAsText() shouldContain "LTR_ACCOUNTS_REQUIRED"
                setSwitch(enabled = false)
                client.post("/test/travelexpense/settings") { header("X-Member-Id", ADMIN_ID) }.status shouldBe HttpStatusCode.OK
            }
        }

        test("the payout choice LTR is refused on create, update and submit while the switch is off") {
            testApplication {
                application {
                    install(StatusPages) { installTravelExpenseExceptionHandlers() }
                    routing { registerTravelExpenseTestRoutes() }
                }
                val member = newMember()
                val actor = member.toString()
                val base = "subjectMemberId=$member&purpose=Reise&from=2026-01-05&to=2026-01-05"
                setSwitch(enabled = false)
                val refusedOnCreate = client.post("/test/travelexpense/create?$base&payout=LTR") { header("X-Member-Id", actor) }
                refusedOnCreate.status shouldBe HttpStatusCode.Conflict
                refusedOnCreate.bodyAsText() shouldContain "LTR_PAYOUT_DISABLED"

                // a draft created while the switch was on, then the switch is turned off
                setSwitch(enabled = true)
                val draftId =
                    client
                        .post("/test/travelexpense/create?$base&payout=LTR") { header("X-Member-Id", actor) }
                        .bodyAsText()
                        .split("|")[0]
                client.post("/test/travelexpense/addline?reportId=$draftId&kind=MILEAGE&description=Fahrt&kilometers=10") {
                    header("X-Member-Id", actor)
                }
                setSwitch(enabled = false)
                client
                    .post("/test/travelexpense/update?id=$draftId&purpose=Reise&from=2026-01-05&to=2026-01-05&payout=LTR") {
                        header("X-Member-Id", actor)
                    }.status shouldBe HttpStatusCode.Conflict
                client.post("/test/travelexpense/submit?id=$draftId") { header("X-Member-Id", actor) }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }
    })
