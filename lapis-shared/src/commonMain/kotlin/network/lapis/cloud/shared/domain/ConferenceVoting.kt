package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * V1.9.24 "Abstimmen im Konferenzraum", wave 1 -- which kind of ballot a [RoomBallotDto] describes.
 * [ELECTION] (V1.9.24) and [VOTE] (V1.9.27, meritocratic vote) are populated; [CONSENSUS] is RESERVED
 * (systemic consensing is a project of its own) and never filled.
 */
@Serializable
enum class RoomBallotKind { ELECTION, VOTE, CONSENSUS }

/**
 * Neutral room-level ballot status, deliberately decoupled from [ElectionStatus]/`VoteStatus` so later
 * waves can map other ballot kinds onto the same three values. Mapping for elections:
 * [ElectionStatus.OPEN] -> [OPEN], [ElectionStatus.CLOSED] -> [CLOSED_AWAITING_TALLY],
 * [ElectionStatus.TALLIED] -> [DECIDED]. All other election states are never delivered.
 *
 * Mapping for meritocratic votes (V1.9.27): `OPEN` -> [OPEN]; `CLOSED` -> [DECIDED] (the result is computed
 * while closing, so [CLOSED_AWAITING_TALLY] never occurs for a vote); `ABORTED` is never delivered.
 */
@Serializable
enum class RoomBallotStatus { OPEN, CLOSED_AWAITING_TALLY, DECIDED }

/** One option of a meritocratic vote shown in the room. [label] is untrusted free text. */
@Serializable
data class RoomBallotOptionDto(
    val id: String,
    val label: String,
    val position: Int,
)

/**
 * One ballot visible in a conference room.
 *
 * **Untrusted free text**: [motionTitle] and [title] are member-authored free text. The server
 * delivers the raw text; every client MUST escape/sanitise it (e.g. `textContent`, never `innerHTML`).
 *
 * **Ballot secrecy**: the DTO carries ONLY the calling account's own flags ([ownEligible],
 * [ownHasVoted]) -- no counters, no timestamps, no selections, no receipts, no names or member ids.
 * For a non-member caller ([MemberStatusSets.NON_MEMBER]) [ownEligible] is always `false`.
 *
 * V1.9.27 (kind [RoomBallotKind.VOTE]): a meritocratic vote is never secret (`secret = false`). The DTO
 * NEVER carries `basketTotalLtr`, `stakeLtr`, `settledLtr`, `secondPriceLtr`, names, timestamps, the
 * caller's own stake or any count of bids -- only [options] (label text) and, once decided, [winnerOptionId].
 */
@Serializable
data class RoomBallotDto(
    val kind: RoomBallotKind,
    val id: String,
    val motionId: String,
    val motionTitle: String,
    val title: String,
    val status: RoomBallotStatus,
    val secret: Boolean,
    val ownEligible: Boolean,
    val ownHasVoted: Boolean,
    /** VOTE only (ordered by position); always empty for ELECTION. Untrusted free text in `label`. */
    val options: List<RoomBallotOptionDto> = emptyList(),
    /** VOTE + DECIDED only; null on a tie and for every ELECTION. */
    val winnerOptionId: String? = null,
)

/**
 * Result of `IConferenceService.getRoomVotingState`. [bound] is `false` iff the room has no Sitzung
 * bound (`conference_room.meeting_id IS NULL`); [ballots] is then empty and the client shows a
 * moderation hint. [truncated] is `true` when the server-side cap of 20 ballots applied.
 * The Sitzung id is intentionally absent -- the client already knows it via `ConferenceRoomDto.meetingId`.
 */
@Serializable
data class RoomVotingStateDto(
    val roomId: String,
    val bound: Boolean,
    val ballots: List<RoomBallotDto>,
    val truncated: Boolean,
)
