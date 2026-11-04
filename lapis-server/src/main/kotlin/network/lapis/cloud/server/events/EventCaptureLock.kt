package network.lapis.cloud.server.events

import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRecordingTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ConferenceStreamTable
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.EventCaptureLiveDto
import network.lapis.cloud.shared.domain.EventOnlinePhase
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.156 -- what "recording" / "streaming" mean for the banner, for the instance switch and (since V1.9.280) for switching a release off. All functions
 * run INSIDE the caller's open `transaction {}`.
 *
 * **V1.9.280 -- what changed.** The release of ONE event is no longer frozen while the room is active: switching it ON works at any time (people who are
 * inside are then asked in the room, and a recording / stream only starts when all of them acknowledged -- `EventCaptureConsentGate`); switching it OFF is
 * refused only while something captures ([isCapturing]). [isActive] and [anyReleasedEventActive] are kept UNCHANGED for the INSTANCE switch: people who
 * entered while the instance switch was off acknowledged nothing, and a person WITHOUT an account leaves no participation row, only a LiveKit identity and a
 * token that stays valid until it expires -- so the instance switch is still frozen from the opening of the window until the room is over.
 */
internal object EventCaptureLock {
    /** Statuses in which a recording still captures (egress runs) or is being stopped. */
    val RECORDING_STATUSES: List<ConferenceRecordingStatus> =
        listOf(ConferenceRecordingStatus.RECORDING, ConferenceRecordingStatus.STOPPING)

    /** Statuses of a stream that is not over -- including PAUSED (it can resume by itself). Used for "busy", never for the banner. */
    val STREAM_ALIVE_STATUSES: List<ConferenceStreamStatus> =
        listOf(
            ConferenceStreamStatus.STARTING,
            ConferenceStreamStatus.LIVE,
            ConferenceStreamStatus.PAUSING,
            ConferenceStreamStatus.PAUSED,
            ConferenceStreamStatus.STOPPING,
        )

    /** Statuses in which the stream may be publishing images right now: the banner is on. PAUSED is the only alive status that is not. */
    val STREAM_PUBLISHING_STATUSES: List<ConferenceStreamStatus> =
        listOf(
            ConferenceStreamStatus.STARTING,
            ConferenceStreamStatus.LIVE,
            ConferenceStreamStatus.PAUSING,
            ConferenceStreamStatus.STOPPING,
        )

    /** `true` iff the release of [eventRow] must not change right now (see the class KDoc for the four reasons). */
    fun isActive(
        eventRow: ResultRow,
        now: Instant,
        orgZone: TimeZone,
    ): Boolean {
        val window = EventOnlineWindow.of(startsAt = eventRow[EventTable.startsAt], endsAt = eventRow[EventTable.endsAt], orgZone = orgZone)
        if (EventOnlineWindow.phase(window = window, now = now) == EventOnlinePhase.OPEN) return true
        val roomId = roomIdOf(eventRow[EventTable.id]) ?: return false
        // (b) The room is alive (not ended): it can still hold people, a recording or a stream.
        if (isAlive(roomId)) return true
        // (c)/(d) Even an ended room blocks while something still captures or somebody still has an open participation.
        return hasOpenParticipation(roomId) || liveState(roomId).let { it.recording || it.streaming }
    }

    /**
     * V1.9.280 -- `true` iff a recording is running / being stopped, or a stream is not over (including PAUSED). The ONLY thing that blocks switching the
     * release of one event OFF. The room row of [roomId] must be locked by the caller (`forUpdate`) for the answer to be race-free against a start.
     */
    fun isCapturing(roomId: Uuid): Boolean =
        ConferenceRecordingTable
            .selectAll()
            .where { (ConferenceRecordingTable.roomId eq roomId) and (ConferenceRecordingTable.status inList RECORDING_STATUSES) }
            .limit(1)
            .any() ||
            ConferenceStreamTable
                .selectAll()
                .where { (ConferenceStreamTable.roomId eq roomId) and (ConferenceStreamTable.status inList STREAM_ALIVE_STATUSES) }
                .limit(1)
                .any()

    /** The conference room of [eventId] (whether or not it has ended), or `null` if none was created yet. */
    fun roomIdOf(eventId: Uuid): Uuid? =
        EventConferenceTable
            .selectAll()
            .where { EventConferenceTable.eventId eq eventId }
            .singleOrNull()
            ?.get(EventConferenceTable.conferenceRoomId)

    /** `true` iff [roomId] exists and has not ended. */
    fun isAlive(roomId: Uuid): Boolean =
        ConferenceRoomTable
            .selectAll()
            .where { (ConferenceRoomTable.id eq roomId) and ConferenceRoomTable.endedAt.isNull() }
            .any()

    /** The conference room of [eventId] if it exists and has NOT ended; `null` otherwise. */
    fun liveRoomId(eventId: Uuid): Uuid? {
        val roomId = roomIdOf(eventId) ?: return null
        return ConferenceRoomTable
            .selectAll()
            .where { (ConferenceRoomTable.id eq roomId) and ConferenceRoomTable.endedAt.isNull() }
            .singleOrNull()
            ?.get(ConferenceRoomTable.id)
    }

    /** `true` iff somebody with an account still has an open participation in [roomId]. */
    fun hasOpenParticipation(roomId: Uuid): Boolean =
        ConferenceParticipationTable
            .selectAll()
            .where { (ConferenceParticipationTable.roomId eq roomId) and ConferenceParticipationTable.leftAt.isNull() }
            .limit(1)
            .any()

    /** V1.9.280 -- `true` iff [memberId] has an open participation in [roomId] (only `joinEventOnline` creates one). */
    fun hasOpenParticipation(
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

    /** What the banner of [roomId] shows. */
    fun liveState(roomId: Uuid): EventCaptureLiveDto =
        EventCaptureLiveDto(
            recording =
                ConferenceRecordingTable
                    .selectAll()
                    .where { (ConferenceRecordingTable.roomId eq roomId) and (ConferenceRecordingTable.status inList RECORDING_STATUSES) }
                    .limit(1)
                    .any(),
            streaming =
                ConferenceStreamTable
                    .selectAll()
                    .where { (ConferenceStreamTable.roomId eq roomId) and (ConferenceStreamTable.status inList STREAM_PUBLISHING_STATUSES) }
                    .limit(1)
                    .any(),
        )

    /** `true` iff any event room records, or runs a stream that is not over. Blocks switching the instance feature off. */
    fun anyEventRoomCapturing(): Boolean {
        val roomIds =
            EventConferenceTable
                .selectAll()
                .where { EventConferenceTable.conferenceRoomId.isNotNull() }
                .mapNotNull { it[EventConferenceTable.conferenceRoomId] }
        if (roomIds.isEmpty()) return false
        val recording =
            ConferenceRecordingTable
                .selectAll()
                .where { (ConferenceRecordingTable.roomId inList roomIds) and (ConferenceRecordingTable.status inList RECORDING_STATUSES) }
                .limit(1)
                .any()
        if (recording) return true
        return ConferenceStreamTable
            .selectAll()
            .where { (ConferenceStreamTable.roomId inList roomIds) and (ConferenceStreamTable.status inList STREAM_ALIVE_STATUSES) }
            .limit(1)
            .any()
    }

    /**
     * `true` iff a RELEASED event (`capture_allowed`) is active right now ([isActive]). Blocks switching the instance feature ON: people who entered
     * while the switch was off acknowledged nothing.
     */
    fun anyReleasedEventActive(
        now: Instant,
        orgZone: TimeZone,
    ): Boolean =
        EventTable
            .selectAll()
            .where { EventTable.captureAllowed eq true }
            .any { isActive(eventRow = it, now = now, orgZone = orgZone) }
}
