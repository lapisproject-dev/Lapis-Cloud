package network.lapis.cloud.server.events

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_TOPIC
import network.lapis.cloud.shared.domain.EventCaptureLiveDto
import network.lapis.cloud.shared.domain.eventCapturePayload
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Privacy rule of this file: logs carry numbers and exception classes only -- never a room, an event, an identity or a name.
private val logger = KotlinLogging.logger {}

/**
 * V1.9.156 -- tells everybody in an EVENT room whether the room is being recorded / streamed right now, so the banner of the participants (including
 * those without an account, who cannot ask the RPC) is correct. A reliable data packet on [EVENT_CAPTURE_TOPIC], sent by the server only (the packet
 * carries no sender; the client drops a packet that has one, because a guest may publish data).
 *
 * Two ways in, both Best-Effort and always AFTER a commit, never inside a `transaction {}`:
 *  - [roomChanged] right after a recording / stream was started, paused, resumed or stopped (the moderator's RPC), for an instant banner;
 *  - [sweep] every few seconds: it compares the database state of every alive, released event room with what was last sent and sends a change at once
 *    and a heartbeat every [HEARTBEAT]. That catches the transitions that only the pollers make (PROCESSING, FAILED, a stream that ended).
 * A packet that cannot be sent is not retried and not remembered as sent, so the next sweep tries again. The initial state of a person who joins is
 * part of the join answer (`captureLive`), not of this class.
 */
internal class EventCaptureLiveBroadcaster(
    private val liveKit: LiveKitAdminClient,
    private val clock: () -> Instant = { ServerClock.nowInstant() },
    private val intervalSeconds: Long = 5,
) {
    private data class Sent(
        val state: EventCaptureLiveDto,
        val at: Instant,
    )

    private val lastSent = ConcurrentHashMap<Uuid, Sent>()
    private var job: Job? = null

    // One lock per room: the state is read AND sent under it, so a slow sweep can never send a state that is older than one already sent by [roomChanged].
    private val roomLocks = ConcurrentHashMap<Uuid, Mutex>()

    private fun lockOf(roomId: Uuid): Mutex = roomLocks.computeIfAbsent(roomId) { Mutex() }

    /** Idempotent. */
    fun start() {
        if (job?.isActive == true) return
        job =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    sweep()
                    delay(intervalSeconds.seconds)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Sends the CURRENT state of [roomId] if it is a released, alive event room. Never throws. */
    suspend fun roomChanged(roomId: Uuid) {
        try {
            lockOf(roomId).withLock {
                val target = readTarget(roomId) ?: return
                send(roomId = roomId, livekitRoomName = target.first, state = target.second, now = clock())
            }
        } catch (e: Exception) {
            logger.warn { "event capture banner: update failed (${e::class.simpleName})" }
        }
    }

    /** The LiveKit room name and the current state of [roomId], or `null` if it is not an alive room of a released event. */
    private fun readTarget(roomId: Uuid) =
        transaction {
            val eventId = EventConferenceGuard.eventIdOfRoom(roomId) ?: return@transaction null
            val event = EventStore.getEventOrNull(eventId) ?: return@transaction null
            if (!event[EventTable.captureAllowed]) return@transaction null
            val room =
                ConferenceRoomTable
                    .selectAll()
                    .where { (ConferenceRoomTable.id eq roomId) and ConferenceRoomTable.endedAt.isNull() }
                    .singleOrNull()
                    ?: return@transaction null
            room[ConferenceRoomTable.livekitRoomName] to EventCaptureLock.liveState(roomId)
        }

    /** One sweep over every alive room of a released event. Never throws; returns the number of packets sent. */
    suspend fun sweep(): Int =
        try {
            val rooms =
                transaction {
                    EventConferenceTable
                        .join(EventTable, JoinType.INNER, EventConferenceTable.eventId, EventTable.id)
                        .join(ConferenceRoomTable, JoinType.INNER, EventConferenceTable.conferenceRoomId, ConferenceRoomTable.id)
                        .selectAll()
                        .where { (EventTable.captureAllowed eq true) and ConferenceRoomTable.endedAt.isNull() }
                        .map { it[ConferenceRoomTable.id] }
                }
            val alive = rooms.toSet()
            lastSent.keys.retainAll(alive)
            var sent = 0
            for (roomId in rooms) {
                // The state is read per room, under the room lock, immediately before the send: a state read before a concurrent change must not overwrite it.
                val didSend =
                    lockOf(roomId).withLock {
                        val now = clock()
                        val (name, state) = readTarget(roomId) ?: return@withLock false
                        val before = lastSent[roomId]
                        val due =
                            before == null || before.state != state || (state.recording || state.streaming) && now - before.at >= HEARTBEAT
                        // A room that never recorded needs no packet at all (a joining person gets its state from the join answer).
                        val needed = before != null || state.recording || state.streaming
                        due && needed && send(roomId = roomId, livekitRoomName = name, state = state, now = now)
                    }
                if (didSend) sent++
            }
            sent
        } catch (e: Exception) {
            logger.error { "event capture banner: the sweep failed (${e::class.simpleName})" }
            0
        }

    private suspend fun send(
        roomId: Uuid,
        livekitRoomName: String,
        state: EventCaptureLiveDto,
        now: Instant,
    ): Boolean =
        try {
            liveKit.sendData(
                room = livekitRoomName,
                topic = EVENT_CAPTURE_TOPIC,
                payload = eventCapturePayload(state).toByteArray(Charsets.UTF_8),
            )
            lastSent[roomId] = Sent(state = state, at = now)
            true
        } catch (e: LiveKitAdminException) {
            logger.warn { "event capture banner: the state could not be sent to the room" }
            false
        }

    companion object {
        /** An active state is repeated this often, so a person whose connection dropped for a moment gets the banner back. */
        val HEARTBEAT = 30.seconds

        @Volatile
        private var instance: EventCaptureLiveBroadcaster? = null

        /** Installed once by `Application.module` (and by tests); the services that start / stop a recording or stream notify through [notifyRoomChanged]. */
        fun install(broadcaster: EventCaptureLiveBroadcaster?) {
            instance = broadcaster
        }

        /** For the recording and streaming services, AFTER their transaction committed. A no-op while nothing is installed (conferencing off). */
        suspend fun notifyRoomChanged(roomId: Uuid) {
            instance?.roomChanged(roomId)
        }
    }
}
