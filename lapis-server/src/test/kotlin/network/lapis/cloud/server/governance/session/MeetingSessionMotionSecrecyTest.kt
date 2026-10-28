package network.lapis.cloud.server.governance.session

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceMeetingStateDto
import network.lapis.cloud.shared.domain.ResolutionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.238 -- a secret vote of a Sitzung: no live figure for anybody, no figure below the minimum, no link between a person and an answer. */
class MeetingSessionMotionSecrecyTest :
    FunSpec({
        val fx = SessionFixture()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec { fx.cleanup() }

        test("a secret vote has no counter -- for the lead, the minute taker, a participant and an administrator") {
            val s = fx.scene()
            val guard = RecordingStreamGuard()
            val m = s.motionOps(guard)
            val motion = s.attachedMotion(motions = m)
            m.openVote(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                motionId = motion.toString(),
                expectedAgendaItemId = s.agenda[0].toString(),
                secret = true,
                boardMemberIds = s.boardIds(),
            )
            s.castBallots(electionId = s.electionOf(motion), yes = 2, no = 1, abstain = 0)
            val admin = fx.newMember(name = "MSX Admin", role = AccountRole.ADMIN)
            listOf(s.lead, s.minutes, s.participant, admin).forEach { caller ->
                val vote = s.ops.getState(current = caller, roomId = s.roomId)!!.runningMotionVote!!
                vote.secret shouldBe true
                vote.ballotCount shouldBe null
                vote.eligibleCount shouldBe null
            }
            val motionVote =
                s.ops
                    .getState(current = s.lead, roomId = s.roomId)!!
                    .agendaMotions
                    .single()
                    .vote!!
            motionVote.ballotCount shouldBe null
        }

        test("the streams are paused for a secret vote and left alone for an open one") {
            val s = fx.scene()
            val guard = RecordingStreamGuard()
            val m = s.motionOps(guard)
            val secret = s.attachedMotion(motions = m)
            m.openVote(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                motionId = secret.toString(),
                expectedAgendaItemId = s.agenda[0].toString(),
                secret = true,
                boardMemberIds = s.boardIds(),
            )
            guard.quiesced shouldBe listOf(s.meetingId)
            val open = fx.scene()
            val guard2 = RecordingStreamGuard()
            val m2 = open.motionOps(guard2)
            val motion = open.attachedMotion(motions = m2)
            m2.openVote(
                current = open.lead,
                roomId = open.roomId,
                meetingId = open.meetingId,
                motionId = motion.toString(),
                expectedAgendaItemId = open.agenda[0].toString(),
                secret = false,
                boardMemberIds = open.boardIds(),
            )
            guard2.quiesced shouldBe emptyList()
        }

        test("four secret ballots: the figures are withheld everywhere, whoever asks") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m)
            m.openVote(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                motionId = motion.toString(),
                expectedAgendaItemId = s.agenda[0].toString(),
                secret = true,
                boardMemberIds = s.boardIds(),
            )
            val election = s.electionOf(motion)
            s.castBallots(electionId = election, yes = 3, no = 1, abstain = 0)
            s.closeVoting(election)
            s.tally(election)
            val admin = fx.newMember(name = "MSX Admin", role = AccountRole.ADMIN)
            listOf(s.lead, s.minutes, s.participant, admin).forEach { caller ->
                val resolution =
                    s.ops
                        .getState(current = caller, roomId = s.roomId)!!
                        .agendaMotions
                        .single()
                        .resolution!!
                resolution.figuresWithheld shouldBe true
                resolution.votesYes shouldBe 0
                resolution.votesNo shouldBe 0
                resolution.votesAbstain shouldBe 0
                // the decision itself is unchanged: the majority adopted it
                resolution.status shouldBe ResolutionStatus.ADOPTED
            }
        }

        test("five secret ballots reach the minimum: the figures are shown") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m)
            m.openVote(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                motionId = motion.toString(),
                expectedAgendaItemId = s.agenda[0].toString(),
                secret = true,
                boardMemberIds = s.boardIds(),
            )
            val election = s.electionOf(motion)
            s.castBallots(electionId = election, yes = 3, no = 1, abstain = 1)
            s.closeVoting(election)
            s.tally(election)
            val resolution =
                s.ops
                    .getState(current = s.participant, roomId = s.roomId)!!
                    .agendaMotions
                    .single()
                    .resolution!!
            resolution.figuresWithheld shouldBe false
            resolution.votesYes shouldBe 3
        }

        test("no answer is linked to a person: stored ballots are anonymous, the state and the audit hold no voter and no answer") {
            val s = fx.scene()
            val m = s.motionOps()
            val motion = s.attachedMotion(motions = m, title = "MSX Titel Geheim")
            m.openVote(
                current = s.lead,
                roomId = s.roomId,
                meetingId = s.meetingId,
                motionId = motion.toString(),
                expectedAgendaItemId = s.agenda[0].toString(),
                secret = true,
                boardMemberIds = s.boardIds(),
            )
            val election = s.electionOf(motion)
            s.castBallots(electionId = election, yes = 4, no = 2, abstain = 1)
            transaction {
                ElectionBallotTable
                    .selectAll()
                    .where {
                        ElectionBallotTable.electionId eq election
                    }.map { it[ElectionBallotTable.memberId] }
            }.toSet() shouldBe setOf(null)
            transaction { ElectionTable.selectAll().where { ElectionTable.id eq election }.single()[ElectionTable.secret] } shouldBe true
            s.closeVoting(election)
            s.tally(election)
            val voters = s.everyone.map { it.memberId.toString() }
            val state: ConferenceMeetingStateDto = s.ops.getState(current = s.participant, roomId = s.roomId)!!
            val json = Json.encodeToString(ConferenceMeetingStateDto.serializer(), state)
            // a participant receives no roster at all, so no voter id can appear next to a result
            voters.forEach { json shouldNotContain it }
            val audits = fx.auditSnapshots(s.meetingId)
            audits.forEach { snapshot ->
                voters.forEach { snapshot shouldNotContain it }
                listOf("YES", "NO", "ABSTAIN", "MSX Titel Geheim").forEach { snapshot shouldNotContain it }
            }
            audits.map { it.contains("MOTION_VOTE_OPENED") } shouldContain true
            audits.map { it.contains("\"secret\":true") } shouldContain true
            audits.map { it.contains("\"secret\":false") } shouldNotContain true
        }
    })
