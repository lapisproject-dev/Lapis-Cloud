package network.lapis.cloud.server.meetingreward

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MeetingRewardCommitteeTable
import network.lapis.cloud.server.db.generated.MeetingRewardLineTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyChangeTable
import network.lapis.cloud.server.db.generated.MeetingRewardPolicyTable
import network.lapis.cloud.server.db.generated.MeetingRewardSettlementTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.governance.session.ProtocolSnapshotV1
import network.lapis.cloud.server.governance.session.ProtocolSnapshots
import network.lapis.cloud.server.rpc.MeetingRewardService
import network.lapis.cloud.server.tender.TenderApp
import network.lapis.cloud.server.tender.TenderFixtures
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeKind
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeStatus
import network.lapis.cloud.shared.domain.MeetingRewardPolicyProposalInput
import network.lapis.cloud.shared.domain.MeetingRewardPolicyValuesDto
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.ProtocolAttendanceDto
import network.lapis.cloud.shared.domain.ProtocolConfirmationMode
import network.lapis.cloud.shared.domain.ProtocolHeaderDto
import network.lapis.cloud.shared.domain.ProtocolPersonDto
import network.lapis.cloud.shared.domain.ProtocolRepresentationDto
import network.lapis.cloud.shared.domain.ProtocolVersionStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

internal val REWARD_MEETING_TIME: LocalDateTime = LocalDateTime(2026, 10, 20, 18, 0)

internal suspend fun <T> TenderApp.reward(
    member: Uuid,
    op: suspend MeetingRewardService.() -> T,
): T = call(member = member) { MeetingRewardService(call = it).op() }

internal suspend fun <T> TenderApp.rewardAttempt(
    member: Uuid,
    op: suspend MeetingRewardService.() -> T,
): Result<T> = attempt(member = member) { MeetingRewardService(call = it).op() }

internal fun valuesDto(
    committees: Collection<Uuid> = emptyList(),
    enabled: Boolean = true,
    base: String = "10.00",
    chair: String = "5.00",
    deputy: String = "4.00",
    secretary: String = "3.00",
    director: String = "6.00",
    capYear: String = "240.00",
    capMeeting: String = "1000.00",
) = MeetingRewardPolicyValuesDto(
    enabled = enabled,
    attendanceAmount = BigDecimal(base),
    bonusChair = BigDecimal(chair),
    bonusDeputyChair = BigDecimal(deputy),
    bonusSecretary = BigDecimal(secretary),
    bonusManagingDirector = BigDecimal(director),
    capPerPersonPerYear = BigDecimal(capYear),
    capPerMeeting = BigDecimal(capMeeting),
    committeeIds = committees.map { it.toString() },
)

internal fun proposal(
    resolution: Uuid,
    values: MeetingRewardPolicyValuesDto,
    reference: String? = null,
) = MeetingRewardPolicyProposalInput(values = values, resolutionId = resolution.toString(), resolutionReference = reference)

/** A committee of the type GENERAL_ASSEMBLY (the table has no uniqueness for it; the service keeps the singleton). */
internal fun TenderFixtures.generalAssembly(): Uuid {
    val id = committee()
    transaction { CommitteeTable.update({ CommitteeTable.id eq id }) { it[type] = CommitteeType.GENERAL_ASSEMBLY } }
    return id
}

/** An ADOPTED (or [status]) resolution taken in a new meeting of [committee]. */
internal fun TenderFixtures.resolutionIn(
    committee: Uuid,
    recordedBy: Uuid,
    status: ResolutionStatus = ResolutionStatus.ADOPTED,
): Uuid {
    val meeting = Uuid.random()
    val id = Uuid.random()
    transaction {
        MeetingTable.insert {
            it[MeetingTable.id] = meeting
            it[committeeId] = committee
            it[title] = "Reward-Test-Mitgliederversammlung"
            it[scheduledAt] = REWARD_MEETING_TIME
            it[location] = null
            it[format] = MeetingFormat.IN_PERSON
            it[MeetingTable.status] = MeetingStatus.HELD
            it[calledBy] = null
            it[calledAt] = null
            it[chairMemberId] = null
            it[minuteTakerMemberId] = null
            it[protocolDocumentId] = null
            it[createdAt] = LocalDateTime(2026, 10, 1, 0, 0)
        }
        ResolutionTable.insert {
            it[ResolutionTable.id] = id
            it[number] = "REWARD-TEST-${id.toString().take(8)}"
            it[title] = "Anerkennung fuer Sitzungsteilnahme"
            it[text] = "Beschlusstext"
            it[votesYes] = 5
            it[votesNo] = 0
            it[votesAbstain] = 0
            it[quorumMet] = true
            it[ResolutionTable.status] = status
            it[decidedAt] = LocalDateTime(2026, 10, 20, 19, 0)
            it[ResolutionTable.recordedBy] = recordedBy
            it[resolutionMode] = ResolutionMode.COMMITTEE_QUORUM
            it[voteId] = null
            it[electionId] = null
            it[systemicConsensusId] = null
            it[meetingId] = meeting
            it[agendaItemId] = null
        }
    }
    meetingIds += meeting
    resolutionIds += id
    return id
}

internal fun TenderFixtures.heldMeeting(
    committee: Uuid,
    at: LocalDateTime = REWARD_MEETING_TIME,
    status: MeetingStatus = MeetingStatus.HELD,
): Uuid {
    val id = Uuid.random()
    transaction {
        MeetingTable.insert {
            it[MeetingTable.id] = id
            it[committeeId] = committee
            it[title] = "Reward-Test-Sitzung"
            it[scheduledAt] = at
            it[location] = null
            it[format] = MeetingFormat.IN_PERSON
            it[MeetingTable.status] = status
            it[calledBy] = null
            it[calledAt] = null
            it[chairMemberId] = null
            it[minuteTakerMemberId] = null
            it[protocolDocumentId] = null
            it[createdAt] = LocalDateTime(2026, 10, 1, 0, 0)
        }
    }
    meetingIds += id
    return id
}

internal fun setAttendance(
    meeting: Uuid,
    member: Uuid,
    status: AttendanceStatus,
    recordedAt: LocalDateTime = DbClock.nowLocalDateTime(),
) {
    transaction {
        AttendanceTable.deleteWhere { (AttendanceTable.meetingId eq meeting) and (AttendanceTable.memberId eq member) }
        AttendanceTable.insert {
            it[id] = Uuid.random()
            it[meetingId] = meeting
            it[memberId] = member
            it[AttendanceTable.status] = status
            it[representedByMemberId] = null
            it[note] = null
            it[AttendanceTable.recordedAt] = recordedAt
        }
    }
}

internal fun seatWithRole(
    committee: Uuid,
    member: Uuid,
    role: CommitteeRole,
    since: LocalDate = LocalDate(2026, 1, 1),
    until: LocalDate? = null,
) {
    transaction {
        CommitteeMembershipTable.insert {
            it[id] = Uuid.random()
            it[committeeId] = committee
            it[memberId] = member
            it[CommitteeMembershipTable.role] = role
            it[CommitteeMembershipTable.since] = since
            it[CommitteeMembershipTable.until] = until
            it[autoEndReason] = null
        }
    }
}

/** Confirms a protocol whose attendance snapshot equals the CURRENT attendance rows of [meeting]. Returns the version id. */
internal fun confirmProtocol(
    meeting: Uuid,
    by: Uuid,
    versionNo: Int = 1,
): Uuid {
    val versionId = Uuid.random()
    transaction {
        val rows = AttendanceTable.selectAll().where { AttendanceTable.meetingId eq meeting }.toList()
        val names =
            MemberTable.selectAll().where { MemberTable.id inList rows.map { it[AttendanceTable.memberId] } }.associate {
                it[MemberTable.id] to
                    it[MemberTable.displayName]
            }

        fun people(status: AttendanceStatus) =
            rows.filter { it[AttendanceTable.status] == status }.map {
                ProtocolPersonDto(
                    displayName = names.getValue(it[AttendanceTable.memberId]),
                )
            }
        val snapshot =
            ProtocolSnapshotV1(
                meetingId = meeting.toString(),
                versionNo = versionNo,
                correctionOfVersionNo = null,
                correctionNote = null,
                minutesRequired = false,
                header =
                    ProtocolHeaderDto(
                        committeeName = "Reward-Test",
                        committeeType = CommitteeType.WORKING_GROUP,
                        title = "Reward-Test-Sitzung",
                        scheduledAt = REWARD_MEETING_TIME,
                        format = MeetingFormat.IN_PERSON,
                        location = null,
                        openedAt = null,
                        endedAt = null,
                        chairName = null,
                        minuteTakerName = null,
                    ),
                attendance =
                    ProtocolAttendanceDto(
                        present = people(AttendanceStatus.PRESENT),
                        excused = people(AttendanceStatus.EXCUSED),
                        absent = people(AttendanceStatus.UNEXCUSED),
                        represented =
                            rows
                                .filter { it[AttendanceTable.status] == AttendanceStatus.REPRESENTED }
                                .map {
                                    ProtocolRepresentationDto(
                                        representedName = names.getValue(it[AttendanceTable.memberId]),
                                        representativeName = null,
                                    )
                                },
                        guests = people(AttendanceStatus.GUEST),
                    ),
                quorum = null,
                items = emptyList(),
                unassignedResolutions = emptyList(),
            )
        val json = ProtocolSnapshots.canonicalJson(snapshot)
        ProtocolVersionTable.insert {
            it[id] = versionId
            it[meetingId] = meeting
            it[ProtocolVersionTable.versionNo] = versionNo
            it[contentJson] = json
            it[contentSha256] = ProtocolSnapshots.sha256(json)
            it[correctionNote] = null
            it[finalisedBy] = by
            it[finalisedAt] = DbClock.nowLocalDateTime()
            it[confirmationMode] = ProtocolConfirmationMode.APP_CONFIRMATION
            it[minutesRequired] = false
            it[status] = ProtocolVersionStatus.CONFIRMED
            it[pdfDocumentVersionId] = null
        }
    }
    return versionId
}

/** Puts a CONFIRMED CHANGE directly into force (what the proposal + confirmation flow ends in), for the settlement specs. Returns the change id. */
internal fun seedPolicy(
    proposer: Uuid,
    approver: Uuid,
    resolution: Uuid,
    committees: Set<Uuid>,
    base: String = "10.00",
    chair: String = "5.00",
    deputy: String = "4.00",
    secretary: String = "3.00",
    director: String = "6.00",
    capYear: String = "240.00",
    capMeeting: String = "1000.00",
    enabled: Boolean = true,
): Uuid =
    transaction {
        val old = MeetingRewardStore.valuesOf(MeetingRewardStore.policyRow())
        val new =
            MeetingRewardPolicyValues(
                enabled = enabled,
                attendanceAmount = BigDecimal(base),
                bonusChair = BigDecimal(chair),
                bonusDeputyChair = BigDecimal(deputy),
                bonusSecretary = BigDecimal(secretary),
                bonusManagingDirector = BigDecimal(director),
                capPerPersonPerYear = BigDecimal(capYear),
                capPerMeeting = BigDecimal(capMeeting),
                committeeIds = committees,
            )
        val id = Uuid.random()
        val now = DbClock.nowLocalDateTime()
        MeetingRewardStore.insertChange(
            id = id,
            kind = MeetingRewardPolicyChangeKind.CHANGE,
            status = MeetingRewardPolicyChangeStatus.CONFIRMED,
            proposedBy = proposer,
            proposedAt = now,
            decidedBy = approver,
            decidedAt = now,
            resolutionId = resolution,
            resolutionReference = null,
            old = old,
            new = new,
        )
        MeetingRewardStore.applyPolicy(
            values = new,
            changeId = id,
            resolutionId = resolution,
            resolutionReference = null,
            keepResolution = false,
            at = now,
        )
        MeetingRewardStore.replaceCommittees(ids = committees, changeId = id, at = now)
        id
    }

/** Marks an administrator as having become one just now (not yet tenured for the second pair of eyes). */
internal fun makeFreshAdmin(member: Uuid) {
    transaction { AccountTable.update({ AccountTable.memberId eq member }) { it[adminChangedAt] = DbClock.nowLocalDateTime() } }
}

internal fun ledgerRows(meeting: Uuid) =
    transaction {
        LtrLedgerEntryTable
            .selectAll()
            .where { LtrLedgerEntryTable.referenceId eq meeting }
            .toList()
            .filter { it[LtrLedgerEntryTable.entryType] == network.lapis.cloud.shared.domain.LtrLedgerEntryType.MEETING_REWARD }
    }

/** Everything the reward specs wrote, then the generic fixture cleanup. */
internal fun TenderFixtures.resetRewards() {
    transaction {
        MeetingRewardLineTable.deleteAll()
        MeetingRewardSettlementTable.deleteAll()
        MeetingRewardCommitteeTable.deleteAll()
        MeetingRewardPolicyTable.update({ MeetingRewardPolicyTable.id eq MEETING_REWARD_POLICY_ID }) {
            it[enabled] = false
            it[attendanceAmount] = BigDecimal("0.00")
            it[bonusChair] = BigDecimal("0.00")
            it[bonusDeputyChair] = BigDecimal("0.00")
            it[bonusSecretary] = BigDecimal("0.00")
            it[bonusManagingDirector] = BigDecimal("0.00")
            it[capPerPersonPerYear] = BigDecimal("240.00")
            it[capPerMeeting] = BigDecimal("1000.00")
            it[resolutionId] = null
            it[resolutionReference] = null
            it[lastChangeId] = null
            it[openChangeId] = null
        }
        MeetingRewardPolicyChangeTable.deleteAll()
        ProtocolVersionTable.deleteWhere { ProtocolVersionTable.meetingId inList meetingIds }
    }
    reset()
}

/** Removes the confirmed Protokoll of [meeting] so a new one (with the changed attendance) can be confirmed. */
internal fun dropProtocol(meeting: Uuid) {
    transaction { ProtocolVersionTable.deleteWhere { ProtocolVersionTable.meetingId eq meeting } }
}
