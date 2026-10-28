package network.lapis.cloud.server.governance.session

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.shared.domain.AttendanceStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.236 -- the shared write of one attendance row, used by `GovernanceService.recordAttendance` and by the Sitzung panel's
 * `setConferenceAttendance` (one persistence path). Insert or update by `(meeting, member)`; returns the row id. Inside a transaction.
 */
internal object AttendanceWriter {
    fun upsert(
        meetingId: Uuid,
        memberId: Uuid,
        status: AttendanceStatus,
        representedBy: Uuid?,
        note: String?,
        now: LocalDateTime,
    ): Uuid {
        val existing =
            AttendanceTable
                .selectAll()
                .where { (AttendanceTable.meetingId eq meetingId) and (AttendanceTable.memberId eq memberId) }
                .singleOrNull()
        if (existing == null) {
            val newId = Uuid.random()
            AttendanceTable.insert {
                it[AttendanceTable.id] = newId
                it[AttendanceTable.meetingId] = meetingId
                it[AttendanceTable.memberId] = memberId
                it[AttendanceTable.status] = status
                it[representedByMemberId] = representedBy
                it[AttendanceTable.note] = note
                it[recordedAt] = now
            }
            return newId
        }
        val existingId = existing[AttendanceTable.id]
        AttendanceTable.update({ AttendanceTable.id eq existingId }) {
            it[AttendanceTable.status] = status
            it[representedByMemberId] = representedBy
            it[AttendanceTable.note] = note
            it[recordedAt] = now
        }
        return existingId
    }
}
