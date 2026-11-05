package network.lapis.cloud.server.events

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.ConferenceHandQueueState
import network.lapis.cloud.server.conference.ConferenceHandsPackets
import network.lapis.cloud.server.conference.HandQueueFullException
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.encounter.EncounterRoomGuard
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_TOPIC
import network.lapis.cloud.shared.domain.EventOnlineKind
import network.lapis.cloud.shared.domain.EventStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Privacy rule of this file: the logs carry exception classes only -- never an identity, a token, an event or a room id. No audit, no database write
// (the raised-hand list is volatile by design, see ConferenceHandQueueState).
private val logger = KotlinLogging.logger {}

/**
 * V1.9.281 -- the ONE entry through which a person WITHOUT an account raises or lowers their OWN hand in the online conference of an event.
 * `ConferenceHandService` (the RPC) needs a session, which these guests do not have; this class serves them with the stateless
 * [EventGuestActionToken]. It uses the same [ConferenceHandQueueState] under the same per-room lock as the RPC and the reconciler, so the order,
 * the cap and the presence grace are the very same.
 *
 * Phases (LiveKit is never called inside a `transaction {}`; the DB phase comes BEFORE the room lock): [verify] checks the event and the room in one
 * transaction and then the token; [act] takes the room lock, checks the presence (raising only), mutates, builds the snapshot; the broadcast goes out
 * AFTER the lock, best effort. The identity always comes out of the verified token, never out of the request body.
 */
internal class EventGuestHands(
    private val config: ConferenceConfig,
    private val liveKit: LiveKitAdminClient,
    private val state: ConferenceHandQueueState,
) {
    /** A guest whose token is good for this event's live room. */
    data class Verified(
        val identity: String,
        val roomId: Uuid,
        val livekitRoomName: String,
    )

    sealed interface Result {
        /** The snapshot in the exact byte format of the broadcast (so the client reads it with the same parser). */
        class Queue(
            val payload: ByteArray,
        ) : Result

        /** A lowering of a guest who is no longer in the room: nothing to read back. */
        data object Lowered : Result

        data object Refused : Result
    }

    /** Event published and a Lapis conference, its room alive and not an encounter room and really the room of this event; THEN the token. `null` = refused. */
    fun verify(
        eventId: Uuid,
        token: String,
        now: Instant,
    ): Verified? {
        val room =
            transaction {
                val event = EventTable.selectAll().where { EventTable.id eq eventId }.singleOrNull() ?: return@transaction null
                if (event[EventTable.status] != EventStatus.PUBLISHED || event[EventTable.onlineKind] != EventOnlineKind.LAPIS_CONFERENCE) {
                    return@transaction null
                }
                val roomId = EventCaptureLock.liveRoomId(eventId) ?: return@transaction null
                val row =
                    ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.singleOrNull() ?: return@transaction null
                if (row[ConferenceRoomTable.endedAt] != null || EncounterRoomGuard.isEncounterRoom(row)) return@transaction null
                if (EventConferenceGuard.eventIdOfRoom(roomId) != eventId) return@transaction null
                roomId to row[ConferenceRoomTable.livekitRoomName]
            } ?: return null
        val identity =
            EventGuestActionToken.verify(
                serverSecret = config.apiSecret,
                token = token,
                eventId = eventId,
                roomId = room.first,
                now = now,
            ) ?: return null
        return Verified(identity = identity, roomId = room.first, livekitRoomName = room.second)
    }

    suspend fun act(
        guest: Verified,
        raise: Boolean,
    ): Result {
        var present = false
        var change: ConferenceHandQueueState.Change = ConferenceHandQueueState.Change.None
        var refused = false
        val snapshot =
            state.withRoomLock(roomId = guest.roomId) {
                present =
                    try {
                        liveKit.listParticipants(guest.livekitRoomName).any { it.identity == guest.identity }
                    } catch (e: LiveKitAdminException) {
                        false
                    }
                if (raise) {
                    if (!present) {
                        refused = true
                    } else {
                        try {
                            change = state.raise(roomId = guest.roomId, identity = guest.identity)
                        } catch (e: HandQueueFullException) {
                            refused = true
                        }
                    }
                } else {
                    change = state.lower(roomId = guest.roomId, identity = guest.identity)
                }
                state.snapshot(guest.roomId)
            }
        if (refused) return Result.Refused
        val payload = ConferenceHandsPackets.snapshot(snapshot)
        if (change is ConferenceHandQueueState.Change.Changed) {
            try {
                liveKit.sendData(room = guest.livekitRoomName, topic = CONFERENCE_HANDS_TOPIC, payload = payload)
            } catch (e: LiveKitAdminException) {
                logger.warn { "event guest hands: the queue broadcast failed (${e::class.simpleName})" }
            }
        }
        return if (present) Result.Queue(payload) else Result.Lowered
    }
}
