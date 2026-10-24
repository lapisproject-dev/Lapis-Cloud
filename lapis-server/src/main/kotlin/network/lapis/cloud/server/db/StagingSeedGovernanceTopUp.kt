package network.lapis.cloud.server.db

import network.lapis.cloud.server.db.StagingSeedGovernance.MotionSeed
import network.lapis.cloud.server.db.StagingSeedLedger.statusesOf
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Committees, meetings and motions for the tender screens (V1.9.205): two committees with seats, three meetings, and five motions in the states
 * that matter for the binding-motion selection (decided and adopted, still open, rejected, an amendment). Complements [StagingSeedGovernance].
 *
 * A decided motion is written like `GovernanceService.resolveMotion` does: the resolution row through the live `insertResolutionRow` (committee
 * quorum from the attendance recorded here), then the motion links to it, and the audit entry (hash chain) comes last. The resolution carries the
 * time of the seed run as its decision time (the live helper takes it from the clock); the meeting itself lies in the past.
 *
 * The amendment (MO5) amends the still open MO3: an amendment of an already decided motion does not exist in the live workflow.
 */
internal object StagingSeedGovernanceTopUp : StagingSeedPart {
    override val name: String = "governance"

    val bauausschuss: Uuid = SeedIds.governanceTopUp(0x01)
    val arbeitskreis: Uuid = SeedIds.governanceTopUp(0x02)
    private val meetingBau: Uuid = SeedIds.governanceTopUp(0x10)
    private val meetingDigital: Uuid = SeedIds.governanceTopUp(0x11)
    private val meetingBauPlanned: Uuid = SeedIds.governanceTopUp(0x12)

    /** Motion ids (fixed). MO1 and MO2 are adopted; MO3 and MO5 are open; MO4 was rejected. */
    val motion1: Uuid = SeedIds.governanceTopUp(0x31)
    val motion2: Uuid = SeedIds.governanceTopUp(0x32)
    val motion3: Uuid = SeedIds.governanceTopUp(0x33)
    val motion4: Uuid = SeedIds.governanceTopUp(0x34)
    val motion5: Uuid = SeedIds.governanceTopUp(0x35)

    private val admin = CurrentMember(memberId = SeedIds.member(1), role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)

    /** Seat plan: committee id, member number, role. Append-only (the position is not the id: see [seatId]). */
    data class Seat(
        val index: Int,
        val committee: Uuid,
        val member: Int,
        val role: CommitteeRole,
    )

    val seats: List<Seat> =
        listOf(
            Seat(index = 1, committee = bauausschuss, member = 1, role = CommitteeRole.CHAIR),
            Seat(index = 2, committee = bauausschuss, member = 2, role = CommitteeRole.MEMBER),
            Seat(index = 3, committee = bauausschuss, member = 4, role = CommitteeRole.MEMBER),
            Seat(index = 4, committee = bauausschuss, member = 5, role = CommitteeRole.MEMBER),
            Seat(index = 5, committee = bauausschuss, member = 9, role = CommitteeRole.MEMBER),
            Seat(index = 6, committee = arbeitskreis, member = 3, role = CommitteeRole.CHAIR),
            Seat(index = 7, committee = arbeitskreis, member = 6, role = CommitteeRole.MEMBER),
            Seat(index = 8, committee = arbeitskreis, member = 7, role = CommitteeRole.MEMBER),
            Seat(index = 9, committee = arbeitskreis, member = 10, role = CommitteeRole.MEMBER),
        )

    private fun seatId(seat: Seat): Uuid = SeedIds.governanceTopUp(0x2000 + seat.index)

    private fun attendanceId(seat: Seat): Uuid = SeedIds.governanceTopUp(0x3000 + seat.index)

    private fun agendaId(n: Int): Uuid = SeedIds.governanceTopUp(0x20 + n)

    /** Every member number the part touches; all must be ACTIVE for the part to write anything. */
    val members: Set<Int> = seats.map { it.member }.toSet() + setOf(1, 2, 3, 5, 9)

    override fun JdbcTransaction.apply(clock: SeedClock): StagingSeedPartResult {
        val statuses = statusesOf(members)
        if (members.any { statuses[it] != MemberStatus.ACTIVE }) return StagingSeedPartResult(inserted = 0, skipped = 1)
        var inserted = 0
        var skipped = 0

        fun exists(
            table: org.jetbrains.exposed.v1.core.Table,
            id: org.jetbrains.exposed.v1.core.Column<Uuid>,
            value: Uuid,
        ): Boolean = !table.selectAll().where { id eq value }.empty()

        val created = clock.utcAt(daysAgo = 200)
        with(StagingSeedGovernance) {
            if (!exists(CommitteeTable, CommitteeTable.id, bauausschuss)) {
                insertCommittee(
                    id = bauausschuss,
                    name = "Bauausschuss Vereinsheim (Demodaten)",
                    type = CommitteeType.COMMISSION,
                    description = "Begleitet Umbauten und Instandhaltung des Vereinsheims (Demodaten).",
                    quorumPercent = 50,
                    createdAt = created,
                )
                inserted++
            }
            if (!exists(CommitteeTable, CommitteeTable.id, arbeitskreis)) {
                insertCommittee(
                    id = arbeitskreis,
                    name = "Arbeitskreis Digitales (Demodaten)",
                    type = CommitteeType.WORKING_GROUP,
                    description = "Kuemmert sich um Webseite und digitale Werkzeuge (Demodaten).",
                    quorumPercent = 50,
                    createdAt = created,
                )
                inserted++
            }
        }
        seats.forEach { seat ->
            if (!exists(CommitteeMembershipTable, CommitteeMembershipTable.id, seatId(seat))) {
                CommitteeMembershipTable.insert {
                    it[id] = seatId(seat)
                    it[role] = seat.role
                    it[since] = clock.dayAgo(199)
                    it[committeeId] = seat.committee
                    it[memberId] = SeedIds.member(seat.member)
                }
                inserted++
            }
        }

        with(StagingSeedGovernance) {
            fun meeting(
                id: Uuid,
                title: String,
                dayOffset: Int,
                format: MeetingFormat,
                status: MeetingStatus,
                committee: Uuid,
                chair: Int,
            ) {
                if (exists(MeetingTable, MeetingTable.id, id)) return
                insertMeeting(
                    id = id,
                    title = title,
                    scheduledAt = clock.wallAt(daysFromToday = dayOffset, hour = 18),
                    format = format,
                    status = status,
                    committeeId = committee,
                    chair = SeedIds.member(chair),
                    clock = clock,
                )
                inserted++
            }
            meeting(meetingBau, "Sitzung Bauausschuss (Demodaten)", -14, MeetingFormat.IN_PERSON, MeetingStatus.HELD, bauausschuss, 1)
            meeting(
                meetingDigital,
                "Sitzung Arbeitskreis Digitales (Demodaten)",
                -10,
                MeetingFormat.ONLINE,
                MeetingStatus.HELD,
                arbeitskreis,
                3,
            )
            meeting(
                meetingBauPlanned,
                "Naechste Sitzung Bauausschuss (Demodaten)",
                14,
                MeetingFormat.IN_PERSON,
                MeetingStatus.PLANNED,
                bauausschuss,
                1,
            )

            fun agenda(
                n: Int,
                meetingId: Uuid,
                position: Int,
                title: String,
            ) {
                if (exists(AgendaItemTable, AgendaItemTable.id, agendaId(n))) return
                insertAgendaItem(meetingId = meetingId, position = position, title = title, presenter = null, fixedId = agendaId(n))
                inserted++
            }
            agenda(1, meetingBau, 1, "Begruessung")
            agenda(2, meetingBau, 2, "Antrag Kuechensanierung")
            agenda(3, meetingBau, 3, "Antrag Stellplaetze")
            agenda(4, meetingDigital, 1, "Begruessung")
            agenda(5, meetingDigital, 2, "Antrag Vereinswebseite")
            agenda(6, meetingBauPlanned, 1, "Begruessung")
        }

        // Attendance: everybody with a seat was present (the quorum of the decisions below needs it).
        fun present(
            seat: Seat,
            meetingId: Uuid,
        ) {
            if (exists(AttendanceTable, AttendanceTable.id, attendanceId(seat))) return
            val scheduled = MeetingTable.selectAll().where { MeetingTable.id eq meetingId }.single()[MeetingTable.scheduledAt]
            AttendanceTable.insert {
                it[id] = attendanceId(seat)
                it[status] = AttendanceStatus.PRESENT
                it[recordedAt] = clock.wallToUtc(scheduled)
                it[AttendanceTable.meetingId] = meetingId
                it[memberId] = SeedIds.member(seat.member)
            }
            inserted++
        }
        seats.forEach { seat -> present(seat, if (seat.committee == bauausschuss) meetingBau else meetingDigital) }

        data class Planned(
            val seed: MotionSeed,
            val meetingId: Uuid?,
            val agendaN: Int?,
            val result: ResolutionStatus?,
            val votes: Triple<Int, Int, Int>?,
            val amends: Uuid? = null,
        )

        fun motion(
            id: Uuid,
            committee: Uuid,
            title: String,
            text: String,
            submitter: Int,
            status: MotionStatus,
            daysAgo: Int,
        ) = MotionSeed(
            id = id,
            committeeId = committee,
            title = title,
            rationale = "Demodaten: Begruendung zum Antrag \"$title\".",
            text = text,
            submitter = SeedIds.member(submitter),
            status = status,
            submittedAt = clock.utcAt(daysAgo = daysAgo),
        )
        val planned =
            listOf(
                Planned(
                    motion(
                        motion1,
                        bauausschuss,
                        "Sanierung der Vereinsheim-Kueche (Demodaten)",
                        "Der Ausschuss moege die Sanierung der Vereinsheim-Kueche befuerworten.",
                        1,
                        MotionStatus.SCHEDULED,
                        40,
                    ),
                    meetingBau,
                    2,
                    ResolutionStatus.ADOPTED,
                    Triple(4, 0, 1),
                ),
                Planned(
                    motion(
                        motion2,
                        arbeitskreis,
                        "Neue Vereinswebseite (Demodaten)",
                        "Der Arbeitskreis moege eine neue Vereinswebseite beauftragen.",
                        3,
                        MotionStatus.SCHEDULED,
                        35,
                    ),
                    meetingDigital,
                    5,
                    ResolutionStatus.ADOPTED,
                    Triple(3, 0, 1),
                ),
                Planned(
                    motion(
                        motion3,
                        bauausschuss,
                        "Anschaffung eines Geschirrmobils (Demodaten)",
                        "Der Ausschuss moege ein fahrbares Geschirrmobil fuer das Vereinsheim anschaffen.",
                        4,
                        MotionStatus.SUBMITTED,
                        5,
                    ),
                    null,
                    null,
                    null,
                    null,
                ),
                Planned(
                    motion(
                        motion4,
                        bauausschuss,
                        "Erweiterung der Stellplaetze (Demodaten)",
                        "Der Ausschuss moege zusaetzliche Stellplaetze am Vereinsheim anlegen.",
                        5,
                        MotionStatus.SCHEDULED,
                        38,
                    ),
                    meetingBau,
                    3,
                    ResolutionStatus.REJECTED,
                    Triple(1, 3, 1),
                ),
                Planned(
                    motion(
                        motion5,
                        bauausschuss,
                        "Aenderungsantrag zum Geschirrmobil (Demodaten)",
                        "Das Geschirrmobil soll zusaetzlich einen abschliessbaren Unterbau erhalten.",
                        9,
                        MotionStatus.SUBMITTED,
                        3,
                    ),
                    null,
                    null,
                    null,
                    null,
                    amends = motion3,
                ),
            )
        val toDecide = mutableListOf<Planned>()
        with(StagingSeedGovernance) {
            planned.forEach { p ->
                if (exists(MotionTable, MotionTable.id, p.seed.id)) {
                    skipped++
                    return@forEach
                }
                insertMotion(motion = p.seed, meetingId = p.meetingId, agendaItemId = p.agendaN?.let { agendaId(it) })
                if (p.amends != null) MotionTable.update({ MotionTable.id eq p.seed.id }) { it[amendsMotionId] = p.amends }
                if (p.result != null) toDecide += p
                inserted++
            }
            // Each decision: resolution row (quorum from the attendance), motion link, audit entry last.
            toDecide.forEach { p ->
                decideByCommittee(
                    motionId = p.seed.id,
                    meetingId = p.meetingId!!,
                    committeeId = p.seed.committeeId,
                    agendaItemId = agendaId(p.agendaN!!),
                    title = p.seed.title,
                    text = p.seed.text,
                    status = p.result!!,
                    votes = p.votes!!,
                    actor = admin,
                )
            }
        }
        return StagingSeedPartResult(inserted = inserted, skipped = skipped)
    }
}
