package network.lapis.cloud.server.governance.session

import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * V1.9.241 -- who may see the attendance, the quorum and the Protokoll draft of a Sitzung: only somebody with a role in it
 * (LEAD / MINUTES / PARTICIPANT of [MeetingSessionAccess]). Everybody else gets the same not-found as for an unknown id.
 */
internal object MeetingAttendanceVisibility {
    /** THE rule: a role in the Sitzung (LEAD/MINUTES/PARTICIPANT) = may see attendance, quorum and protocol draft. Must run in a transaction. */
    fun canSeeAttendance(
        current: CurrentMember,
        meeting: ResultRow,
    ): Boolean = MeetingSessionAccess.rolesOf(current = current, meeting = meeting).isNotEmpty()

    /** Same message for unknown id and not allowed; shared with the Sitzung loader so the two strings cannot drift. */
    fun notFoundMessage(meetingId: Uuid): String = "Meeting $meetingId not found"

    /** Reads the MeetingTable row; unknown or not visible -> the identical [NotFoundException]. Must run in a transaction. */
    fun requireVisibleMeetingRow(
        current: CurrentMember,
        meetingId: Uuid,
    ): ResultRow {
        val row =
            MeetingTable.selectAll().where { MeetingTable.id eq meetingId }.singleOrNull()
                ?: throw NotFoundException(notFoundMessage(meetingId))
        if (!canSeeAttendance(current = current, meeting = row)) throw NotFoundException(notFoundMessage(meetingId))
        return row
    }
}
