package network.lapis.cloud.server.tender

import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import kotlin.uuid.Uuid

/*
 * V1.9.201 -- THE one place that knows who has a conflict of interest on a tender. "Conflicted" means: the member has ANY bid row on the tender,
 * a withdrawn one included (withdrawing tactically must not lift the conflict). A conflicted member may neither award, nor accept, nor register
 * the award motion, whatever their role, and on the award motion they are excluded from the right to vote and from both sides of the quorum.
 * No other file may copy this definition. All functions run inside an open `transaction {}`.
 */

/** Refusal text of the election and systemic-consensus paths: an award motion is decided by the quorum or the meritocratic vote only. */
internal const val TENDER_AWARD_MOTION_PATH_REFUSED =
    "The award motion of a tender can only be decided by the committee quorum or a meritocratic vote"

/**
 * Every bidder (any bid status) of the tender whose `award_motion_id` is [motionId]; empty when the motion is no award motion. The governance
 * code subtracts this set from the electorate and the quorum; it never calls the electorate function again.
 */
internal fun tenderConflictedMemberIdsForMotion(motionId: Uuid): Set<Uuid> {
    val tenderId = TenderStore.idByAwardMotion(motionId) ?: return emptySet()
    return TenderStore.bidderIds(tenderId)
}

internal fun isTenderAwardMotion(motionId: Uuid): Boolean = TenderStore.idByAwardMotion(motionId) != null

/** [ForbiddenException] when [memberId] has any bid row on the tender. */
internal fun requireNotTenderBidder(
    tenderId: Uuid,
    memberId: Uuid,
) {
    if (TenderStore.hasAnyBid(tenderId = tenderId, memberId = memberId)) throw ForbiddenException()
}

/** [ConflictException] when [motionId] is the award motion of a tender (used by the election and systemic-consensus paths). */
internal fun requireNotTenderAwardMotion(motionId: Uuid) {
    if (isTenderAwardMotion(motionId)) throw ConflictException(TENDER_AWARD_MOTION_PATH_REFUSED)
}
