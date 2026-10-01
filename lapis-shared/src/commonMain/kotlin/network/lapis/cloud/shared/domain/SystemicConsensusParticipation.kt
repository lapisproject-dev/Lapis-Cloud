package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * V1.9.28: the calling member's OWN view of one Systemic Consensus -- the single read the consensus
 * UI needs to decide which actions to offer. Only the caller's own rows are ever read; nothing here
 * reveals how anybody else (or the caller) rated.
 *
 * [eligible] is `null` while no eligibility snapshot exists for the current [round] (COLLECTION).
 * [hasRated] refers to the current round only. [canRate] mirrors the server-side checks of
 * `castResistanceBallot` (RATING, in the frozen snapshot, not yet rated, active membership) so a
 * dormant member is not shown a booth that can only fail on submit.
 */
@Serializable
data class SystemicConsensusParticipationDto(
    val systemicConsensusId: String,
    val round: Int,
    val eligible: Boolean?,
    val hasRated: Boolean,
    val canProposeOptions: Boolean,
    val canManage: Boolean,
    val canRate: Boolean,
    val eligibleCount: Int?,
    val ballotCount: Int,
)

/** One option's resistance as it appears on a verified receipt. The label is meaningless for the status quo option -- the UI translates by [isStatusQuoOption]. */
@Serializable
data class SystemicConsensusReceiptResistanceDto(
    val optionId: String,
    val isStatusQuoOption: Boolean,
    val label: String,
    val resistance: Int,
)

/**
 * Result of checking a receipt code of a secret Systemic Consensus. [round] is the round the receipt
 * was issued in. [resistances] is only present once the consensus is EVALUATED and [round] is the
 * current round ([countedInCurrentResult]) -- before that the receipt only proves that the ballot exists.
 */
@Serializable
data class SystemicConsensusReceiptVerificationDto(
    val found: Boolean,
    val round: Int?,
    val countedInCurrentResult: Boolean,
    val resistances: List<SystemicConsensusReceiptResistanceDto>?,
)
