package network.lapis.cloud.server.dsgvo

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import network.lapis.cloud.server.db.generated.ConferenceRoomPollVotedTable
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.304 "Raum-Umfragen" -- owns [ConferenceRoomPollVotedTable] only: the marker "this member has answered poll X", kept while the poll is
 * open (so nobody answers twice) and deleted when it ends. The ANSWER rows carry no member at all (structurally anonymous, see
 * `82-conference-room-poll.kuml.kts`), so there is nothing to export or erase for them.
 *
 * **Export**: the ids of the polls the member has a marker for (that is, polls still running). **Erasure**: the markers are deleted in EVERY
 * [ErasureMode]. That is safe: erasure keeps the member's status, so it is the answer call that refuses an erased
 * (`anonymized_at` set) member ([network.lapis.cloud.server.rpc.ConferenceRoomPollService]); the answer rows never pointed at the person, so no
 * count changes and nobody can answer twice.
 */
object RoomPollPersonalData : MemberPersonalDataContributor {
    override val sectionKey = "roomPolls"
    override val displayName = "Umfragen in Videokonferenzen"
    override val coveredTables = setOf(ConferenceRoomPollVotedTable)

    override fun exportMember(memberId: Uuid): JsonElement {
        val pollIds =
            ConferenceRoomPollVotedTable
                .selectAll()
                .where { ConferenceRoomPollVotedTable.memberId eq memberId }
                .map { it[ConferenceRoomPollVotedTable.pollId].toString() }
        return buildJsonObject {
            putJsonArray("openPollsAnswered") { pollIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
    }

    override fun eraseMember(
        memberId: Uuid,
        mode: ErasureMode,
    ): List<TableErasureOutcome> {
        val deleted = ConferenceRoomPollVotedTable.deleteWhere { ConferenceRoomPollVotedTable.memberId eq memberId }
        return listOf(TableErasureOutcome(table = "conference_room_poll_voted", rowsDeleted = deleted))
    }
}
