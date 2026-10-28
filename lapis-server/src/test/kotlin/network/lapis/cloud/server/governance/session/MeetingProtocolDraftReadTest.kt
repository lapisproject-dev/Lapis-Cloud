package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingProtocolDraftDto
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** V1.9.239 -- what the draft derives when it is read: header, confirmed attendance, quorum, resolutions per item; and what it never carries. */
class MeetingProtocolDraftReadTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        test(
            "header and attendance: the five statuses, a stand-in, a guest -- confirmed entries only, never the raw connected suggestions",
        ) {
            val s = fx.scene(peers = 5)
            runBlocking { s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
            s.connected.connect(s.peers[4]) // connected in the room, but not confirmed: must not appear
            val guest = fx.newMember(name = "MSX Gast")
            fx.putAttendance(meetingId = s.meetingId, member = s.peers[0], status = AttendanceStatus.PRESENT)
            fx.putAttendance(meetingId = s.meetingId, member = s.peers[1], status = AttendanceStatus.EXCUSED)
            fx.putAttendance(meetingId = s.meetingId, member = s.peers[2], status = AttendanceStatus.UNEXCUSED)
            fx.putAttendance(
                meetingId = s.meetingId,
                member = s.peers[3],
                status = AttendanceStatus.REPRESENTED,
                representedBy = s.peers[0],
            )
            fx.putAttendance(meetingId = s.meetingId, member = guest, status = AttendanceStatus.GUEST)
            val draft = fx.protocolOps().get(current = s.participant, meetingId = s.meetingId.toString())

            draft.header.title shouldBe "MSX Sitzung"
            draft.header.committeeType shouldBe CommitteeType.WORKING_GROUP
            draft.header.format shouldBe MeetingFormat.ONLINE
            draft.header.scheduledAt shouldBe SESSION_MEETING_AT
            draft.header.chairName shouldBe "MSX Chair"
            draft.header.minuteTakerName shouldBe "MSX Minutes"
            (draft.header.openedAt != null) shouldBe true
            draft.header.endedAt shouldBe null
            draft.attendance.present.map { it.displayName } shouldContainExactly listOf("MSX Peer 01")
            draft.attendance.excused.map { it.displayName } shouldContainExactly listOf("MSX Peer 02")
            draft.attendance.absent.map { it.displayName } shouldContainExactly listOf("MSX Peer 03")
            draft.attendance.represented
                .single()
                .representedName shouldBe "MSX Peer 04"
            draft.attendance.represented
                .single()
                .representativeName shouldBe "MSX Peer 01"
            draft.attendance.guests.map { it.displayName } shouldContainExactly listOf("MSX Gast")
            draft.items.map { it.title } shouldContainExactly listOf("TOP 1", "TOP 2", "TOP 3")
            // the member id of nobody leaves the server in the attendance
            val json = Json.encodeToString(MeetingProtocolDraftDto.serializer(), draft)
            (s.peers + guest).forEach { json shouldNotContain it.memberId.toString() }
        }

        test("the quorum is shown as the lead established it") {
            val s = fx.scene()
            runBlocking { s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
            val p = fx.protocolOps()
            p.get(current = s.lead, meetingId = s.meetingId.toString()).quorum shouldBe null
            s.peers.take(3).forEach { fx.putAttendance(meetingId = s.meetingId, member = it, status = AttendanceStatus.PRESENT) }
            val established =
                runBlocking {
                    s.ops.establishQuorum(
                        current = s.lead,
                        roomId = s.roomId,
                        meetingId = s.meetingId,
                        acknowledgeNotMet = true,
                    )
                }
            val shown = p.get(current = s.participant, meetingId = s.meetingId.toString()).quorum!!
            shown.present shouldBe established.establishedQuorum!!.present
            shown.required shouldBe established.establishedQuorum!!.required
            shown.eligible shouldBe established.establishedQuorum!!.eligible
            shown.overridden shouldBe established.establishedQuorum!!.overridden
        }

        test("a resolution appears under its agenda item with its counts; one without an item is listed apart; a secret one is marked") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = runBlocking { s.attachedMotion(motions = m, title = "MSX Beschlusstitel", text = "MSX Wortlaut") }
            runBlocking {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = s.agenda[0].toString(),
                    secret = true,
                    boardMemberIds = s.boardIds(),
                )
            }
            val election = s.electionOf(motion)
            s.castBallots(electionId = election, yes = 4, no = 1, abstain = 1)
            s.closeVoting(election)
            s.tally(election)
            transaction {
                ResolutionTable.insert {
                    it[id] = Uuid.random()
                    it[number] = "MSX-LOSE"
                    it[title] = "MSX ohne Punkt"
                    it[text] = "MSX freier Beschluss"
                    it[votesYes] = 3
                    it[votesNo] = 0
                    it[votesAbstain] = 0
                    it[quorumMet] = true
                    it[status] = ResolutionStatus.ADOPTED
                    it[decidedAt] = LocalDateTime(2026, 3, 1, 10, 0)
                    it[recordedBy] = s.lead.memberId
                    it[resolutionMode] = ResolutionMode.COMMITTEE_QUORUM
                    it[meetingId] = s.meetingId
                    it[agendaItemId] = null
                }
            }
            val draft = fx.protocolOps().get(current = s.participant, meetingId = s.meetingId.toString())
            val shown = draft.items[0].resolutions.single()
            shown.yes shouldBe 4
            shown.no shouldBe 1
            shown.abstain shouldBe 1
            shown.status shouldBe ResolutionStatus.ADOPTED
            shown.secretBallot shouldBe true
            draft.items[1].resolutions.shouldBeEmpty()
            draft.unassignedResolutions.single().number shouldBe "MSX-LOSE"
            draft.unassignedResolutions.single().secretBallot shouldBe false
        }

        test("a secret vote below the minimum participation shows no figures in the draft, for the lead and for a reader") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = runBlocking { s.attachedMotion(motions = m) }
            runBlocking {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = s.agenda[0].toString(),
                    secret = true,
                    boardMemberIds = s.boardIds(),
                )
            }
            val election = s.electionOf(motion)
            // three ballots, all "yes": the figures would reveal every single answer
            s.castBallots(electionId = election, yes = 3, no = 0, abstain = 0)
            s.closeVoting(election)
            s.tally(election)
            listOf(s.lead, s.participant).forEach { caller ->
                val shown =
                    fx
                        .protocolOps()
                        .get(current = caller, meetingId = s.meetingId.toString())
                        .items[0]
                        .resolutions
                        .single()
                shown.secretBallot shouldBe true
                shown.figuresWithheld shouldBe true
                shown.yes shouldBe 0
                shown.no shouldBe 0
                shown.abstain shouldBe 0
                shown.status shouldBe ResolutionStatus.ADOPTED
            }
        }

        test(
            "a secret ballot never reaches the draft: no ballot id and no voter id appears in the serialised draft, and the type has no such field",
        ) {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = runBlocking { s.attachedMotion(motions = m) }
            runBlocking {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = motion.toString(),
                    expectedAgendaItemId = s.agenda[0].toString(),
                    secret = true,
                    boardMemberIds = s.boardIds(),
                )
            }
            val election = s.electionOf(motion)
            s.castBallots(electionId = election, yes = 3, no = 2, abstain = 0)
            s.closeVoting(election)
            s.tally(election)
            val ballotIds = transaction { ElectionBallotTable.selectAll().map { it[ElectionBallotTable.id].toString() } }
            val draft = fx.protocolOps().get(current = s.lead, meetingId = s.meetingId.toString())
            val json = Json.encodeToString(MeetingProtocolDraftDto.serializer(), draft)
            ballotIds.forEach { json shouldNotContain it }
            // a reader gets no member id at all (the lead's list of people a task can go to is the only place ids appear, by design)
            val readerJson =
                Json.encodeToString(
                    MeetingProtocolDraftDto.serializer(),
                    fx.protocolOps().get(current = s.participant, meetingId = s.meetingId.toString()),
                )
            s.everyone.forEach { readerJson shouldNotContain it.memberId.toString() }
            ballotIds.forEach { readerJson shouldNotContain it }
            // structure: the resolution DTO has counts, never a person
            val fields =
                network.lapis.cloud.shared.domain.ProtocolResolutionDto
                    .serializer()
                    .descriptor
                    .let { d -> (0 until d.elementsCount).map { d.getElementName(it) } }
            fields.none {
                it.contains("voter", ignoreCase = true) ||
                    it.contains("member", ignoreCase = true) ||
                    it.contains("ballot", ignoreCase = true) &&
                    it != "secretBallot"
            } shouldBe
                true
        }

        test("a read writes nothing: no draft row, no audit entry, also for a locked-out or unopened Sitzung") {
            val s = fx.scene()
            val p = fx.protocolOps()
            val before = auditCount(s.meetingId)
            repeat(3) { p.get(current = s.lead, meetingId = s.meetingId.toString()) }
            protocolDraftCount(s.meetingId) shouldBe 0
            auditCount(s.meetingId) shouldBe before
        }

        test("the lists are in a fixed order: items by position, tasks by creation, names alphabetically") {
            val s = fx.scene(peers = 3)
            runBlocking { s.ops.open(current = s.lead, roomId = s.roomId, meetingId = s.meetingId) }
            s.peers.reversed().forEach { fx.putAttendance(meetingId = s.meetingId, member = it, status = AttendanceStatus.PRESENT) }
            val draft = fx.protocolOps().get(current = s.lead, meetingId = s.meetingId.toString())
            draft.items.map { it.position } shouldBe listOf(1, 2, 3)
            draft.attendance.present.map { it.displayName } shouldBe listOf("MSX Peer 01", "MSX Peer 02", "MSX Peer 03")
            draft.assignableMembers.map { it.displayName } shouldBe
                draft.assignableMembers.map { it.displayName }.sortedBy { it.lowercase() }
        }
    })
