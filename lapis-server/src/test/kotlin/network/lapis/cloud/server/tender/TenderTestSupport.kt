package network.lapis.cloud.server.tender

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
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TenderAcceptanceTable
import network.lapis.cloud.server.db.generated.TenderAwardTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.db.generated.VoteEligibleVoterTable
import network.lapis.cloud.server.db.generated.VoteOptionTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.server.rpc.TenderService
import network.lapis.cloud.server.time.MutableTestClock
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderBidInput
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderCreateInput
import network.lapis.cloud.shared.domain.TenderCurrency
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** The pinned instant of the tender tests: 11:00 wall-clock in Europe/Berlin (winter time, UTC+1). */
internal const val TENDER_NOW = "2026-11-02T10:00:00Z"

/** The wall-clock deadline used by default: one day after [TENDER_NOW] in the organisation zone. */
internal val TENDER_DEADLINE: LocalDateTime = LocalDateTime(2026, 11, 3, 11, 0)

internal fun tenderClock(): MutableTestClock = MutableTestClock(TENDER_NOW)

/**
 * Harness of the V1.9.201 tender tests. Same house style as `FinderApp`: ONE generic throwaway route runs a lambda against the call of that
 * request (`X-Member-Id` resolves the caller) and stores the [Result], so tests assert on real exception TYPES. The lambda builds whichever service
 * it needs from the call (`TenderService(call)`, `GovernanceService(call = call)`, `OrganizationSettingsService(call = call, pulpitRecording = ...)`).
 */
internal class TenderApp(
    private val client: HttpClient,
    private val ops: ConcurrentHashMap<String, suspend (ApplicationCall) -> Any?>,
    private val results: ConcurrentHashMap<String, Result<Any?>>,
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> attempt(
        member: Uuid,
        op: suspend (ApplicationCall) -> T,
    ): Result<T> {
        val id = Uuid.random().toString()
        ops[id] = op
        client.post("/tender-op/$id") { header("X-Member-Id", member.toString()) }
        ops.remove(id)
        return results.remove(id)!! as Result<T>
    }

    suspend fun <T> call(
        member: Uuid,
        op: suspend (ApplicationCall) -> T,
    ): T = attempt(member = member, op = op).getOrThrow()
}

internal fun tenderTestApplication(block: suspend TenderApp.() -> Unit) {
    val ops = ConcurrentHashMap<String, suspend (ApplicationCall) -> Any?>()
    val results = ConcurrentHashMap<String, Result<Any?>>()
    testApplication {
        application {
            routing {
                post("/tender-op/{id}") {
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
        TenderApp(client = client, ops = ops, results = results).block()
    }
}

/** Fixtures + cleanup of everything a tender test creates (own rows only, never the dev seed's). */
internal class TenderFixtures {
    val memberIds = mutableListOf<Uuid>()
    val committeeIds = mutableListOf<Uuid>()
    val meetingIds = mutableListOf<Uuid>()
    val motionIds = mutableListOf<Uuid>()
    val resolutionIds = mutableListOf<Uuid>()

    fun connect() {
        DatabaseConfig.connect()
        // the pinned zone of the tests
        TimeTestSupport.setOrganizationZone("Europe/Berlin")
        OrganizationTimeZone.invalidate()
    }

    fun member(
        label: String,
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole = AccountRole.MEMBER,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "Tender-Test $label"
                it[email] = "tender-test-$id@example.org"
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

    fun committee(quorumPercent: Int = 50): Uuid {
        val id = Uuid.random()
        transaction {
            CommitteeTable.insert {
                it[CommitteeTable.id] = id
                it[name] = "Tender-Testgremium ${id.toString().take(8)}"
                it[type] = CommitteeType.WORKING_GROUP
                it[description] = "Testgremium"
                it[active] = true
                it[CommitteeTable.quorumPercent] = quorumPercent
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
        }
        committeeIds += id
        return id
    }

    fun seat(
        committee: Uuid,
        member: Uuid,
    ) {
        transaction {
            CommitteeMembershipTable.insert {
                it[CommitteeMembershipTable.id] = Uuid.random()
                it[committeeId] = committee
                it[memberId] = member
                it[role] = CommitteeRole.MEMBER
                it[since] = LocalDate(2026, 1, 1)
                it[until] = null
                it[autoEndReason] = null
            }
        }
    }

    fun meeting(committee: Uuid): Uuid {
        val id = Uuid.random()
        transaction {
            MeetingTable.insert {
                it[MeetingTable.id] = id
                it[committeeId] = committee
                it[title] = "Tender-Testsitzung"
                it[scheduledAt] = LocalDateTime(2026, 10, 20, 18, 0)
                it[location] = "Vereinsheim"
                it[format] = MeetingFormat.IN_PERSON
                it[status] = MeetingStatus.PLANNED
                it[calledBy] = null
                it[calledAt] = null
                it[chairMemberId] = null
                it[minuteTakerMemberId] = null
                it[protocolDocumentId] = null
                it[createdAt] = LocalDateTime(2026, 10, 1, 0, 0)
            }
        }
        meetingIds += id
        return id
    }

    fun attend(
        meeting: Uuid,
        member: Uuid,
    ) {
        transaction {
            AttendanceTable.insert {
                it[AttendanceTable.id] = Uuid.random()
                it[meetingId] = meeting
                it[memberId] = member
                it[status] = AttendanceStatus.PRESENT
                it[representedByMemberId] = null
                it[note] = null
                it[recordedAt] = LocalDateTime(2026, 10, 20, 18, 0)
            }
        }
    }

    /** A motion addressed to [committee]. [status] RESOLVED also writes an [resolutionStatus] resolution taken in a meeting of [resolutionCommittee]. */
    fun motion(
        committee: Uuid,
        submitter: Uuid,
        status: MotionStatus = MotionStatus.SCHEDULED,
        meeting: Uuid? = null,
        resolutionStatus: ResolutionStatus = ResolutionStatus.ADOPTED,
        resolutionCommittee: Uuid = committee,
        amends: Uuid? = null,
    ): Uuid {
        val id = Uuid.random()
        val meetingOfMotion = meeting ?: if (status == MotionStatus.SCHEDULED) meeting(committee) else null
        val resolutionId =
            if (status ==
                MotionStatus.RESOLVED
            ) {
                resolution(meeting = meeting(resolutionCommittee), recordedBy = submitter, resolutionStatus = resolutionStatus)
            } else {
                null
            }
        transaction {
            MotionTable.insert {
                it[MotionTable.id] = id
                it[targetCommitteeId] = committee
                it[title] = "Tender-Testantrag"
                it[rationale] = "Begruendung"
                it[text] = "Antragstext"
                it[submitterMemberId] = submitter
                it[MotionTable.status] = status
                it[submittedAt] = LocalDateTime(2026, 10, 1, 0, 0)
                it[reviewedBy] = null
                it[reviewedAt] = null
                it[reviewNote] = null
                it[MotionTable.meetingId] = meetingOfMotion
                it[agendaItemId] = null
                it[MotionTable.resolutionId] = resolutionId
                it[withdrawnAt] = null
                it[amendsMotionId] = amends
                it[currentText] = null
            }
        }
        motionIds += id
        return id
    }

    private fun resolution(
        meeting: Uuid,
        recordedBy: Uuid,
        resolutionStatus: ResolutionStatus,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            ResolutionTable.insert {
                it[ResolutionTable.id] = id
                it[number] = "TENDER-TEST-${id.toString().take(8)}"
                it[title] = "Tender-Testbeschluss"
                it[text] = "Beschlusstext"
                it[votesYes] = 5
                it[votesNo] = 1
                it[votesAbstain] = 0
                it[quorumMet] = true
                it[status] = resolutionStatus
                it[decidedAt] = LocalDateTime(2026, 10, 20, 19, 0)
                it[ResolutionTable.recordedBy] = recordedBy
                it[resolutionMode] = ResolutionMode.COMMITTEE_QUORUM
                it[voteId] = null
                it[electionId] = null
                it[systemicConsensusId] = null
                it[ResolutionTable.meetingId] = meeting
                it[agendaItemId] = null
            }
        }
        resolutionIds += id
        return id
    }

    /** The default fixture: a committee, a chair-like submitter and an ADOPTED binding motion of that committee. */
    fun approvedMotion(
        committee: Uuid = committee(),
        submitter: Uuid = member(label = "submitter"),
    ): Uuid = motion(committee = committee, submitter = submitter, status = MotionStatus.RESOLVED)

    /** A bid row written straight into the table (what existed before a rule was tightened); bypasses every service rule on purpose. */
    fun legacyBid(
        tenderId: String,
        bidder: Uuid,
        price: String,
        bidStatus: TenderBidStatus = TenderBidStatus.SUBMITTED,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            TenderBidTable.insert {
                it[TenderBidTable.id] = id
                it[TenderBidTable.tenderId] = Uuid.parse(tenderId)
                it[bidderMemberId] = bidder
                it[TenderBidTable.price] = BigDecimal(price)
                it[currency] = TenderCurrency.LTR
                it[text] = "Altgebot"
                it[status] = bidStatus
                it[submittedAt] = LocalDateTime(2026, 11, 2, 11, 0)
                it[priceSetAt] = LocalDateTime(2026, 11, 2, 11, 0)
            }
        }
        return id
    }

    fun setDefaultVisibility(visibility: TenderBidVisibility) {
        transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[tenderDefaultVisibility] = visibility
            }
        }
    }

    /** Between two tests: no tender row, default visibility restored, none of the fixture rows left. */
    fun reset() {
        transaction {
            TenderAcceptanceTable.deleteAll()
            TenderAwardTable.deleteAll()
            TenderBidTable.deleteAll()
            TenderTable.deleteAll()
            val voteIds = VoteTable.selectAll().where { VoteTable.motionId inList motionIds }.map { it[VoteTable.id] }
            VoteBallotTable.deleteWhere { VoteBallotTable.voteId inList voteIds }
            VoteEligibleVoterTable.deleteWhere { VoteEligibleVoterTable.voteId inList voteIds }
            VoteOptionTable.deleteWhere { VoteOptionTable.voteId inList voteIds }
            VoteTable.deleteWhere { VoteTable.id inList voteIds }
            MotionTable.update({ MotionTable.id inList motionIds }) { it[resolutionId] = null }
            val resolutions =
                ResolutionTable.selectAll().where { ResolutionTable.meetingId inList meetingIds }.map { it[ResolutionTable.id] }
            MotionTable.update({ MotionTable.resolutionId inList resolutions }) { it[resolutionId] = null }
            MotionTable.deleteWhere { MotionTable.id inList motionIds }
            ResolutionTable.deleteWhere { ResolutionTable.id inList resolutions }
            AttendanceTable.deleteWhere { AttendanceTable.meetingId inList meetingIds }
            MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
            CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
            CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
            MemberStatusHistoryTable.deleteWhere { MemberStatusHistoryTable.memberId inList memberIds }
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
            AccountTable.deleteWhere { memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
        memberIds.clear()
        committeeIds.clear()
        meetingIds.clear()
        motionIds.clear()
        resolutionIds.clear()
        setDefaultVisibility(TenderBidVisibility.HIDDEN_UNTIL_DEADLINE)
    }
}

// ── shortcuts shared by the tender specs ─────────────────────────────────────────────────────────────────

internal fun createInput(
    binding: Uuid,
    visibility: TenderBidVisibility? = null,
    criterion: TenderAwardCriterion = TenderAwardCriterion.LOWEST_PRICE,
    deadline: LocalDateTime = TENDER_DEADLINE,
) = TenderCreateInput(
    bindingMotionId = binding.toString(),
    title = "Website neu aufsetzen",
    description = "Die Webseite des Vereins wird neu aufgesetzt.",
    expectedResult = "Eine lauffaehige Webseite.",
    deadline = deadline,
    awardCriterion = criterion,
    bidVisibility = visibility,
)

internal fun bidInput(
    price: String,
    text: String = "Ich mache das.",
) = TenderBidInput(price = BigDecimal(price), text = text)

/** Creates (and by default publishes) a tender as [board]; returns its id. */
internal suspend fun TenderApp.newTender(
    board: Uuid,
    binding: Uuid,
    visibility: TenderBidVisibility? = null,
    criterion: TenderAwardCriterion = TenderAwardCriterion.LOWEST_PRICE,
    deadline: LocalDateTime = TENDER_DEADLINE,
    publish: Boolean = true,
): String {
    val created =
        tender(
            member = board,
        ) { createTender(createInput(binding = binding, visibility = visibility, criterion = criterion, deadline = deadline)) }
    val id = created.tender.id
    if (publish) {
        tender(member = board) { publishTender(id) }
    }
    return id
}

internal suspend fun <T> TenderApp.tender(
    member: Uuid,
    op: suspend TenderService.() -> T,
): T = call(member = member) { TenderService(call = it).op() }

internal suspend fun <T> TenderApp.tenderAttempt(
    member: Uuid,
    op: suspend TenderService.() -> T,
): Result<T> = attempt(member = member) { TenderService(call = it).op() }

/** The BEST_OFFER fixture: a committee of five seats (quorum 60 %), s1..s3 clean, s4 and s5 the bidders; the clock ends past the deadline. */
internal class BestOfferScenario(
    val board: Uuid,
    val committee: Uuid,
    val meeting: Uuid,
    val clean: List<Uuid>,
    val bidders: List<Uuid>,
    val tenderId: String,
    val awardMotion: Uuid,
    val bidIds: List<String>,
)

/** [present] = who attends the meeting; [register] = whether the award motion is bound to the tender. */
internal suspend fun TenderApp.bestOfferScenario(
    fx: TenderFixtures,
    clock: MutableTestClock,
    present: (clean: List<Uuid>, bidders: List<Uuid>) -> List<Uuid>,
    register: Boolean = true,
): BestOfferScenario {
    val board = fx.member(label = "board", role = AccountRole.BOARD)
    val clean = (1..3).map { fx.member(label = "s$it") }
    val bidders = (4..5).map { fx.member(label = "s$it") }
    val committee = fx.committee(quorumPercent = 60)
    (clean + bidders).forEach { fx.seat(committee = committee, member = it) }
    val id = newTender(board = board, binding = fx.approvedMotion(committee = committee), criterion = TenderAwardCriterion.BEST_OFFER)
    val bids =
        bidders.mapIndexed {
            i,
            who,
            ->
            tender(member = who) { submitBid(tenderId = id, input = bidInput(price = "${10 + i}.00", text = "Angebot $i")) }.id
        }
    val meeting = fx.meeting(committee)
    val award = fx.motion(committee = committee, submitter = clean.first(), status = MotionStatus.SCHEDULED, meeting = meeting)
    present(clean, bidders).forEach { fx.attend(meeting = meeting, member = it) }
    clock.advance(1.days + 1.hours)
    if (register) tender(member = board) { registerAwardMotion(tenderId = id, motionId = award.toString()) }
    return BestOfferScenario(
        board = board,
        committee = committee,
        meeting = meeting,
        clean = clean,
        bidders = bidders,
        tenderId = id,
        awardMotion = award,
        bidIds = bids,
    )
}
