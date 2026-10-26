package network.lapis.cloud.server.dashboard

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ContributionTable
import network.lapis.cloud.server.db.generated.CrowdfundingProjectTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.EncounterSpaceTable
import network.lapis.cloud.server.db.generated.EventConferenceStatus
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventRegistrationTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.McpPostDraftTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MembershipTierTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.db.generated.TenderAwardTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.db.generated.VoteEligibleVoterTable
import network.lapis.cloud.server.db.generated.VoteOptionTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ContributionPaymentMethod
import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.EventRegistrationStatus
import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.McpPostDraftStatus
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.PollKind
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.domain.SystemicConsensusAggregation
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderBidVisibility
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.VoteStatus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** The frozen "now" of the scenarios (class A, UTC). */
internal val DASHBOARD_NOW: LocalDateTime = LocalDateTime(2030, 6, 10, 12, 0)

internal fun dashboardContext(
    memberId: Uuid,
    status: MemberStatus = MemberStatus.ACTIVE,
    role: AccountRole = AccountRole.MEMBER,
    zone: TimeZone = TimeZone.UTC,
    flags: SignalModuleFlags =
        SignalModuleFlags(auctionEnabled = true, conferenceEnabled = true, mcpEnabled = true, dunningEnabled = true),
): SignalContext {
    val wall = ServerClock.systemToWall(utc = DASHBOARD_NOW, orgZone = zone)
    return SignalContext(
        memberId = memberId,
        status = status,
        role = role,
        nowSystem = DASHBOARD_NOW,
        nowWall = wall,
        today = wall.date,
        zone = zone,
        flags = flags,
    )
}

/**
 * Runs [block] inside one transaction and ROLLS IT BACK at the end, so a scenario needs no clean-up and cannot disturb the other specs
 * that share the
 * database. The collector's own `transaction {}` joins this one (same connection), so it sees the fixtures.
 */
internal fun <T> rolledBack(block: () -> T): T {
    var result: Result<T>? = null
    transaction {
        result = runCatching(block)
        TransactionManager.current().rollback()
    }
    return result!!.getOrThrow()
}

/** Row builders; every function must be called inside [rolledBack] (or any open transaction). Names and titles are recognisable secrets. */
@Suppress("LongParameterList", "TooManyFunctions")
internal object DashboardFixtures {
    const val SECRET = "GEHEIM-ZEICHENFOLGE"

    fun member(
        status: MemberStatus = MemberStatus.ACTIVE,
        role: AccountRole = AccountRole.MEMBER,
        name: String = "$SECRET-Name",
    ): Uuid {
        val memberId = Uuid.random()
        MemberTable.insert {
            it[MemberTable.id] = memberId
            it[displayName] = name
            it[email] = "dashboard-$memberId@example.org"
            it[MemberTable.status] = status
            it[joinedAt] = LocalDate(2020, 1, 1)
        }
        AccountTable.insert {
            it[AccountTable.id] = Uuid.random()
            it[AccountTable.memberId] = memberId
            it[AccountTable.role] = role
        }
        return memberId
    }

    fun committee(
        type: CommitteeType = CommitteeType.WORKING_GROUP,
        meetingAt: LocalDateTime? = null,
        meetingStatus: MeetingStatus = MeetingStatus.PLANNED,
    ): Pair<Uuid, Uuid?> {
        val committeeId = Uuid.random()
        CommitteeTable.insert {
            it[CommitteeTable.id] = committeeId
            it[name] = "$SECRET-Gremium"
            it[CommitteeTable.type] = type
            it[description] = "x"
            it[active] = true
            it[quorumPercent] = 50
            it[createdAt] = DASHBOARD_NOW
        }
        val meetingId = meetingAt?.let { meeting(committeeId = committeeId, at = it, status = meetingStatus) }
        return committeeId to meetingId
    }

    fun meeting(
        committeeId: Uuid,
        at: LocalDateTime,
        status: MeetingStatus = MeetingStatus.PLANNED,
    ): Uuid {
        val meetingId = Uuid.random()
        MeetingTable.insert {
            it[MeetingTable.id] = meetingId
            it[MeetingTable.committeeId] = committeeId
            it[title] = "$SECRET-Sitzung"
            it[scheduledAt] = at
            it[location] = null
            it[format] = MeetingFormat.IN_PERSON
            it[MeetingTable.status] = status
            it[calledBy] = null
            it[calledAt] = null
            it[chairMemberId] = null
            it[minuteTakerMemberId] = null
            it[protocolDocumentId] = null
            it[createdAt] = DASHBOARD_NOW
        }
        return meetingId
    }

    fun seat(
        committeeId: Uuid,
        memberId: Uuid,
        since: LocalDate = LocalDate(2020, 1, 1),
        until: LocalDate? = null,
    ) {
        CommitteeMembershipTable.insert {
            it[CommitteeMembershipTable.id] = Uuid.random()
            it[role] = CommitteeRole.MEMBER
            it[CommitteeMembershipTable.since] = since
            it[CommitteeMembershipTable.until] = until
            it[CommitteeMembershipTable.committeeId] = committeeId
            it[CommitteeMembershipTable.memberId] = memberId
        }
    }

    fun motion(
        committeeId: Uuid,
        meetingId: Uuid,
        submitter: Uuid,
    ): Uuid {
        val motionId = Uuid.random()
        MotionTable.insert {
            it[MotionTable.id] = motionId
            it[targetCommitteeId] = committeeId
            it[title] = "$SECRET-Antrag"
            it[rationale] = "R"
            it[text] = "T"
            it[submitterMemberId] = submitter
            it[status] = MotionStatus.SCHEDULED
            it[submittedAt] = DASHBOARD_NOW
            it[reviewedBy] = null
            it[reviewedAt] = null
            it[reviewNote] = null
            it[MotionTable.meetingId] = meetingId
            it[agendaItemId] = null
            it[resolutionId] = null
            it[withdrawnAt] = null
        }
        return motionId
    }

    /** A committee + meeting + motion + submitter in one go (the base of tenders, elections, consensuses and votes). */
    class Governance(
        val committeeId: Uuid,
        val meetingId: Uuid,
        val submitter: Uuid,
    ) {
        fun newMotion(): Uuid = motion(committeeId = committeeId, meetingId = meetingId, submitter = submitter)
    }

    fun governance(): Governance {
        val (committeeId, meetingId) = committee(meetingAt = LocalDateTime(2031, 1, 1, 18, 0))
        return Governance(committeeId = committeeId, meetingId = meetingId!!, submitter = member())
    }

    fun auction(
        seller: Uuid,
        endsAt: LocalDateTime,
        status: AuctionStatus = AuctionStatus.OPEN,
        winner: Uuid? = null,
        settledAt: LocalDateTime? = null,
        startingBid: String = "1.00",
    ): Uuid {
        val auctionId = Uuid.random()
        AuctionTable.insert {
            it[AuctionTable.id] = auctionId
            it[title] = "$SECRET-Auktion"
            it[description] = "x"
            it[startingBidLtr] = BigDecimal(startingBid)
            it[buyNowPriceLtr] = null
            it[AuctionTable.status] = status
            it[sellerMemberId] = seller
            it[winnerMemberId] = winner
            it[finalPriceLtr] = null
            it[listingFeeLtr] = BigDecimal("0.01")
            it[createdAt] = DASHBOARD_NOW
            it[AuctionTable.endsAt] = endsAt
            it[AuctionTable.settledAt] = settledAt
        }
        return auctionId
    }

    fun bid(
        auctionId: Uuid,
        bidder: Uuid,
        max: String,
        at: LocalDateTime = DASHBOARD_NOW,
    ) {
        AuctionBidTable.insert {
            it[AuctionBidTable.id] = Uuid.random()
            it[AuctionBidTable.auctionId] = auctionId
            it[bidderMemberId] = bidder
            it[maxBidLtr] = BigDecimal(max)
            it[createdAt] = at
        }
    }

    fun tender(
        g: Governance,
        deadline: LocalDateTime,
        status: TenderStatus = TenderStatus.PUBLISHED,
        currency: TenderCurrency = TenderCurrency.LTR,
    ): Uuid {
        val tenderId = Uuid.random()
        val motionId = g.newMotion()
        TenderTable.insert {
            it[TenderTable.id] = tenderId
            it[title] = "$SECRET-Ausschreibung"
            it[description] = "x"
            it[expectedResult] = "x"
            it[bindingMotionId] = motionId
            it[awardCriterion] = TenderAwardCriterion.LOWEST_PRICE
            it[TenderTable.currency] = currency
            it[bidVisibility] = TenderBidVisibility.OWN_ONLY
            it[TenderTable.status] = status
            it[TenderTable.deadline] = deadline
            it[createdBy] = g.submitter
            it[createdAt] = DASHBOARD_NOW
            it[publishedBy] = g.submitter
            it[publishedAt] = DASHBOARD_NOW
        }
        return tenderId
    }

    fun tenderBid(
        tenderId: Uuid,
        bidder: Uuid,
        status: TenderBidStatus = TenderBidStatus.SUBMITTED,
    ): Uuid {
        val bidId = Uuid.random()
        TenderBidTable.insert {
            it[TenderBidTable.id] = bidId
            it[TenderBidTable.tenderId] = tenderId
            it[bidderMemberId] = bidder
            it[price] = BigDecimal("10.00")
            it[currency] = TenderCurrency.LTR
            it[text] = "$SECRET-Angebot"
            it[TenderBidTable.status] = status
            it[submittedAt] = DASHBOARD_NOW
            it[priceSetAt] = DASHBOARD_NOW
        }
        return bidId
    }

    fun tenderAward(
        tenderId: Uuid,
        bidId: Uuid,
        by: Uuid,
        at: LocalDateTime,
    ) {
        TenderAwardTable.insert {
            it[TenderAwardTable.id] = Uuid.random()
            it[TenderAwardTable.tenderId] = tenderId
            it[TenderAwardTable.bidId] = bidId
            it[awardedBy] = by
            it[awardedAt] = at
            it[conflictedCount] = 0
        }
    }

    fun election(
        g: Governance,
        secret: Boolean,
        status: ElectionStatus = ElectionStatus.OPEN,
        eligible: List<Uuid> = emptyList(),
        voted: List<Uuid> = emptyList(),
    ): Uuid {
        val electionId = Uuid.random()
        val motionId = g.newMotion()
        val snapshot = status == ElectionStatus.OPEN
        ElectionTable.insert {
            it[ElectionTable.id] = electionId
            it[title] = "$SECRET-Wahl"
            it[electionType] = ElectionType.YES_NO
            it[ElectionTable.secret] = secret
            it[seatCount] = 1
            it[targetCommitteeId] = null
            it[targetRole] = null
            it[requiredMajorityPercent] = 50
            it[ElectionTable.status] = status
            it[openedBy] = g.submitter
            it[openedAt] = DASHBOARD_NOW
            it[votingOpenedAt] = if (snapshot) DASHBOARD_NOW else null
            it[votingClosedAt] = null
            it[tallyThreshold] = 2
            it[ElectionTable.motionId] = motionId
            it[meetingId] = g.meetingId
            it[activeMotionId] = motionId
        }
        eligible.forEach { member ->
            ElectionEligibleVoterTable.insert {
                it[ElectionEligibleVoterTable.id] = Uuid.random()
                it[ElectionEligibleVoterTable.electionId] = electionId
                it[ElectionEligibleVoterTable.memberId] = member
            }
        }
        voted.forEach { member ->
            // a SECRET election keeps "who voted" in election_participation (no link to the ballot)
            ElectionParticipationTable.insert {
                it[ElectionParticipationTable.id] = Uuid.random()
                it[ElectionParticipationTable.electionId] = electionId
                it[ElectionParticipationTable.memberId] = member
                it[votedAt] = DASHBOARD_NOW
            }
        }
        return electionId
    }

    fun consensus(
        g: Governance,
        eligible: List<Uuid> = emptyList(),
    ): Uuid {
        val consensusId = Uuid.random()
        val motionId = g.newMotion()
        SystemicConsensusTable.insert {
            it[SystemicConsensusTable.id] = consensusId
            it[title] = "$SECRET-Konsensieren"
            it[status] = SystemicConsensusStatus.RATING
            it[secret] = true
            it[scaleMax] = 10
            it[aggregation] = SystemicConsensusAggregation.MEAN
            it[tiebreakRule] = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE
            it[groupConflictViableThreshold] = BigDecimal("0.500")
            it[groupConflictWarnThreshold] = BigDecimal("0.800")
            it[statusQuoOptionAuto] = true
            it[bindingness] = SystemicConsensusBindingness.ADVISORY
            it[maxRounds] = 3
            it[round] = 1
            it[winnerOptionId] = null
            it[openedBy] = g.submitter
            it[openedAt] = DASHBOARD_NOW
            it[ratingOpenedAt] = DASHBOARD_NOW
            it[ratingClosedAt] = null
            it[SystemicConsensusTable.motionId] = motionId
            it[meetingId] = g.meetingId
            it[resolutionId] = null
        }
        eligible.forEach { member ->
            SystemicConsensusEligibleVoterTable.insert {
                it[SystemicConsensusEligibleVoterTable.id] = Uuid.random()
                it[systemicConsensusId] = consensusId
                it[round] = 1
                it[SystemicConsensusEligibleVoterTable.memberId] = member
            }
        }
        return consensusId
    }

    fun poll(
        creator: Uuid,
        closesAt: LocalDateTime?,
        status: PollStatus = PollStatus.OPEN,
    ): Uuid {
        val pollId = Uuid.random()
        PollTable.insert {
            it[PollTable.id] = pollId
            it[question] = "$SECRET-Frage"
            it[description] = null
            it[PollTable.status] = status
            it[kind] = PollKind.SINGLE_CHOICE
            it[createdBy] = creator
            it[createdAt] = LocalDateTime(2030, 6, 1, 0, 0)
            it[PollTable.closesAt] = closesAt
            it[closedAt] = if (status == PollStatus.OPEN) null else DASHBOARD_NOW
            it[closedBy] = if (status == PollStatus.OPEN) null else creator
        }
        return pollId
    }

    fun pollAnswered(
        pollId: Uuid,
        memberId: Uuid,
    ) {
        PollParticipationTable.insert {
            it[PollParticipationTable.id] = Uuid.random()
            it[PollParticipationTable.pollId] = pollId
            it[PollParticipationTable.memberId] = memberId
        }
    }

    fun vote(
        g: Governance,
        eligible: List<Uuid>,
        voted: List<Uuid> = emptyList(),
    ): Uuid {
        val voteId = Uuid.random()
        val motionId = g.newMotion()
        VoteTable.insert {
            it[VoteTable.id] = voteId
            it[VoteTable.motionId] = motionId
            it[meetingId] = g.meetingId
            it[title] = "$SECRET-Abstimmung"
            it[status] = VoteStatus.OPEN
            it[openedBy] = g.submitter
            it[openedAt] = DASHBOARD_NOW
            it[electorateFrozenAt] = DASHBOARD_NOW
        }
        eligible.forEach { member ->
            VoteEligibleVoterTable.insert {
                it[VoteEligibleVoterTable.id] = Uuid.random()
                it[VoteEligibleVoterTable.voteId] = voteId
                it[VoteEligibleVoterTable.memberId] = member
            }
        }
        return voteId
    }

    fun crowdfunding(
        submitter: Uuid,
        status: CrowdfundingProjectStatus,
        reviewedAt: LocalDateTime?,
    ): Uuid {
        val projectId = Uuid.random()
        CrowdfundingProjectTable.insert {
            it[CrowdfundingProjectTable.id] = projectId
            it[title] = "$SECRET-Projekt"
            it[description] = "x"
            it[submitterMemberId] = submitter
            it[initialWeightLtr] = BigDecimal("1.00")
            it[CrowdfundingProjectTable.status] = status
            it[CrowdfundingProjectTable.reviewedAt] = reviewedAt
            it[submittedAt] = DASHBOARD_NOW
        }
        return projectId
    }

    fun event(
        creator: Uuid,
        startsAt: LocalDateTime,
        status: EventStatus = EventStatus.PUBLISHED,
    ): Uuid {
        val eventId = Uuid.random()
        EventTable.insert {
            it[EventTable.id] = eventId
            it[slug] = "dashboard-$eventId"
            it[title] = "$SECRET-Veranstaltung"
            it[description] = "x"
            it[EventTable.startsAt] = startsAt
            it[endsAt] = startsAt
            it[feeAmount] = BigDecimal.ZERO
            it[feeCurrency] = "EUR"
            it[locationText] = "Vereinsheim"
            it[EventTable.status] = status
            it[visibility] = EventVisibility.MEMBERS_ONLY
            it[createdAt] = DASHBOARD_NOW
            it[createdBy] = creator
        }
        return eventId
    }

    fun registration(
        eventId: Uuid,
        memberId: Uuid,
        status: EventRegistrationStatus = EventRegistrationStatus.CONFIRMED,
    ) {
        EventRegistrationTable.insert {
            it[EventRegistrationTable.id] = Uuid.random()
            it[EventRegistrationTable.eventId] = eventId
            it[EventRegistrationTable.memberId] = memberId
            it[activeParticipantKey] = "m:$memberId"
            it[EventRegistrationTable.status] = status
            it[feeAmount] = BigDecimal.ZERO
            it[registeredAt] = DASHBOARD_NOW
        }
    }

    fun contribution(
        memberId: Uuid,
        status: ContributionStatus,
        dueDate: LocalDate = LocalDate(2030, 7, 1),
        method: ContributionPaymentMethod = ContributionPaymentMethod.MANUAL,
    ): Uuid {
        val tierId = Uuid.random()
        MembershipTierTable.insert {
            it[MembershipTierTable.id] = tierId
            it[nameKey] = tierId.toString()
            it[name] = "dashboard-tier-$tierId"
            it[description] = "x"
            it[contributionAmount] = BigDecimal("10.00")
            it[billingInterval] = BillingInterval.YEARLY
            it[active] = true
            it[paymentTermDays] = 14
        }
        val contributionId = Uuid.random()
        ContributionTable.insert {
            it[ContributionTable.id] = contributionId
            it[periodStart] = dueDate
            it[periodEnd] = dueDate
            it[amountDue] = BigDecimal("10.00")
            it[ContributionTable.status] = status
            it[createdAt] = DASHBOARD_NOW
            it[ContributionTable.memberId] = memberId
            it[membershipTierId] = tierId
            it[ContributionTable.dueDate] = dueDate
            it[paymentMethod] = method
            it[sepaMandateId] = null
        }
        return contributionId
    }

    fun travel(
        memberId: Uuid,
        status: TravelExpenseReportStatus,
        decidedAt: LocalDateTime?,
        executedAt: LocalDateTime? = null,
    ): Uuid {
        val reportId = Uuid.random()
        // EXECUTED <=> posted_journal_entry_id IS NOT NULL (chk_ter_posted_entry_state): a plain FK target is enough here
        val journalEntryId =
            if (status == TravelExpenseReportStatus.EXECUTED) {
                Uuid.random().also { entry ->
                    JournalEntryTable.insert {
                        it[JournalEntryTable.id] = entry
                        it[entryDate] = LocalDate(2030, 6, 1)
                        it[description] = "Test-Fixture"
                        it[voucherReference] = "TEST-FIXTURE-$entry"
                        it[createdBy] = memberId
                        it[JournalEntryTable.status] = JournalEntryStatus.POSTED
                        it[postedAt] = DASHBOARD_NOW
                        it[createdAt] = DASHBOARD_NOW
                        it[donorMemberId] = null
                        it[externalDonorId] = null
                        it[donorCategory] = null
                    }
                }
            } else {
                null
            }
        TravelExpenseReportTable.insert {
            it[TravelExpenseReportTable.id] = reportId
            it[subjectMemberId] = memberId
            it[TravelExpenseReportTable.status] = status
            it[purpose] = "$SECRET-Zweck"
            it[travelFrom] = LocalDate(2030, 5, 1)
            it[travelTo] = LocalDate(2030, 5, 2)
            it[totalAmount] = BigDecimal("12.00")
            it[createdAt] = DASHBOARD_NOW
            it[requestedBy] = memberId
            it[submittedAt] = if (status == TravelExpenseReportStatus.DRAFT) null else DASHBOARD_NOW
            it[TravelExpenseReportTable.decidedAt] = decidedAt
            it[decisionNote] = if (decidedAt != null) "ok" else null
            it[TravelExpenseReportTable.executedAt] = executedAt
            it[postedJournalEntryId] = journalEntryId
            it[advanceAmount] = BigDecimal.ZERO
            it[refundAccountChangedAfterSubmit] = false
            it[paidOutsideLapis] = false
            it[sepaReexportCount] = 0
        }
        return reportId
    }

    fun aiDraft(
        memberId: Uuid,
        status: McpPostDraftStatus = McpPostDraftStatus.OPEN,
    ) {
        McpPostDraftTable.insert {
            it[McpPostDraftTable.id] = Uuid.random()
            it[McpPostDraftTable.memberId] = memberId
            it[agentLabel] = "agent"
            it[content] = "$SECRET-Entwurf"
            it[visibility] = SocialPostVisibility.MEMBERS_ONLY
            it[McpPostDraftTable.status] = status
            it[createdAt] = DASHBOARD_NOW
            it[updatedAt] = DASHBOARD_NOW
        }
    }

    fun room(
        creator: Uuid,
        ended: Boolean = false,
        encounterSpaceId: Uuid? = null,
    ): Uuid {
        val roomId = Uuid.random()
        ConferenceRoomTable.insert {
            it[ConferenceRoomTable.id] = roomId
            it[title] = "$SECRET-Raum"
            it[description] = ""
            it[livekitRoomName] = "lc-dash-${roomId.toString().take(40)}"
            it[createdByMemberId] = creator
            it[createdAt] = DASHBOARD_NOW
            it[endedAt] = if (ended) DASHBOARD_NOW else null
            it[maxParticipants] = 25
            it[ConferenceRoomTable.encounterSpaceId] = encounterSpaceId
        }
        return roomId
    }

    fun present(
        roomId: Uuid,
        memberId: Uuid,
        left: Boolean = false,
    ) {
        ConferenceParticipationTable.insert {
            it[ConferenceParticipationTable.id] = Uuid.random()
            it[ConferenceParticipationTable.roomId] = roomId
            it[ConferenceParticipationTable.memberId] = memberId
            it[role] = ConferenceRole.PARTICIPANT
            it[joinedAt] = DASHBOARD_NOW
            it[leftAt] = if (left) DASHBOARD_NOW else null
        }
    }

    /** Marks [roomId] as the room of an event (the ordinary room list never shows it). */
    fun asEventRoom(
        roomId: Uuid,
        creator: Uuid,
    ) {
        val eventId = event(creator = creator, startsAt = LocalDateTime(2030, 6, 20, 18, 0))
        EventConferenceTable.insert {
            it[EventConferenceTable.eventId] = eventId
            it[conferenceRoomId] = roomId
            it[livekitRoomName] = "lc-dash-event-${roomId.toString().take(30)}"
            it[status] = EventConferenceStatus.ACTIVE
            it[claimToken] = Uuid.random()
            it[claimedAt] = DASHBOARD_NOW
            it[windowOpensAt] = DASHBOARD_NOW
            it[windowClosesAt] = LocalDateTime(2030, 6, 11, 12, 0)
            it[createdAt] = DASHBOARD_NOW
            it[endedAt] = null
        }
    }

    fun space(
        creator: Uuid,
        guests: Boolean = false,
        archived: Boolean = false,
    ): Uuid {
        val spaceId = Uuid.random()
        EncounterSpaceTable.insert {
            it[EncounterSpaceTable.id] = spaceId
            it[title] = "$SECRET-Begegnung"
            it[description] = ""
            it[themeKey] = "CHURCH"
            it[profile] = "CHURCH_SERVICE"
            it[reactionSet] = "HAND,AMEN"
            it[notifyMode] = "NONE"
            it[tablesEnabled] = false
            it[tableCount] = 4.toShort()
            it[tableSeats] = 6.toShort()
            it[mode] = "SERVICE"
            it[guestPolicy] = if (guests) "MEMBERS_AND_GUESTS" else "MEMBERS_ONLY"
            it[maxParticipants] = null
            it[closedNotice] = null
            it[createdAt] = DASHBOARD_NOW
            it[createdByMemberId] = creator
            it[updatedAt] = null
            it[archivedAt] = if (archived) DASHBOARD_NOW else null
        }
        return spaceId
    }

    /** The member's own bid on the vote (one option + one ballot row). */
    fun voteBallot(
        voteId: Uuid,
        memberId: Uuid,
    ) {
        val optionId = Uuid.random()
        VoteOptionTable.insert {
            it[VoteOptionTable.id] = optionId
            it[VoteOptionTable.voteId] = voteId
            it[label] = "$SECRET-Option"
            it[position] = 0
        }
        VoteBallotTable.insert {
            it[VoteBallotTable.id] = Uuid.random()
            it[VoteBallotTable.optionId] = optionId
            it[stakeLtr] = BigDecimal("1.00")
            it[settledLtr] = null
            it[castAt] = DASHBOARD_NOW
            it[VoteBallotTable.voteId] = voteId
            it[VoteBallotTable.memberId] = memberId
        }
    }
}
