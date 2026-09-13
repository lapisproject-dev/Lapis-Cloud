package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.VatRate
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.13 "USt-Voranmeldung" -- posts all 20 (`GemeinnuetzigkeitSphere` x `VatRate`)
 * combinations and asserts every one is ACCEPTED. Exists precisely so that nobody later re-adds a
 * `requireVatRateMatchesSphere`-style guard by mistake -- see `AccountingService` KDoc comment next
 * to `requireCashRegisterOnlyOnAsset` for the deliberate-non-goal rationale (the 7-%-Zweckbetrieb
 * eligibility hinges on the Wettbewerbsvorbehalt, §12 Abs.2 Nr.8a UStG, a Vorstands-
 * Ermessensfrage this software cannot adjudicate).
 */
class VatRateSphereIndependenceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        val createdLedgerAccountIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                    it[vatEnabled] = true
                    it[isKleinunternehmer] = false
                }
            }
        }

        afterSpec {
            transaction {
                OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) { it[vatEnabled] = false }
                if (createdMemberIds.isNotEmpty()) {
                    val journalEntryIds =
                        JournalEntryTable.selectAll().where { JournalEntryTable.createdBy inList createdMemberIds }.map {
                            it[JournalEntryTable.id]
                        }
                    if (journalEntryIds.isNotEmpty()) {
                        PostingTable.deleteWhere { PostingTable.journalEntryId inList journalEntryIds }
                        JournalEntryTable.deleteWhere { JournalEntryTable.id inList journalEntryIds }
                    }
                }
                if (createdLedgerAccountIds.isNotEmpty()) {
                    PostingTable.deleteWhere { PostingTable.ledgerAccountId inList createdLedgerAccountIds }
                    LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList createdLedgerAccountIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    // postJournalEntry calls AuditLogRecorder.record with actorMemberId =
                    // current.memberId -- null it out before deleting the member, otherwise the
                    // MemberTable delete below trips fk_audit_log_entry_actor_member_id (same fix
                    // as AccountingServiceVatTest's afterSpec).
                    AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList createdMemberIds }) {
                        it[actorMemberId] = null
                    }
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        test("all 20 (sphere x rate) combinations are accepted and correctly reflected in the posted entry") {
            testApplication {
                application {
                    install(StatusPages) { installIndependenceExceptionHandlers() }
                    routing { registerIndependenceTestRoutes() }
                }
                val treasurerId = Uuid.random()
                transaction {
                    MemberTable.insert {
                        it[id] = treasurerId
                        it[displayName] = "Independence Testmitglied"
                        it[email] = "vat-independence-$treasurerId@example.org"
                        it[status] = MemberStatus.ACTIVE
                        it[joinedAt] = LocalDate(2026, 1, 1)
                        it[membershipTierId] = null
                    }
                    AccountTable.insert {
                        it[id] = Uuid.random()
                        it[memberId] = treasurerId
                        it[role] = AccountRole.TREASURER
                    }
                }
                createdMemberIds += treasurerId

                val bankId = Uuid.random()
                transaction {
                    LedgerAccountTable.insert {
                        it[id] = bankId
                        it[accountNumber] = "IND0100"
                        it[name] = "Independence Bank"
                        it[accountClass] = 0
                        it[type] = LedgerAccountType.ASSET
                        it[active] = true
                        it[reserveType] = null
                        it[isCashRegister] = false
                    }
                }
                createdLedgerAccountIds += bankId

                val incomeId = Uuid.random()
                transaction {
                    LedgerAccountTable.insert {
                        it[id] = incomeId
                        it[accountNumber] = "IND4000"
                        it[name] = "Independence Erloese"
                        it[accountClass] = 0
                        it[type] = LedgerAccountType.INCOME
                        it[active] = true
                        it[reserveType] = null
                        it[isCashRegister] = false
                    }
                }
                createdLedgerAccountIds += incomeId

                var caseNumber = 0
                for (sphere in GemeinnuetzigkeitSphere.entries) {
                    for (rate in VatRate.entries) {
                        caseNumber++
                        val postings =
                            "$bankId:DEBIT:100.00:$sphere::UNCLASSIFIED,$incomeId:CREDIT:100.00:$sphere::$rate"
                        val response =
                            client.post(
                                "/test/independence/post?date=2026-02-01&description=Case-$caseNumber&postings=$postings",
                            ) { header("X-Member-Id", treasurerId.toString()) }
                        response.bodyAsText() shouldBe "POSTED:$sphere:$rate"
                    }
                }
            }
        }
    })

private fun StatusPagesConfig.installIndependenceExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText("UNAUTHENTICATED:${cause.message}") }
    exception<ForbiddenException> { call, cause -> call.respondText("FORBIDDEN:${cause.message}") }
    exception<NotFoundException> { call, cause -> call.respondText("NOT_FOUND:${cause.message}") }
    exception<ConflictException> { call, cause -> call.respondText("CONFLICT:${cause.message}") }
    exception<BadRequestException> { call, cause -> call.respondText("BAD_REQUEST:${cause.message}") }
}

private fun Route.registerIndependenceTestRoutes() {
    post("/test/independence/post") {
        val service = AccountingService(call)
        val q = call.request.queryParameters
        val postings =
            (q["postings"] ?: "")
                .split(",")
                .filter { it.isNotBlank() }
                .map { entry ->
                    val parts = entry.split(":")
                    PostingInput(
                        ledgerAccountId = parts[0],
                        side = PostingSide.valueOf(parts[1]),
                        amount = BigDecimal(parts[2]),
                        sphere = GemeinnuetzigkeitSphere.valueOf(parts[3]),
                        costCenterId = null,
                        vatRate = VatRate.valueOf(parts[5]),
                    )
                }
        val dto =
            service.postJournalEntry(
                JournalEntryInput(
                    entryDate = LocalDate.parse(q["date"]!!),
                    description = q["description"]!!,
                    postings = postings,
                ),
            )
        val creditPosting = dto.postings.first { it.side == PostingSide.CREDIT }
        call.respondText("${dto.status}:${creditPosting.sphere}:${creditPosting.vatRate}")
    }
}
