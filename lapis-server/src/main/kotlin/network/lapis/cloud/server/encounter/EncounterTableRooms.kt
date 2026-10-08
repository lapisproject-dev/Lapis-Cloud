package network.lapis.cloud.server.encounter

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitAdminException

// Privacy rule of this file (Art. 9 GDPR): the logs name counts and exception classes only -- never a member id, identity, name, table or room id.
private val logger = KotlinLogging.logger {}

/** LiveKit `empty_timeout` of a table room: an abandoned room must vanish quickly (the reconciler deletes it anyway). */
internal const val ENCOUNTER_TABLE_EMPTY_TIMEOUT_SECONDS = 60

/**
 * Welle V1.9.80 -- carries out the LiveKit side of [EncounterTableState.Rotation]s: create the new table room, then delete the old one
 * (which disconnects everybody in it at once). **Never call inside a `transaction {}` or a `synchronized` block.** Best effort: a failed
 * LiveKit call is only logged (without ids); the [EncounterTableReconciler] deletes every `lc-et-*` room that is not a current table room,
 * so a failed deletion never leaves a listener behind for long. Data protection beats availability: the old room is deleted even if
 * the new one could not be created (a client that gets a token for a not-yet-existing name simply auto-creates an empty room).
 */
internal object EncounterTableRooms {
    suspend fun apply(
        liveKit: LiveKitAdminClient,
        rotations: Collection<EncounterTableState.Rotation>,
    ) {
        rotations.forEach { rotation ->
            val newRoom = rotation.newRoom
            if (newRoom != null) {
                try {
                    liveKit.createRoom(
                        name = newRoom,
                        maxParticipants = rotation.maxParticipants,
                        emptyTimeoutSeconds = ENCOUNTER_TABLE_EMPTY_TIMEOUT_SECONDS,
                        departureTimeoutSeconds = null,
                    )
                } catch (e: LiveKitAdminException) {
                    logger.warn { "encounter tables: a table room could not be created -- LiveKit creates it on the first join" }
                }
            }
            val oldRoom = rotation.oldRoom
            if (oldRoom != null) deleteBestEffort(liveKit = liveKit, room = oldRoom)
        }
    }

    suspend fun deleteAll(
        liveKit: LiveKitAdminClient,
        rooms: Collection<String>,
    ) {
        rooms.forEach { deleteBestEffort(liveKit = liveKit, room = it) }
    }

    private suspend fun deleteBestEffort(
        liveKit: LiveKitAdminClient,
        room: String,
    ) {
        try {
            liveKit.deleteRoom(room)
        } catch (e: LiveKitAdminException) {
            logger.warn { "encounter tables: a table room could not be deleted -- the reconciler retries" }
        }
    }
}
