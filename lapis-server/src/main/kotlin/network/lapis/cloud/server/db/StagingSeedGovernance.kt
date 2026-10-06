package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionCandidacyTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.PollOptionTable
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollResponseTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.server.rpc.MotionDecisionLock
import network.lapis.cloud.server.rpc.auditResolutionCreate
import network.lapis.cloud.server.rpc.eligibleMemberIds
import network.lapis.cloud.server.rpc.insertResolutionRow
import network.lapis.cloud.server.rpc.lockElectionRow
import network.lapis.cloud.server.rpc.recordElectionBallotLocked
import network.lapis.cloud.server.rpc.tallyElectionLocked
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionAnswer
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.PollKind
import network.lapis.cloud.shared.domain.PollSnapshot
import network.lapis.cloud.shared.domain.PollStatus
import network.lapis.cloud.shared.domain.ResolutionInput
import network.lapis.cloud.shared.domain.ResolutionStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Staging seed, area "governance" (Welle V1.9.63): committees, meetings with agenda and attendance, motions in every status,
 * a CLOSED + tallied secret yes/no election, a RUNNING secret personnel election, and two polls (one closed, one running).
 *
 * Everything goes through the code the live services use, wherever that works without an HTTP call: resolutions via
 * [insertResolutionRow] + [auditResolutionCreate] (the resolution book), and the elections via [recordElectionBallotLocked]
 * and [tallyElectionLocked] (the cast and tally cores extracted from `ElectionService`). This object never reads or writes a
 * ballot table itself, so the allowlist of `ServerElectionDisclosureTripwireTest` stays untouched.
 *
 * **Ballot secrecy.** The people who vote are drawn from the electoral roll independently of the choices, the plan of choices is
 * a plain list of answers without people, and no code, log line or test pairs the two. `election_ballot.member_id` is NULL and
 * `cast_at` is the constant `voting_opened_at`, exactly as in a live secret election. Ballot and receipt ids are random.
 *
 * **Honest limits.** Resolutions, tally timestamps and audit entries carry the moment of the seed run (the live code stamps
 * "now"; the seed does not back-date audit evidence). The poll results stay unweighted: there is no LTR data, so the weighted
 * result reads `ZERO_TOTAL_WEIGHT`.
 */
internal object StagingSeedGovernance {
    private val boardCommitteeId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000010")
    private val pressCommitteeId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000011")
    private val assemblyCommitteeId: Uuid = SeedIds.governance(0x01)
    private val auditorsCommitteeId: Uuid = SeedIds.governance(0x02)

    private val plannedMeetingId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000020")
    private val heldMeetingId: Uuid = Uuid.parse("0000000a-0000-0000-0000-000000000021")
    private val olderBoardMeetingId: Uuid = SeedIds.governance(0x10)
    private val oldBoardMeetingId: Uuid = SeedIds.governance(0x11)
    private val heldAssemblyId: Uuid = SeedIds.governance(0x12)
    private val onlineAssemblyId: Uuid = SeedIds.governance(0x13)

    private const val ASSEMBLY_QUORUM_PERCENT = 10
    private const val BOARD_QUORUM_PERCENT = 50

    /** What an election needs from the seed: the motion that is decided, the meeting it belongs to and the electoral board. */
    private class ElectionContext(
        val electionId: Uuid,
        val motionId: Uuid,
        val meetingId: Uuid,
        val boardMemberIds: List<Uuid>,
    )

    fun JdbcTransaction.seedGovernance(
        clock: SeedClock,
        actors: SeedActors,
    ) {
        insertCommittees(clock = clock, actors = actors)

        // Board meetings: 3 held (+ the original "Q2"), 1 planned.
        insertMeeting(
            id = olderBoardMeetingId,
            title = "Vorstandssitzung Nr. 7",
            scheduledAt = clock.wallAt(daysFromToday = -120, hour = 19),
            format = MeetingFormat.IN_PERSON,
            status = MeetingStatus.HELD,
            committeeId = boardCommitteeId,
            chair = actors.admin.id,
            clock = clock,
        )
        insertMeeting(
            id = oldBoardMeetingId,
            title = "Vorstandssitzung Nr. 8",
            scheduledAt = clock.wallAt(daysFromToday = -60, hour = 19),
            format = MeetingFormat.ONLINE,
            status = MeetingStatus.HELD,
            committeeId = boardCommitteeId,
            chair = actors.admin.id,
            clock = clock,
        )
        insertMeeting(
            id = heldMeetingId,
            title = "Vorstandssitzung Q2",
            scheduledAt = clock.wallAt(daysFromToday = -30, hour = 19),
            format = MeetingFormat.IN_PERSON,
            status = MeetingStatus.HELD,
            committeeId = boardCommitteeId,
            chair = actors.admin.id,
            clock = clock,
        )
        insertMeeting(
            id = plannedMeetingId,
            title = "Vorstandssitzung Q3",
            scheduledAt = clock.wallAt(daysFromToday = 14, hour = 19),
            format = MeetingFormat.ONLINE,
            status = MeetingStatus.PLANNED,
            committeeId = boardCommitteeId,
            chair = actors.admin.id,
            clock = clock,
        )
        // Member assemblies: one held in person, one running online right now (the live election belongs to it).
        insertMeeting(
            id = heldAssemblyId,
            title = "Ordentliche Mitgliederversammlung",
            scheduledAt = clock.wallAt(daysFromToday = -45, hour = 18),
            format = MeetingFormat.IN_PERSON,
            status = MeetingStatus.HELD,
            committeeId = assemblyCommitteeId,
            chair = actors.admin.id,
            clock = clock,
        )
        insertMeeting(
            id = onlineAssemblyId,
            title = "Online-Mitgliederversammlung",
            scheduledAt = clock.wallAt(daysFromToday = -1, hour = 18),
            format = MeetingFormat.ONLINE,
            status = MeetingStatus.HELD,
            committeeId = assemblyCommitteeId,
            chair = actors.admin.id,
            clock = clock,
        )

        val boardAttendees = listOf(actors.admin, actors.board, actors.treasurer)
        listOf(olderBoardMeetingId, oldBoardMeetingId, heldMeetingId).forEach { meetingId ->
            recordAttendance(meetingId = meetingId, clock = clock, present = boardAttendees)
        }
        // One excused absence, so the attendance list is not uniformly green.
        excuse(meetingId = oldBoardMeetingId, memberId = actors.treasurer.id)
        val assemblyAttendees = actors.active.filter { it.activeSinceDaysAgo != null && it.activeSinceDaysAgo > 45 }
        recordAttendance(meetingId = heldAssemblyId, clock = clock, present = assemblyAttendees.take(16))

        seedBoardMotions(clock = clock, actors = actors)

        val fee = seedClosedFeeElection(clock = clock, actors = actors)
        // The running election is deliberately never tallied: it stays OPEN for a live tester.
        seedRunningAuditorsElection(clock = clock, actors = actors)
        tallyClosedElection(fee = fee, actors = actors)

        seedPolls(clock = clock, actors = actors)
    }

    // ---- committees -------------------------------------------------------------------------------------------------------

    private fun JdbcTransaction.insertCommittees(
        clock: SeedClock,
        actors: SeedActors,
    ) {
        val created = clock.utcAt(daysAgo = 720)
        insertCommittee(
            id = boardCommitteeId,
            name = "Vorstand",
            type = CommitteeType.EXECUTIVE_BOARD,
            description = "Geschaeftsfuehrender Vorstand des Testvereins.",
            quorumPercent = BOARD_QUORUM_PERCENT,
            createdAt = created,
        )
        insertCommittee(
            id = pressCommitteeId,
            name = "AG Oeffentlichkeitsarbeit",
            type = CommitteeType.WORKING_GROUP,
            description = "Arbeitsgruppe fuer Presse- und Oeffentlichkeitsarbeit.",
            quorumPercent = BOARD_QUORUM_PERCENT,
            createdAt = created,
        )
        // GENERAL_ASSEMBLY: every ACTIVE member is eligible, no membership rows.
        insertCommittee(
            id = assemblyCommitteeId,
            name = "Mitgliederversammlung",
            type = CommitteeType.GENERAL_ASSEMBLY,
            description = "Die Versammlung aller aktiven Mitglieder des Testvereins.",
            quorumPercent = ASSEMBLY_QUORUM_PERCENT,
            createdAt = created,
        )
        insertCommittee(
            id = auditorsCommitteeId,
            name = "Kassenpruefung",
            type = CommitteeType.COMMISSION,
            description = "Prueft die Kassenfuehrung des Testvereins (Zielgremium der laufenden Wahl).",
            quorumPercent = BOARD_QUORUM_PERCENT,
            createdAt = created,
        )

        val since = clock.dayAgo(719)
        insertMembership(committeeId = boardCommitteeId, memberId = actors.admin.id, role = CommitteeRole.CHAIR, since = since)
        insertMembership(committeeId = boardCommitteeId, memberId = actors.board.id, role = CommitteeRole.DEPUTY_CHAIR, since = since)
        insertMembership(committeeId = boardCommitteeId, memberId = actors.treasurer.id, role = CommitteeRole.SECRETARY, since = since)
        val workingGroup = actors.members.filter { it.accountRole == AccountRole.MEMBER }
        workingGroup.take(3).forEachIndexed { index, member ->
            insertMembership(
                committeeId = pressCommitteeId,
                memberId = member.id,
                role = if (index == 0) CommitteeRole.CHAIR else CommitteeRole.MEMBER,
                since = since,
            )
        }
    }

    private fun JdbcTransaction.insertCommittee(
        id: Uuid,
        name: String,
        type: CommitteeType,
        description: String,
        quorumPercent: Int,
        createdAt: LocalDateTime,
    ) {
        CommitteeTable.insert {
            it[CommitteeTable.id] = id
            it[CommitteeTable.name] = name
            it[CommitteeTable.type] = type
            it[CommitteeTable.description] = description
            it[active] = true
            it[CommitteeTable.quorumPercent] = quorumPercent
            it[CommitteeTable.createdAt] = createdAt
        }
    }

    private fun JdbcTransaction.insertMembership(
        committeeId: Uuid,
        memberId: Uuid,
        role: CommitteeRole,
        since: LocalDate,
    ) {
        CommitteeMembershipTable.insert {
            it[id] = Uuid.random()
            it[CommitteeMembershipTable.role] = role
            it[CommitteeMembershipTable.since] = since
            it[CommitteeMembershipTable.committeeId] = committeeId
            it[CommitteeMembershipTable.memberId] = memberId
        }
    }

    // ---- meetings ---------------------------------------------------------------------------------------------------------

    private fun JdbcTransaction.insertMeeting(
        id: Uuid,
        title: String,
        scheduledAt: LocalDateTime,
        format: MeetingFormat,
        status: MeetingStatus,
        committeeId: Uuid,
        chair: Uuid,
        clock: SeedClock,
    ) {
        MeetingTable.insert {
            it[MeetingTable.id] = id
            it[MeetingTable.title] = title
            it[MeetingTable.scheduledAt] = scheduledAt
            it[MeetingTable.format] = format
            it[MeetingTable.status] = status
            it[chairMemberId] = chair
            // class A: the creation stamp, a week before the (class B wall-clock) date, never in the future
            it[createdAt] = minOf(clock.wallToUtc(scheduledAt).let { utc -> clock.utcShift(base = utc, byDays = -7) }, clock.now)
            it[MeetingTable.committeeId] = committeeId
        }
    }

    private fun JdbcTransaction.insertAgendaItem(
        meetingId: Uuid,
        position: Int,
        title: String,
        presenter: Uuid?,
    ): Uuid {
        val agendaItemId = Uuid.random()
        AgendaItemTable.insert {
            it[id] = agendaItemId
            it[AgendaItemTable.position] = position
            it[AgendaItemTable.title] = title
            it[presenterMemberId] = presenter
            it[AgendaItemTable.meetingId] = meetingId
        }
        return agendaItemId
    }

    private fun JdbcTransaction.recordAttendance(
        meetingId: Uuid,
        clock: SeedClock,
        present: List<SeedMemberRef>,
    ) {
        val scheduled = MeetingTable.selectAll().where { MeetingTable.id eq meetingId }.single()[MeetingTable.scheduledAt]
        present.forEach { member ->
            AttendanceTable.insert {
                it[id] = Uuid.random()
                it[status] = AttendanceStatus.PRESENT
                it[recordedAt] = clock.wallToUtc(scheduled)
                it[AttendanceTable.meetingId] = meetingId
                it[memberId] = member.id
            }
        }
    }

    private fun JdbcTransaction.excuse(
        meetingId: Uuid,
        memberId: Uuid,
    ) {
        AttendanceTable.update({ (AttendanceTable.meetingId eq meetingId) and (AttendanceTable.memberId eq memberId) }) {
            it[status] = AttendanceStatus.EXCUSED
            it[note] = "Entschuldigt (Demodaten)."
        }
    }

    // ---- motions ----------------------------------------------------------------------------------------------------------

    private class MotionSeed(
        val id: Uuid,
        val committeeId: Uuid,
        val title: String,
        val rationale: String,
        val text: String,
        val submitter: Uuid,
        val status: MotionStatus,
        val submittedAt: LocalDateTime,
    )

    private fun JdbcTransaction.insertMotion(
        motion: MotionSeed,
        meetingId: Uuid? = null,
        agendaItemId: Uuid? = null,
        reviewer: Uuid? = null,
        reviewedAt: LocalDateTime? = null,
        reviewNote: String? = null,
        withdrawnAt: LocalDateTime? = null,
    ) {
        MotionTable.insert {
            it[id] = motion.id
            it[targetCommitteeId] = motion.committeeId
            it[title] = motion.title
            it[rationale] = motion.rationale
            it[text] = motion.text
            it[submitterMemberId] = motion.submitter
            it[status] = motion.status
            it[submittedAt] = motion.submittedAt
            it[MotionTable.meetingId] = meetingId
            it[MotionTable.agendaItemId] = agendaItemId
            it[reviewedBy] = reviewer
            it[MotionTable.reviewedAt] = reviewedAt
            it[MotionTable.reviewNote] = reviewNote
            it[MotionTable.withdrawnAt] = withdrawnAt
        }
    }

    /**
     * Records the decision of a SCHEDULED motion exactly like `GovernanceService.resolveMotion`: resolution row (committee
     * quorum), then the motion becomes RESOLVED / REJECTED / POSTPONED and links to it, the audit entry comes last.
     */
    private fun JdbcTransaction.decideByCommittee(
        motionId: Uuid,
        meetingId: Uuid,
        committeeId: Uuid,
        agendaItemId: Uuid,
        title: String,
        text: String,
        status: ResolutionStatus,
        votes: Triple<Int, Int, Int>,
        actor: CurrentMember,
    ) {
        val meetingDate =
            MeetingTable
                .selectAll()
                .where { MeetingTable.id eq meetingId }
                .single()[MeetingTable.scheduledAt]
                .date
        val resolution =
            insertResolutionRow(
                sId = meetingId,
                committeeId = committeeId,
                scheduledDate = meetingDate,
                input =
                    ResolutionInput(
                        agendaItemId = agendaItemId.toString(),
                        title = title,
                        text = text,
                        votesYes = votes.first,
                        votesNo = votes.second,
                        votesAbstain = votes.third,
                        status = status,
                    ),
                current = actor,
            )
        val newStatus =
            when (status) {
                ResolutionStatus.ADOPTED -> MotionStatus.RESOLVED
                ResolutionStatus.REJECTED -> MotionStatus.REJECTED
                ResolutionStatus.POSTPONED -> MotionStatus.POSTPONED
            }
        MotionTable.update({ MotionTable.id eq motionId }) {
            it[MotionTable.status] = newStatus
            it[resolutionId] = Uuid.parse(resolution.id)
        }
        auditResolutionCreate(resolution = resolution, current = actor)
    }

    private fun JdbcTransaction.seedBoardMotions(
        clock: SeedClock,
        actors: SeedActors,
    ) {
        val admin = actors.admin
        val board = actors.board
        val treasurer = actors.treasurer
        val member = actors.members.first { it.accountRole == AccountRole.MEMBER }

        fun motion(
            n: Int,
            title: String,
            text: String,
            submitter: Uuid,
            status: MotionStatus,
            submittedDaysAgo: Int,
        ) = MotionSeed(
            id = SeedIds.governance(0x20 + n),
            committeeId = boardCommitteeId,
            title = title,
            rationale = "Demodaten: Begruendung zum Antrag \"$title\".",
            text = text,
            submitter = submitter,
            status = status,
            submittedAt = clock.utcAt(daysAgo = submittedDaysAgo),
        )

        // SUBMITTED, REVIEWED, REJECTED_PRELIMINARY, WITHDRAWN: the early-stage and abandoned states.
        insertMotion(
            motion =
                motion(
                    1,
                    "Anschaffung neuer Vereinswebseite",
                    "Der Vorstand moege beschliessen, ein Budget fuer eine neue Webseite freizugeben.",
                    board.id,
                    MotionStatus.SUBMITTED,
                    submittedDaysAgo = 2,
                ),
        )
        insertMotion(
            motion =
                motion(
                    2,
                    "Einfuehrung eines Mitglieder-Newsletters",
                    "Der Vorstand moege einen vierteljaehrlichen Newsletter fuer Mitglieder einrichten.",
                    member.id,
                    MotionStatus.REVIEWED,
                    submittedDaysAgo = 12,
                ),
            reviewer = admin.id,
            reviewedAt = clock.utcAt(daysAgo = 9),
            reviewNote = "Formal in Ordnung, wird fuer die naechste Sitzung vorgemerkt.",
        )
        insertMotion(
            motion =
                motion(
                    3,
                    "Abschaffung aller Mitgliedsbeitraege",
                    "Der Vorstand moege beschliessen, saemtliche Beitraege ersatzlos abzuschaffen.",
                    member.id,
                    MotionStatus.REJECTED_PRELIMINARY,
                    submittedDaysAgo = 35,
                ),
            reviewer = admin.id,
            reviewedAt = clock.utcAt(daysAgo = 33),
            reviewNote = "Widerspricht der Satzung (Beitragspflicht), daher nicht zur Abstimmung zugelassen.",
        )
        insertMotion(
            motion =
                motion(
                    4,
                    "Umbenennung des Vereins",
                    "Der Vorstand moege dem Verein einen neuen Namen vorschlagen.",
                    treasurer.id,
                    MotionStatus.WITHDRAWN,
                    submittedDaysAgo = 50,
                ),
            withdrawnAt = clock.utcAt(daysAgo = 47),
        )

        // SCHEDULED for the planned meeting (the original demo motion).
        val plannedAgenda1 = insertAgendaItem(meetingId = plannedMeetingId, position = 1, title = "Begruessung", presenter = admin.id)
        insertAgendaItem(meetingId = plannedMeetingId, position = 2, title = "Kassenbericht", presenter = treasurer.id)
        val plannedAgenda3 = insertAgendaItem(meetingId = plannedMeetingId, position = 3, title = "Antraege", presenter = admin.id)
        insertAgendaItem(meetingId = plannedMeetingId, position = 4, title = "Sonstiges", presenter = null)
        insertMotion(
            motion =
                motion(
                    5,
                    "Erhoehung des Foerdermitgliedsbeitrags",
                    "Der Vorstand moege beschliessen, den Foerdermitgliedsbeitrag auf 150 EUR/Jahr anzuheben.",
                    treasurer.id,
                    MotionStatus.SCHEDULED,
                    submittedDaysAgo = 5,
                ),
            meetingId = plannedMeetingId,
            agendaItemId = plannedAgenda1,
        )
        insertMotion(
            motion =
                motion(
                    6,
                    "Neue Vereinsfarben",
                    "Der Vorstand moege neue Vereinsfarben festlegen.",
                    member.id,
                    MotionStatus.SCHEDULED,
                    submittedDaysAgo = 4,
                ),
            meetingId = plannedMeetingId,
            agendaItemId = plannedAgenda3,
        )

        // RESOLVED / REJECTED / POSTPONED by committee quorum, in the held board meetings.
        val olderAgenda = insertAgendaItem(meetingId = olderBoardMeetingId, position = 1, title = "Antrag Beamer", presenter = admin.id)
        insertAgendaItem(meetingId = olderBoardMeetingId, position = 2, title = "Sonstiges", presenter = null)
        val oldAgenda = insertAgendaItem(meetingId = oldBoardMeetingId, position = 1, title = "Antrag Sommerfest", presenter = board.id)
        insertAgendaItem(meetingId = oldBoardMeetingId, position = 2, title = "Kassenbericht", presenter = treasurer.id)
        insertAgendaItem(meetingId = heldMeetingId, position = 1, title = "Begruessung", presenter = admin.id)
        insertAgendaItem(meetingId = heldMeetingId, position = 2, title = "Kassenbericht", presenter = treasurer.id)
        val heldAgenda3 = insertAgendaItem(meetingId = heldMeetingId, position = 3, title = "Antrag Vereinsfahne", presenter = board.id)
        insertAgendaItem(meetingId = heldMeetingId, position = 4, title = "Sonstiges", presenter = null)

        data class Decided(
            val motion: MotionSeed,
            val meetingId: Uuid,
            val agendaItemId: Uuid,
            val status: ResolutionStatus,
            val votes: Triple<Int, Int, Int>,
        )
        val decided =
            listOf(
                Decided(
                    motion(
                        7,
                        "Beschaffung eines Beamers",
                        "Der Vorstand moege einen Beamer fuer Veranstaltungen anschaffen (bis 600 EUR).",
                        treasurer.id,
                        MotionStatus.SCHEDULED,
                        submittedDaysAgo = 130,
                    ),
                    olderBoardMeetingId,
                    olderAgenda,
                    ResolutionStatus.ADOPTED,
                    Triple(3, 0, 0),
                ),
                Decided(
                    motion(
                        8,
                        "Sommerfest im Vereinsheim",
                        "Der Vorstand moege ein Sommerfest im Vereinsheim ausrichten.",
                        member.id,
                        MotionStatus.SCHEDULED,
                        submittedDaysAgo = 70,
                    ),
                    oldBoardMeetingId,
                    oldAgenda,
                    ResolutionStatus.REJECTED,
                    Triple(1, 2, 0),
                ),
                Decided(
                    motion(
                        9,
                        "Anschaffung einer Vereinsfahne",
                        "Der Vorstand moege eine Vereinsfahne in Auftrag geben.",
                        board.id,
                        MotionStatus.SCHEDULED,
                        submittedDaysAgo = 40,
                    ),
                    heldMeetingId,
                    heldAgenda3,
                    ResolutionStatus.POSTPONED,
                    Triple(1, 1, 1),
                ),
            )
        decided.forEach { insertMotion(motion = it.motion, meetingId = it.meetingId, agendaItemId = it.agendaItemId) }
        decided.forEach {
            decideByCommittee(
                motionId = it.motion.id,
                meetingId = it.meetingId,
                committeeId = boardCommitteeId,
                agendaItemId = it.agendaItemId,
                title = it.motion.title,
                text = it.motion.text,
                status = it.status,
                votes = it.votes,
                actor = actors.adminActor,
            )
        }
    }

    // ---- elections --------------------------------------------------------------------------------------------------------

    private fun JdbcTransaction.insertAssemblyMotion(
        id: Uuid,
        meetingId: Uuid,
        title: String,
        text: String,
        submitter: Uuid,
        submittedAt: LocalDateTime,
        agendaTitle: String,
        presenter: Uuid,
    ): Uuid {
        val agendaItemId = insertAgendaItem(meetingId = meetingId, position = 1, title = agendaTitle, presenter = presenter)
        insertMotion(
            motion =
                MotionSeed(
                    id = id,
                    committeeId = assemblyCommitteeId,
                    title = title,
                    rationale = "Demodaten: Begruendung zum Antrag \"$title\".",
                    text = text,
                    submitter = submitter,
                    status = MotionStatus.SCHEDULED,
                    submittedAt = submittedAt,
                ),
            meetingId = meetingId,
            agendaItemId = agendaItemId,
        )
        return agendaItemId
    }

    private fun JdbcTransaction.insertElection(
        id: Uuid,
        motionId: Uuid,
        meetingId: Uuid,
        title: String,
        type: ElectionType,
        seatCount: Int,
        targetCommitteeId: Uuid?,
        status: ElectionStatus,
        openedBy: Uuid,
        openedAt: LocalDateTime,
        candidateListApprovedAt: LocalDateTime?,
        votingOpenedAt: LocalDateTime,
        votingClosedAt: LocalDateTime?,
    ) {
        // Mirrors ElectionService.openElection (+ the later status steps): the unique active_motion_id is the motion itself.
        ElectionTable.insert {
            it[ElectionTable.id] = id
            it[ElectionTable.motionId] = motionId
            it[activeMotionId] = motionId
            it[ElectionTable.meetingId] = meetingId
            it[ElectionTable.title] = title
            it[electionType] = type
            it[secret] = true
            it[ElectionTable.seatCount] = seatCount
            it[ElectionTable.targetCommitteeId] = targetCommitteeId
            it[targetRole] = if (targetCommitteeId != null) CommitteeRole.MEMBER else null
            it[requiredMajorityPercent] = REQUIRED_MAJORITY_PERCENT
            it[ElectionTable.status] = status
            it[ElectionTable.openedBy] = openedBy
            it[ElectionTable.openedAt] = openedAt
            it[ElectionTable.candidateListApprovedAt] = candidateListApprovedAt
            it[ElectionTable.votingOpenedAt] = votingOpenedAt
            it[ElectionTable.votingClosedAt] = votingClosedAt
            it[tallyThreshold] = TALLY_THRESHOLD
            it[tallyRunAt] = null
            it[resolutionId] = null
        }
    }

    private fun JdbcTransaction.appointElectionBoard(
        electionId: Uuid,
        memberIds: List<Uuid>,
        appointedAt: LocalDateTime,
    ) {
        memberIds.forEach { memberId ->
            ElectionBoardMemberTable.insert {
                it[id] = Uuid.random()
                it[ElectionBoardMemberTable.electionId] = electionId
                it[ElectionBoardMemberTable.memberId] = memberId
                it[ElectionBoardMemberTable.appointedAt] = appointedAt
            }
        }
    }

    /** The electoral roll snapshot of `openVoting`: everybody `eligibleMemberIds` yields for the meeting's committee. */
    private fun JdbcTransaction.snapshotElectoralRoll(
        electionId: Uuid,
        meetingId: Uuid,
        committeeId: Uuid,
    ): List<Uuid> {
        val committeeRow = CommitteeTable.selectAll().where { CommitteeTable.id eq committeeId }.single()
        val scheduledDate =
            MeetingTable
                .selectAll()
                .where { MeetingTable.id eq meetingId }
                .single()[MeetingTable.scheduledAt]
                .date
        val eligible = eligibleMemberIds(committeeRow = committeeRow, scheduledDate = scheduledDate).sortedBy { it.toString() }
        eligible.forEach { memberId ->
            ElectionEligibleVoterTable.insert {
                it[id] = Uuid.random()
                it[ElectionEligibleVoterTable.electionId] = electionId
                it[ElectionEligibleVoterTable.memberId] = memberId
            }
        }
        return eligible
    }

    /**
     * Casts [choicePlan] (one entry per ballot, a list of option ids WITHOUT any person) through the live cast core. The
     * voters are drawn from [roll] independently of the plan, so no (person, choice) pair exists anywhere.
     */
    private fun JdbcTransaction.castBallots(
        electionId: Uuid,
        roll: List<Uuid>,
        voterPool: Set<Uuid>,
        choicePlan: List<Uuid>,
        clock: SeedClock,
    ) {
        val voters = roll.filter { it in voterPool }.shuffled().take(choicePlan.size)
        check(voters.size == choicePlan.size) { "electoral roll too small for the planned ballots" }
        val electionRow = ElectionTable.selectAll().where { ElectionTable.id eq electionId }.single()
        val opened = requireNotNull(electionRow[ElectionTable.votingOpenedAt])
        val shuffledChoices = choicePlan.shuffled()
        voters.forEachIndexed { index, voterId ->
            recordElectionBallotLocked(
                electionRow = electionRow,
                voterMemberId = voterId,
                selectedOptionIds = listOf(shuffledChoices[index]),
                now = minOf(clock.utcShift(base = opened, byMinutes = (index + 1) * 7), clock.now),
            )
        }
    }

    /** Members of the pool: active, no login (so a live tester can still vote), not on the electoral board. */
    private fun SeedActors.voterPool(excluding: Collection<Uuid>): Set<Uuid> =
        active.filter { it.accountRole == null && it.id !in excluding }.map { it.id }.toSet()

    private fun JdbcTransaction.seedClosedFeeElection(
        clock: SeedClock,
        actors: SeedActors,
    ): ElectionContext {
        val motionId = SeedIds.governance(0x40)
        val electionId = SeedIds.governance(0x41)
        insertAssemblyMotion(
            id = motionId,
            meetingId = heldAssemblyId,
            title = "Aenderung der Beitragsordnung",
            text = "Die Mitgliederversammlung moege die Beitragsordnung in der vorgelegten Fassung beschliessen.",
            submitter = actors.board.id,
            submittedAt = clock.utcAt(daysAgo = 70),
            agendaTitle = "Beitragsordnung",
            presenter = actors.treasurer.id,
        )
        val meetingStart = clock.wallToUtc(clock.wallAt(daysFromToday = -45, hour = 18))
        insertElection(
            id = electionId,
            motionId = motionId,
            meetingId = heldAssemblyId,
            title = "Aenderung der Beitragsordnung",
            type = ElectionType.YES_NO,
            seatCount = 1,
            targetCommitteeId = null,
            status = ElectionStatus.OPEN,
            openedBy = actors.admin.id,
            openedAt = clock.utcAt(daysAgo = 46),
            candidateListApprovedAt = null,
            votingOpenedAt = meetingStart,
            votingClosedAt = null,
        )
        val answerOptionId =
            listOf(ElectionAnswer.YES, ElectionAnswer.NO, ElectionAnswer.ABSTAIN)
                .mapIndexed { index, answer ->
                    val optionId = Uuid.random()
                    ElectionOptionTable.insert {
                        it[id] = optionId
                        it[ElectionOptionTable.electionId] = electionId
                        it[label] = answer.name
                        it[position] = index
                        it[candidacyId] = null
                    }
                    answer to optionId
                }.toMap()
        val boardIds = listOf("Erika", "Frank", "Gisela").map { first -> actors.members.first { it.displayName.startsWith(first) }.id }
        appointElectionBoard(electionId = electionId, memberIds = boardIds, appointedAt = clock.utcAt(daysAgo = 46))
        val roll = snapshotElectoralRoll(electionId = electionId, meetingId = heldAssemblyId, committeeId = assemblyCommitteeId)

        // 6 yes, 2 no, 1 abstention -- a list of answers, without people.
        val plan =
            List(6) { answerOptionId.getValue(ElectionAnswer.YES) } +
                List(2) { answerOptionId.getValue(ElectionAnswer.NO) } +
                listOf(answerOptionId.getValue(ElectionAnswer.ABSTAIN))
        castBallots(
            electionId = electionId,
            roll = roll,
            voterPool = actors.voterPool(excluding = boardIds),
            choicePlan = plan,
            clock = clock,
        )

        ElectionTable.update({ ElectionTable.id eq electionId }) {
            it[status] = ElectionStatus.CLOSED
            it[votingClosedAt] = clock.utcShift(base = meetingStart, byMinutes = 120)
        }
        boardIds.take(TALLY_THRESHOLD).forEach { memberId ->
            ElectionTallyApprovalTable.insert {
                it[id] = Uuid.random()
                it[ElectionTallyApprovalTable.electionId] = electionId
                it[ElectionTallyApprovalTable.memberId] = memberId
                it[approvedAt] = clock.utcShift(base = meetingStart, byMinutes = 130)
            }
        }
        return ElectionContext(electionId = electionId, motionId = motionId, meetingId = heldAssemblyId, boardMemberIds = boardIds)
    }

    /** Tallies through the live core: motion lock first, then the election row lock, as `ElectionService.tally` does. */
    private fun JdbcTransaction.tallyClosedElection(
        fee: ElectionContext,
        actors: SeedActors,
    ) {
        val motionRow = MotionDecisionLock.lockMotion(fee.motionId)
        val electionRow = lockElectionRow(fee.electionId)
        tallyElectionLocked(electionMotionRow = motionRow, electionRow = electionRow, actor = actors.adminActor)
    }

    private fun JdbcTransaction.seedRunningAuditorsElection(
        clock: SeedClock,
        actors: SeedActors,
    ) {
        val motionId = SeedIds.governance(0x50)
        val electionId = SeedIds.governance(0x51)
        insertAssemblyMotion(
            id = motionId,
            meetingId = onlineAssemblyId,
            title = "Wahl der Kassenpruefung",
            text = "Die Mitgliederversammlung moege zwei Kassenpruefende fuer das laufende Geschaeftsjahr waehlen.",
            submitter = actors.board.id,
            submittedAt = clock.utcAt(daysAgo = 14),
            agendaTitle = "Wahl der Kassenpruefung",
            presenter = actors.admin.id,
        )
        val votingStart = clock.utcAt(daysAgo = 1, hour = 18)
        insertElection(
            id = electionId,
            motionId = motionId,
            meetingId = onlineAssemblyId,
            title = "Wahl der Kassenpruefung",
            type = ElectionType.SINGLE_CHOICE,
            seatCount = 1,
            targetCommitteeId = auditorsCommitteeId,
            status = ElectionStatus.OPEN,
            openedBy = actors.admin.id,
            openedAt = clock.utcAt(daysAgo = 10),
            candidateListApprovedAt = clock.utcAt(daysAgo = 2),
            votingOpenedAt = votingStart,
            votingClosedAt = null,
        )
        val boardIds = listOf("Hans", "Erika", "Daniel").map { first -> actors.members.first { it.displayName.startsWith(first) }.id }
        appointElectionBoard(electionId = electionId, memberIds = boardIds, appointedAt = clock.utcAt(daysAgo = 10))

        // Three candidacies, released as the option list (what `releaseCandidateList` writes).
        val candidates = listOf("Ines", "Jonas", "Kevin").map { first -> actors.members.first { it.displayName.startsWith(first) } }
        val optionIds =
            candidates.mapIndexed { index, candidate ->
                val candidacyId = Uuid.random()
                ElectionCandidacyTable.insert {
                    it[id] = candidacyId
                    it[motivationText] = "Ich moechte die Kassenfuehrung des Vereins transparent begleiten (Demodaten)."
                    it[submittedAt] = clock.utcAt(daysAgo = 8)
                    it[withdrawnAt] = null
                    it[ElectionCandidacyTable.electionId] = electionId
                    it[memberId] = candidate.id
                }
                val optionId = Uuid.random()
                ElectionOptionTable.insert {
                    it[id] = optionId
                    it[ElectionOptionTable.electionId] = electionId
                    it[label] = candidate.displayName
                    it[position] = index
                    it[ElectionOptionTable.candidacyId] = candidacyId
                }
                optionId
            }
        val roll = snapshotElectoralRoll(electionId = electionId, meetingId = onlineAssemblyId, committeeId = assemblyCommitteeId)
        // 6 ballots, 3 / 2 / 1 -- a list of options without people. Voters have no login, the three demo logins have NOT voted.
        val plan = List(3) { optionIds[0] } + List(2) { optionIds[1] } + listOf(optionIds[2])
        castBallots(
            electionId = electionId,
            roll = roll,
            voterPool = actors.voterPool(excluding = boardIds + candidates.map { it.id }),
            choicePlan = plan,
            clock = clock,
        )
    }

    // ---- polls ------------------------------------------------------------------------------------------------------------

    private fun JdbcTransaction.insertPoll(
        id: Uuid,
        question: String,
        description: String,
        options: List<String>,
        createdBy: CurrentMember,
        createdAt: LocalDateTime,
        closesAt: LocalDateTime?,
    ): List<Uuid> {
        PollTable.insert {
            it[PollTable.id] = id
            it[PollTable.question] = question
            it[PollTable.description] = description
            it[status] = PollStatus.OPEN
            it[kind] = PollKind.SINGLE_CHOICE
            it[PollTable.createdBy] = createdBy.memberId
            it[PollTable.createdAt] = createdAt
            it[PollTable.closesAt] = closesAt
        }
        val optionIds =
            options.mapIndexed { index, text ->
                val optionId = Uuid.random()
                PollOptionTable.insert {
                    it[PollOptionTable.id] = optionId
                    it[pollId] = id
                    it[position] = index
                    it[PollOptionTable.text] = text
                    it[explanation] = null
                    it[isPassive] = false
                }
                optionId
            }
        AuditLogRecorder.record(
            actorMemberId = createdBy.memberId,
            actorRole = createdBy.role,
            entityType = AuditEntityType.POLL,
            entityId = id,
            action = AuditAction.CREATE,
            after =
                Json.encodeToString(
                    PollSnapshot.serializer(),
                    PollSnapshot(
                        question = question,
                        options = options,
                        status = PollStatus.OPEN.name,
                        closesAt = closesAt,
                        closedAt = null,
                    ),
                ),
        )
        return optionIds
    }

    /** Participation (with the member) and the response (WITHOUT the member) are separate rows, exactly as `castPollResponse` writes them. */
    private fun JdbcTransaction.respond(
        pollId: Uuid,
        voters: List<Uuid>,
        choicePlan: List<Uuid>,
    ) {
        check(voters.size >= choicePlan.size) { "not enough respondents for the planned poll answers" }
        val shuffledChoices = choicePlan.shuffled()
        voters.take(choicePlan.size).forEachIndexed { index, memberId ->
            PollParticipationTable.insert {
                it[id] = Uuid.random()
                it[PollParticipationTable.pollId] = pollId
                it[PollParticipationTable.memberId] = memberId
            }
            PollResponseTable.insert {
                it[id] = Uuid.random()
                it[PollResponseTable.pollId] = pollId
                it[optionId] = shuffledChoices[index]
                it[weightLtr] = BigDecimal("0.00")
            }
        }
    }

    private fun JdbcTransaction.seedPolls(
        clock: SeedClock,
        actors: SeedActors,
    ) {
        // Respondents: active members without a login (the demo logins can still answer live), oldest first so they were members
        // when the closed poll was created.
        val respondents = actors.active.filter { it.accountRole == null }
        val closedPollId = SeedIds.governance(0x60)
        val closedOptions =
            insertPoll(
                id = closedPollId,
                question = "Welcher Wochentag passt am besten fuer den Stammtisch?",
                description = "Umfrage unter den Mitgliedern des Testvereins (Demodaten).",
                options = listOf("Montag", "Mittwoch", "Freitag"),
                createdBy = actors.adminActor,
                createdAt = clock.utcAt(daysAgo = 40),
                closesAt = null,
            )
        respond(
            pollId = closedPollId,
            voters = respondents.filter { (it.activeSinceDaysAgo ?: 0) > 45 }.map { it.id }.shuffled(),
            choicePlan = List(4) { closedOptions[0] } + List(3) { closedOptions[1] } + List(2) { closedOptions[2] },
        )
        // `finish`: status, closedAt, closedBy, then the UPDATE audit with the before/after snapshots.
        val closedAt = clock.utcAt(daysAgo = 30)
        PollTable.update({ PollTable.id eq closedPollId }) {
            it[status] = PollStatus.CLOSED
            it[PollTable.closedAt] = closedAt
            it[closedBy] = actors.admin.id
        }
        val before =
            PollSnapshot(
                question = "Welcher Wochentag passt am besten fuer den Stammtisch?",
                options = listOf("Montag", "Mittwoch", "Freitag"),
                status = PollStatus.OPEN.name,
                closesAt = null,
                closedAt = null,
            )
        AuditLogRecorder.record(
            actorMemberId = actors.admin.id,
            actorRole = actors.adminActor.role,
            entityType = AuditEntityType.POLL,
            entityId = closedPollId,
            action = AuditAction.UPDATE,
            before = Json.encodeToString(PollSnapshot.serializer(), before),
            after = Json.encodeToString(PollSnapshot.serializer(), before.copy(status = PollStatus.CLOSED.name, closedAt = closedAt)),
        )

        val openPollId = SeedIds.governance(0x61)
        val openOptions =
            insertPoll(
                id = openPollId,
                question = "Soll der Verein ein gedrucktes Mitgliedermagazin herausgeben?",
                description = "Laufende Umfrage, bis zum Ende der Frist kann noch abgestimmt werden (Demodaten).",
                options = listOf("Ja, gedruckt", "Nur digital", "Nein"),
                createdBy = actors.adminActor,
                createdAt = clock.utcAt(daysAgo = 10),
                closesAt = clock.wallAt(daysFromToday = 14, hour = 20),
            )
        respond(
            pollId = openPollId,
            voters = respondents.map { it.id }.shuffled(),
            choicePlan = List(3) { openOptions[0] } + List(2) { openOptions[1] } + listOf(openOptions[2]),
        )
        // Nobody else may have participated: the demo logins keep their vote for the live tester.
        check(
            PollParticipationTable
                .selectAll()
                .where { (PollParticipationTable.pollId eq openPollId) and (PollParticipationTable.memberId inList listOfLogins(actors)) }
                .none(),
        ) { "a demo login must not have answered the running poll" }
    }

    private fun listOfLogins(actors: SeedActors): List<Uuid> = actors.members.filter { it.accountRole != null }.map { it.id }

    private const val REQUIRED_MAJORITY_PERCENT = 50
    private const val TALLY_THRESHOLD = 2
}
