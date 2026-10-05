package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import network.lapis.cloud.shared.rpc.ConflictException

/**
 * Demokratische Electionen (V0.2.4): one-person-one-vote elections/ballots, structurally distinct
 * from [VoteDto] (LTR-weighted eBay/Vickrey basket auction, V0.2.3) -- see
 * `network.lapis.cloud.server.rpc.ElectionService` KDoc for the full lifecycle and
 * `03 Bereiche/Lapis Cloud/Demokratische Electionen.md` for the concept document this implements.
 *
 * [LIST_VOTE]/[RANKED_CHOICE] are reserved for forward compatibility (DTO/DB shape only, so a
 * later wave does not need another migration) but are rejected by
 * `ElectionService.openElection` in this wave with a [ConflictException] -- D'Hondt/Sainte-Laguë and
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
 * [ReceiptVerificationDto.optionLabel] staying `null`.
 *
 * V1.9.53: for a secret election with fewer than [DisclosureRules.MIN_ANONYMOUS_RESPONSES] ballots
 * [voteCount] is always `0` and [ElectionDto.figuresWithheld] is set -- never evaluate it without that flag.
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
    /**
     * Legacy display value. When [requiredMajorityNumerator]/[requiredMajorityDenominator] are set it is
     * only `ceil(numerator * 100 / denominator)` for old clients; the exact fraction is authoritative.
     */
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
    /** Exact required majority as a reduced fraction; both `null` for elections created before V1.9.23. */
    val requiredMajorityNumerator: Int? = null,
    val requiredMajorityDenominator: Int? = null,
    /**
     * V1.9.53: `true` iff the election is TALLIED, secret and has fewer than
     * [DisclosureRules.MIN_ANONYMOUS_RESPONSES] ballots -- every [ElectionOptionDto.voteCount] is then `0`
     * and means "not disclosed", not "no votes". Always `false` before the tally.
     */
    val figuresWithheld: Boolean = false,
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
    /**
     * Exact required majority (V1.9.23): `ja * denominator >= numerator * (ja + nein)`. Both or neither
     * must be set; when set they take precedence over [requiredMajorityPercent]. Constraints, enforced
     * by the server and the database: `1 <= numerator <= denominator <= 100` and at least one half
     * (`2 * numerator >= denominator`). Not allowed for [ElectionType.MULTI_CHOICE].
     */
    val requiredMajorityNumerator: Int? = null,
    val requiredMajorityDenominator: Int? = null,
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
    /** Blank for a secret election (V1.9.23): a ballot id would make the later ballot list linkable to the voter. */
    val id: String,
    val castAt: LocalDateTime,
    val receiptCode: String?,
)

/**
 * Transparency read of the ballots of an open-ballot (non-secret) election: named, with the chosen option
 * labels. V1.9.46: never produced for a [ElectionDto.secret] election -- a secret election delivers no
 * single ballots at all (the fields stay nullable for serialization compatibility). The count is in
 * `ElectionParticipationDto.ballotCount`, the result in [ElectionResultDto].
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
    /**
     * V1.9.53: `true` iff the election is secret and has fewer than [minimumResponses] ballots. [perOptionVotes]
     * is then empty; winners, [tie] and [majorityMet] stay (decided on the full data). Decide on this flag, never
     * on the emptiness of [perOptionVotes]. No role exception.
     */
    val figuresWithheld: Boolean = false,
    val minimumResponses: Int = DisclosureRules.MIN_ANONYMOUS_RESPONSES,
)

/**
 * Result of checking a receipt code (V1.9.54: receipt-freeness).
 *
 * A receipt proves **inclusion only**: [found] says the ballot with this code exists in this election, [counted]
 * says it was part of the tally (`found` and the election is TALLIED). It never proves the **content** of the
 * ballot: [optionLabel] is **always `null` for a secret election**, in every status. Otherwise a receipt would be
 * transferable proof of how someone voted, which makes coercion and vote buying possible.
 *
 * The price of this: nobody can check individually that their own option stands in the result as chosen; that
 * stays trust in the server and the election board (see `elections-integrity.adoc`). For an open (non-secret)
 * election the labels are still returned after TALLIED, as before -- there the ballot is public anyway.
 */
@Serializable
data class ReceiptVerificationDto(
    val found: Boolean,
    /** Secret election: ALWAYS `null` (V1.9.54). Open election: the label(s) after TALLIED. */
    val optionLabel: String?,
    /** `found` and the election is TALLIED: this ballot was part of the tally (inclusion only, never its content). */
    val counted: Boolean = false,
)

/**
 * The calling member's own participation state plus public counters for one Election (V1.9.22).
 * Never contains a ballot selection, a receipt code or a timestamp -- the only personal parts are
 * [eligible], [hasVoted], [isElectionBoardMember] and [hasApprovedTally], all about the caller.
 *
 * [eligible] and [eligibleCount] are `null` while `votingOpenedAt == null` (no electorate snapshot
 * exists yet). [isElectionBoardMember] is strict: no BOARD/ADMIN bypass, mirroring `approveTally`.
 */
@Serializable
data class ElectionParticipationDto(
    val electionId: String,
    val eligible: Boolean?,
    val hasVoted: Boolean,
    val isElectionBoardMember: Boolean,
    val hasApprovedTally: Boolean,
    val tallyApprovalCount: Int,
    val tallyThreshold: Int,
    val electionBoardSize: Int,
    val eligibleCount: Int?,
    val ballotCount: Int,
)
