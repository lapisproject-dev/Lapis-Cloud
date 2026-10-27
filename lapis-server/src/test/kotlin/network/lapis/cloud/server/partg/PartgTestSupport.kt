package network.lapis.cloud.server.partg

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.PartgReportPositionTable
import network.lapis.cloud.server.db.generated.PartgReportSheetTable
import network.lapis.cloud.server.db.generated.PartgReportValueTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.PartyReportService
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
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
    private val client: HttpClient,
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
    block: suspend PartgApp.() -> Unit,
) {
    val ops = ConcurrentHashMap<String, suspend (ApplicationCall) -> Any?>()
    val results = ConcurrentHashMap<String, Result<Any?>>()
    testApplication {
        application {
            routing {
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

    fun service(call: ApplicationCall) = PartyReportService(call = call, writeRateLimiter = limiter)

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
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
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
