package network.lapis.cloud.server.partg

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.DsgvoAuditLogTable
import network.lapis.cloud.server.db.generated.ErasureRequestTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartgAccountMappingTable
import network.lapis.cloud.server.db.generated.PartgConsolidationSheetTable
import network.lapis.cloud.server.db.generated.PartgDonorCombinationRowTable
import network.lapis.cloud.server.db.generated.PartgDonorCombinationTable
import network.lapis.cloud.server.db.generated.PartgIntraTransferTable
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportDeadlineTable
import network.lapis.cloud.server.db.generated.PartgReportDeclarationTable
import network.lapis.cloud.server.db.generated.PartgReportEnterpriseTable
import network.lapis.cloud.server.db.generated.PartgReportHoldingTable
import network.lapis.cloud.server.db.generated.PartgReportLargeDonorTable
import network.lapis.cloud.server.db.generated.PartgReportNoteTable
import network.lapis.cloud.server.db.generated.PartgReportNotesSheetTable
import network.lapis.cloud.server.db.generated.PartgReportPositionTable
import network.lapis.cloud.server.db.generated.PartgReportSheetTable
import network.lapis.cloud.server.db.generated.PartgReportSponsorTable
import network.lapis.cloud.server.db.generated.PartgReportSponsoringEntryTable
import network.lapis.cloud.server.db.generated.PartgReportUnitModeTable
import network.lapis.cloud.server.db.generated.PartgReportValueTable
import network.lapis.cloud.server.db.generated.PartgReportVersionCounterTable
import network.lapis.cloud.server.db.generated.PartgReportVersionTable
import network.lapis.cloud.server.db.generated.PartgReportYouthOrgTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.db.generated.RegionalChapterTreasurerTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.AccountingService
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PartyReportService
import network.lapis.cloud.server.rpc.RegionalChapterService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Harness of the V1.9.225 party-report tests. House style of the gallery tests: ONE generic throwaway route runs a lambda against the call of
 * that request (`X-Member-Id` resolves the caller) and stores the [Result], so tests assert on real exception TYPES. Works on H2 and PostgreSQL.
 */
internal class PartgApp(
    val client: HttpClient,
    private val ops: ConcurrentHashMap<String, suspend (ApplicationCall) -> Any?>,
    private val results: ConcurrentHashMap<String, Result<Any?>>,
    val fixtures: PartgFixtures,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> attempt(
        member: Uuid,
        op: suspend (ApplicationCall) -> T,
    ): Result<T> {
        val id = Uuid.random().toString()
        ops[id] = op
        client.post("/partg-op/$id") { header("X-Member-Id", member.toString()) }
        ops.remove(id)
        return results.remove(id)!! as Result<T>
    }

    suspend fun <T> call(
        member: Uuid,
        op: suspend (ApplicationCall) -> T,
    ): T = attempt(member = member, op = op).getOrThrow()

    /** The service as the call's caller sees it. */
    fun service(callArg: ApplicationCall) = fixtures.service(callArg)
}

internal fun partgTestApplication(
    fixtures: PartgFixtures,
    routes: io.ktor.server.routing.Route.() -> Unit = {},
    block: suspend PartgApp.() -> Unit,
) {
    val ops = ConcurrentHashMap<String, suspend (ApplicationCall) -> Any?>()
    val results = ConcurrentHashMap<String, Result<Any?>>()
    testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                routes()
                post("/partg-op/{id}") {
                    val id = call.parameters["id"]!!
                    val op = ops.getValue(id)
                    results[id] =
                        try {
                            Result.success(op(call))
                        } catch (e: Throwable) {
                            Result.failure(e)
                        }
                    call.respondText("done")
                }
            }
        }
        PartgApp(client = client, ops = ops, results = results, fixtures = fixtures).block()
    }
}

/** Fixtures + cleanup of everything a party-report test creates (own rows only; the seeded position catalogue stays). */
internal class PartgFixtures(
    val limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 100_000, window = 60.minutes),
) {
    val memberIds = mutableListOf<Uuid>()
    val chapterIds = mutableListOf<Uuid>()
    val accountIds = mutableListOf<Uuid>()

    fun service(call: ApplicationCall) = PartyReportService(call = call, writeRateLimiter = limiter)

    fun accounting(call: ApplicationCall) = AccountingService(call)

    fun chapterService(call: ApplicationCall) = RegionalChapterService(call)

    /** A ledger account with a unique number (V1.9.289: the booking and mapping scenarios). */
    fun ledgerAccount(
        type: LedgerAccountType,
        name: String = "Test ${Uuid.random()}",
        active: Boolean = true,
        cashRegister: Boolean = false,
    ): Uuid {
        val id = Uuid.random()
        val number = (10_000_000 + (Math.abs(id.hashCode()) % 80_000_000)).toString()
        transaction {
            LedgerAccountTable.insert {
                it[LedgerAccountTable.id] = id
                it[accountNumber] = number
                it[LedgerAccountTable.name] = name
                it[accountClass] = number.first().digitToInt()
                it[LedgerAccountTable.type] = type
                it[LedgerAccountTable.active] = active
                it[reserveType] = null
                it[isCashRegister] = cashRegister
            }
        }
        accountIds += id
        return id
    }

    /** An ACTIVE chapter-treasurer grant, inserted directly (the RPC is tested on its own). */
    fun grantTreasurer(
        member: Uuid,
        chapter: Uuid,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            RegionalChapterTreasurerTable.insert {
                it[RegionalChapterTreasurerTable.id] = id
                it[memberId] = member
                it[regionalChapterId] = chapter
                it[grantedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[grantedByMemberId] = null
                it[revokedAt] = null
                it[activeKey] = "$member:$chapter"
            }
        }
        return id
    }

    fun revokeTreasurer(grantId: Uuid) {
        transaction {
            RegionalChapterTreasurerTable.update({ RegionalChapterTreasurerTable.id eq grantId }) {
                it[revokedAt] = LocalDateTime(2026, 2, 1, 0, 0)
                it[activeKey] = null
            }
        }
    }

    fun member(
        label: String = "m",
        role: AccountRole = AccountRole.BOARD,
        status: MemberStatus = MemberStatus.ACTIVE,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "Partg-Test $label"
                it[email] = "partg-test-$id@example.org"
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
            }
            AccountTable.insert {
                it[AccountTable.id] = Uuid.random()
                it[memberId] = id
                it[AccountTable.role] = role
            }
        }
        memberIds += id
        return id
    }

    fun chapter(name: String = "Landesverband ${Uuid.random()}"): Uuid {
        val id = Uuid.random()
        transaction {
            RegionalChapterTable.insert {
                it[RegionalChapterTable.id] = id
                it[RegionalChapterTable.name] = name
                it[nameKey] = name.lowercase()
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
        }
        chapterIds += id
        return id
    }

    fun setParty(party: Boolean) {
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) { it[isPoliticalParty] = party }
        }
    }

    fun sheetCount(): Long =
        transaction {
            PartgReportSheetTable.selectAll().where { PartgReportSheetTable.updatedBy inList memberIds }.count()
        }

    fun cleanUp() {
        transaction {
            // V1.9.291 (R6): the kept versions of the test (declarations, versions, counters) and the deadlines come first
            val versionIds =
                PartgReportVersionTable
                    .select(PartgReportVersionTable.id)
                    .where { (PartgReportVersionTable.frozenBy inList memberIds) or (PartgReportVersionTable.finalizedBy inList memberIds) }
                    .map { it[PartgReportVersionTable.id] }
            PartgReportDeclarationTable.deleteWhere {
                (PartgReportDeclarationTable.versionId inList versionIds) or (PartgReportDeclarationTable.declaredBy inList memberIds)
            }
            PartgReportVersionTable.deleteWhere { PartgReportVersionTable.id inList versionIds }
            PartgReportVersionCounterTable.deleteWhere { PartgReportVersionCounterTable.updatedBy inList memberIds }
            PartgReportDeadlineTable.deleteWhere { PartgReportDeadlineTable.confirmedBy inList memberIds }
            // V1.9.289: bookings, mapping, modes, grants and the accounts of the test first (they point to members, chapters and accounts)
            val entryIds =
                JournalEntryTable
                    .selectAll()
                    .where {
                        JournalEntryTable.createdBy inList memberIds
                    }.map { it[JournalEntryTable.id] }
            PostingTable.deleteWhere { PostingTable.journalEntryId inList entryIds }
            JournalEntryTable.deleteWhere { JournalEntryTable.id inList entryIds }
            PartgAccountMappingTable.deleteWhere { (ledgerAccountId inList accountIds) or (updatedBy inList memberIds) }
            LedgerAccountTable.deleteWhere { LedgerAccountTable.id inList accountIds }
            PartgReportUnitModeTable.deleteWhere { (regionalChapterId inList chapterIds) or (updatedBy inList memberIds) }
            RegionalChapterTreasurerTable.deleteWhere {
                (memberId inList memberIds) or (regionalChapterId inList chapterIds) or (grantedByMemberId inList memberIds)
            }
            // V1.9.269: the R3 rows first (children before headers)
            val annexIds =
                PartgReportAnnexTable
                    .selectAll()
                    .where {
                        PartgReportAnnexTable.updatedBy inList memberIds
                    }.map { it[PartgReportAnnexTable.id] }
            // V1.9.290 (R5): the donor combinations (rows, heads), the transfer pairs and the headers of the test come first
            val testDonorIds =
                PartgReportLargeDonorTable
                    .selectAll()
                    .where {
                        (PartgReportLargeDonorTable.annexId inList annexIds) or (PartgReportLargeDonorTable.createdBy inList memberIds)
                    }.map { it[PartgReportLargeDonorTable.id] }
            val comboIds =
                PartgDonorCombinationTable
                    .selectAll()
                    .where { PartgDonorCombinationTable.createdBy inList memberIds }
                    .map { it[PartgDonorCombinationTable.id] }
            PartgDonorCombinationRowTable.deleteWhere {
                (PartgDonorCombinationRowTable.largeDonorId inList testDonorIds) or
                    (PartgDonorCombinationRowTable.combinationId inList comboIds)
            }
            PartgDonorCombinationTable.deleteWhere { PartgDonorCombinationTable.id inList comboIds }
            PartgIntraTransferTable.deleteWhere {
                (PartgIntraTransferTable.createdBy inList memberIds) or (PartgIntraTransferTable.updatedBy inList memberIds) or
                    (PartgIntraTransferTable.payerChapterId inList chapterIds) or (PartgIntraTransferTable.payeeChapterId inList chapterIds)
            }
            PartgConsolidationSheetTable.deleteWhere { PartgConsolidationSheetTable.updatedBy inList memberIds }
            // V1.9.283: sponsoring entries, then sponsors, then youth organisations
            val sponsorIds =
                PartgReportSponsorTable
                    .selectAll()
                    .where { PartgReportSponsorTable.annexId inList annexIds }
                    .map { it[PartgReportSponsorTable.id] }
            PartgReportSponsoringEntryTable.deleteWhere {
                (PartgReportSponsoringEntryTable.sponsorId inList sponsorIds) or
                    (PartgReportSponsoringEntryTable.createdBy inList memberIds)
            }
            PartgReportSponsorTable.deleteWhere {
                (PartgReportSponsorTable.id inList sponsorIds) or (PartgReportSponsorTable.createdBy inList memberIds)
            }
            PartgReportEnterpriseTable.deleteWhere {
                (PartgReportEnterpriseTable.annexId inList annexIds) or (PartgReportEnterpriseTable.createdBy inList memberIds)
            }
            PartgReportYouthOrgTable.deleteWhere {
                (PartgReportYouthOrgTable.annexId inList annexIds) or (PartgReportYouthOrgTable.createdBy inList memberIds)
            }
            PartgReportLargeDonorTable.deleteWhere {
                (PartgReportLargeDonorTable.annexId inList annexIds) or (PartgReportLargeDonorTable.createdBy inList memberIds)
            }
            PartgReportAnnexTable.deleteWhere { PartgReportAnnexTable.id inList annexIds }
            val notesIds =
                PartgReportNotesSheetTable
                    .selectAll()
                    .where { PartgReportNotesSheetTable.updatedBy inList memberIds }
                    .map { it[PartgReportNotesSheetTable.id] }
            PartgReportNoteTable.deleteWhere { PartgReportNoteTable.notesSheetId inList notesIds }
            PartgReportHoldingTable.deleteWhere { PartgReportHoldingTable.notesSheetId inList notesIds }
            PartgReportNotesSheetTable.deleteWhere { PartgReportNotesSheetTable.id inList notesIds }
            val sheetIds =
                PartgReportSheetTable
                    .selectAll()
                    .where {
                        PartgReportSheetTable.updatedBy inList memberIds
                    }.map { it[PartgReportSheetTable.id] }
            PartgReportValueTable.deleteWhere { PartgReportValueTable.sheetId inList sheetIds }
            PartgReportSheetTable.deleteWhere { PartgReportSheetTable.id inList sheetIds }
            PartgReportPositionTable.update({ PartgReportPositionTable.orgHintUpdatedBy.isNotNull() }) {
                it[orgHint] = null
                it[orgHintUpdatedAt] = null
                it[orgHintUpdatedBy] = null
            }
            // the erasure scenarios (executeErasure end to end): the DSGVO audit rows and requests of the test members
            DsgvoAuditLogTable.deleteWhere { (subjectMemberId inList memberIds) or (actorMemberId inList memberIds) }
            ErasureRequestTable.deleteWhere { (subjectMemberId inList memberIds) or (requestedBy inList memberIds) }
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
            MemberTable.update({ MemberTable.regionalChapterId inList chapterIds }) { it[regionalChapterId] = null }
            RegionalChapterTable.deleteWhere { RegionalChapterTable.id inList chapterIds }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
        setParty(false)
    }
}

internal fun PartgFixtures.currentOf(
    memberId: Uuid,
    role: AccountRole = AccountRole.BOARD,
) = network.lapis.cloud.server.security
    .CurrentMember(memberId = memberId, role = role, status = MemberStatus.ACTIVE)
