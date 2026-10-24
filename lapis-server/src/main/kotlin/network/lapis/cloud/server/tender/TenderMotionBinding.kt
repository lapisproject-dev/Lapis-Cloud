package network.lapis.cloud.server.tender

import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TenderTable
import network.lapis.cloud.server.rpc.MotionDecisionLock
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.TenderAwardMotionIneligibility
import network.lapis.cloud.shared.domain.TenderMotionIneligibility
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.201 -- the two rules that tie a tender to the governance book. Both must run inside an open `transaction {}` and on a motion row that the
 * caller has ALREADY locked ([MotionDecisionLock.lockMotion]); the refusals carry a FIXED text without any motion text.
 *
 *  * BINDING motion: the tender implements an ADOPTED resolution. [requireApprovedBindingMotion] demands that the motion is RESOLVED, is no
 *    amendment, that its resolution is ADOPTED and that the resolution was taken in a meeting of the very committee the motion is addressed to.
 *  * AWARD motion (BEST_OFFER only): the committee resolution that decides the award, see [requireEligibleAwardMotion].
 */
internal object TenderMotionBinding {
    const val NOT_APPROVED = "The binding motion is not an adopted resolution of its committee"
    const val NOT_ELIGIBLE_AWARD_MOTION = "This motion cannot serve as the award motion of the tender"

    fun requireApprovedBindingMotion(motionRow: ResultRow) {
        if (bindingIneligibility(motionRow) != null) throw ConflictException(NOT_APPROVED)
    }

    /**
     * V1.9.202 -- why [motionRow] cannot be the binding motion of a tender; `null` = it can. The single definition behind
     * [requireApprovedBindingMotion] and the motion list of the screens. Pure reads, no lock.
     */
    fun bindingIneligibility(motionRow: ResultRow): TenderMotionIneligibility? {
        if (motionRow[MotionTable.amendsMotionId] != null) return TenderMotionIneligibility.AMENDMENT
        if (motionRow[MotionTable.status] != MotionStatus.RESOLVED) return TenderMotionIneligibility.NOT_RESOLVED
        val resolutionId = motionRow[MotionTable.resolutionId] ?: return TenderMotionIneligibility.NOT_RESOLVED
        val resolution =
            ResolutionTable.selectAll().where { ResolutionTable.id eq resolutionId }.singleOrNull()
                ?: return TenderMotionIneligibility.RESOLUTION_NOT_ADOPTED
        return bindingIneligibilityOf(
            motionRow = motionRow,
            resolution = resolution,
            committeeOfMeeting = { meetingId ->
                MeetingTable
                    .selectAll()
                    .where { MeetingTable.id eq meetingId }
                    .singleOrNull()
                    ?.get(MeetingTable.committeeId)
            },
        )
    }

    /**
     * Same decision with the resolution already loaded and the meeting's committee resolved by [committeeOfMeeting] (the screens load the
     * resolutions and meetings of a whole list in bulk, so no query runs per motion).
     */
    fun bindingIneligibilityOf(
        motionRow: ResultRow,
        resolution: ResultRow?,
        committeeOfMeeting: (Uuid) -> Uuid?,
    ): TenderMotionIneligibility? {
        if (motionRow[MotionTable.amendsMotionId] != null) return TenderMotionIneligibility.AMENDMENT
        if (motionRow[MotionTable.status] != MotionStatus.RESOLVED || motionRow[MotionTable.resolutionId] == null) {
            return TenderMotionIneligibility.NOT_RESOLVED
        }
        if (resolution == null || resolution[ResolutionTable.status] != ResolutionStatus.ADOPTED) {
            return TenderMotionIneligibility.RESOLUTION_NOT_ADOPTED
        }
        val committeeId =
            committeeOfMeeting(resolution[ResolutionTable.meetingId]) ?: return TenderMotionIneligibility.RESOLUTION_NOT_ADOPTED
        if (committeeId != motionRow[MotionTable.targetCommitteeId]) return TenderMotionIneligibility.WRONG_COMMITTEE
        return null
    }

    /**
     * [motionRow] may serve as the award motion of [tenderRow]: it is addressed to the committee of the binding resolution, is no amendment, is
     * not the binding motion of any tender, has not been decided yet and no decision path (election, vote, systemic consensus) has been opened
     * on it. The caller has locked [motionRow].
     */
    fun requireEligibleAwardMotion(
        motionRow: ResultRow,
        tenderRow: ResultRow,
    ) {
        if (awardIneligibility(motionRow = motionRow, tenderRow = tenderRow) != null) throw ConflictException(NOT_ELIGIBLE_AWARD_MOTION)
        // Before any decision path is opened: after that the circle of voters is already fixed without the conflicted bidders.
        MotionDecisionLock.requireNoRunningDecisionPath(motionRow[MotionTable.id])
        MotionDecisionLock.requireNoElection(motionRow[MotionTable.id])
        MotionDecisionLock.requireNoActiveSystemicConsensus(motionRow[MotionTable.id])
        MotionDecisionLock.requireNoOpenVote(motionRow[MotionTable.id])
    }

    /**
     * V1.9.202 -- why [motionRow] cannot be the award motion of [tenderRow]; `null` = it can (the decision-path checks aside, see
     * [awardIneligibilityWithDecisionPath]). The single definition behind [requireEligibleAwardMotion] and the award-motion list of the screens.
     */
    fun awardIneligibility(
        motionRow: ResultRow,
        tenderRow: ResultRow,
        binding: ResultRow? = null,
    ): TenderAwardMotionIneligibility? {
        val motionId = motionRow[MotionTable.id]
        val bindingMotionId = tenderRow[TenderTable.bindingMotionId]
        if (motionId == bindingMotionId) return TenderAwardMotionIneligibility.SAME_AS_BINDING
        val bindingRow =
            binding ?: MotionTable.selectAll().where { MotionTable.id eq bindingMotionId }.singleOrNull()
                ?: return TenderAwardMotionIneligibility.WRONG_COMMITTEE
        if (motionRow[MotionTable.targetCommitteeId] != bindingRow[MotionTable.targetCommitteeId]) {
            return TenderAwardMotionIneligibility.WRONG_COMMITTEE
        }
        if (motionRow[MotionTable.amendsMotionId] != null) return TenderAwardMotionIneligibility.AMENDMENT
        if (motionRow[MotionTable.status] !in AWARD_MOTION_OPEN_STATUSES) return TenderAwardMotionIneligibility.NOT_OPEN
        if (TenderStore.idsByBindingMotion(motionId).isNotEmpty()) return TenderAwardMotionIneligibility.ALREADY_BOUND
        // The award motion of another tender is refused as well (registerAwardMotion enforces the same rule).
        TenderStore.idByAwardMotion(motionId)?.let { other ->
            if (other != tenderRow[TenderTable.id]) return TenderAwardMotionIneligibility.ALREADY_BOUND
        }
        // An amendment that already exists (pending or adopted) was shaped without the conflict of interest and would rewrite the award text.
        val hasEffectiveAmendment =
            MotionTable
                .selectAll()
                .where { (MotionTable.amendsMotionId eq motionId) and (MotionTable.status notInList INEFFECTIVE_AMENDMENT_STATUSES) }
                .limit(1)
                .any()
        if (hasEffectiveAmendment) return TenderAwardMotionIneligibility.HAS_AMENDMENT
        return null
    }

    /** [awardIneligibility] plus the decision-path checks (without a lock: a display-only read; the binding check stays in the writing path). */
    fun awardIneligibilityWithDecisionPath(
        motionRow: ResultRow,
        tenderRow: ResultRow,
        binding: ResultRow? = null,
    ): TenderAwardMotionIneligibility? {
        awardIneligibility(motionRow = motionRow, tenderRow = tenderRow, binding = binding)?.let { return it }
        val motionId = motionRow[MotionTable.id]
        return try {
            MotionDecisionLock.requireNoRunningDecisionPath(motionId)
            MotionDecisionLock.requireNoElection(motionId)
            MotionDecisionLock.requireNoActiveSystemicConsensus(motionId)
            MotionDecisionLock.requireNoOpenVote(motionId)
            null
        } catch (_: ConflictException) {
            TenderAwardMotionIneligibility.DECISION_STARTED
        }
    }

    /** Amendments in these statuses can never change the motion text any more. */
    private val INEFFECTIVE_AMENDMENT_STATUSES: Set<MotionStatus> =
        setOf(MotionStatus.REJECTED, MotionStatus.REJECTED_PRELIMINARY, MotionStatus.WITHDRAWN)

    /** An award motion that is registered but not decided yet. */
    val AWARD_MOTION_OPEN_STATUSES: Set<MotionStatus> = setOf(MotionStatus.SUBMITTED, MotionStatus.REVIEWED, MotionStatus.SCHEDULED)

    /** A previously registered award motion may be replaced only when it can never decide anything any more. */
    val REPLACEABLE_AWARD_MOTION_STATUSES: Set<MotionStatus> =
        setOf(MotionStatus.REJECTED, MotionStatus.REJECTED_PRELIMINARY, MotionStatus.WITHDRAWN)
}
