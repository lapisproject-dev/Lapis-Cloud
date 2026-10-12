package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.VoteEligibleVoterTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.rules.MemberFactsLoader
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.rules.CommitteeRuleGuard
import network.lapis.cloud.shared.domain.rules.InvalidMemberRuleException
import network.lapis.cloud.shared.domain.rules.MemberRule
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleContext
import network.lapis.cloud.shared.domain.rules.matches
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * The "who counts" set shared by [GovernanceService.computeQuorum] (headcount quorum),
 * [GovernanceService.castVoteBallot] (Meritokratische Voteen, V0.2.3 LTR-staking eligibility)
 * and `ElectionService.openVoting` (Demokratische Electionen, V0.2.4 eligibility snapshot) -- extracted
 * out of [GovernanceService] (where this originated in V0.2.3, factored out of
 * [GovernanceService.computeQuorum] at the time) into this standalone file so all three call
 * sites share exactly one definition of Committee/General Assembly eligibility rather than
 * risking the democratic and meritocratic paths silently drifting apart.
 *
 * For a [CommitteeType.GENERAL_ASSEMBLY]-typed Committee, eligibility is "all members with
 * [MemberStatus.ACTIVE]" queried directly from [MemberTable]: syncing every member into
 * [CommitteeMembershipTable] on join/leave would be a brittle parallel bookkeeping system.
 * Known limitation of this path: unlike the Committee path (date-scoped via `since`/`until`), it
 * checks *current* [MemberStatus], not "status as of [scheduledDate]" -- an accepted
 * simplification carried over unchanged from the original V0.2.1 KDoc.
 *
 * V1.9.112 -- a third branch: a committee with [CommitteeMembershipMode.RULE] has no seat rows; its members are whoever its stored rule
 * selects *today* (organisation calendar day, live -- [scheduledDate] is ignored exactly as for the General Assembly), restricted by
 * [CommitteeRuleGuard] to ACTIVE, non-anonymised, living members. Callers that need the electorate of a *running or finished* decision must
 * NOT call this directly but [frozenOrLiveElectorate], which prefers the snapshot frozen when the decision was opened.
 *
 * Must run inside an already-open `transaction {}` (all call sites do).
 */
internal fun eligibleMemberIds(
    committeeRow: ResultRow,
    scheduledDate: LocalDate,
): Set<Uuid> =
    if (committeeRow[CommitteeTable.type] == CommitteeType.GENERAL_ASSEMBLY) {
        MemberTable
            .selectAll()
            .where { generalAssemblyCondition() }
            .map { it[MemberTable.id] }
            .toSet()
    } else if (isRuleCommittee(committeeRow)) {
        eligibleIdsForRule(rule = committeeRuleOf(committeeRow), today = OrganizationTimeZone.today())
    } else {
        CommitteeMembershipTable
            .selectAll()
            .where { committeeMembershipCondition(committeeId = committeeRow[CommitteeTable.id], scheduledDate = scheduledDate) }
            .map { it[CommitteeMembershipTable.memberId] }
            .toSet()
    }

/**
 * V1.9.27 -- point-access twin of [eligibleMemberIds]: is exactly [memberId] part of the eligible set?
 * Uses the SAME conditions (shared builders below) plus `memberId eq`, so the polling path of the
 * conference room never loads "all ACTIVE members". A parity test pins both against each other.
 */
internal fun isCommitteeEligible(
    committeeRow: ResultRow,
    scheduledDate: LocalDate,
    memberId: Uuid,
): Boolean =
    if (committeeRow[CommitteeTable.type] == CommitteeType.GENERAL_ASSEMBLY) {
        MemberTable
            .selectAll()
            .where { generalAssemblyCondition() and (MemberTable.id eq memberId) }
            .limit(1)
            .count() > 0
    } else if (isRuleCommittee(committeeRow)) {
        val today = OrganizationTimeZone.today()
        val rule = committeeRuleOf(committeeRow)
        MemberFactsLoader.loadOne(memberId = memberId, today = today)?.let { isRuleMember(rule = rule, member = it, today = today) }
            ?: false
    } else {
        CommitteeMembershipTable
            .selectAll()
            .where {
                committeeMembershipCondition(committeeId = committeeRow[CommitteeTable.id], scheduledDate = scheduledDate) and
                    (CommitteeMembershipTable.memberId eq memberId)
            }.limit(1)
            .count() > 0
    }

/**
 * V1.9.101 -- the single source of truth for "who is part of the General Assembly": it feeds N
 * (`countGeneralAssemblyEligible`), the quorum, the vote/election eligibility, the invitation to
 * "all active members" and the recipient set of the postal invitation. Never copy this predicate.
 */
internal fun generalAssemblyCondition(): Op<Boolean> = MemberTable.status eq MemberStatus.ACTIVE

private fun committeeMembershipCondition(
    committeeId: Uuid,
    scheduledDate: LocalDate,
): Op<Boolean> =
    (CommitteeMembershipTable.committeeId eq committeeId) and
        (CommitteeMembershipTable.since lessEq scheduledDate) and
        (
            CommitteeMembershipTable.until.isNull() or
                (CommitteeMembershipTable.until greaterEq scheduledDate)
        )

/** V1.9.112 -- true for a committee whose members are derived from its rule. */
internal fun isRuleCommittee(committeeRow: ResultRow): Boolean = committeeRow[CommitteeTable.membershipMode] == CommitteeMembershipMode.RULE

/**
 * V1.9.112 -- the parsed rule of a rule-based committee. A stored rule that no longer parses is a [ConflictException], NEVER an empty set:
 * zero eligible members would make every quorum "met" and silently turn a data fault into a decision. The message carries no rule text.
 */
internal fun committeeRuleOf(committeeRow: ResultRow): MemberRule {
    val text = committeeRow[CommitteeTable.ruleJson] ?: throw ConflictException(RULE_INVALID_MESSAGE)
    return try {
        MemberRuleCodec.parse(text = text, context = RuleContext.COMMITTEE)
    } catch (e: InvalidMemberRuleException) {
        throw ConflictException(RULE_INVALID_MESSAGE)
    }
}

private const val RULE_INVALID_MESSAGE = "Regel des Gremiums ist ungültig"

private fun isRuleMember(
    rule: MemberRule,
    member: network.lapis.cloud.server.rules.LoadedMember,
    today: LocalDate,
): Boolean =
    rule.matches(facts = member.facts, today = today) &&
        CommitteeRuleGuard.eligible(member = member.facts, anonymised = member.anonymised, deceased = member.deceased)

/**
 * V1.9.112 -- THE one evaluator of a committee rule: the live eligibility, the preview and the count all call it, so the number shown to
 * the board can never disagree with who actually belongs. Reads only (3 queries, independent of the member count).
 */
internal fun eligibleIdsForRule(
    rule: MemberRule,
    today: LocalDate,
): Set<Uuid> =
    MemberFactsLoader
        .loadAll(today = today, statuses = MemberStatusSets.RULE_COMMITTEE)
        .filter { isRuleMember(rule = rule, member = it, today = today) }
        .map { it.id }
        .toSet()

/**
 * V1.9.112 -- N of a derived committee: the General Assembly (everybody ACTIVE) or a rule-based one. A manual committee has no derived N
 * (its seat rows are the truth); asking is a programming error, the RPC layer refuses before.
 */
internal fun countEligible(
    committeeRow: ResultRow,
    today: LocalDate = OrganizationTimeZone.today(),
): Int =
    when {
        committeeRow[CommitteeTable.type] == CommitteeType.GENERAL_ASSEMBLY ->
            MemberTable
                .selectAll()
                .where { generalAssemblyCondition() }
                .count()
                .toInt()
        isRuleCommittee(committeeRow) -> eligibleIdsForRule(rule = committeeRuleOf(committeeRow), today = today).size
        else -> error("countEligible is only defined for the General Assembly and rule-based committees")
    }

/** The electorate of a decision and whether it is the frozen snapshot ([frozen]) or the live set. */
internal data class Electorate(
    val memberIds: Set<Uuid>,
    val frozen: Boolean,
)

/**
 * V1.9.112 -- the electorate that counts for a decision of [meetingId]: the snapshot taken when the decision was opened if there is one,
 * otherwise the live set. Resolution order (first hit wins):
 *
 * 1. [voteId] given and `vote.electorate_frozen_at` set -> that vote's snapshot (even if it is empty -- the marker, never "no rows", says
 *    whether a vote was frozen: a vote opened before V1.9.112 has no marker and keeps the live set);
 * 2. [electionId] given and its voting was opened -> the election's snapshot;
 * 3. [consensusId] given and it has snapshot rows -> the snapshot of its latest round;
 * 4. the snapshot of the EARLIEST frozen vote of this meeting (any status: the circle is fixed from the first opening on, even if that
 *    vote was aborted) -- this is what the meeting's quorum uses;
 * 5. live ([eligibleMemberIds]).
 *
 * Whoever checks the right to vote or the quorum must use this function and never [eligibleMemberIds] directly.
 */
internal fun frozenOrLiveElectorate(
    meetingId: Uuid,
    committeeRow: ResultRow,
    scheduledDate: LocalDate,
    voteId: Uuid? = null,
    electionId: Uuid? = null,
    consensusId: Uuid? = null,
): Electorate {
    if (voteId != null) {
        val frozenAt =
            VoteTable
                .selectAll()
                .where { VoteTable.id eq voteId }
                .singleOrNull()
                ?.get(VoteTable.electorateFrozenAt)
        if (frozenAt != null) return Electorate(memberIds = voteElectorate(voteId), frozen = true)
    }
    if (electionId != null) {
        val opened =
            ElectionTable
                .selectAll()
                .where { ElectionTable.id eq electionId }
                .singleOrNull()
                ?.get(ElectionTable.votingOpenedAt)
        if (opened != null) {
            val ids =
                ElectionEligibleVoterTable
                    .selectAll()
                    .where { ElectionEligibleVoterTable.electionId eq electionId }
                    .map { it[ElectionEligibleVoterTable.memberId] }
                    .toSet()
            return Electorate(memberIds = ids, frozen = true)
        }
    }
    if (consensusId != null) {
        val rows =
            SystemicConsensusEligibleVoterTable
                .selectAll()
                .where { SystemicConsensusEligibleVoterTable.systemicConsensusId eq consensusId }
                .toList()
        val latestRound = rows.maxOfOrNull { it[SystemicConsensusEligibleVoterTable.round] }
        if (latestRound != null) {
            val ids =
                rows
                    .filter {
                        it[SystemicConsensusEligibleVoterTable.round] == latestRound
                    }.map { it[SystemicConsensusEligibleVoterTable.memberId] }
            return Electorate(memberIds = ids.toSet(), frozen = true)
        }
    }
    val earliest =
        VoteTable
            .selectAll()
            .where { (VoteTable.meetingId eq meetingId) and VoteTable.electorateFrozenAt.isNotNull() }
            .orderBy(VoteTable.openedAt to SortOrder.ASC, VoteTable.id to SortOrder.ASC)
            .limit(1)
            .singleOrNull()
    if (earliest != null) return Electorate(memberIds = voteElectorate(earliest[VoteTable.id]), frozen = true)
    return Electorate(memberIds = eligibleMemberIds(committeeRow = committeeRow, scheduledDate = scheduledDate), frozen = false)
}

private fun voteElectorate(voteId: Uuid): Set<Uuid> =
    VoteEligibleVoterTable
        .selectAll()
        .where { VoteEligibleVoterTable.voteId eq voteId }
        .map { it[VoteEligibleVoterTable.memberId] }
        .toSet()

/** V1.9.112 -- fixed refusal text: a rule-based committee has no seat rows, so an election cannot seat anybody there. */
internal const val RULE_COMMITTEE_NO_ELECTION = "Regelgremien haben keine Sitze: Eine Wahl kann dort niemanden einsetzen."

/** V1.9.112 -- fail-closed guard for every path that would WRITE seat rows (an election's succession, a manual seat). */
internal fun requireNotRuleCommittee(committeeId: Uuid) {
    val mode =
        CommitteeTable
            .select(CommitteeTable.membershipMode)
            .where { CommitteeTable.id eq committeeId }
            .singleOrNull()
            ?.get(CommitteeTable.membershipMode)
    if (mode == CommitteeMembershipMode.RULE) throw ConflictException(RULE_COMMITTEE_NO_ELECTION)
}
