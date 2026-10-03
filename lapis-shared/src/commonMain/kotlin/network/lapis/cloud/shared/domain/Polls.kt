package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.9.30 "Umfragen auf LTR-Basis" (Server) -- see `network.lapis.cloud.server.rpc.PollService`
 * KDoc and `docs/architecture/polls.adoc`.
 *
 * The wire value is always the EFFECTIVE status: a stored [OPEN] poll whose deadline has passed is
 * reported as [CLOSED] (lazy expiry -- the stored row is never rewritten by a read).
 */
@Serializable
enum class PollStatus { OPEN, CLOSED, ABORTED }

/**
 * V1.9.41 -- the kind of a poll. [SINGLE_CHOICE] is the classic LTR-weighted single choice; the two consensus kinds
 * are rated by resistance (0..[PollRules.SK_SCALE_MAX]) per option, carry no LTR weight and are always anonymous.
 */
@Serializable
enum class PollKind { SINGLE_CHOICE, SK_DECISION, SK_PRIORITY }

val PollKind.isConsensus: Boolean get() = this != PollKind.SINGLE_CHOICE

/**
 * Pure, shared validation constants and text normalisation -- reused by the V1.9.31 UI for local
 * checks, so client and server cannot drift apart.
 */
object PollRules {
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 10
    const val MAX_QUESTION_LENGTH = 500
    const val MAX_DESCRIPTION_LENGTH = 2000
    const val MAX_OPTION_LENGTH = 200

    /** Fewer responses and not even the head count is disclosed (a unanimous result of 3 would reveal all 3 answers). */
    const val MIN_RESPONSES_FOR_RESULT = 5

    /** The weighted result needs at least this many responses with weight greater than zero ... */
    const val MIN_WEIGHTED_RESPONSES = 5

    /** ... and every option that received any weighted vote needs at least this many of them. */
    const val MIN_WEIGHTED_GROUP_SIZE = 3

    /** Maximum number of effectively OPEN polls at any time (enforced under a global lock). */
    const val MAX_OPEN_POLLS = 20

    /** Maximum number of effectively OPEN polls per creator, so one seat holder cannot exhaust the global cap. */
    const val MAX_OPEN_POLLS_PER_CREATOR = 5

    /** Maximum number of polls one creator may create within [CREATE_RATE_WINDOW_HOURS] (aborted ones count). */
    const val MAX_POLLS_CREATED_PER_WINDOW = 10
    const val CREATE_RATE_WINDOW_HOURS = 24

    const val MAX_LIST_LIMIT = 100
    const val DEFAULT_LIST_LIMIT = 50
    const val MAX_LIST_OFFSET = 10_000
    const val MAX_PARTICIPATION_BATCH = 100

    /** A deadline must lie at least this far in the future (catches a typo with an already-past deadline) ... */
    const val MIN_DEADLINE_LEAD_MINUTES = 15

    /** ... and at most this far (no "eternal" polls). */
    const val MAX_DEADLINE_DAYS = 365

    /** V1.9.41: resistance scale 0..[SK_SCALE_MAX] of the consensus kinds. */
    const val SK_SCALE_MAX = 10
    const val MAX_EXPLANATION_LENGTH = SystemicConsensusRules.MAX_RATIONALE_LENGTH
    const val EXPLANATION_COUNTER_FROM = 800
    const val PASSIVE_OPTION_POSITION = 10

    /** Stored only, never displayed: the client translates the passive option at its flag. */
    const val PASSIVE_OPTION_STORED_LABEL = "No change"

    fun normalizeExplanation(raw: String?): PublicTextNormalization = SystemicConsensusRules.normalizeRationale(raw)

    private val whitespaceRun = Regex("\\s+")

    /** Trim and collapse every internal whitespace run to a single space. */
    fun normalizeText(raw: String): String = raw.trim().replace(whitespaceRun, " ")

    /** Duplicate-detection key of an already-normalised option text. */
    fun optionKey(normalized: String): String = normalized.lowercase()
}

@Serializable
data class PollCreateInput(
    val question: String,
    val description: String? = null,
    val options: List<String>,
    val closesAt: LocalDateTime? = null,
    val kind: PollKind = PollKind.SINGLE_CHOICE,
    /** Empty OR exactly `options.size` entries; non-empty only allowed for the consensus kinds. */
    val optionExplanations: List<String?> = emptyList(),
)

@Serializable
data class PollOptionDto(
    val id: String,
    val position: Int,
    val text: String,
    /** UNTRUSTED text of the creator. */
    val explanation: String? = null,
    val isPassive: Boolean = false,
)

/**
 * A NON-BINDING opinion poll (Stimmungsbild), never a resolution -- [binding] is always `false`.
 * Never contains per-response data, weights, respondents, or counts before the poll is closed.
 */
@Serializable
data class PollDto(
    val id: String,
    val question: String,
    val description: String?,
    /** Sorted by position. */
    val options: List<PollOptionDto>,
    /** The EFFECTIVE status, see [PollStatus]. */
    val status: PollStatus,
    val createdAt: LocalDateTime,
    val createdByDisplayName: String,
    val closesAt: LocalDateTime?,
    /** The manual close/abort instant, or [closesAt] if the deadline expired. */
    val closedAt: LocalDateTime?,
    val binding: Boolean = false,
    /** `status == CLOSED && responseCount >= PollRules.MIN_RESPONSES_FOR_RESULT`. */
    val resultAvailable: Boolean,
    /** `null` while OPEN and when ABORTED (never disclosed). */
    val responseCount: Int?,
    /** Caller may close/abort -- a UI hint only, the server re-checks on every call. */
    val canManage: Boolean,
    val kind: PollKind = PollKind.SINGLE_CHOICE,
)

/** The calling member's OWN participation state for one poll -- never anybody else's. */
@Serializable
data class PollParticipationDto(
    val pollId: String,
    /** The caller is CURRENTLY an ACTIVE member (no snapshot is taken). */
    val eligible: Boolean,
    val hasResponded: Boolean,
    /** `eligible && !hasResponded && status == OPEN`. */
    val canRespond: Boolean,
)

@Serializable
data class PollResponseInput(
    val pollId: String,
    val optionId: String,
)

/** Why the LTR-weighted result of a closed poll is withheld, see `PollTally`. */
@Serializable
enum class PollWeightedWithheldReason {
    TOO_FEW_RESPONSES,
    ZERO_TOTAL_WEIGHT,
    TOO_FEW_WEIGHTED_RESPONSES,
    SMALL_WEIGHTED_GROUP,
}

@Serializable
data class PollHeadOptionResultDto(
    val optionId: String,
    val count: Int,
)

/** Whole-percent share, the shares of one result always sum to exactly 100. NO absolute LTR sums are ever disclosed. */
@Serializable
data class PollWeightedOptionResultDto(
    val optionId: String,
    val sharePercent: Int,
)

@Serializable
data class PollResultDto(
    val pollId: String,
    val responseCount: Int,
    val headResultAvailable: Boolean,
    /** Empty if [headResultAvailable] is `false`. */
    val headResult: List<PollHeadOptionResultDto>,
    val weightedResultAvailable: Boolean,
    val weightedWithheldReason: PollWeightedWithheldReason?,
    /** Empty if [weightedResultAvailable] is `false`. */
    val weightedResult: List<PollWeightedOptionResultDto>,
    val kind: PollKind = PollKind.SINGLE_CHOICE,
    val ratingResultAvailable: Boolean = false,
    val ratingResult: PollRatingResultDto? = null,
)

/** V1.9.41: the complete resistance vector of the caller over ALL options of a consensus poll (passive option included). */
@Serializable
data class PollRatingInput(
    val pollId: String,
    val ratings: Map<String, Int>,
)

@Serializable
enum class PollDecisionOutcome { OPTION_WINS, NO_CHANGE_WINS, NO_CLEAR_RESULT }

@Serializable
data class PollRatingOptionResultDto(
    val optionId: String,
    /** Competition rank by cumulative resistance: 1,2,2,4. */
    val rank: Int,
    val tied: Boolean,
    val cumulativeResistance: Int,
    val meanResistance: Double,
    val maxResistance: Int,
    val topValueCount: Int,
    val consensusIndex: Double,
    val strongObjection: Boolean,
    /** Complete 0..SK_SCALE_MAX, gaps as 0. */
    val distribution: Map<Int, Int>,
)

@Serializable
data class PollRatingResultDto(
    /** Sorted by the server. */
    val options: List<PollRatingOptionResultDto>,
    /** Only for SK_DECISION. */
    val outcome: PollDecisionOutcome? = null,
    val winnerOptionId: String? = null,
    val tieAtLowest: Boolean = false,
    val decidedByLowestMax: Boolean = false,
)
