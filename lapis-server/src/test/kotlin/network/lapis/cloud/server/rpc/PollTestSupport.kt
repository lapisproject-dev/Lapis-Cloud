package network.lapis.cloud.server.rpc

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
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PollOptionTable
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollResponseRatingTable
import network.lapis.cloud.server.db.generated.PollResponseTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PollCreateInput
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollKind
import network.lapis.cloud.shared.domain.PollResponseInput
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * Shared harness of the V1.9.30 poll tests. Same "throwaway route calls the service class directly,
 * `X-Member-Id` resolves the caller" house style as the other service tests, but ONE generic route:
 * the test registers a lambda `suspend PollService.() -> Any?` under a request id, the route runs it
 * against a fresh [PollService] built from that request's call and stores the [Result], so the tests
 * assert on the real exception TYPES (no HTTP status mapping in between).
 */
internal class PollApp(
    private val client: HttpClient,
    private val ops: ConcurrentHashMap<String, suspend PollService.() -> Any?>,
    private val results: ConcurrentHashMap<String, Result<Any?>>,
) {
    /** Runs [op] as [member] and returns its [Result] (exceptions captured, never thrown). */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> attempt(
        member: Uuid,
        op: suspend PollService.() -> T,
    ): Result<T> {
        val id = Uuid.random().toString()
        ops[id] = op
        client.post("/poll-op/$id") { header("X-Member-Id", member.toString()) }
        ops.remove(id)
        return results.remove(id)!! as Result<T>
    }

    suspend fun <T> call(
        member: Uuid,
        op: suspend PollService.() -> T,
    ): T = attempt(member = member, op = op).getOrThrow()
}

internal fun pollTestApplication(
    ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider(),
    block: suspend PollApp.() -> Unit,
) {
    val ops = ConcurrentHashMap<String, suspend PollService.() -> Any?>()
    val results = ConcurrentHashMap<String, Result<Any?>>()
    testApplication {
        application {
            routing {
                post("/poll-op/{id}") {
                    val id = call.parameters["id"]!!
                    val op = ops.getValue(id)
                    results[id] =
                        try {
                            Result.success(PollService(call = call, ltrBalanceProvider = ltrBalanceProvider).op())
                        } catch (e: Throwable) {
                            Result.failure(e)
                        }
                    call.respondText("done")
                }
            }
        }
        PollApp(client = client, ops = ops, results = results).block()
    }
}

internal fun pollInput(
    question: String = "Soll das Sommerfest im Juli stattfinden?",
    options: List<String> = listOf("Ja", "Nein"),
    closesAt: LocalDateTime? = null,
    description: String? = null,
    kind: PollKind = PollKind.SINGLE_CHOICE,
    optionExplanations: List<String?> = emptyList(),
) = PollCreateInput(
    question = question,
    description = description,
    options = options,
    closesAt = closesAt,
    kind = kind,
    optionExplanations = optionExplanations,
)

/** Test fixtures + cleanup of everything a poll test spec creates (members, committees, polls, ledger rows, audit rows). */
internal class PollTestData {
    val memberIds = mutableListOf<Uuid>()
    val committeeIds = mutableListOf<Uuid>()

    fun member(
        label: String,
        role: AccountRole = AccountRole.MEMBER,
        status: MemberStatus = MemberStatus.ACTIVE,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "Poll-Test $label"
                it[email] = "poll-test-$id@example.org"
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

    fun committee(active: Boolean = true): Uuid {
        val id = Uuid.random()
        transaction {
            CommitteeTable.insert {
                it[CommitteeTable.id] = id
                it[name] = "Poll-Test Gremium ${id.toString().take(8)}"
                it[type] = CommitteeType.EXECUTIVE_BOARD
                it[description] = "Testgremium"
                it[CommitteeTable.active] = active
                it[quorumPercent] = 50
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
        }
        committeeIds += id
        return id
    }

    fun seat(
        committeeId: Uuid,
        memberId: Uuid,
        role: CommitteeRole,
        since: LocalDate = LocalDate(2020, 1, 1),
        until: LocalDate? = null,
    ) {
        transaction {
            CommitteeMembershipTable.insert {
                it[id] = Uuid.random()
                it[CommitteeMembershipTable.committeeId] = committeeId
                it[CommitteeMembershipTable.memberId] = memberId
                it[CommitteeMembershipTable.role] = role
                it[CommitteeMembershipTable.since] = since
                it[CommitteeMembershipTable.until] = until
            }
        }
    }

    fun mint(
        memberId: Uuid,
        amount: String,
        at: LocalDateTime = LocalDateTime(2026, 1, 1, 0, 0),
    ) {
        transaction {
            LtrLedgerEntryTable.insert {
                it[id] = Uuid.random()
                it[LtrLedgerEntryTable.memberId] = memberId
                it[entryType] = LtrLedgerEntryType.MINT
                it[amountLtr] = BigDecimal(amount)
                it[referenceType] = null
                it[referenceId] = null
                it[note] = "Poll test seed"
                it[createdBy] = null
                it[createdAt] = at
            }
        }
    }

    /** A fresh chair-of-a-committee member (the usual poll creator in the tests). */
    fun chair(label: String = "Vorsitz"): Uuid {
        val member = member(label = label)
        seat(committeeId = committee(), memberId = member, role = CommitteeRole.CHAIR)
        return member
    }

    fun ledgerCount(): Long = transaction { LtrLedgerEntryTable.selectAll().count() }

    /** Removes EVERY poll (and its options/participations/responses) -- the cap tests need an empty slate. */
    fun deleteAllPolls() {
        transaction {
            PollResponseRatingTable.deleteAll()
            PollResponseTable.deleteAll()
            PollParticipationTable.deleteAll()
            PollOptionTable.deleteAll()
            PollTable.deleteAll()
        }
    }

    fun cleanUp() {
        transaction {
            val pollIds =
                PollTable
                    .selectAll()
                    .where { (PollTable.createdBy inList memberIds) }
                    .map { it[PollTable.id] }
            val responseIds =
                PollResponseTable
                    .selectAll()
                    .where {
                        PollResponseTable.pollId inList pollIds
                    }.map { it[PollResponseTable.id] }
            PollResponseRatingTable.deleteWhere { PollResponseRatingTable.responseId inList responseIds }
            PollResponseTable.deleteWhere { PollResponseTable.pollId inList pollIds }
            PollParticipationTable.deleteWhere { PollParticipationTable.pollId inList pollIds }
            PollParticipationTable.deleteWhere { PollParticipationTable.memberId inList memberIds }
            PollOptionTable.deleteWhere { PollOptionTable.pollId inList pollIds }
            PollTable.deleteWhere { PollTable.id inList pollIds }
            // Test-only cleanup -- audit_log_entry stays append-only in production.
            AuditLogEntryTable.deleteWhere { AuditLogEntryTable.actorMemberId inList memberIds }
            LtrLedgerEntryTable.deleteWhere { LtrLedgerEntryTable.memberId inList memberIds }
            CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.memberId inList memberIds }
            CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
            CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}

/** Opens a poll as [creator] and has every [(member, optionIndex)] respond; returns the OPEN poll. */
internal suspend fun PollApp.createAndAnswer(
    creator: Uuid,
    answers: List<Pair<Uuid, Int>>,
    input: PollCreateInput = pollInput(),
): PollDto {
    val poll = call(member = creator) { createPoll(input) }
    answers.forEach { (member, optionIndex) ->
        call(member = member) { castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[optionIndex].id)) }
    }
    return poll
}
