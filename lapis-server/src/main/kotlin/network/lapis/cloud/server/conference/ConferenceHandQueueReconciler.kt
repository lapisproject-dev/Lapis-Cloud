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
import network.lapis.cloud.shared.domain.CONFERENCE_HANDS_TOPIC
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
 * V1.9.127 -- keeps the raised-hand queue honest ([ConferenceHandQueueState]). Runs every [intervalSeconds] and never throws. It only
 * looks at rooms that currently hold at least one raised hand (no database scan of all rooms). Per such room, under the room lock of
 * the state: asks LiveKit who is present and
 *
 *  - removes everybody who has been missing for [ConferenceHandQueueState.DEFAULT_GRACE_SECONDS] (a reconnect with the same identity
 *    within the grace keeps the position; a random `evg-<uuid>` guest who rejoins gets a NEW identity and loses the hand; a ticket guest keeps a deterministic `evg-<32 hex>` identity), then broadcasts;
 *  - sends the current snapshot, point to point, to every identity seen in the room for the first time: an account-less event guest
 *    has no RPC read access, so this packet is how they learn the queue.
 *
 * Rooms that ended are dropped from the state. Identities that are neither a member UUID nor an `evg-` guest identity (egress, recorder) are ignored.
 * An unreachable LiveKit is a single warning without ids.
 */
class ConferenceHandQueueReconciler(
    private val liveKitAdminClient: LiveKitAdminClient,
    private val state: ConferenceHandQueueState,
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
            var removed = 0
            var failedRooms = 0
            for (roomId in state.activeRoomIds()) {
                val livekitRoomName = live[roomId] ?: continue
                try {
                    removed += reconcile(roomId = roomId, livekitRoomName = livekitRoomName)
                } catch (e: LiveKitAdminException) {
                    failedRooms++
                }
            }
            if (removed > 0) logger.info { "conference hands: $removed absent hand(s) removed" }
            if (failedRooms > 0) logger.warn { "conference hands: LiveKit failed for $failedRooms room(s)" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error { "conference hands: the reconciliation failed (${e::class.simpleName})" }
        }
    }

    /** One room; returns the number of hands removed. The broadcasts go out after the room lock is released. */
    private suspend fun reconcile(
        roomId: Uuid,
        livekitRoomName: String,
    ): Int {
        val (removedCount, snapshot, toTell) =
            state.withRoomLock(roomId = roomId) {
                val before = state.snapshot(roomId).identities.size
                val present =
                    liveKitAdminClient
                        .listParticipants(livekitRoomName)
                        .map { it.identity }
                        .filter { HAND_IDENTITY_REGEX.matches(it) }
                        .toSet()
                val result = state.observePresence(roomId = roomId, present = present)
                val snap = state.snapshot(roomId)
                val changed = result.change is ConferenceHandQueueState.Change.Changed
                Triple(
                    if (changed) before - snap.identities.size else 0,
                    snap.takeIf { changed || result.newlySeen.isNotEmpty() },
                    if (changed) null else result.newlySeen,
                )
            }
        if (snapshot == null) return removedCount
        val payload = ConferenceHandsPackets.snapshot(snapshot)
        try {
            if (toTell == null) {
                liveKitAdminClient.sendData(room = livekitRoomName, topic = CONFERENCE_HANDS_TOPIC, payload = payload)
            } else {
                toTell.toList().chunked(MAX_SEND_DATA_DESTINATIONS).forEach { chunk ->
                    liveKitAdminClient.sendDataTo(
                        room = livekitRoomName,
                        topic = CONFERENCE_HANDS_TOPIC,
                        payload = payload,
                        destinationIdentities = chunk,
                    )
                }
            }
        } catch (e: LiveKitAdminException) {
            logger.warn { "conference hands: a queue notice could not be sent" }
        }
        return removedCount
    }
}
