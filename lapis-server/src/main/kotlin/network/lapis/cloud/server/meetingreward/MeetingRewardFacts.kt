package network.lapis.cloud.server.meetingreward

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.governance.session.ProtocolSnapshots
import network.lapis.cloud.server.governance.session.ProtocolVersionStore
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.MeetingRewardUnavailableReason
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** Everything a settlement is computed from, read from the live rows of one meeting. */
internal class MeetingRewardFacts(
    val meetingId: Uuid,
    val committeeId: Uuid,
    /** The wall-clock date of the meeting in the organisation zone (class B value, never converted). */
    val meetingDate: LocalDate,
    val attendance: List<AttendanceFact>,
    val rolesByMember: Map<Uuid, Set<CommitteeRole>>,
    val statusByMember: Map<Uuid, network.lapis.cloud.shared.domain.MemberStatus>,
    /** The id of the latest CONFIRMED protocol version, `null` when there is none. */
    val protocolVersionId: Uuid?,
    /** Why no settlement can exist (`null` = available). The first failing condition wins. */
    val unavailable: MeetingRewardUnavailableReason?,
) {
    val year: Int get() = meetingDate.year
}

/**
 * V1.9.316 -- reads the facts of a meeting for the settlement and decides whether a settlement is available at all.
 *
 * The attendance is bound to the confirmed Protokoll: the number of persons per category AND the names of the persons present must equal the
 * attendance table, otherwise [MeetingRewardUnavailableReason.ATTENDANCE_CHANGED_AFTER_PROTOCOL]. That keeps an administrator from adding themselves as
 * "present" after the Protokoll was confirmed. Known limit: renaming a present member after the confirmation also trips the check (the Protokoll
 * stores names only); the settlement is then unavailable until a corrected Protokoll version is confirmed.
 */
internal object MeetingRewardFactsReader {
    fun load(
        meetingId: Uuid,
        policy: MeetingRewardPolicyValues,
    ): MeetingRewardFacts {
        val meeting = MeetingTable.selectAll().where { MeetingTable.id eq meetingId }.singleOrNull() ?: throw NotFoundException("Not found")
        val committeeId = meeting[MeetingTable.committeeId]
        val date = meeting[MeetingTable.scheduledAt].date
        val rows = AttendanceTable.selectAll().where { AttendanceTable.meetingId eq meetingId }.toList()
        val attendance = rows.map { AttendanceFact(memberId = it[AttendanceTable.memberId], status = it[AttendanceTable.status]) }
        val memberIds = attendance.map { it.memberId }.toSet()
        val roles =
            if (memberIds.isEmpty()) {
                emptyMap()
            } else {
                CommitteeMembershipTable
                    .selectAll()
                    .where {
                        (CommitteeMembershipTable.committeeId eq committeeId) and
                            (CommitteeMembershipTable.memberId inList memberIds) and
                            (CommitteeMembershipTable.since lessEq date) and
                            (CommitteeMembershipTable.until.isNull() or (CommitteeMembershipTable.until greaterEq date))
                    }.groupBy({ it[CommitteeMembershipTable.memberId] }, { it[CommitteeMembershipTable.role] })
                    .mapValues { it.value.toSet() }
            }
        val statuses =
            if (memberIds.isEmpty()) {
                emptyMap()
            } else {
                MemberTable.selectAll().where { MemberTable.id inList memberIds }.associate { it[MemberTable.id] to it[MemberTable.status] }
            }
        val protocol = ProtocolVersionStore.latestConfirmed(meetingId)
        val unavailable =
            when {
                !policy.enabled -> MeetingRewardUnavailableReason.FEATURE_DISABLED
                committeeId !in policy.committeeIds -> MeetingRewardUnavailableReason.COMMITTEE_NOT_ENABLED
                meeting[MeetingTable.status] != MeetingStatus.HELD -> MeetingRewardUnavailableReason.MEETING_NOT_HELD
                protocol == null -> MeetingRewardUnavailableReason.PROTOCOL_NOT_CONFIRMED
                !attendanceMatchesProtocol(
                    rows = rows,
                    contentJson = protocol[ProtocolVersionTable.contentJson],
                    finalisedAt = protocol[ProtocolVersionTable.finalisedAt],
                    names = nameOf(memberIds),
                ) ->
                    MeetingRewardUnavailableReason.ATTENDANCE_CHANGED_AFTER_PROTOCOL
                else -> null
            }
        return MeetingRewardFacts(
            meetingId = meetingId,
            committeeId = committeeId,
            meetingDate = date,
            attendance = attendance,
            rolesByMember = roles,
            statusByMember = statuses,
            protocolVersionId = protocol?.get(ProtocolVersionTable.id),
            unavailable = unavailable,
        )
    }

    private fun nameOf(memberIds: Set<Uuid>): Map<Uuid, String> =
        if (memberIds.isEmpty()) {
            emptyMap()
        } else {
            MemberTable
                .selectAll()
                .where {
                    MemberTable.id inList memberIds
                }.associate { it[MemberTable.id] to it[MemberTable.displayName] }
        }

    private fun attendanceMatchesProtocol(
        rows: List<org.jetbrains.exposed.v1.core.ResultRow>,
        contentJson: String,
        finalisedAt: LocalDateTime,
        names: Map<Uuid, String>,
    ): Boolean {
        // The snapshot stores display names only, so names and counts alone can be spoofed (rename plus swap, or two members with the same name).
        // Any attendance row written after the confirmation therefore invalidates the binding.
        if (rows.any { it[AttendanceTable.recordedAt] > finalisedAt }) return false
        val snapshot = runCatching { ProtocolSnapshots.parse(contentJson) }.getOrNull() ?: return false
        val attendance = snapshot.attendance

        fun count(status: AttendanceStatus) = rows.count { it[AttendanceTable.status] == status }
        val presentNames =
            rows
                .filter {
                    it[AttendanceTable.status] == AttendanceStatus.PRESENT
                }.map { names[it[AttendanceTable.memberId]].orEmpty() }
        return count(AttendanceStatus.PRESENT) == attendance.present.size &&
            count(AttendanceStatus.EXCUSED) == attendance.excused.size &&
            count(AttendanceStatus.UNEXCUSED) == attendance.absent.size &&
            count(AttendanceStatus.REPRESENTED) == attendance.represented.size &&
            count(AttendanceStatus.GUEST) == attendance.guests.size &&
            presentNames.sorted() == attendance.present.map { it.displayName }.sorted()
    }
}
