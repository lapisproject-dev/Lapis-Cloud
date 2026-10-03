package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollResponseRatingTable
import network.lapis.cloud.server.db.generated.PollResponseTable
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.rpc.BadRequestException
import org.jetbrains.exposed.v1.jdbc.insert
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Validation and storage of one consensus-poll rating vector (V1.9.41). Pure helpers of [PollService.castPollRatings]; they
 * run inside the caller's transaction, after the poll row lock. No LTR is read or written.
 */
internal object PollRatingCast {
    /**
     * Parses [ratings] into a map keyed by option id. The key set must equal [optionIds] exactly (no gap, no foreign id, no
     * duplicate through upper/lower-case spelling) and every value must lie in `0..SK_SCALE_MAX`.
     */
    fun validate(
        ratings: Map<String, Int>,
        optionIds: Set<Uuid>,
    ): Map<Uuid, Int> {
        val parsed =
            ratings.entries.associate { (key, value) ->
                val id = runCatching { Uuid.parse(key) }.getOrElse { throw BadRequestException("Invalid option") }
                id to value
            }
        if (parsed.size != ratings.size) throw BadRequestException("Duplicate option")
        if (parsed.keys != optionIds) throw BadRequestException("Every option must be rated exactly once")
        if (parsed.values.any { it !in 0..PollRules.SK_SCALE_MAX }) throw BadRequestException("Invalid rating")
        return parsed
    }

    /**
     * Writes participation (WHO), one response row without option and weight 0 (THAT), and one rating row per option in
     * position order (HOW MUCH). Random ids throughout; the three tables share no member key.
     */
    fun insert(
        pollId: Uuid,
        memberId: Uuid,
        ratingsInPositionOrder: List<Pair<Uuid, Int>>,
    ) {
        PollParticipationTable.insert {
            it[PollParticipationTable.id] = Uuid.random()
            it[PollParticipationTable.pollId] = pollId
            it[PollParticipationTable.memberId] = memberId
        }
        val responseId = Uuid.random()
        PollResponseTable.insert {
            it[PollResponseTable.id] = responseId
            it[PollResponseTable.pollId] = pollId
            it[PollResponseTable.optionId] = null
            it[weightLtr] = BigDecimal.ZERO.setScale(2)
        }
        ratingsInPositionOrder.forEach { (optionId, resistance) ->
            PollResponseRatingTable.insert {
                it[PollResponseRatingTable.id] = Uuid.random()
                it[PollResponseRatingTable.responseId] = responseId
                it[PollResponseRatingTable.optionId] = optionId
                it[PollResponseRatingTable.resistance] = resistance
            }
        }
    }
}
