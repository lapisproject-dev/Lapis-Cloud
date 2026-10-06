package network.lapis.cloud.server.encounter

import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * Welle V1.9.61 -- the SINGLE answer to "may this caller moderate this conference room", shared by every conference service
 * (`ConferenceService`, `ConferenceStreamingService`, `ConferenceRecordingService`, `ConferenceBreakoutService`,
 * `ConferenceWhiteboardService`, `ConferenceNotesService`) so the six private copies of `requireModeratorOrPrivileged` can no longer
 * drift apart. Always RE-DERIVED from the database on every call -- never trusts `conference_participation.role` (a per-join snapshot)
 * and never a cached role.
 *
 * - **Ordinary room** (`encounter_space_id IS NULL`): the creator, or BOARD/ADMIN -- byte-for-byte the rule that existed before V1.9.61.
 * - **Encounter session** (`encounter_space_id` set): an ACTIVE holder of a PULPIT/STEWARD office in that space, or BOARD/ADMIN. The
 *   creator (whoever pressed "open") does NOT count by that fact alone: an office can be withdrawn while a session is running, and
 *   the withdrawal must take effect immediately.
 *
 * Must run INSIDE the caller's open `transaction {}`.
 */
internal object ConferenceModeratorAuthority {
    fun isModerator(
        row: ResultRow,
        current: CurrentMember,
    ): Boolean {
        val spaceId = row[ConferenceRoomTable.encounterSpaceId]
        return if (spaceId == null) {
            row[ConferenceRoomTable.createdByMemberId] == current.memberId || current.isPrivileged
        } else {
            current.isPrivileged || EncounterRoles.isOfficer(spaceId = spaceId, memberId = current.memberId)
        }
    }

    fun requireModerator(
        row: ResultRow,
        current: CurrentMember,
    ) {
        if (!isModerator(row = row, current = current)) throw ForbiddenException()
    }
}
