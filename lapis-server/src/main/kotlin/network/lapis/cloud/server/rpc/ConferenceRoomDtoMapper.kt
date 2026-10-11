package network.lapis.cloud.server.rpc

import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * The one mapping of a `conference_room` row to [ConferenceRoomDto], shared by [ConferenceService] and [ConferenceModerationService]
 * (V1.9.108) so a new DTO field cannot be filled in one service and forgotten in the other. Must run inside an open transaction.
 */
internal object ConferenceRoomDtoMapper {
    fun toDto(
        row: ResultRow,
        callerId: Uuid,
        liveParticipantCount: Int,
    ): ConferenceRoomDto {
        val creatorId = row[ConferenceRoomTable.createdByMemberId]
        return ConferenceRoomDto(
            id = row[ConferenceRoomTable.id].toString(),
            title = row[ConferenceRoomTable.title],
            description = row[ConferenceRoomTable.description],
            livekitRoomName = row[ConferenceRoomTable.livekitRoomName],
            createdByMemberId = creatorId.toString(),
            createdByDisplayName = displayName(creatorId),
            createdAt = row[ConferenceRoomTable.createdAt],
            endedAt = row[ConferenceRoomTable.endedAt],
            active = row[ConferenceRoomTable.endedAt] == null,
            maxParticipants = row[ConferenceRoomTable.maxParticipants],
            liveParticipantCount = liveParticipantCount,
            myRole = if (creatorId == callerId) ConferenceRole.MODERATOR else ConferenceRole.PARTICIPANT,
            allowFederationGuests = row[ConferenceRoomTable.allowFederationGuests],
            meetingId = row[ConferenceRoomTable.meetingId]?.toString(),
            meetingTitle = row[ConferenceRoomTable.meetingId]?.let { meetingTitle(it) },
            muteOnJoin = row[ConferenceRoomTable.muteOnJoin],
            participantsMayUnmute = row[ConferenceRoomTable.participantsMayUnmute],
        )
    }

    private fun displayName(memberId: Uuid): String =
        MemberTable
            .selectAll()
            .where {
                MemberTable.id eq memberId
            }.single()[MemberTable.displayName]

    private fun meetingTitle(meetingId: Uuid): String? =
        MeetingTable
            .selectAll()
            .where { MeetingTable.id eq meetingId }
            .singleOrNull()
            ?.get(MeetingTable.title)
}
