package network.lapis.cloud.server.rpc

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.server.carpool.CarpoolDeps
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CarpoolParticipationTable
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CarpoolParticipationInitiator
import network.lapis.cloud.shared.domain.CarpoolParticipationStatus
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Harness of the V1.9.125 carpool participation tests. Same house style as `PollApp`: ONE generic throwaway route runs a lambda against a fresh
 * [CarpoolService] built from that request's call (`X-Member-Id` resolves the caller) and stores the [Result], so tests assert on real exception
 * TYPES. The [CarpoolDeps] are shared across requests of one app (the rate-limit windows and the invitee tokens must outlive a request).
 */
internal class CarpoolApp(
    private val client: HttpClient,
    private val ops: ConcurrentHashMap<String, suspend CarpoolService.() -> Any?>,
    private val results: ConcurrentHashMap<String, Result<Any?>>,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> attempt(
        member: Uuid,
        op: suspend CarpoolService.() -> T,
    ): Result<T> {
        val id = Uuid.random().toString()
        ops[id] = op
        client.post("/carpool-op/$id") { header("X-Member-Id", member.toString()) }
        ops.remove(id)
        return results.remove(id)!! as Result<T>
    }

    suspend fun <T> call(
        member: Uuid,
        op: suspend CarpoolService.() -> T,
    ): T = attempt(member = member, op = op).getOrThrow()
}

internal fun carpoolTestApplication(
    deps: CarpoolDeps = CarpoolDeps(),
    block: suspend CarpoolApp.() -> Unit,
) {
    val ops = ConcurrentHashMap<String, suspend CarpoolService.() -> Any?>()
    val results = ConcurrentHashMap<String, Result<Any?>>()
    testApplication {
        application {
            routing {
                post("/carpool-op/{id}") {
                    val id = call.parameters["id"]!!
                    val op = ops.getValue(id)
                    results[id] =
                        try {
                            Result.success(CarpoolService(call = call, deps = deps).op())
                        } catch (e: Throwable) {
                            Result.failure(e)
                        }
                    call.respondText("done")
                }
            }
        }
        CarpoolApp(client = client, ops = ops, results = results).block()
    }
}

/** Fixtures + cleanup of everything a carpool participation test creates (members, postings, participations, audit rows). */
internal class CarpoolTestData {
    val memberIds = mutableListOf<Uuid>()
    val postingIds = mutableListOf<Uuid>()

    fun member(
        label: String,
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole = AccountRole.MEMBER,
        displayName: String = "Carpool-Test $label",
        emailVerified: Boolean = true,
        anonymized: Boolean = false,
        email: String? = null,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[MemberTable.displayName] = displayName
                it[MemberTable.email] = email ?: "carpool-test-$id@example.org"
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
                if (emailVerified) it[emailVerifiedAt] = LocalDateTime(2026, 1, 1, 0, 0)
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

    fun actor(
        memberId: Uuid,
        status: MemberStatus = MemberStatus.ACTIVE,
    ) = CurrentMember(memberId = memberId, role = AccountRole.MEMBER, status = status)

    fun today(): LocalDate = OrganizationTimeZone.today()

    fun posting(
        author: Uuid,
        seats: Int? = 3,
        type: CarpoolPostingType = CarpoolPostingType.OFFER,
        daysAhead: Int = 10,
        fromPlace: String = "Braunschweig",
        toPlace: String = "Hannover",
        notes: String? = null,
        meetingNote: String? = null,
    ): Uuid {
        val id = Uuid.random()
        val date = today().plus(DatePeriod(days = daysAhead))
        transaction {
            CarpoolPostingTable.insert {
                it[CarpoolPostingTable.id] = id
                it[authorMemberId] = author
                it[CarpoolPostingTable.type] = type
                it[CarpoolPostingTable.fromPlace] = fromPlace
                it[CarpoolPostingTable.toPlace] = toPlace
                it[departureDate] = date
                it[departureTime] = null
                it[seatsOffered] = if (type == CarpoolPostingType.OFFER) seats else null
                it[CarpoolPostingTable.notes] = notes
                it[CarpoolPostingTable.meetingNote] = meetingNote
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
        }
        postingIds += id
        return id
    }

    /** Inserts a participation row directly (state fixtures that the service would only reach in several steps). */
    fun participation(
        postingId: Uuid,
        personId: Uuid,
        status: CarpoolParticipationStatus,
        initiator: CarpoolParticipationInitiator = CarpoolParticipationInitiator.PASSENGER,
        decidedAt: LocalDateTime? = null,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            CarpoolParticipationTable.insert {
                it[CarpoolParticipationTable.id] = id
                it[CarpoolParticipationTable.postingId] = postingId
                it[CarpoolParticipationTable.personId] = personId
                it[CarpoolParticipationTable.initiator] = initiator
                it[CarpoolParticipationTable.status] = status
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[updatedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[CarpoolParticipationTable.decidedAt] =
                    decidedAt ?: if (status == CarpoolParticipationStatus.OPEN) null else LocalDateTime(2026, 1, 1, 0, 0)
            }
        }
        return id
    }

    fun setStatus(
        memberId: Uuid,
        status: MemberStatus,
    ) {
        transaction { MemberTable.update({ MemberTable.id eq memberId }) { it[MemberTable.status] = status } }
    }

    fun participationRows(postingId: Uuid) =
        transaction {
            CarpoolParticipationTable.selectAll().where { CarpoolParticipationTable.postingId eq postingId }.toList()
        }

    fun cleanUp() {
        transaction {
            CarpoolParticipationTable.deleteWhere { (postingId inList postingIds) or (personId inList memberIds) }
            val authored =
                CarpoolPostingTable
                    .selectAll()
                    .where { CarpoolPostingTable.authorMemberId inList memberIds }
                    .map { it[CarpoolPostingTable.id] }
            CarpoolParticipationTable.deleteWhere { postingId inList authored }
            CarpoolPostingTable.deleteWhere { (id inList postingIds) or (authorMemberId inList memberIds) }
            // The audit chain is append-only and other specs verify ranges of it: NEVER delete a row (a missing predecessor breaks their
            // verification), only detach the actor so the member row can go (same as OpenItemRpcWireTest).
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
            AccountTable.deleteWhere { memberId inList memberIds }
            MemberTable.deleteWhere { id inList memberIds }
        }
    }
}
