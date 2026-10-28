package network.lapis.cloud.server.governance.session

import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.rpc.frozenOrLiveElectorate
import network.lapis.cloud.shared.domain.AttendanceRuleKind
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/** One person of a roster. The name is read from the member row, never from the client. */
internal data class RosterPerson(
    val memberId: Uuid,
    val displayName: String,
)

/** An attendance change after the request has been parsed (ids are real UUIDs). `status == null` means "open": the entry is cleared. */
internal data class ValidatedAttendance(
    val memberId: Uuid,
    val status: AttendanceStatus?,
    val representedBy: Uuid?,
)

/**
 * V1.9.236 -- how the attendance of one kind of committee is kept. Exchangeable per [CommitteeType] ([CommitteeAttendanceRules.forType]); the
 * Sitzung panel never decides this itself. Both functions must run inside a transaction. No message of a thrown exception carries a name.
 */
internal interface CommitteeAttendanceRule {
    val kind: AttendanceRuleKind

    /** The people who can be marked present, ordered by display name, then id (deterministic with equal names). */
    fun roster(
        meetingId: Uuid,
        committeeRow: ResultRow,
        scheduledDate: LocalDate,
    ): List<RosterPerson>

    /**
     * Throws [ConflictException] when [input] is not allowed. [roster] is the set of roster ids, [current] the status of every attendance row of
     * the Sitzung, [representatives] the ids that currently stand in for somebody (the `represented_by` values of the Sitzung).
     */
    fun validate(
        input: ValidatedAttendance,
        roster: Set<Uuid>,
        current: Map<Uuid, AttendanceStatus>,
        representatives: Set<Uuid>,
    )
}

/** Attendance kept as a list of names: the electorate of the committee, plus guests. */
internal object NameListAttendanceRule : CommitteeAttendanceRule {
    override val kind: AttendanceRuleKind = AttendanceRuleKind.NAME_LIST

    private const val ID_CHUNK = 500

    override fun roster(
        meetingId: Uuid,
        committeeRow: ResultRow,
        scheduledDate: LocalDate,
    ): List<RosterPerson> {
        // The very same set computeQuorum counts: what is shown and what is calculated stay identical.
        val ids = frozenOrLiveElectorate(meetingId = meetingId, committeeRow = committeeRow, scheduledDate = scheduledDate).memberIds
        if (ids.isEmpty()) return emptyList()
        val people =
            ids.chunked(ID_CHUNK).flatMap { chunk ->
                MemberTable
                    .selectAll()
                    .where { MemberTable.id inList chunk }
                    .map { RosterPerson(memberId = it[MemberTable.id], displayName = it[MemberTable.displayName]) }
            }
        return people.sortedWith(compareBy({ it.displayName.lowercase() }, { it.displayName }, { it.memberId.toString() }))
    }

    override fun validate(
        input: ValidatedAttendance,
        roster: Set<Uuid>,
        current: Map<Uuid, AttendanceStatus>,
        representatives: Set<Uuid>,
    ) {
        val status = input.status
        if (status == null) {
            if (input.representedBy != null) throw ConflictException("representedBy is only allowed with REPRESENTED")
            // Clearing a person whom somebody else stands in for would leave a dangling stand-in.
            if (input.memberId in representatives) throw ConflictException("this person stands in for somebody; clear that entry first")
            return
        }
        if (status == AttendanceStatus.GUEST) {
            if (input.memberId in roster) throw ConflictException("a member of the circle cannot be a guest")
            if (input.representedBy != null) throw ConflictException("representedBy is only allowed with REPRESENTED")
            val isOrganizationMember =
                MemberTable
                    .selectAll()
                    .where { (MemberTable.id eq input.memberId) and (MemberTable.status eq MemberStatus.ACTIVE) }
                    .count() > 0
            if (!isOrganizationMember) throw ConflictException("a guest must be a member of the organization")
            return
        }
        if (input.memberId !in roster) throw ConflictException("this person is not on the roster")
        if (status == AttendanceStatus.REPRESENTED) {
            val standIn = input.representedBy ?: throw ConflictException("REPRESENTED needs a stand-in")
            if (standIn == input.memberId) throw ConflictException("a person cannot stand in for themselves")
            if (standIn !in roster) throw ConflictException("the stand-in must be on the roster")
            if (current[standIn] == AttendanceStatus.REPRESENTED) throw ConflictException("the stand-in is represented themselves")
            if (current[standIn] == AttendanceStatus.EXCUSED || current[standIn] == AttendanceStatus.UNEXCUSED) {
                throw ConflictException("the stand-in is absent themselves")
            }
            // This person cannot be represented while they are somebody's stand-in: that would build a chain.
            if (input.memberId in representatives) throw ConflictException("this person already stands in for somebody")
        } else {
            if (input.representedBy != null) throw ConflictException("representedBy is only allowed with REPRESENTED")
            // A stand-in who is absent would leave the person they represent counted as present by nobody.
            val absent = status == AttendanceStatus.EXCUSED || status == AttendanceStatus.UNEXCUSED
            if (absent && input.memberId in representatives) {
                throw ConflictException("this person stands in for somebody; clear that entry first")
            }
        }
    }
}

/** The general assembly keeps its attendance elsewhere (its roster is the whole membership): the panel offers no attendance for it. */
internal object UnsupportedAttendanceRule : CommitteeAttendanceRule {
    override val kind: AttendanceRuleKind = AttendanceRuleKind.UNSUPPORTED

    override fun roster(
        meetingId: Uuid,
        committeeRow: ResultRow,
        scheduledDate: LocalDate,
    ): List<RosterPerson> = emptyList()

    override fun validate(
        input: ValidatedAttendance,
        roster: Set<Uuid>,
        current: Map<Uuid, AttendanceStatus>,
        representatives: Set<Uuid>,
    ): Unit = throw ConflictException("attendance not supported for this committee kind")
}

internal object CommitteeAttendanceRules {
    fun forType(type: CommitteeType): CommitteeAttendanceRule =
        when (type) {
            CommitteeType.EXECUTIVE_BOARD,
            CommitteeType.WORKING_GROUP,
            CommitteeType.COMMISSION,
            CommitteeType.OTHER,
            -> NameListAttendanceRule
            CommitteeType.GENERAL_ASSEMBLY -> UnsupportedAttendanceRule
        }
}
