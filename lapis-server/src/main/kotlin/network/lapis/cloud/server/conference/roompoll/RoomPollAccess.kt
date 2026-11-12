package network.lapis.cloud.server.conference.roompoll

import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.encounter.ConferenceModeratorAuthority
import network.lapis.cloud.server.encounter.EncounterRoomGuard
import network.lapis.cloud.server.events.EventConferenceGuard
import network.lapis.cloud.server.rpc.requireRoomEntryAuthorization
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** The one server-side-only reason of EVERY refused room-poll call (Kilua transmits only the exception TYPE: no existence oracle). */
internal const val ROOM_POLL_UNAVAILABLE = "Room poll not available"

/** How a caller may reach the poll of a room. */
internal enum class RoomPollAudience {
    /** An open participation in the room. */
    PARTICIPANT,

    /** Not in the room, but the room streams (LIVE / PAUSED) and the account passes the room entry check (wave B shows the poll there). */
    STREAM_VIEWER,

    /** The room has ended; only a read of the result of the last poll (12 hours) is allowed. */
    ENDED_READER,
}

/**
 * The single gate of every room-poll call. Must run INSIDE the caller's open `transaction {}`. Every refusal throws the SAME
 * [ForbiddenException] and the same queries run on every path (the participation check also runs for an unknown room), so neither the
 * message nor the work tells a caller why it was refused.
 *
 * Rights come from the shared authorities and nowhere else: [ConferenceModeratorAuthority] for leadership (room creator while still an
 * organization member, BOARD, ADMIN) and [requireRoomEntryAuthorization] for "may enter this room at all".
 */
internal object RoomPollAccess {
    fun audience(
        roomRow: ResultRow?,
        current: CurrentMember,
        allowEnded: Boolean = false,
        requirePresence: Boolean = true,
    ): RoomPollAudience {
        val roomId = roomRow?.get(ConferenceRoomTable.id)
        // Same work for every path: the participation lookup runs even for an unknown room.
        val participating = hasOpenParticipation(roomId = roomId ?: Uuid.NIL, memberId = current.memberId)
        if (roomRow == null) throw denied()
        if (EncounterRoomGuard.isEncounterRoom(roomRow) || EventConferenceGuard.isEventRoom(roomRow)) throw denied()
        val status =
            try {
                requireRoomEntryAuthorization(roomRow = roomRow, current = current)
            } catch (e: ForbiddenException) {
                throw denied()
            }
        requireResponderStatus(status)
        val ended = roomRow[ConferenceRoomTable.endedAt] != null
        if (ended) {
            if (allowEnded) return RoomPollAudience.ENDED_READER
            throw denied()
        }
        // Ending a poll needs the authority, not the presence (a moderator who dropped out can still end it).
        if (participating || !requirePresence) return RoomPollAudience.PARTICIPANT
        if (isStreaming(roomId!!)) return RoomPollAudience.STREAM_VIEWER
        throw denied()
    }

    fun isLeadership(
        roomRow: ResultRow,
        current: CurrentMember,
    ): Boolean = ConferenceModeratorAuthority.isModerator(row = roomRow, current = current)

    /** The status must be one that may answer a room poll at all. */
    fun requireResponderStatus(status: MemberStatus) {
        if (status !in MemberStatusSets.ROOM_POLL_RESPONDER) throw denied()
    }

    fun denied() = ForbiddenException(ROOM_POLL_UNAVAILABLE)

    private fun hasOpenParticipation(
        roomId: Uuid,
        memberId: Uuid,
    ): Boolean =
        ConferenceParticipationTable
            .selectAll()
            .where {
                (ConferenceParticipationTable.roomId eq roomId) and
                    (ConferenceParticipationTable.memberId eq memberId) and
                    ConferenceParticipationTable.leftAt.isNull()
            }.limit(1)
            .any()

    private fun isStreaming(roomId: Uuid): Boolean =
        ConferenceStreamTable
            .selectAll()
            .where {
                (ConferenceStreamTable.roomId eq roomId) and
                    (ConferenceStreamTable.status inList listOf(ConferenceStreamStatus.LIVE, ConferenceStreamStatus.PAUSED))
            }.limit(1)
            .any()
}
