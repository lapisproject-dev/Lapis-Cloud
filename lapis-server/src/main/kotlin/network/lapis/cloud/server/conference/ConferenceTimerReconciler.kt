package network.lapis.cloud.server.conference

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.shared.domain.CONFERENCE_TIMER_TOPIC
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// Privacy rule of this file: the logs name counts and exception classes only -- never a member id, identity, display name or room id.
private val logger = KotlinLogging.logger {}

/**
 * V1.9.133 -- tells people who arrive in a room with a non-off timer the current state ([ConferenceTimerState]). Runs every
 * [intervalSeconds] and never throws. It only looks at rooms whose timer is running, paused or expired. Per such room, under the room
 * lock of the state: asks LiveKit who is present and sends the current snapshot, point to point, to every participant (identity + LiveKit sid, so a reload counts as new) not yet told -- an account-less event guest has no RPC read access, so this packet is how they learn the timer. No broadcast to
 * everybody and no "expired" packet: clients derive the end from the timestamps they already hold.
 *
 * Rooms that ended are dropped from the state. Identities that are neither a member UUID nor an `evg-` guest identity (egress, recorder)
 * are ignored. An unreachable LiveKit is a single warning without ids.
 */
class ConferenceTimerReconciler(
    private val liveKitAdminClient: LiveKitAdminClient,
    private val state: ConferenceTimerState,
    private val liveKitEnabled: Boolean,
    private val intervalSeconds: Long = 5,
) {
    private var job: Job? = null

    fun start() {
        if (!liveKitEnabled || job?.isActive == true) return
        job =
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    tick()
                    delay(intervalSeconds.seconds)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One full run. Never throws (except for coroutine cancellation). */
    suspend fun tick() {
        if (!liveKitEnabled) return
        try {
            val known = state.knownRoomIds()
            if (known.isEmpty()) return
            val live: Map<Uuid, String> =
                transaction {
                    ConferenceRoomTable
                        .selectAll()
                        .where { (ConferenceRoomTable.id inList known.toList()) and ConferenceRoomTable.endedAt.isNull() }
                        .associate { it[ConferenceRoomTable.id] to it[ConferenceRoomTable.livekitRoomName] }
                }
            // Rooms created after the snapshot of `known` are never touched: only the ones the database says have ended are dropped.
            state.pruneInactiveRooms(state.knownRoomIds() - (known - live.keys))
            var failedRooms = 0
            for (roomId in state.nonOffRoomIds()) {
                val livekitRoomName = live[roomId] ?: continue
                try {
                    reconcile(roomId = roomId, livekitRoomName = livekitRoomName)
                } catch (e: LiveKitAdminException) {
                    failedRooms++
                }
            }
            if (failedRooms > 0) logger.warn { "conference timer: LiveKit failed for $failedRooms room(s)" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error { "conference timer: the reconciliation failed (${e::class.simpleName})" }
        }
    }

    /** One room. The point-to-point sends go out after the room lock is released. */
    private suspend fun reconcile(
        roomId: Uuid,
        livekitRoomName: String,
    ) {
        val (snapshot, toTell) =
            state.withRoomLock(roomId = roomId) {
                val present =
                    liveKitAdminClient
                        .listParticipants(livekitRoomName)
                        .filter { HAND_IDENTITY_REGEX.matches(it.identity) }
                        .associate { it.identity to it.sid }
                state.snapshot(roomId) to state.observePresence(roomId = roomId, present = present)
            }
        if (toTell.isEmpty()) return
        val payload = ConferenceTimerPackets.snapshot(snapshot)
        try {
            toTell.keys.toList().chunked(MAX_SEND_DATA_DESTINATIONS).forEach { chunk ->
                liveKitAdminClient.sendDataTo(
                    room = livekitRoomName,
                    topic = CONFERENCE_TIMER_TOPIC,
                    payload = payload,
                    destinationIdentities = chunk,
                )
                // Only a confirmed send counts as "told"; a failed chunk (and every later one) is retried on the next tick.
                state.markTold(roomId = roomId, seq = snapshot.seq, told = toTell.filterKeys { it in chunk })
            }
        } catch (e: LiveKitAdminException) {
            logger.warn { "conference timer: a timer notice could not be sent" }
        }
    }
}
