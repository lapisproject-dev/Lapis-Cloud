package network.lapis.cloud.server.governance.session

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.ConferenceConnectedMembers
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AttendanceTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.db.generated.DocumentVersionTable
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ProtocolDraftBlockTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTable
import network.lapis.cloud.server.db.generated.ProtocolDraftTaskTable
import network.lapis.cloud.server.db.generated.ProtocolTaskStatusTable
import network.lapis.cloud.server.db.generated.ProtocolVersionConfirmationTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTable
import network.lapis.cloud.server.db.generated.ProtocolVersionTaskTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

internal val SESSION_MEETING_AT = LocalDateTime(2030, 5, 4, 19, 0)

/** A throwaway committee with its people, Sitzung and room; everything is removed again by [cleanup]. */
internal class SessionFixture {
    val memberIds = mutableListOf<Uuid>()
    val committeeIds = mutableListOf<Uuid>()
    val meetingIds = mutableListOf<Uuid>()
    val roomIds = mutableListOf<Uuid>()
    val motionIds = mutableListOf<Uuid>()
    private var counter = 0

    fun bigLimiter() = FederationInboxRateLimiter(maxRequests = 100_000, window = 1.minutes)

    fun operations(
        connected: ConferenceConnectedMembers = FakeConnectedMembers(),
        enabled: Boolean = true,
    ) = MeetingSessionOperations(
        conferencesEnabled = { enabled },
        connectedMembers = connected,
        readLimiter = bigLimiter(),
        mutateLimiter = bigLimiter(),
    )

    fun newMember(
        name: String? = null,
        role: AccountRole = AccountRole.MEMBER,
        status: MemberStatus = MemberStatus.ACTIVE,
    ): CurrentMember {
        val id = Uuid.random()
        val display = name ?: "MSX Person ${"%03d".format(++counter)}"
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = display
                it[email] = "msx-$id@example.org"
                it[MemberTable.status] = status
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
            }
            AccountTable.insert {
                it[AccountTable.id] = Uuid.random()
                it[memberId] = id
                it[AccountTable.role] = role
            }
        }
        memberIds += id
        return CurrentMember(memberId = id, role = role, status = status)
    }

    fun newCommittee(
        type: CommitteeType = CommitteeType.WORKING_GROUP,
        quorumPercent: Int = 50,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            CommitteeTable.insert {
                it[CommitteeTable.id] = id
                it[name] = "MSX-${id.toString().take(8)}"
                it[CommitteeTable.type] = type
                it[description] = "MSX"
                it[active] = true
                it[CommitteeTable.quorumPercent] = quorumPercent
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[membershipMode] = CommitteeMembershipMode.MANUAL
                it[ruleJson] = null
            }
        }
        committeeIds += id
        return id
    }

    fun seat(
        member: CurrentMember,
        committee: Uuid,
        role: CommitteeRole = CommitteeRole.MEMBER,
    ) = transaction {
        CommitteeMembershipTable.insert {
            it[id] = Uuid.random()
            it[CommitteeMembershipTable.role] = role
            it[since] = LocalDate(2020, 1, 1)
            it[until] = null
            it[committeeId] = committee
            it[memberId] = member.memberId
        }
    }

    fun newMeeting(
        committee: Uuid,
        chair: CurrentMember? = null,
        minuteTaker: CurrentMember? = null,
        status: MeetingStatus = MeetingStatus.PLANNED,
        agenda: List<String> = emptyList(),
        at: LocalDateTime = SESSION_MEETING_AT,
    ): Pair<Uuid, List<Uuid>> {
        val id = Uuid.random()
        transaction {
            MeetingTable.insert {
                it[MeetingTable.id] = id
                it[committeeId] = committee
                it[title] = "MSX Sitzung"
                it[scheduledAt] = at
                it[location] = null
                it[format] = MeetingFormat.ONLINE
                it[MeetingTable.status] = status
                it[calledBy] = null
                it[calledAt] = null
                it[chairMemberId] = chair?.memberId
                it[minuteTakerMemberId] = minuteTaker?.memberId
                it[protocolDocumentId] = null
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
        }
        val itemIds =
            agenda.mapIndexed { index, title ->
                val itemId = Uuid.random()
                transaction {
                    AgendaItemTable.insert {
                        it[AgendaItemTable.id] = itemId
                        it[AgendaItemTable.meetingId] = id
                        it[position] = index + 1
                        it[AgendaItemTable.title] = title
                        it[description] = null
                        it[presenterMemberId] = null
                    }
                }
                itemId
            }
        meetingIds += id
        return id to itemIds
    }

    fun newRoom(
        meetingId: Uuid?,
        creator: CurrentMember,
        ended: Boolean = false,
    ): Uuid {
        val id = Uuid.random()
        transaction {
            ConferenceRoomTable.insert {
                it[ConferenceRoomTable.id] = id
                it[title] = "MSX Raum"
                it[description] = ""
                it[livekitRoomName] = "msx-${id.toString().take(12)}"
                it[createdByMemberId] = creator.memberId
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[endedAt] = if (ended) LocalDateTime(2026, 1, 1, 1, 0) else null
                it[maxParticipants] = 25
                it[allowFederationGuests] = false
                it[ConferenceRoomTable.meetingId] = meetingId
            }
        }
        roomIds += id
        return id
    }

    fun livekitName(roomId: Uuid): String =
        transaction {
            ConferenceRoomTable.selectAll().where { ConferenceRoomTable.id eq roomId }.single()[ConferenceRoomTable.livekitRoomName]
        }

    fun setStatus(
        meetingId: Uuid,
        status: MeetingStatus,
    ) = transaction { MeetingTable.update({ MeetingTable.id eq meetingId }) { it[MeetingTable.status] = status } }

    fun attendance(meetingId: Uuid): Map<Uuid, AttendanceStatus> =
        transaction {
            AttendanceTable.selectAll().where { AttendanceTable.meetingId eq meetingId }.associate {
                it[AttendanceTable.memberId] to
                    it[AttendanceTable.status]
            }
        }

    fun putAttendance(
        meetingId: Uuid,
        member: CurrentMember,
        status: AttendanceStatus,
        representedBy: CurrentMember? = null,
    ) = transaction {
        AttendanceTable.insert {
            it[id] = Uuid.random()
            it[AttendanceTable.meetingId] = meetingId
            it[memberId] = member.memberId
            it[AttendanceTable.status] = status
            it[representedByMemberId] = representedBy?.memberId
            it[note] = null
            it[recordedAt] = LocalDateTime(2026, 1, 1, 0, 0)
        }
    }

    /** The after-snapshots of this Sitzung's MEETING_SESSION audit entries, oldest first. */
    fun auditSnapshots(meetingId: Uuid): List<String> =
        transaction {
            AuditLogEntryTable
                .selectAll()
                .where { (AuditLogEntryTable.entityType eq AuditEntityType.MEETING_SESSION) and (AuditLogEntryTable.entityId eq meetingId) }
                .orderBy(AuditLogEntryTable.sequenceNumber)
                .mapNotNull { it[AuditLogEntryTable.afterSnapshot] }
        }

    /** Everything the motion/vote tests wrote for this fixture's Sitzungen (resolutions, elections with their ballots, motions). */
    private fun removeMotionRows() {
        if (meetingIds.isEmpty()) return
        val elections = ElectionTable.selectAll().where { ElectionTable.meetingId inList meetingIds }.map { it[ElectionTable.id] }
        MotionTable.update({ MotionTable.meetingId inList meetingIds }) { it[resolutionId] = null }
        if (motionIds.isNotEmpty()) MotionTable.update({ MotionTable.id inList motionIds }) { it[resolutionId] = null }
        ElectionTable.update({ ElectionTable.id inList elections }) { it[resolutionId] = null }
        VoteTable.update({ VoteTable.meetingId inList meetingIds }) { it[resolutionId] = null }
        SystemicConsensusTable.update({ SystemicConsensusTable.meetingId inList meetingIds }) { it[resolutionId] = null }
        VoteTable.deleteWhere { VoteTable.meetingId inList meetingIds }
        SystemicConsensusTable.deleteWhere { SystemicConsensusTable.meetingId inList meetingIds }
        ResolutionTable.deleteWhere { ResolutionTable.meetingId inList meetingIds }
        val ballots =
            ElectionBallotTable
                .selectAll()
                .where {
                    ElectionBallotTable.electionId inList elections
                }.map { it[ElectionBallotTable.id] }
        ElectionBallotSelectionTable.deleteWhere { ElectionBallotSelectionTable.ballotId inList ballots }
        ElectionBallotTable.deleteWhere { ElectionBallotTable.electionId inList elections }
        ElectionParticipationTable.deleteWhere { ElectionParticipationTable.electionId inList elections }
        ElectionEligibleVoterTable.deleteWhere { ElectionEligibleVoterTable.electionId inList elections }
        ElectionTallyApprovalTable.deleteWhere { ElectionTallyApprovalTable.electionId inList elections }
        ElectionBoardMemberTable.deleteWhere { ElectionBoardMemberTable.electionId inList elections }
        ElectionOptionTable.deleteWhere { ElectionOptionTable.electionId inList elections }
        ElectionTable.deleteWhere { ElectionTable.id inList elections }
        MotionTable.deleteWhere { MotionTable.meetingId inList meetingIds }
        if (motionIds.isNotEmpty()) MotionTable.deleteWhere { MotionTable.id inList motionIds }
    }

    fun cleanup() {
        transaction {
            removeMotionRows()
            if (memberIds.isNotEmpty()) {
                AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
            }
            if (roomIds.isNotEmpty()) ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList roomIds }
            if (meetingIds.isNotEmpty()) {
                // V1.9.240: finalised versions, their confirmations/tasks, task states, the archived PDF documents and the approval tombstones
                val versions =
                    ProtocolVersionTable
                        .selectAll()
                        .where {
                            ProtocolVersionTable.meetingId inList meetingIds
                        }.map { it[ProtocolVersionTable.id] }
                ProtocolVersionConfirmationTable.deleteWhere { ProtocolVersionConfirmationTable.versionId inList versions }
                ProtocolVersionTaskTable.deleteWhere { ProtocolVersionTaskTable.versionId inList versions }
                ProtocolTaskStatusTable.deleteWhere { ProtocolTaskStatusTable.meetingId inList meetingIds }
                ProtocolVersionTable.update({ ProtocolVersionTable.id inList versions }) { it[pdfDocumentVersionId] = null }
                ProtocolVersionTable.deleteWhere { ProtocolVersionTable.id inList versions }
                val documents =
                    MeetingTable.selectAll().where { MeetingTable.id inList meetingIds }.mapNotNull { it[MeetingTable.protocolDocumentId] }
                MeetingTable.update({ MeetingTable.id inList meetingIds }) {
                    it[protocolDocumentId] = null
                    it[protocolApprovalSourceMeetingId] = null
                }
                if (documents.isNotEmpty()) {
                    DocumentTable.update({ DocumentTable.id inList documents }) { it[currentVersionId] = null }
                    DocumentVersionTable.deleteWhere { DocumentVersionTable.documentId inList documents }
                    DocumentTable.deleteWhere { DocumentTable.id inList documents }
                }
                AgendaItemTable.update({ AgendaItemTable.meetingId inList meetingIds }) { it[protocolApprovalSourceMeetingId] = null }
                // V1.9.239: the Protokoll draft points at the Sitzung and its agenda items
                val drafts =
                    ProtocolDraftTable
                        .selectAll()
                        .where {
                            ProtocolDraftTable.meetingId inList meetingIds
                        }.map { it[ProtocolDraftTable.id] }
                ProtocolDraftTaskTable.deleteWhere { ProtocolDraftTaskTable.draftId inList drafts }
                ProtocolDraftBlockTable.deleteWhere { ProtocolDraftBlockTable.draftId inList drafts }
                ProtocolDraftTable.deleteWhere { ProtocolDraftTable.id inList drafts }
                AttendanceTable.deleteWhere { AttendanceTable.meetingId inList meetingIds }
                MeetingTable.update({ MeetingTable.id inList meetingIds }) { it[currentAgendaItemId] = null }
                AgendaItemTable.deleteWhere { AgendaItemTable.meetingId inList meetingIds }
                MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
            }
            if (committeeIds.isNotEmpty()) {
                CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
                CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
            }
            if (memberIds.isNotEmpty()) {
                AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                MemberTable.deleteWhere { MemberTable.id inList memberIds }
            }
        }
    }
}

/** Connected identities set by the test; counts how often LiveKit was "asked". */
internal class FakeConnectedMembers(
    val identities: MutableSet<String> = mutableSetOf(),
    var failing: Boolean = false,
) : ConferenceConnectedMembers {
    var calls = 0

    override suspend fun connectedIdentities(livekitRoomName: String): Set<String> {
        calls++
        return if (failing) emptySet() else identities.toSet()
    }

    fun connect(vararg members: CurrentMember) {
        members.forEach { identities += it.memberId.toString() }
    }
}

/** A committee with a lead (seated CHAIR), a minute taker, [peers] seated members and a Sitzung bound to a room. */
internal class SessionScene(
    val fx: SessionFixture,
    val committee: Uuid,
    val lead: CurrentMember,
    val minutes: CurrentMember,
    val peers: List<CurrentMember>,
    val participant: CurrentMember,
    val meetingId: Uuid,
    val agenda: List<Uuid>,
    val roomId: Uuid,
    val connected: FakeConnectedMembers,
) {
    val ops = fx.operations(connected = connected)
    val everyone: List<CurrentMember> get() = listOf(lead, minutes) + peers + participant
}

internal fun SessionFixture.scene(
    peers: Int = 4,
    type: CommitteeType = CommitteeType.WORKING_GROUP,
    agenda: List<String> = listOf("TOP 1", "TOP 2", "TOP 3"),
    quorumPercent: Int = 50,
): SessionScene {
    val committee = newCommittee(type = type, quorumPercent = quorumPercent)
    val lead = newMember(name = "MSX Chair")
    val minutes = newMember(name = "MSX Minutes")
    val participant = newMember(name = "MSX Participant")
    val peerList = (1..peers).map { newMember(name = "MSX Peer ${"%02d".format(it)}") }
    seat(member = lead, committee = committee, role = CommitteeRole.CHAIR)
    seat(member = minutes, committee = committee, role = CommitteeRole.MEMBER)
    seat(member = participant, committee = committee, role = CommitteeRole.MEMBER)
    peerList.forEach { seat(member = it, committee = committee, role = CommitteeRole.MEMBER) }
    val (meetingId, items) = newMeeting(committee = committee, chair = lead, minuteTaker = minutes, agenda = agenda)
    val roomId = newRoom(meetingId = meetingId, creator = lead)
    return SessionScene(
        fx = this,
        committee = committee,
        lead = lead,
        minutes = minutes,
        peers = peerList,
        participant = participant,
        meetingId = meetingId,
        agenda = items,
        roomId = roomId,
        connected = FakeConnectedMembers(),
    )
}
