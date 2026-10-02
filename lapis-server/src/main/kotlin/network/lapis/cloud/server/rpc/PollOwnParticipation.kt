package network.lapis.cloud.server.rpc

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.shared.domain.PollParticipationDto
import network.lapis.cloud.shared.domain.PollStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.30 -- the single place the "did I respond / may I respond" lookup lives (pattern of
 * [ElectionOwnParticipation]). Only the CALLER'S OWN `poll_participation` rows are ever read -- never
 * anybody else's -- with ONE `inList` query regardless of `pollRows.size`.
 */
internal object PollOwnParticipation {
    /**
     * MUST run inside a transaction. [eligible] is the caller's CURRENT active-membership state (no
     * snapshot is taken for polls); it is computed once by the caller.
     */
    fun load(
        pollRows: List<ResultRow>,
        memberId: Uuid,
        eligible: Boolean,
        wallNow: LocalDateTime,
    ): Map<Uuid, PollParticipationDto> {
        if (pollRows.isEmpty()) return emptyMap()
        val ids = pollRows.map { it[PollTable.id] }
        val responded: Set<Uuid> =
            PollParticipationTable
                .selectAll()
                .where { (PollParticipationTable.pollId inList ids) and (PollParticipationTable.memberId eq memberId) }
                .map { it[PollParticipationTable.pollId] }
                .toSet()
        return pollRows.associate { row ->
            val id = row[PollTable.id]
            val hasResponded = id in responded
            id to
                PollParticipationDto(
                    pollId = id.toString(),
                    eligible = eligible,
                    hasResponded = hasResponded,
                    canRespond = eligible && !hasResponded && row.effectivePollStatus(wallNow) == PollStatus.OPEN,
                )
        }
    }
}
