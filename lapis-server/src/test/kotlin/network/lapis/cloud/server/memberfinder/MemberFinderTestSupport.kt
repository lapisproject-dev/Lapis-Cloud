package network.lapis.cloud.server.memberfinder

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.agreementtexts.AgreementTextResolver
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.DirectMessageTable
import network.lapis.cloud.server.db.generated.MemberFinderBlockTable
import network.lapis.cloud.server.db.generated.MemberFinderConsentEventTable
import network.lapis.cloud.server.db.generated.MemberFinderRegionBandTable
import network.lapis.cloud.server.db.generated.MemberFinderRequestTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.encounter.NO_PULPIT_RECORDING
import network.lapis.cloud.server.rpc.MemberFinderService
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.OrganizationSettingsService
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AgreementTextKind
import network.lapis.cloud.shared.domain.MEMBER_FINDER_NOTICE_VERSION
import network.lapis.cloud.shared.domain.MemberFinderInterest
import network.lapis.cloud.shared.domain.MemberFinderPurpose
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Harness of the V1.9.144 member finder tests. Same house style as `CarpoolApp`: ONE generic throwaway route runs a lambda against a fresh
 * [MemberFinderService] built from that request's call (`X-Member-Id` resolves the caller) and stores the [Result], so tests assert on real exception
 * TYPES. The rate limiter and the random source are shared by every request of one app.
 */
internal class FinderApp(
    private val client: HttpClient,
    private val ops: ConcurrentHashMap<String, suspend MemberFinderService.() -> Any?>,
    private val results: ConcurrentHashMap<String, Result<Any?>>,
    private val settingsOps: ConcurrentHashMap<String, suspend OrganizationSettingsService.() -> Any?> = ConcurrentHashMap(),
    private val settingsResults: ConcurrentHashMap<String, Result<Any?>> = ConcurrentHashMap(),
) {
    /** A call of the ORGANIZATION settings service (the instance switch), through the same throwaway-route idiom. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> attemptSettings(
        member: Uuid,
        op: suspend OrganizationSettingsService.() -> T,
    ): Result<T> {
        val id = Uuid.random().toString()
        settingsOps[id] = op
        client.post("/finder-settings-op/$id") { header("X-Member-Id", member.toString()) }
        settingsOps.remove(id)
        return settingsResults.remove(id)!! as Result<T>
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> attempt(
        member: Uuid,
        op: suspend MemberFinderService.() -> T,
    ): Result<T> {
        val id = Uuid.random().toString()
        ops[id] = op
        client.post("/finder-op/$id") { header("X-Member-Id", member.toString()) }
        ops.remove(id)
        return results.remove(id)!! as Result<T>
    }

    suspend fun <T> call(
        member: Uuid,
        op: suspend MemberFinderService.() -> T,
    ): T = attempt(member = member, op = op).getOrThrow()
}

internal fun finderTestApplication(
    random: MemberFinderRandom = MemberFinderRandom { 0 },
    rateLimiter: MemberFinderRateLimiter = MemberFinderRateLimiter(),
    block: suspend FinderApp.() -> Unit,
) {
    val ops = ConcurrentHashMap<String, suspend MemberFinderService.() -> Any?>()
    val results = ConcurrentHashMap<String, Result<Any?>>()
    val settingsOps = ConcurrentHashMap<String, suspend OrganizationSettingsService.() -> Any?>()
    val settingsResults = ConcurrentHashMap<String, Result<Any?>>()
    testApplication {
        application {
            routing {
                post("/finder-settings-op/{id}") {
                    val id = call.parameters["id"]!!
                    val op = settingsOps.getValue(id)
                    settingsResults[id] =
                        try {
                            Result.success(OrganizationSettingsService(call = call, pulpitRecording = NO_PULPIT_RECORDING).op())
                        } catch (e: Throwable) {
                            Result.failure(e)
                        }
                    call.respondText("done")
                }
                post("/finder-op/{id}") {
                    val id = call.parameters["id"]!!
                    val op = ops.getValue(id)
                    results[id] =
                        try {
                            Result.success(MemberFinderService(call = call, rateLimiter = rateLimiter, random = random).op())
                        } catch (e: Throwable) {
                            Result.failure(e)
                        }
                    call.respondText("done")
                }
            }
        }
        FinderApp(client = client, ops = ops, results = results, settingsOps = settingsOps, settingsResults = settingsResults).block()
    }
}

/** Fixtures + cleanup of everything a finder test creates. The finder tables are emptied completely: only finder specs ever fill them. */
internal class FinderTestData {
    val memberIds = mutableListOf<Uuid>()

    fun member(
        label: String,
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole = AccountRole.MEMBER,
        postalCode: String? = "38100",
        countryCode: String? = "DE",
        legacyCountry: String? = null,
        dateOfBirth: LocalDate? = null,
        anonymized: Boolean = false,
        city: String? = "Braunschweig",
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "Finder-Test $label"
                it[email] = "finder-test-$id@example.org"
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
                it[MemberTable.postalCode] = postalCode
                it[MemberTable.countryCode] = countryCode
                it[country] = legacyCountry
                it[MemberTable.city] = city
                it[MemberTable.dateOfBirth] = dateOfBirth
                if (anonymized) it[anonymizedAt] = LocalDateTime(2026, 1, 2, 0, 0)
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

    /** [count] ACTIVE members in the same region (postal code 38xxx) that all consent to everything. */
    fun region(
        count: Int,
        label: String = "r",
        postalPrefix: String = "38",
        interest: MemberFinderInterest = MemberFinderInterest.BOTH,
    ): List<Uuid> =
        (1..count).map { i ->
            member(label = "$label$i", postalCode = postalPrefix + (100 + i).toString().padStart(3, '0')).also {
                consent(memberId = it, interest = interest)
            }
        }

    /** An effective consent of the CURRENT text, written straight through the store (the way the service would). */
    fun consent(
        memberId: Uuid,
        interest: MemberFinderInterest = MemberFinderInterest.BOTH,
        at: LocalDateTime = ServerClock.now(),
    ) {
        transaction {
            val text = AgreementTextResolver.current(kind = AgreementTextKind.FINDER)
            MemberFinderConsentStore.grant(
                memberId = memberId,
                version = text.versionLabel,
                sha256 = text.sha256,
                interest = interest,
                now = at,
            )
        }
    }

    fun enableFinder(enabled: Boolean = true) {
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[memberFinderEnabled] = enabled
                it[memberFinderNoticeVersion] = if (enabled) MEMBER_FINDER_NOTICE_VERSION else null
            }
        }
    }

    /** The daily band evaluation, at the pinned clock. */
    fun computeBands(now: LocalDateTime = ServerClock.now()) {
        transaction {
            MemberFinderBands.recompute(
                now = now,
                today = OrganizationTimeZone.dateOf(now),
                current = AgreementTextResolver.current(kind = AgreementTextKind.FINDER),
            )
        }
    }

    fun requests(): List<MemberFinderRequestRow> =
        transaction {
            MemberFinderRequestTable.selectAll().map {
                MemberFinderRequestRow(
                    id = it[MemberFinderRequestTable.id],
                    fromMemberId = it[MemberFinderRequestTable.fromMemberId],
                    toMemberId = it[MemberFinderRequestTable.toMemberId],
                    purpose = it[MemberFinderRequestTable.purpose],
                    note = it[MemberFinderRequestTable.note],
                    status = it[MemberFinderRequestTable.status],
                    createdAt = it[MemberFinderRequestTable.createdAt],
                    expiresAt = it[MemberFinderRequestTable.expiresAt],
                    decidedAt = it[MemberFinderRequestTable.decidedAt],
                    senderWithdrawnAt = it[MemberFinderRequestTable.senderWithdrawnAt],
                )
            }
        }

    fun nameOf(memberId: Uuid): String =
        transaction { MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.displayName] }

    fun blockCount(): Int = transaction { MemberFinderBlockTable.selectAll().count().toInt() }

    fun eventCount(memberId: Uuid): Int =
        transaction {
            MemberFinderConsentEventTable
                .selectAll()
                .where { MemberFinderConsentEventTable.memberId eq memberId }
                .count()
                .toInt()
        }

    /** Number of OPEN consent rows (superseded_at NULL) of the member; a correct store never leaves more than one. */
    fun openConsentRows(memberId: Uuid): Int =
        transaction {
            MemberFinderConsentEventTable
                .selectAll()
                .where { (MemberFinderConsentEventTable.memberId eq memberId) and MemberFinderConsentEventTable.supersededAt.isNull() }
                .count()
                .toInt()
        }

    fun bandRows(): Int = transaction { MemberFinderRegionBandTable.selectAll().count().toInt() }

    fun setStatus(
        memberId: Uuid,
        status: MemberStatus,
    ) {
        transaction { MemberTable.update({ MemberTable.id eq memberId }) { it[MemberTable.status] = status } }
    }

    /** Inserts a request row directly (state fixtures the service would reach in several steps). */
    fun request(
        from: Uuid,
        to: Uuid?,
        status: MemberFinderRequestStatus = MemberFinderRequestStatus.OPEN,
        purpose: MemberFinderPurpose = MemberFinderPurpose.MEETUP,
        note: String? = null,
        createdAt: LocalDateTime = ServerClock.now(),
        expiresAt: LocalDateTime = createdAt.plusDays(30),
        decidedAt: LocalDateTime? = if (status == MemberFinderRequestStatus.OPEN) null else createdAt,
        senderWithdrawnAt: LocalDateTime? = if (status == MemberFinderRequestStatus.WITHDRAWN) decidedAt else null,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberFinderRequestTable.insert {
                it[MemberFinderRequestTable.id] = id
                it[fromMemberId] = from
                it[toMemberId] = to
                it[MemberFinderRequestTable.purpose] = purpose
                it[MemberFinderRequestTable.note] = note
                it[MemberFinderRequestTable.status] = status
                it[MemberFinderRequestTable.createdAt] = createdAt
                it[MemberFinderRequestTable.expiresAt] = expiresAt
                it[MemberFinderRequestTable.decidedAt] = decidedAt
                it[MemberFinderRequestTable.senderWithdrawnAt] = senderWithdrawnAt
            }
        }
        return id
    }

    fun emptyFinderTables() {
        transaction {
            MemberFinderRequestTable.deleteAll()
            MemberFinderBlockTable.deleteAll()
            MemberFinderConsentEventTable.deleteAll()
            MemberFinderRegionBandTable.deleteAll()
        }
    }

    /** Between two tests: no finder row, switch off, no member of this instance left. */
    fun reset() {
        emptyFinderTables()
        enableFinder(false)
        transaction {
            // rows the tests of the status paths and of the connection messages leave behind (they hang on the member by foreign key)
            MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList memberIds }
            DirectMessageTable.deleteWhere {
                (DirectMessageTable.senderId inList memberIds) or
                    (DirectMessageTable.recipientId inList memberIds)
            }
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
            AccountTable.deleteWhere { memberId inList memberIds }
            MemberTable.deleteWhere { id inList memberIds }
        }
        memberIds.clear()
    }
}
