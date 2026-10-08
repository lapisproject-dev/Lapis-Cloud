package network.lapis.cloud.server.encounter

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Privacy rule of this file (Art. 9 GDPR): the logs name counts and exception classes only -- never a member id, identity, table or room id.
private val logger = KotlinLogging.logger {}

/** LiveKit's name of the one track source a table participant may publish. */
private const val MICROPHONE_SOURCE = "MICROPHONE"

/**
 * Welle V1.9.80 -- defence in depth for the table audio rooms (the primary protection is the room rotation of [EncounterTableState]).
 * Runs every [intervalSeconds] and never throws:
 *
 * - **(a)** one `listRooms` per tick: every `lc-et-*` room that is not the current room of a table (phantom rooms, rooms orphaned by a
 *   restart or a failed rotation, rooms re-created by a stale token) is deleted.
 * - **(b)** per live table room: `listParticipants`. The table is ROTATED (not just a participant removed -- a removed client with a
 *   refreshed token could walk back in) when somebody is connected who is not assigned there, holds a track that is not the microphone
 *   (camera or screen, in case LiveKit does not enforce `canPublishSources`), or may publish / publishes in a quieted table.
 * - **(c)** lifts quiets whose five minutes are up (rotation, so the members get a publishing token again).
 *
 * If LiveKit is unreachable only a warning WITHOUT ids is logged.
 */
class EncounterTableReconciler(
    private val liveKitAdminClient: LiveKitAdminClient,
    private val tableState: EncounterTableState,
    private val liveKitEnabled: Boolean,
    private val moderationState: EncounterModerationState? = null,
    private val intervalSeconds: Long = 5,
    private val clock: () -> Instant = { Clock.System.now() },
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

    /** One full run. Never throws. */
    suspend fun tick() {
        if (!liveKitEnabled) return
        try {
            val now = clock()
            val expired = tableState.expireQuiet(now)
            if (expired.isNotEmpty()) EncounterTableRooms.apply(liveKit = liveKitAdminClient, rotations = expired)
            // Order matters: LiveKit's rooms FIRST, the state afterwards. A table that is created in between (state first, then the room)
            // is then in `active` as well; the other order would delete the room of somebody who has just sat down as an "orphan".
            val live = liveKitAdminClient.listRooms().map { it.name }.filter { it.startsWith(ENCOUNTER_TABLE_ROOM_PREFIX) }
            val active = tableState.activeRooms(now)
            val orphans = live.filter { it !in active }
            if (orphans.isNotEmpty()) {
                EncounterTableRooms.deleteAll(liveKit = liveKitAdminClient, rooms = orphans)
                logger.info { "encounter tables: ${orphans.size} orphaned table room(s) deleted" }
            }
            var rotated = 0
            active.keys.filter { it in live }.forEach { room ->
                val participants = liveKitAdminClient.listParticipants(room)
                // The Twirp calls take time: judge against the state of NOW, not of the start of the tick (somebody may have sat down since;
                // a room that is not current any more is none of our business -- the rotation that replaced it deleted it).
                val expected = tableState.activeRooms(clock())[room] ?: return@forEach
                if (participants.any { violates(participant = it, expected = expected) }) {
                    tableState.rotateRoom(room)?.let {
                        EncounterTableRooms.apply(liveKit = liveKitAdminClient, rotations = listOf(it))
                        rotated++
                    }
                }
            }
            if (rotated > 0) logger.info { "encounter tables: $rotated table room(s) rotated by the reconciler" }
        } catch (e: LiveKitAdminException) {
            logger.warn { "encounter tables: LiveKit is not reachable -- skipping the reconciliation" }
        } catch (e: Exception) {
            logger.error { "encounter tables: the reconciliation failed (${e::class.simpleName})" }
        }
    }

    private fun silencedIdentities(sessionRoomId: Uuid): Set<String> = moderationState?.silencedIdentities(sessionRoomId).orEmpty()

    private fun violates(
        participant: LiveKitParticipantInfo,
        expected: EncounterTableState.ActiveRoom,
    ): Boolean {
        if (participant.identity !in expected.identities) return true
        // A silenced person must not hold a microphone channel, even if a stale seat or token still lets them in.
        if (participant.identity in silencedIdentities(expected.sessionRoomId) &&
            (participant.permission?.canPublish == true || participant.tracks.isNotEmpty())
        ) {
            return true
        }
        if (participant.tracks.any { !it.source.equals(MICROPHONE_SOURCE, ignoreCase = true) }) return true
        if (!expected.publishAllowed && (participant.permission?.canPublish == true || participant.tracks.isNotEmpty())) return true
        return false
    }
}
