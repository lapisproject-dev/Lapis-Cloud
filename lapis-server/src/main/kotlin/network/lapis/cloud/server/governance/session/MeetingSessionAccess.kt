package network.lapis.cloud.server.governance.session

import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.encounter.EncounterRoomGuard
import network.lapis.cloud.server.events.EventConferenceGuard
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canRecordForMeeting
import network.lapis.cloud.server.security.canSubmitMotion
import network.lapis.cloud.shared.domain.MeetingSessionRole
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** The rows behind one Sitzung-in-a-room request, plus what the caller is. All three rows were read in the SAME transaction. */
internal class SessionContext(
    val roomRow: ResultRow,
    val meetingRow: ResultRow,
    val committeeRow: ResultRow,
    val role: MeetingSessionRole,
) {
    val meetingId: Uuid get() = meetingRow[MeetingTable.id]
    val committeeId: Uuid get() = committeeRow[CommitteeTable.id]
}

/**
 * V1.9.239 -- the rows behind one request that names a Sitzung directly (the Protokoll draft), without a conference room: the Sitzung, its
 * committee, and what the caller is. Read in the SAME transaction.
 */
internal class MeetingContext(
    val meetingRow: ResultRow,
    val committeeRow: ResultRow,
    val role: MeetingSessionRole,
    /** V1.9.240 -- EVERY role the caller has (a chair who is also the minute taker has both); [role] is the single winning one. */
    val roles: Set<MeetingSessionRole> = setOf(role),
) {
    val meetingId: Uuid get() = meetingRow[MeetingTable.id]
    val committeeId: Uuid get() = committeeRow[CommitteeTable.id]
}

/**
 * V1.9.236 -- the ONE place that turns a `(roomId, expectedMeetingId)` pair of a request into a [SessionContext] -- or into "nothing". Everything a
 * caller must not see is the same `null` / the same [NotFoundException] message: an unknown room, a room without a Sitzung, an encounter or event
 * room, a room bound to another Sitzung than the one the client names (the room was re-bound meanwhile), and a caller without a seat in the
 * Sitzung's committee. Roles use only existing predicates; there are no new rights keys.
 *
 * Must run inside a transaction. With [lock] the Sitzung row is read `FOR UPDATE`, so a mutation reads the committee and the phase from the
 * LOCKED row.
 */
internal object MeetingSessionAccess {
    const val NOT_FOUND_MESSAGE = "Conference room not found"

    fun resolve(
        current: CurrentMember,
        roomId: Uuid,
        expectedMeetingId: Uuid?,
        lock: Boolean,
    ): SessionContext? {
        val room = ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.singleOrNull() ?: return null
        // Defensive: neither kind is ever bound to a Sitzung (setRoomMeeting refuses it), but the panel must never work in them.
        if (EncounterRoomGuard.isEncounterRoom(room) || EventConferenceGuard.isEventRoom(room)) return null
        val boundMeetingId = room[ConferenceRoomTable.meetingId] ?: return null
        if (expectedMeetingId != null && expectedMeetingId != boundMeetingId) return null
        val query = MeetingTable.selectAll().where { MeetingTable.id eq boundMeetingId }
        val meeting = (if (lock) query.forUpdate() else query).singleOrNull() ?: return null
        val committee =
            CommitteeTable.selectAll().where { CommitteeTable.id eq meeting[MeetingTable.committeeId] }.singleOrNull() ?: return null
        if (!MeetingAttendanceVisibility.canSeeAttendance(current = current, meeting = meeting)) return null
        val role = roleOf(current = current, meeting = meeting) ?: return null
        return SessionContext(roomRow = room, meetingRow = meeting, committeeRow = committee, role = role)
    }

    fun require(
        current: CurrentMember,
        roomId: Uuid,
        expectedMeetingId: Uuid?,
        lock: Boolean,
    ): SessionContext =
        resolve(current = current, roomId = roomId, expectedMeetingId = expectedMeetingId, lock = lock)
            ?: throw NotFoundException(NOT_FOUND_MESSAGE)

    /**
     * V1.9.239 -- resolves a Sitzung by its id alone (the Protokoll is often finished after the conference room ended). The role is the very
     * same [roleOf]. An unknown id and a Sitzung without a role for the caller are the same `null`. Must run inside a transaction.
     */
    fun resolveMeeting(
        current: CurrentMember,
        meetingId: Uuid,
        lock: Boolean,
    ): MeetingContext? {
        val query = MeetingTable.selectAll().where { MeetingTable.id eq meetingId }
        val meeting = (if (lock) query.forUpdate() else query).singleOrNull() ?: return null
        if (!MeetingAttendanceVisibility.canSeeAttendance(current = current, meeting = meeting)) return null
        val role = roleOf(current = current, meeting = meeting) ?: return null
        val committee =
            CommitteeTable.selectAll().where { CommitteeTable.id eq meeting[MeetingTable.committeeId] }.singleOrNull() ?: return null
        return MeetingContext(
            meetingRow = meeting,
            committeeRow = committee,
            role = role,
            roles = rolesOf(current = current, meeting = meeting),
        )
    }

    /** [resolveMeeting] or the one not-found every caller without a view gets (a malformed id included -- no existence oracle). */
    fun requireMeeting(
        current: CurrentMember,
        meetingId: Uuid,
        lock: Boolean,
    ): MeetingContext = resolveMeeting(current = current, meetingId = meetingId, lock = lock) ?: throw NotFoundException(NOT_FOUND_MESSAGE)

    /**
     * V1.9.240 -- ALL roles of the caller. [roleOf] returns one role and LEAD wins; somebody who is the recording office of the committee AND the
     * minute taker is both, and the Protokoll needs to know (each role confirms once; one person may fill both). [roleOf] is deliberately
     * unchanged -- the D1 behaviour and its tests hang on it.
     */
    internal fun rolesOf(
        current: CurrentMember,
        meeting: ResultRow,
    ): Set<MeetingSessionRole> {
        val committeeId = meeting[MeetingTable.committeeId]
        val roles = mutableSetOf<MeetingSessionRole>()
        if (meeting[MeetingTable.chairMemberId] == current.memberId ||
            current.canRecordForMeeting(committeeId)
        ) {
            roles += MeetingSessionRole.LEAD
        }
        if (meeting[MeetingTable.minuteTakerMemberId] == current.memberId) roles += MeetingSessionRole.MINUTES
        if (roles.isEmpty() && current.canSubmitMotion(committeeId)) roles += MeetingSessionRole.PARTICIPANT
        return roles
    }

    /**
     * LEAD: the Sitzung's chair, or anybody who may record for the committee (a recording office of the committee, or BOARD/ADMIN -- the same set
     * that may call `recordAttendance`). MINUTES: the minute taker. PARTICIPANT: anybody who is seated in the committee or, for the general
     * assembly, an active member (the `canSubmitMotion` circle). Federation guests, room guests and friends are none of these.
     */
    private fun roleOf(
        current: CurrentMember,
        meeting: ResultRow,
    ): MeetingSessionRole? {
        val committeeId = meeting[MeetingTable.committeeId]
        return when {
            meeting[MeetingTable.chairMemberId] == current.memberId || current.canRecordForMeeting(committeeId) -> MeetingSessionRole.LEAD
            meeting[MeetingTable.minuteTakerMemberId] == current.memberId -> MeetingSessionRole.MINUTES
            current.canSubmitMotion(committeeId) -> MeetingSessionRole.PARTICIPANT
            else -> null
        }
    }
}
