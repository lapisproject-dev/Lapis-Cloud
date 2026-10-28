package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MeetingSessionAuditKind
import network.lapis.cloud.shared.domain.MeetingSessionAuditSnapshot
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.238 -- MOTION_ATTACHED and MOTION_VOTE_OPENED hold ids, a flag and a count; never a name, a title or a text. */
class MeetingSessionMotionAuditTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        test("attaching and opening a vote write exactly one entry each, with ids and flags only") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m, title = "MSX Titel im Audit", text = "MSX Text im Audit")
            m.openVote(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                motionId = motion.toString(),
                expectedAgendaItemId = s.agenda[0].toString(),
                secret = false,
                boardMemberIds = s.boardIds(),
            )
            val snapshots = fx.auditSnapshots(s.meetingId).map { Json.decodeFromString(MeetingSessionAuditSnapshot.serializer(), it) }
            val kinds = snapshots.map { it.kind }
            kinds.count { it == MeetingSessionAuditKind.MOTION_ATTACHED } shouldBe 1
            kinds.count { it == MeetingSessionAuditKind.MOTION_VOTE_OPENED } shouldBe 1
            val attached = snapshots.single { it.kind == MeetingSessionAuditKind.MOTION_ATTACHED }
            attached.meetingId shouldBe s.meetingId.toString()
            attached.motionId shouldBe motion.toString()
            attached.toAgendaItemId shouldBe s.agenda[0].toString()
            val opened = snapshots.single { it.kind == MeetingSessionAuditKind.MOTION_VOTE_OPENED }
            opened.motionId shouldBe motion.toString()
            opened.electionId shouldBe s.electionOf(motion).toString()
            opened.secret shouldBe false
            opened.electionBoardSize shouldBe 3
            fx.auditSnapshots(s.meetingId).forEach { raw ->
                listOf("MSX Titel im Audit", "MSX Text im Audit", "MSX Chair", "MSX Minutes", "MSX Participant").forEach {
                    raw shouldNotContain
                        it
                }
            }
        }

        test("the vote entry is the LAST audit step of the Sitzung: nothing of this Sitzung is chained after it") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m)
            m.openVote(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                motionId = motion.toString(),
                expectedAgendaItemId = s.agenda[0].toString(),
                secret = false,
                boardMemberIds = s.boardIds(),
            )
            val last =
                transaction {
                    AuditLogEntryTable
                        .selectAll()
                        .where {
                            (AuditLogEntryTable.entityType eq AuditEntityType.MEETING_SESSION) and
                                (AuditLogEntryTable.entityId eq s.meetingId)
                        }.orderBy(AuditLogEntryTable.sequenceNumber)
                        .last()[AuditLogEntryTable.afterSnapshot]!!
                }
            Json.decodeFromString(MeetingSessionAuditSnapshot.serializer(), last).kind shouldBe MeetingSessionAuditKind.MOTION_VOTE_OPENED
        }

        test("a refused attempt writes no audit entry") {
            val s = fx.scene()
            val m = s.motionOps()
            val item = s.openMeeting()
            val before = fx.auditSnapshots(s.meetingId).size
            runCatching {
                m.attach(
                    current = s.participant,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = fx.newMotion(committee = s.committee, submitter = s.participant).toString(),
                    expectedAgendaItemId = item,
                )
            }
            runCatching {
                m.openVote(
                    current = s.lead,
                    roomId = s.roomId,
                    meetingId = s.meetingId,
                    motionId = fx.newMotion(committee = s.committee, submitter = s.participant).toString(),
                    expectedAgendaItemId = item,
                    secret = false,
                    boardMemberIds = s.boardIds(),
                )
            }
            fx.auditSnapshots(s.meetingId).size shouldBe before
        }
    })
