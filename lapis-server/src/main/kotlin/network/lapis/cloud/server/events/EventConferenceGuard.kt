package network.lapis.cloud.server.events

import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.encounter.ConferenceModeratorAuthority
import network.lapis.cloud.server.rpc.EventCaptureSettingsStore
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventOnlineOutcome
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** Server-side-only reason of a rejected ordinary-conference call on an event room (Kilua transmits only the exception TYPE). */
internal const val EVENT_ROOM_UNSUPPORTED = "This function is not available in an event conference room"

/**
 * Welle V1.9.113 -- fences an EVENT conference room (a `conference_room` row that `event_conference.conference_room_id` points to) off
 * from the ordinary conference RPC surface, the way [network.lapis.cloud.server.encounter.EncounterRoomGuard] does for encounter sessions.
 *
 * **Why this is a security boundary**: the registration rule ("with a registration deadline only confirmed registrations may enter") is
 * enforced by `IEventService.joinEventOnline` at token-minting time. `ConferenceService.joinRoom` would mint a publishing token for ANY
 * active member who has the room id, so an event room must never be reachable through it. The room id is also never part of an event DTO
 * and the room is filtered out of `listActiveRooms`. `EventConferenceGuardTripwireTest` fails the build if a guarded method stops
 * referencing this guard.
 *
 * All functions must run INSIDE the caller's open `transaction {}`.
 */
internal object EventConferenceGuard {
    /** The event a conference room belongs to, or `null` for every ordinary room. */
    fun eventIdOfRoom(roomId: Uuid): Uuid? =
        EventConferenceTable
            .selectAll()
            .where { EventConferenceTable.conferenceRoomId eq roomId }
            .singleOrNull()
            ?.get(EventConferenceTable.eventId)

    fun isEventRoom(row: ResultRow): Boolean = eventIdOfRoom(row[ConferenceRoomTable.id]) != null

    /** For every method that would let a caller ENTER or reconfigure an event room outside of `joinEventOnline`. */
    fun requireNotEventRoom(row: ResultRow) {
        if (isEventRoom(row)) throw ForbiddenException(EVENT_ROOM_UNSUPPORTED)
    }

    /**
     * V1.9.156 -- ONLY for `ConferenceRecordingService.startRecordingInTransaction` and `ConferenceStreamingService.startStream` (tripwire
     * `EventConferenceGuardTripwireTest` counts exactly these two call sites). An ordinary room is not touched. An event room may be recorded /
     * streamed iff the instance switch is on with the CURRENT operator notice, the event is released (`capture_allowed`) and it is a Lapis
     * conference. Runs under the room-row lock of the caller; the release itself is frozen while the room is active (`EventCaptureLock`), so what
     * is read here cannot change between the participants' entry and this start.
     */
    fun requireEventCaptureAllowed(row: ResultRow) {
        val eventId = eventIdOfRoom(row[ConferenceRoomTable.id]) ?: return
        val event = EventTable.selectAll().where { EventTable.id eq eventId }.singleOrNull()
        val allowed =
            event != null &&
                EventCaptureSettingsStore.load().startAllowed() &&
                event[EventTable.captureAllowed] &&
                event[EventTable.onlineKind] == EventOnlineKind.LAPIS_CONFERENCE
        if (!allowed) throw ForbiddenException(EVENT_ROOM_UNSUPPORTED)
    }

    /**
     * For read methods an event participant legitimately needs (`getRoom`, `listParticipants`): an ordinary room is not touched; an
     * event room may only be read by its moderators and by somebody who currently holds an open participation (which only
     * `joinEventOnline` creates, after the full authorization). Applies to EVERY caller, members included.
     */
    fun requireParticipantOrModerator(
        row: ResultRow,
        current: CurrentMember,
    ) {
        if (!isEventRoom(row)) return
        if (ConferenceModeratorAuthority.isModerator(row = row, current = current)) return
        val roomId = row[ConferenceRoomTable.id]
        val open =
            ConferenceParticipationTable
                .selectAll()
                .where {
                    (ConferenceParticipationTable.roomId eq roomId) and
                        (ConferenceParticipationTable.memberId eq current.memberId) and
                        ConferenceParticipationTable.leftAt.isNull()
                }.limit(1)
                .any()
        if (!open) throw ForbiddenException(EVENT_ROOM_UNSUPPORTED)
    }

    /**
     * Re-runs the full event authorization for a member who already sits in an event room and asks for a fresh token (the breakout
     * return): a registration cancelled since the join, or a window that has closed, ends the stay. No-op for an ordinary room.
     */
    fun requireStillEntitled(
        row: ResultRow,
        current: CurrentMember,
    ) {
        val eventId = eventIdOfRoom(row[ConferenceRoomTable.id]) ?: return
        val eventRow = EventTable.selectAll().where { EventTable.id eq eventId }.singleOrNull()
        val decision =
            decideEventOnlineAccess(
                eventRow = eventRow,
                caller = EventOnlineCaller.Session(current),
                now = ServerClock.nowInstant(),
                orgZone = OrganizationTimeZone.current(),
            )
        if (decision.outcome != EventOnlineOutcome.JOINED) throw ForbiddenException(EVENT_ROOM_UNSUPPORTED)
    }
}
