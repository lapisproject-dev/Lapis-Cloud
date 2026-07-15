package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Demokratische Electionen (V0.2.4): one-person-one-vote elections/ballots, structurally distinct
 * from [VoteDto] (LTR-weighted eBay/Vickrey basket auction, V0.2.3) -- see
 * `network.lapis.cloud.server.rpc.ElectionService` KDoc for the full lifecycle and
 * `03 Bereiche/Lapis Cloud/Demokratische Electionen.md` for the concept document this implements.
 *
 * [LIST_VOTE]/[RANKED_CHOICE] are reserved for forward compatibility (DTO/DB shape only, so a
 * later wave does not need another migration) but are rejected by
 * `ElectionService.openElection` in this wave with a `ConflictException` -- D'Hondt/Sainte-Laguë and
 * Schulze/Ranked-Pairs/STV are each a real, non-trivial algorithm, explicitly out of scope for
 * the "standard implementation, no novel algorithm" framing of V0.2.4.
 */
@Serializable
enum class ElectionType { YES_NO, SINGLE_CHOICE, MULTI_CHOICE, LIST_VOTE, RANKED_CHOICE }

/**
 * [PREPARATION] -> ([CANDIDATE_LIST_RELEASED], personnel types only) -> [OPEN] ->
 * [CLOSED] -> [TALLIED], or [ABORTED] from any non-terminal state -- see
 * `network.lapis.cloud.server.rpc.ElectionService` for the exact transition guards.
 */
@Serializable
enum class ElectionStatus { PREPARATION, CANDIDATE_LIST_RELEASED, OPEN, CLOSED, TALLIED, ABORTED }

/** Ballot answer for [ElectionType.YES_NO] Electionen only -- personnel-type Electionen select option ids instead. */
@Serializable
enum class ElectionAnswer { YES, NO, ABSTAIN }

/**
 * A ballot-selectable option: either a fixed YES/NO/ABSTAIN row ([ElectionType.YES_NO], created
 * automatically by `openElection`) or a candidate row ([candidacyId] set, created by
 * `releaseCandidateList` from the approved Candidacies). [voteCount] is always `0` while the
 * Election has not reached [ElectionStatus.TALLIED] -- exposing a live running count while voting is
 * still open would leak a partial tally and undermine ballot secrecy, the same reasoning behind
 * [ReceiptVerificationDto.optionLabel] staying `null` before the tally runs.
 */
@Serializable
data class ElectionOptionDto(
    val id: String,
    val electionId: String,
    val label: String,
    val position: Int,
    val candidacyId: String?,
    val voteCount: Int,
)

@Serializable
data class ElectionDto(
    val id: String,
    val motionId: String,
    val meetingId: String,
    val title: String,
    val electionType: ElectionType,
    val secret: Boolean,
    val seatCount: Int,
    val targetCommitteeId: String?,
    val targetCommitteeName: String?,
    val targetRole: CommitteeRole?,
    val requiredMajorityPercent: Int,
    val status: ElectionStatus,
    val openedById: String,
    val openedByDisplayName: String,
    val openedAt: LocalDateTime,
    val candidateListApprovedAt: LocalDateTime?,
    val votingOpenedAt: LocalDateTime?,
    val votingClosedAt: LocalDateTime?,
    val tallyThreshold: Int,
    val tallyRunAt: LocalDateTime?,
    val resolutionId: String?,
    val options: List<ElectionOptionDto>,
)

/**
 * [targetCommitteeId] is required (enforced by `ElectionService.openElection`) for personnel [electionType]s
 * ([ElectionType.SINGLE_CHOICE]/[ElectionType.MULTI_CHOICE]) -- it is the Committee winners join, which may
 * differ from the Motion's own target Committee (e.g. a General Assembly-hosted Motion electing
 * the Executive Board: `motion.targetCommitteeId` is the General Assembly, `targetCommitteeId` is the
 * Executive Board). `null` for [ElectionType.YES_NO], which seats nobody.
 */
@Serializable
data class ElectionOpenInput(
    val motionId: String,
    val electionType: ElectionType,
    val secret: Boolean = true,
    val seatCount: Int = 1,
    val targetCommitteeId: String? = null,
    val targetRole: CommitteeRole? = null,
    val requiredMajorityPercent: Int = 50,
    val tallyThreshold: Int = 2,
)

@Serializable
data class CandidacyDto(
    val id: String,
    val electionId: String,
    val memberId: String,
    val memberDisplayName: String,
    val motivationText: String?,
    val submittedAt: LocalDateTime,
    val withdrawnAt: LocalDateTime?,
)

@Serializable
data class CandidacyInput(
    val motivationText: String? = null,
)

@Serializable
data class ElectionBoardMemberDto(
    val id: String,
    val electionId: String,
    val memberId: String,
    val memberDisplayName: String,
    val appointedAt: LocalDateTime,
)

/**
 * The ballot itself. For a [ElectionType.YES_NO] Election, set [answer] and leave [selectedOptionIds]
 * empty. For a personnel Election, set [selectedOptionIds] (1..`seatCount` distinct option ids) and
 * leave [answer] `null`. `ElectionService.castElectionBallot` rejects any other combination.
 */
@Serializable
data class ElectionBallotInput(
    val electionId: String,
    val answer: ElectionAnswer? = null,
    val selectedOptionIds: List<String> = emptyList(),
)

/**
 * [receiptCode] is present only when the Election is [ElectionDto.secret] -- the one time it is ever
 * returned to a caller; from then on only [network.lapis.cloud.shared.rpc.IElectionService
 * .verifyReceipt] can look up its own ballot by that code, and even then only the option label
 * once [ElectionStatus.TALLIED], never the fact of who cast it.
 */
@Serializable
data class ElectionBallotCastResultDto(
    val id: String,
    val castAt: LocalDateTime,
    val receiptCode: String?,
)

/**
 * Transparency read of ballots cast so far. For a [ElectionDto.secret] Election, [memberId]/
 * [memberDisplayName] are always `null` -- there is no member FK on the ballot row to begin with
 * in that case (see `network.lapis.cloud.server.db.tables.ElectionTables` KDoc), so this is a direct
 * reflection of the storage shape, not a filtered projection.
 *
 * [selectedOptionLabels] is `emptyList()` for a [ElectionDto.secret] Election until it reaches
 * [ElectionStatus.TALLIED] -- same pre-tally-secrecy invariant as [ElectionOptionDto.voteCount] (held
 * at `0` until the tally runs) and [ReceiptVerificationDto.optionLabel] (`null` until the tally
 * runs). Without this gate, anyone could enumerate every anonymized ballot's plaintext choice
 * while voting is still open and tally a running result themselves. Non-secret Electionen always
 * reveal the labels, since the ballot's `memberId` is already visible in the clear.
 */
@Serializable
data class ElectionBallotDto(
    val id: String,
    val electionId: String,
    val memberId: String?,
    val memberDisplayName: String?,
    val selectedOptionLabels: List<String>,
    val castAt: LocalDateTime,
)

/**
 * [majorityMet] is only meaningful for [ElectionType.YES_NO] (`null` for personnel Electionen).
 * [winnerOptionIds] is empty whenever [tie] is `true` -- a tie resolves the whole Election to "no
 * winners" (see `network.lapis.cloud.server.rpc.ElectionTally` KDoc), never a partial result.
 * For [ElectionType.SINGLE_CHOICE], [tie] is also `true` when the plurality winner fails to reach
 * `ElectionDto.requiredMajorityPercent` of the votes cast -- the concept document requires an
 * absolute majority for this Electiontyp ("ggf. Stichelection"), so a sub-majority plurality result is
 * reported the same way as a genuine seat-cutoff tie: no winner seated, signalling a runoff is
 * needed (see `network.lapis.cloud.server.rpc.ElectionService.tally`).
 */
@Serializable
data class ElectionResultDto(
    val electionId: String,
    val winnerOptionIds: List<String>,
    val tie: Boolean,
    val majorityMet: Boolean?,
    val perOptionVotes: Map<String, Int>,
)

/**
 * [optionLabel] is `null` until the Election reaches [ElectionStatus.TALLIED], even when [found] is
 * `true` -- returning a partial tally result to a receipt holder while voting is still open (or
 * closed but not yet counted) would leak information no other caller can see, defeating the point
 * of holding vote-counts back until the tally (see [ElectionOptionDto.voteCount] KDoc).
 */
@Serializable
data class ReceiptVerificationDto(
    val found: Boolean,
    val optionLabel: String?,
)
