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
import network.lapis.cloud.server.conference.MAX_SEND_DATA_DESTINATIONS
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.EventConferenceTable
import network.lapis.cloud.server.db.generated.EventTable
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_ASK_TOPIC
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_COUNT_TOPIC
import network.lapis.cloud.shared.domain.EVENT_CAPTURE_TOPIC
import network.lapis.cloud.shared.domain.EventCaptureConsentCountDto
import network.lapis.cloud.shared.domain.EventCaptureLiveDto
import network.lapis.cloud.shared.domain.eventCaptureAskPayload
import network.lapis.cloud.shared.domain.eventCaptureCountPayload
import network.lapis.cloud.shared.domain.eventCapturePayload
import network.lapis.cloud.shared.rpc.ServiceBusyException
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
    // V1.9.280 -- the consent pass: the join coordinator (pending guest tokens), the guest-key secret and the cadence while a recording / stream runs.
    private val join: EventOnlineJoin? = null,
    private val guestKeySecret: String? = null,
    private val capturingIntervalSeconds: Long = 2,
) {
    private data class Sent(
        val state: EventCaptureLiveDto,
        val at: Instant,
    )

    private val lastSent = ConcurrentHashMap<Uuid, Sent>()
    private var job: Job? = null
    private var consentJob: Job? = null

    private data class SentCount(
        val count: EventCaptureConsentCountDto,
        val moderators: Set<String>,
        val at: Instant,
    )

    private val lastCount = ConcurrentHashMap<Uuid, SentCount>()

    // Who was asked when, per room. The ask is sent once (and again after [ASK_REPEAT] in case a packet was lost, or at once when a pass is forced): a dialog that is
    // already open must not be nudged every few seconds.
    private val lastAsked = ConcurrentHashMap<Uuid, MutableMap<String, Instant>>()
    private val lastConsentRun = ConcurrentHashMap<Uuid, Instant>()

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

    /**
     * V1.9.280 -- the consent pass runs in its own loop: every [capturingIntervalSeconds] for a room that records / streams (a person without a proof is
     * removed within that time), every [intervalSeconds] for any other released room. Idempotent.
     */
    fun startConsentPass() {
        if (consentJob?.isActive == true) return
        consentJob =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    consentSweep()
                    delay(capturingIntervalSeconds.seconds)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        consentJob?.cancel()
        consentJob = null
    }

    /** V1.9.280 -- right after a release was switched on / off or somebody acknowledged (AFTER the commit): runs the consent pass of that event's room. Never throws. */
    suspend fun consentChanged(eventId: Uuid) {
        try {
            val roomId = transaction { EventCaptureLock.liveRoomId(eventId) } ?: return
            consentPass(roomId = roomId, force = true)
        } catch (e: Exception) {
            logger.warn { "event capture consent: update failed (${e::class.simpleName})" }
        }
    }

    /** One pass over every alive room of a released event. Never throws; returns the number of rooms that were looked at. */
    suspend fun consentSweep(): Int =
        try {
            val rooms = transaction { releasedAliveRooms() }
            lastCount.keys.retainAll(rooms.toSet())
            lastConsentRun.keys.retainAll(rooms.toSet())
            lastAsked.keys.retainAll(rooms.toSet())
            var looked = 0
            for (roomId in rooms) {
                if (consentPass(roomId = roomId, force = false)) looked++
            }
            looked
        } catch (e: Exception) {
            logger.error { "event capture consent: the sweep failed (${e::class.simpleName})" }
            0
        }

    private fun releasedAliveRooms(): List<Uuid> =
        EventConferenceTable
            .join(EventTable, JoinType.INNER, EventConferenceTable.eventId, EventTable.id)
            .join(ConferenceRoomTable, JoinType.INNER, EventConferenceTable.conferenceRoomId, ConferenceRoomTable.id)
            .selectAll()
            .where { (EventTable.captureAllowed eq true) and ConferenceRoomTable.endedAt.isNull() }
            .map { it[ConferenceRoomTable.id] }

    private class ConsentTarget(
        val eventId: Uuid,
        val livekitRoomName: String,
    )

    private fun consentTarget(roomId: Uuid): ConsentTarget? {
        val eventId = EventConferenceGuard.eventIdOfRoom(roomId) ?: return null
        val event = EventStore.getEventOrNull(eventId) ?: return null
        if (!EventCaptureRequirement.applies(event)) return null
        val room =
            ConferenceRoomTable
                .selectAll()
                .where { (ConferenceRoomTable.id eq roomId) and ConferenceRoomTable.endedAt.isNull() }
                .singleOrNull()
                ?: return null
        return ConsentTarget(eventId = eventId, livekitRoomName = room[ConferenceRoomTable.livekitRoomName])
    }

    /**
     * The consent pass of ONE room (under its lock): who is in the room, who lacks a proof of the current text. The missing people (connected ones) get
     * the ask packet, the moderators get the count, and while a recording / stream runs the missing people are removed. Nothing is sent to anybody else,
     * nothing names anybody. Returns `false` if the room was skipped.
     */
    private suspend fun consentPass(
        roomId: Uuid,
        force: Boolean,
    ): Boolean =
        lockOf(roomId).withLock {
            val now = clock()
            val target = transaction { consentTarget(roomId) } ?: return@withLock false
            val capturingNow = transaction { EventCaptureLock.isCapturing(roomId) }
            val ran = lastConsentRun[roomId]
            if (!force && !capturingNow && ran != null && now - ran < intervalSeconds.seconds) return@withLock false
            lastConsentRun[roomId] = now
            val present =
                try {
                    EventCaptureConsentGate.collect(livekitRoomName = target.livekitRoomName, liveKit = liveKit, join = join)
                } catch (e: ServiceBusyException) {
                    return@withLock false
                }
            val (count, moderators) =
                transaction {
                    val counted =
                        EventCaptureConsentGate.count(
                            eventId = target.eventId,
                            roomId = roomId,
                            present = present,
                            guestKeySecret = guestKeySecret,
                        )
                    val mods =
                        ConferenceParticipationTable
                            .selectAll()
                            .where {
                                (ConferenceParticipationTable.roomId eq roomId) and
                                    ConferenceParticipationTable.leftAt.isNull() and
                                    (ConferenceParticipationTable.role eq ConferenceRole.MODERATOR)
                            }.map { it[ConferenceParticipationTable.memberId].toString() }
                            .toSet()
                    counted to mods
                }
            val connectedMissing = count.missingIdentities.filter { it in present.liveKitIdentities }
            val asked = lastAsked.getOrPut(roomId) { ConcurrentHashMap() }
            // forget the people who are not missing any more (they must be asked again if a new text comes)
            asked.keys.retainAll(connectedMissing.toSet())
            val toAsk = connectedMissing.filter { identity -> force || asked[identity].let { it == null || now - it >= ASK_REPEAT } }
            if (toAsk.isNotEmpty() &&
                sendTo(
                    livekitRoomName = target.livekitRoomName,
                    topic = EVENT_CAPTURE_ASK_TOPIC,
                    payload = eventCaptureAskPayload(),
                    identities = toAsk,
                )
            ) {
                toAsk.forEach { asked[it] = now }
            }
            val connectedModerators = moderators.filter { it in present.liveKitIdentities }.toSet()
            val sentCount = lastCount[roomId]
            val dto = count.toDto()
            if (connectedModerators.isNotEmpty() &&
                (
                    sentCount == null ||
                        sentCount.count != dto ||
                        sentCount.moderators != connectedModerators ||
                        now - sentCount.at >= HEARTBEAT
                )
            ) {
                if (sendTo(
                        livekitRoomName = target.livekitRoomName,
                        topic = EVENT_CAPTURE_COUNT_TOPIC,
                        payload = eventCaptureCountPayload(dto),
                        identities = connectedModerators.toList(),
                    )
                ) {
                    lastCount[roomId] = SentCount(count = dto, moderators = connectedModerators, at = now)
                }
            }
            // While something captures, nobody without a proof may stay (an old token, a breakout return, a connection made during the start).
            if (capturingNow && count.missingIdentities.isNotEmpty()) {
                EventCaptureConsentGate.removeUnacknowledged(
                    liveKit = liveKit,
                    livekitRoomName = target.livekitRoomName,
                    roomId = roomId,
                    missing = count.missingIdentities,
                    connected = present.liveKitIdentities,
                )
            }
            true
        }

    /** Best-effort, in chunks of [MAX_SEND_DATA_DESTINATIONS]; never throws. */
    private suspend fun sendTo(
        livekitRoomName: String,
        topic: String,
        payload: String,
        identities: List<String>,
    ): Boolean {
        var all = true
        for (chunk in identities.chunked(MAX_SEND_DATA_DESTINATIONS)) {
            try {
                liveKit.sendDataTo(
                    room = livekitRoomName,
                    topic = topic,
                    payload = payload.toByteArray(Charsets.UTF_8),
                    destinationIdentities = chunk,
                )
            } catch (e: LiveKitAdminException) {
                logger.warn { "event capture consent: a packet could not be sent" }
                all = false
            }
        }
        return all
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

        /** A person who still lacks the proof is asked again after this long (a lost packet); never more often unless the release or an acknowledgment changed. */
        val ASK_REPEAT = 30.seconds

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

        /** V1.9.280 -- for `setEventCaptureAllowed` and the in-room acknowledgments, AFTER their transaction committed. A no-op while nothing is installed. */
        suspend fun notifyConsentChanged(eventId: Uuid) {
            instance?.consentChanged(eventId)
        }
    }
}
