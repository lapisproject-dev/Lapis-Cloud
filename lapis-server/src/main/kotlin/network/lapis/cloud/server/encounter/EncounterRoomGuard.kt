package network.lapis.cloud.server.encounter

import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.ResultRow

/** The server-side-only reason of a rejected ordinary-conference call on an encounter session (Kilua transmits only the exception TYPE). */
internal const val ENCOUNTER_ROOM_UNSUPPORTED = "This function is not available in an encounter space session"

/**
 * Welle V1.9.61 -- fences an encounter SESSION (a `conference_room` row with `encounter_space_id` set) off from the ordinary conference
 * RPC surface. Every ordinary conference method that takes a room id calls [requireNotEncounterRoom] BEFORE any other logic.
 *
 * **Why this is a security boundary, not a nicety**: the congregation's LiveKit token is "listen only" (`canPublish = false`), and that
 * is enforced by the SERVER at token-minting time. `ConferenceService.joinRoom` and `ConferenceBreakoutService.rejoinMainRoomToken`
 * mint tokens with `canPublish = true` for any ACTIVE member; if they accepted an encounter session id, any congregation member could
 * simply request a publishing token for the same LiveKit room and speak from the pulpit. `EncounterRoomGuardTripwireTest` fails the
 * build if a room-id method of those services stops referencing the guard.
 *
 * The same fence keeps recording, breakout rooms, shared notes, whiteboard and the participant history (`listParticipants`) out of an
 * encounter session: all of them would create a lasting record of who attended.
 */
internal object EncounterRoomGuard {
    fun isEncounterRoom(row: ResultRow): Boolean = row[ConferenceRoomTable.encounterSpaceId] != null

    fun requireNotEncounterRoom(row: ResultRow) {
        if (isEncounterRoom(row)) throw ForbiddenException(ENCOUNTER_ROOM_UNSUPPORTED)
    }
}
