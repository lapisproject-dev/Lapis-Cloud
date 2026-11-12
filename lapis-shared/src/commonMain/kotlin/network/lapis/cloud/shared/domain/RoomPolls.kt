package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/*
 * Welle V1.9.304 "Raum-Umfragen" (A) -- non-binding polls inside a video conference. See `network.lapis.cloud.server.rpc.ConferenceRoomPollService`,
 * `docs/features/room-polls.adoc` and `docs/architecture/room-polls.adoc`. Every DTO field with a default is optional on the wire; new fields
 * are always appended at the END.
 */

/** How the answers are counted: by head (democratic), by LTR weight at the start of the poll (meritocratic) or both side by side. */
@Serializable
enum class RoomPollCountingMode { HEAD_COUNT, WEIGHTED, BOTH }

@Serializable
enum class RoomPollStatus { OPEN, CLOSED }

/** Why a poll ended: the moderator, the end of the room, or the 240 minute limit. */
@Serializable
enum class RoomPollCloseReason { MANUAL, ROOM_ENDED, TIMEOUT }

/** The two groups the result is split by. "All together" is the third column and has no enum value (`group == null`). */
@Serializable
enum class RoomPollVoterGroup { MEMBERS, GUESTS_AND_FRIENDS }

/** Why the split by group is not shown. */
@Serializable
enum class RoomPollSplitWithheldReason { GROUP_BELOW_MINIMUM, }

/** Why a question or an option text is refused (client and server use the same check). */
@Serializable
enum class RoomPollTextProblem { CONTROL_CHARACTER, BIDI_CONTROL, ANGLE_BRACKET }

/** The server-only data packet topic that tells the room "a poll started or ended"; the payload is empty and never read. */
const val ROOM_POLL_TOPIC = "lapis-room-poll"
const val ROOM_POLL_MAX_PAYLOAD_BYTES = 16

object RoomPollRules {
    const val MIN_QUESTION = 3
    const val MAX_QUESTION = 300
    const val MIN_OPTIONS = PollRules.MIN_OPTIONS
    const val MAX_OPTIONS = PollRules.MAX_OPTIONS
    const val MAX_OPTION_LENGTH = 80

    /** Fewer answers per group (and fewer in total) and no figures are shown. */
    const val MIN_ANSWERS_PER_GROUP = PollRules.MIN_RESPONSES_FOR_RESULT

    const val MAX_OPEN_MINUTES = 240L
    const val RESULT_VISIBLE_HOURS = 12L
    const val CLIENT_POLL_MS = 5_000
    const val LEADERSHIP_POLL_MS = 4_000
    const val VIEWER_VERSION_POLL_MS = 5_000

    fun normalize(raw: String): String = PollRules.normalizeText(raw)

    /** `null` = fine. Checked on the RAW input, before normalisation. */
    fun rejectReason(raw: String): RoomPollTextProblem? {
        for (ch in raw) {
            val code = ch.code
            when {
                code < 0x20 || code in 0x7F..0x9F -> return RoomPollTextProblem.CONTROL_CHARACTER
                code in 0x202A..0x202E || code in 0x2066..0x2069 || code in 0x200B..0x200F || code == 0xFEFF || code == 0x061C ->
                    return RoomPollTextProblem.BIDI_CONTROL
                ch == '<' || ch == '>' -> return RoomPollTextProblem.ANGLE_BRACKET
            }
        }
        return null
    }

    /** Returns the normalised input or throws [IllegalArgumentException] (the server maps that to a bad request). */
    fun validate(input: RoomPollStartInput): RoomPollStartInput {
        require(rejectReason(input.question) == null) { "Question contains forbidden characters" }
        require(input.options.all { rejectReason(it) == null }) { "Option contains forbidden characters" }
        val question = normalize(input.question)
        // The minimum counts code points like the database CHECK (CHAR_LENGTH); UTF-16 units would let "😀😀" pass and fail on PostgreSQL.
        // The maximum stays in UTF-16 units: that is never more than the code points, so the CHECK cannot be exceeded either.
        val questionInRange = question.length <= MAX_QUESTION && PublicTextRules.codePointCount(question) >= MIN_QUESTION
        require(questionInRange) { "Question length out of range" }
        val options = input.options.map { normalize(it) }
        require(options.size in MIN_OPTIONS..MAX_OPTIONS) { "Number of options out of range" }
        require(options.all { it.length in 1..MAX_OPTION_LENGTH }) { "Option length out of range" }
        require(options.map { PollRules.optionKey(it) }.toSet().size == options.size) { "Options must be distinct" }
        return input.copy(question = question, options = options)
    }
}

@Serializable
data class RoomPollStartInput(
    val roomId: String,
    val question: String,
    val options: List<String>,
    val countingMode: RoomPollCountingMode = RoomPollCountingMode.BOTH,
)

@Serializable
data class RoomPollOptionDto(
    val id: String,
    val position: Int,
    val text: String,
)

/**
 * One column of the result. [group] `null` is "all together". Only whole percent shares, in option position order; never a weight sum.
 */
@Serializable
data class RoomPollGroupResultDto(
    val group: RoomPollVoterGroup? = null,
    val answerCount: Int,
    val headSharesPercent: List<Int> = emptyList(),
    val weightedAvailable: Boolean = false,
    val weightedSharesPercent: List<Int> = emptyList(),
    val weightedWithheldReason: PollWeightedWithheldReason? = null,
)

@Serializable
data class RoomPollResultDto(
    val totalAnswerCount: Int,
    /** `totalAnswerCount >= 5`. Below that no figures at all are shown. */
    val resultAvailable: Boolean,
    /** Both groups have at least 5 answers each. Otherwise neither group is shown (no group size leaks, no subtraction is possible). */
    val splitAvailable: Boolean,
    val splitWithheldReason: RoomPollSplitWithheldReason? = null,
    val members: RoomPollGroupResultDto? = null,
    val guestsAndFriends: RoomPollGroupResultDto? = null,
    val all: RoomPollGroupResultDto? = null,
    /** Where the LTR-weighted shares are shown: [RoomPollVoterGroup.MEMBERS], or `null` = in the "all together" column. */
    val weightedShownIn: RoomPollVoterGroup? = null,
)

@Serializable
data class RoomPollDto(
    val id: String,
    val question: String,
    val options: List<RoomPollOptionDto>,
    val countingMode: RoomPollCountingMode,
    val status: RoomPollStatus,
    val closeReason: RoomPollCloseReason? = null,
    /** Always `false`: a room poll is a mood picture, never a resolution. */
    val binding: Boolean = false,
)

@Serializable
data class RoomPollStateDto(
    val roomId: String,
    val poll: RoomPollDto? = null,
    val voted: Boolean = false,
    val canAnswer: Boolean = false,
    val isLeadership: Boolean = false,
    /** Leadership only: the exact number of answers so far. */
    val answerCount: Int? = null,
    /** Always `null` since the anonymity fix: an interim result next to the final one would isolate the last answers. Kept for wire compatibility. */
    val interim: RoomPollResultDto? = null,
    val interimAnswerCount: Int? = null,
    /** Only for a CLOSED poll. */
    val result: RoomPollResultDto? = null,
    val version: String,
)

@Serializable
data class RoomPollVersionDto(
    val changed: Boolean,
    val version: String? = null,
)
