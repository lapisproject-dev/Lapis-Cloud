package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.conference.ConferenceConfig
import network.lapis.cloud.server.conference.LiveKitAdminClient
import network.lapis.cloud.server.conference.LiveKitParticipantInfo
import network.lapis.cloud.server.conference.LiveKitRoomInfo
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ConferenceBreakoutRoomTable
import network.lapis.cloud.server.db.generated.ConferenceParticipationTable
import network.lapis.cloud.server.db.generated.ConferenceRoomTable
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusParticipationTable
import network.lapis.cloud.server.db.generated.SystemicConsensusResistanceTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.db.generated.VoteBallotTable
import network.lapis.cloud.server.db.generated.VoteOptionTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.RoomBallotKind
import network.lapis.cloud.shared.domain.RoomBallotStatus
import network.lapis.cloud.shared.domain.RoomVotingStateDto
import network.lapis.cloud.shared.domain.SystemicConsensusAggregation
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

private val VOTING_ENABLED_CONFIG =
    ConferenceConfig.load { key ->
        when (key) {
            "LAPIS_LIVEKIT_URL" -> "ws://localhost:7880"
            "LAPIS_LIVEKIT_API_KEY" -> "test-livekit-key"
            "LAPIS_LIVEKIT_API_SECRET" -> "test-livekit-secret-at-least-32-bytes-long!!"
            else -> null
        }
    }

private const val UNAVAILABLE = "Room voting state is not available"

/**
 * V1.9.24 -- [ConferenceService.getRoomVotingState]: authorization matrix (every denial is the SAME
 * Forbidden + message), status filter, caps, own-flag semantics, ballot-secrecy field reduction, rate
 * limit. Elections are seeded directly into the DB (one motion each: `uq_election_active_motion` allows
 * only one non-aborted election per motion). [afterSpec] hard-deletes everything this file created.
 */
class ConferenceRoomVotingStateTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val committeeIds = mutableListOf<Uuid>()
        val meetingIds = mutableListOf<Uuid>()
        val motionIds = mutableListOf<Uuid>()
        val electionIds = mutableListOf<Uuid>()
        val electionOptionIds = mutableListOf<Uuid>()
        val electionBallotIds = mutableListOf<Uuid>()
        val voteIds = mutableListOf<Uuid>()
        val consensusIds = mutableListOf<Uuid>()
        val roomIds = mutableListOf<Uuid>()
        val breakoutIds = mutableListOf<Uuid>()
        val json = Json { ignoreUnknownKeys = false }

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (breakoutIds.isNotEmpty()) ConferenceBreakoutRoomTable.deleteWhere { ConferenceBreakoutRoomTable.id inList breakoutIds }
                ConferenceParticipationTable.deleteWhere { ConferenceParticipationTable.roomId inList roomIds }
                ConferenceRoomTable.deleteWhere { ConferenceRoomTable.id inList roomIds }
                ElectionBallotSelectionTable.deleteWhere { ElectionBallotSelectionTable.ballotId inList electionBallotIds }
                ElectionBallotTable.deleteWhere { ElectionBallotTable.electionId inList electionIds }
                ElectionOptionTable.deleteWhere { ElectionOptionTable.id inList electionOptionIds }
                ElectionParticipationTable.deleteWhere { ElectionParticipationTable.electionId inList electionIds }
                ElectionEligibleVoterTable.deleteWhere { ElectionEligibleVoterTable.electionId inList electionIds }
                ElectionTable.deleteWhere { ElectionTable.id inList electionIds }
                if (consensusIds.isNotEmpty()) {
                    val ballotIds =
                        SystemicConsensusBallotTable
                            .selectAll()
                            .where { SystemicConsensusBallotTable.systemicConsensusId inList consensusIds }
                            .map { it[SystemicConsensusBallotTable.id] }
                    SystemicConsensusResistanceTable.deleteWhere { SystemicConsensusResistanceTable.ballotId inList ballotIds }
                    SystemicConsensusBallotTable.deleteWhere { SystemicConsensusBallotTable.systemicConsensusId inList consensusIds }
                    SystemicConsensusParticipationTable.deleteWhere {
                        SystemicConsensusParticipationTable.systemicConsensusId inList
                            consensusIds
                    }
                    SystemicConsensusEligibleVoterTable.deleteWhere {
                        SystemicConsensusEligibleVoterTable.systemicConsensusId inList
                            consensusIds
                    }
                    SystemicConsensusOptionTable.deleteWhere { SystemicConsensusOptionTable.systemicConsensusId inList consensusIds }
                    SystemicConsensusTable.deleteWhere { SystemicConsensusTable.id inList consensusIds }
                }
                VoteBallotTable.deleteWhere { VoteBallotTable.voteId inList voteIds }
                VoteOptionTable.deleteWhere { VoteOptionTable.voteId inList voteIds }
                VoteTable.deleteWhere { VoteTable.id inList voteIds }
                MotionTable.deleteWhere { MotionTable.id inList motionIds }
                MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
                CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
                CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
                AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                MemberTable.deleteWhere { MemberTable.id inList memberIds }
            }
        }

        fun member(
            tag: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "VotingState $tag"
                    it[email] = "votingstate-$tag-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    if (status == MemberStatus.FRIEND) it[friendSince] = LocalDate(2026, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            memberIds += id
            return id
        }

        class Fixture(
            val committeeId: Uuid,
            val meetingId: Uuid,
            val creator: Uuid,
        )

        fun fixture(
            tag: String,
            type: CommitteeType = CommitteeType.EXECUTIVE_BOARD,
        ): Fixture {
            val committeeId = Uuid.random()
            val meetingId = Uuid.random()
            val creator = member("$tag-creator")
            transaction {
                CommitteeTable.insert {
                    it[id] = committeeId
                    it[name] = "VotingState-$tag"
                    it[CommitteeTable.type] = type
                    it[description] = "VotingState"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                MeetingTable.insert {
                    it[id] = meetingId
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "VotingState-Sitzung $tag"
                    it[scheduledAt] = LocalDateTime(2026, 3, 1, 18, 0)
                    it[location] = "Vereinsheim"
                    it[format] = MeetingFormat.IN_PERSON
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = LocalDateTime(2026, 3, 1, 18, 0)
                }
            }
            committeeIds += committeeId
            meetingIds += meetingId
            return Fixture(committeeId, meetingId, creator)
        }

        fun room(
            f: Fixture,
            meetingId: Uuid? = f.meetingId,
            allowGuests: Boolean = false,
            ended: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ConferenceRoomTable.insert {
                    it[ConferenceRoomTable.id] = id
                    it[title] = "VotingState-Raum"
                    it[description] = ""
                    it[livekitRoomName] = "lc-vs-${id.toString().take(40)}"
                    it[createdByMemberId] = f.creator
                    it[createdAt] = DbClock.nowLocalDateTime()
                    it[endedAt] = if (ended) DbClock.nowLocalDateTime() else null
                    it[maxParticipants] = 25
                    it[allowFederationGuests] = allowGuests
                    it[ConferenceRoomTable.meetingId] = meetingId
                }
            }
            roomIds += id
            return id
        }

        fun participate(
            roomId: Uuid,
            memberId: Uuid,
            left: Boolean = false,
        ) {
            transaction {
                ConferenceParticipationTable.insert {
                    it[id] = Uuid.random()
                    it[ConferenceParticipationTable.roomId] = roomId
                    it[ConferenceParticipationTable.memberId] = memberId
                    it[role] = ConferenceRole.PARTICIPANT
                    it[joinedAt] = DbClock.nowLocalDateTime()
                    it[leftAt] = if (left) DbClock.nowLocalDateTime() else null
                }
            }
        }

        fun hoursAgo(h: Int): LocalDateTime {
            val zone = TimeZone.UTC
            return (DbClock.nowLocalDateTime().toInstant(zone) - h.hours).toLocalDateTime(zone)
        }

        /** Seeds one election (with its own motion) directly; returns the election id. */
        fun election(
            f: Fixture,
            title: String,
            status: ElectionStatus,
            secret: Boolean = false,
            meetingId: Uuid = f.meetingId,
            tallyRunAt: LocalDateTime? = null,
            openedAt: LocalDateTime = DbClock.nowLocalDateTime(),
        ): Uuid {
            val motionId = Uuid.random()
            val electionId = Uuid.random()
            val snapshot = status in setOf(ElectionStatus.OPEN, ElectionStatus.CLOSED, ElectionStatus.TALLIED)
            transaction {
                MotionTable.insert {
                    it[id] = motionId
                    it[targetCommitteeId] = f.committeeId
                    it[MotionTable.title] = "Antrag zu $title"
                    it[rationale] = "R"
                    it[text] = "T"
                    it[submitterMemberId] = f.creator
                    it[MotionTable.status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = f.creator
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
                ElectionTable.insert {
                    it[id] = electionId
                    it[ElectionTable.title] = title
                    it[electionType] = ElectionType.YES_NO
                    it[ElectionTable.secret] = secret
                    it[seatCount] = 1
                    it[targetCommitteeId] = null
                    it[targetRole] = null
                    it[requiredMajorityPercent] = 50
                    it[ElectionTable.status] = status
                    it[openedBy] = f.creator
                    it[ElectionTable.openedAt] = openedAt
                    it[votingOpenedAt] = if (snapshot) openedAt else null
                    it[votingClosedAt] = if (status == ElectionStatus.CLOSED || status == ElectionStatus.TALLIED) openedAt else null
                    it[tallyThreshold] = 2
                    it[ElectionTable.tallyRunAt] = tallyRunAt
                    it[ElectionTable.motionId] = motionId
                    it[ElectionTable.meetingId] = meetingId
                    it[activeMotionId] = if (status == ElectionStatus.ABORTED) null else motionId
                }
            }
            motionIds += motionId
            electionIds += electionId
            return electionId
        }

        /** Seeds one systemic consensus (with its own motion) directly; returns the consensus id. */
        fun consensus(
            f: Fixture,
            title: String,
            status: SystemicConsensusStatus,
            secret: Boolean = true,
            round: Int = 1,
            meetingId: Uuid = f.meetingId,
            openedAt: LocalDateTime = DbClock.nowLocalDateTime(),
            tallyRunAt: LocalDateTime? = null,
        ): Uuid {
            val motionId = Uuid.random()
            val consensusId = Uuid.random()
            val rated =
                status in setOf(SystemicConsensusStatus.RATING, SystemicConsensusStatus.CLOSED, SystemicConsensusStatus.EVALUATED)
            val closed = status == SystemicConsensusStatus.CLOSED || status == SystemicConsensusStatus.EVALUATED
            transaction {
                MotionTable.insert {
                    it[id] = motionId
                    it[targetCommitteeId] = f.committeeId
                    it[MotionTable.title] = "Antrag zu $title"
                    it[rationale] = "R"
                    it[text] = "T"
                    it[submitterMemberId] = f.creator
                    it[MotionTable.status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = f.creator
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
                SystemicConsensusTable.insert {
                    it[id] = consensusId
                    it[SystemicConsensusTable.title] = title
                    it[SystemicConsensusTable.status] = status
                    it[SystemicConsensusTable.secret] = secret
                    it[scaleMax] = 10
                    it[aggregation] = SystemicConsensusAggregation.MEAN
                    it[tiebreakRule] = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE
                    it[groupConflictViableThreshold] = BigDecimal("0.500")
                    it[groupConflictWarnThreshold] = BigDecimal("0.800")
                    it[statusQuoOptionAuto] = true
                    it[bindingness] = SystemicConsensusBindingness.ADVISORY
                    it[maxRounds] = 3
                    it[SystemicConsensusTable.round] = round
                    it[winnerOptionId] = null
                    it[openedBy] = f.creator
                    it[SystemicConsensusTable.openedAt] = openedAt
                    it[ratingOpenedAt] = if (rated) openedAt else null
                    it[ratingClosedAt] = if (closed) openedAt else null
                    it[SystemicConsensusTable.tallyRunAt] = tallyRunAt
                    it[SystemicConsensusTable.motionId] = motionId
                    it[SystemicConsensusTable.meetingId] = meetingId
                    it[resolutionId] = null
                }
            }
            motionIds += motionId
            consensusIds += consensusId
            return consensusId
        }

        fun consensusSnapshot(
            consensusId: Uuid,
            memberId: Uuid,
            round: Int = 1,
        ) = transaction {
            SystemicConsensusEligibleVoterTable.insert {
                it[id] = Uuid.random()
                it[systemicConsensusId] = consensusId
                it[SystemicConsensusEligibleVoterTable.round] = round
                it[SystemicConsensusEligibleVoterTable.memberId] = memberId
            }
        }

        /** A rating in [round]: anonymous -> participation row (+ unlinked ballot), open -> named ballot row. */
        fun consensusRated(
            consensusId: Uuid,
            memberId: Uuid,
            secret: Boolean,
            round: Int = 1,
            receipt: String? = null,
        ) = transaction {
            if (secret) {
                SystemicConsensusParticipationTable.insert {
                    it[id] = Uuid.random()
                    it[systemicConsensusId] = consensusId
                    it[votedAt] = DbClock.nowLocalDateTime()
                    it[SystemicConsensusParticipationTable.round] = round
                    it[SystemicConsensusParticipationTable.memberId] = memberId
                }
            }
            SystemicConsensusBallotTable.insert {
                it[id] = Uuid.random()
                it[systemicConsensusId] = consensusId
                it[receiptCode] = receipt ?: "OPEN-${Uuid.random().toString().take(30)}"
                it[castAt] = DbClock.nowLocalDateTime()
                it[SystemicConsensusBallotTable.round] = round
                it[SystemicConsensusBallotTable.memberId] = if (secret) null else memberId
            }
        }

        fun eligible(
            electionId: Uuid,
            memberId: Uuid,
        ) = transaction {
            ElectionEligibleVoterTable.insert {
                it[id] = Uuid.random()
                it[ElectionEligibleVoterTable.electionId] = electionId
                it[ElectionEligibleVoterTable.memberId] = memberId
            }
        }

        fun votedSecret(
            electionId: Uuid,
            memberId: Uuid,
            receipt: String? = null,
        ) = transaction {
            ElectionParticipationTable.insert {
                it[id] = Uuid.random()
                it[votedAt] = DbClock.nowLocalDateTime()
                it[ElectionParticipationTable.electionId] = electionId
                it[ElectionParticipationTable.memberId] = memberId
            }
            if (receipt != null) {
                ElectionBallotTable.insert {
                    it[id] = Uuid.random()
                    it[receiptCode] = receipt
                    it[castAt] = DbClock.nowLocalDateTime()
                    it[ElectionBallotTable.electionId] = electionId
                    it[ElectionBallotTable.memberId] = null
                }
            }
        }

        fun votedOpen(
            electionId: Uuid,
            memberId: Uuid,
        ) = transaction {
            ElectionBallotTable.insert {
                it[id] = Uuid.random()
                it[receiptCode] = "OPEN-${Uuid.random().toString().take(30)}"
                it[castAt] = DbClock.nowLocalDateTime()
                it[ElectionBallotTable.electionId] = electionId
                it[ElectionBallotTable.memberId] = memberId
            }
        }

        class SeededVote(
            val id: Uuid,
            val optionIds: List<Uuid>,
        )

        /** Seeds one meritocratic vote (with its own motion and options) directly. [winner] is the index of the winning option. */
        fun vote(
            f: Fixture,
            title: String,
            status: VoteStatus,
            labels: List<String> = listOf("YES", "NO"),
            meetingId: Uuid = f.meetingId,
            openedAt: LocalDateTime = DbClock.nowLocalDateTime(),
            closedAt: LocalDateTime? = null,
            winner: Int? = null,
            secondPrice: BigDecimal? = null,
        ): SeededVote {
            val motionId = Uuid.random()
            val voteId = Uuid.random()
            val optionIds = labels.map { Uuid.random() }
            transaction {
                MotionTable.insert {
                    it[id] = motionId
                    it[targetCommitteeId] = f.committeeId
                    it[MotionTable.title] = "Antrag zu $title"
                    it[rationale] = "R"
                    it[text] = "T"
                    it[submitterMemberId] = f.creator
                    it[MotionTable.status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = f.creator
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
                VoteTable.insert {
                    it[id] = voteId
                    it[VoteTable.title] = title
                    it[VoteTable.status] = status
                    it[openedBy] = f.creator
                    it[VoteTable.openedAt] = openedAt
                    it[VoteTable.closedAt] = closedAt
                    it[winnerOptionId] = winner?.let { index -> optionIds[index] }
                    it[secondPriceLtr] = secondPrice
                    it[VoteTable.motionId] = motionId
                    it[VoteTable.meetingId] = meetingId
                    it[resolutionId] = null
                }
                labels.forEachIndexed { index, label ->
                    VoteOptionTable.insert {
                        it[id] = optionIds[index]
                        it[VoteOptionTable.label] = label
                        it[position] = index
                        it[VoteOptionTable.voteId] = voteId
                    }
                }
            }
            motionIds += motionId
            voteIds += voteId
            return SeededVote(voteId, optionIds)
        }

        fun bid(
            v: SeededVote,
            memberId: Uuid,
            optionIndex: Int = 0,
            stake: String = "10.00",
        ) = transaction {
            VoteBallotTable.insert {
                it[id] = Uuid.random()
                it[optionId] = v.optionIds[optionIndex]
                it[stakeLtr] = BigDecimal(stake)
                it[settledLtr] = null
                it[castAt] = DbClock.nowLocalDateTime()
                it[VoteBallotTable.voteId] = v.id
                it[VoteBallotTable.memberId] = memberId
            }
        }

        fun seat(
            f: Fixture,
            memberId: Uuid,
            since: LocalDate = LocalDate(2026, 1, 1),
            until: LocalDate? = null,
        ) = transaction {
            CommitteeMembershipTable.insert {
                it[id] = Uuid.random()
                it[role] = CommitteeRole.MEMBER
                it[CommitteeMembershipTable.since] = since
                it[CommitteeMembershipTable.until] = until
                it[committeeId] = f.committeeId
                it[CommitteeMembershipTable.memberId] = memberId
            }
        }

        suspend fun HttpClient.state(
            roomId: String,
            asMember: Uuid,
        ): HttpResponse = get("/test/room-voting-state?roomId=$roomId") { header("X-Member-Id", asMember.toString()) }

        suspend fun HttpClient.stateOk(
            roomId: Uuid,
            asMember: Uuid,
        ): RoomVotingStateDto {
            val r = state(roomId.toString(), asMember)
            r.status shouldBe HttpStatusCode.OK
            return json.decodeFromString(RoomVotingStateDto.serializer(), r.bodyAsText())
        }

        suspend fun HttpClient.shouldBeUniformlyDenied(
            roomId: String,
            asMember: Uuid,
        ) {
            val r = state(roomId, asMember)
            r.status shouldBe HttpStatusCode.Forbidden
            r.bodyAsText() shouldBe UNAVAILABLE
        }

        fun testApp(
            block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
            config: ConferenceConfig = VOTING_ENABLED_CONFIG,
            limiter: () -> FederationInboxRateLimiter = { FederationInboxRateLimiter(maxRequests = 1000, window = 1.minutes) },
        ) = testApplication {
            application {
                install(StatusPages) { installVotingStateExceptionHandlers() }
                routing { registerVotingStateTestRoutes(config = config, limiter = limiter()) }
            }
            block()
        }

        test("authorization matrix: every denial is the SAME Forbidden with the SAME message, positives succeed") {
            testApp({
                val f = fixture("authz")
                val main = room(f, allowGuests = false)
                val open = room(f, allowGuests = true)
                election(f, "Wahl A", ElectionStatus.OPEN)

                val inRoom = member("inroom").also { participate(main, it) }
                val notInRoom = member("notinroom")
                val left = member("left").also { participate(main, it, left = true) }
                val withdrawn = member("withdrawn", MemberStatus.WITHDRAWN).also { participate(main, it) }
                val friendDenied = member("friend-denied", MemberStatus.FRIEND).also { participate(main, it) }
                val friendOk = member("friend-ok", MemberStatus.FRIEND).also { participate(open, it) }
                val friendNoPart = member("friend-nopart", MemberStatus.FRIEND)
                val guestOk = member("guest-ok", MemberStatus.GUEST).also { participate(open, it) }
                val guestNoPart = member("guest-nopart", MemberStatus.GUEST)

                // Positives.
                client.stateOk(main, inRoom).bound shouldBe true
                client
                    .stateOk(open, friendOk)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
                client
                    .stateOk(open, guestOk)
                    .ballots
                    .single()
                    .ownEligible shouldBe false

                // Room creator without own participation is denied (participation is required for EVERY role).
                client.shouldBeUniformlyDenied(main.toString(), f.creator)
                client.shouldBeUniformlyDenied(main.toString(), notInRoom)
                client.shouldBeUniformlyDenied(main.toString(), left)
                client.shouldBeUniformlyDenied(main.toString(), withdrawn)
                client.shouldBeUniformlyDenied(main.toString(), friendDenied)
                client.shouldBeUniformlyDenied(open.toString(), friendNoPart)
                client.shouldBeUniformlyDenied(open.toString(), guestNoPart)

                // Removed participant: leftAt gets set by removeParticipant -- covered by `left`; a FOREIGN room
                // (caller in main only) is a plain "not in room".
                client.shouldBeUniformlyDenied(open.toString(), inRoom)

                // Unknown / ended / breakout / malformed ids.
                val ended = room(f, ended = true).also { participate(it, inRoom) }
                client.shouldBeUniformlyDenied(Uuid.random().toString(), inRoom)
                client.shouldBeUniformlyDenied(ended.toString(), inRoom)
                client.shouldBeUniformlyDenied("not-a-uuid", inRoom)
                client.shouldBeUniformlyDenied("%20", inRoom)

                val breakoutId = Uuid.random()
                val breakoutName = "lc-vs-breakout-${breakoutId.toString().take(30)}"
                transaction {
                    ConferenceBreakoutRoomTable.insert {
                        it[id] = breakoutId
                        it[parentRoomId] = main
                        it[label] = "Gruppe 1"
                        it[livekitRoomName] = breakoutName
                        it[createdByMemberId] = f.creator
                        it[createdAt] = DbClock.nowLocalDateTime()
                        it[closedAt] = null
                    }
                }
                breakoutIds += breakoutId
                client.shouldBeUniformlyDenied(breakoutId.toString(), inRoom)
                client.shouldBeUniformlyDenied(breakoutName, inRoom)
                // A member sitting in a breakout keeps its OPEN main-room participation and asks with the MAIN room id.
                client.stateOk(main, inRoom).ballots.size shouldBe 1
            })
        }

        test(
            "status filter and ordering: only OPEN, CLOSED and recently TALLIED elections of the room's Sitzung, mapped to neutral statuses",
        ) {
            testApp({
                val f = fixture("status")
                val other = fixture("status-other")
                val r = room(f)
                val me = member("status-me").also { participate(r, it) }
                election(f, "prep", ElectionStatus.PREPARATION)
                election(f, "released", ElectionStatus.CANDIDATE_LIST_RELEASED)
                election(f, "tallied-13h", ElectionStatus.TALLIED, tallyRunAt = hoursAgo(13))
                election(f, "aborted", ElectionStatus.ABORTED)
                election(other, "foreign-open", ElectionStatus.OPEN, meetingId = other.meetingId)
                election(f, "tallied-1h", ElectionStatus.TALLIED, tallyRunAt = hoursAgo(1))
                election(f, "closed", ElectionStatus.CLOSED)
                election(f, "open", ElectionStatus.OPEN)

                val s = client.stateOk(r, me)
                s.bound shouldBe true
                s.truncated shouldBe false
                s.ballots.map { it.title } shouldBe listOf("open", "closed", "tallied-1h")
                s.ballots.map { it.status } shouldBe
                    listOf(RoomBallotStatus.OPEN, RoomBallotStatus.CLOSED_AWAITING_TALLY, RoomBallotStatus.DECIDED)
                s.ballots.all { it.kind == RoomBallotKind.ELECTION || it.kind == RoomBallotKind.VOTE } shouldBe true
                s.ballots.first().motionTitle shouldBe "Antrag zu open"
            })
        }

        test("cap: 25 open elections -> 20 delivered, truncated; 20 open + 5 tallied -> only open ones delivered") {
            testApp({
                val f = fixture("cap")
                val r = room(f)
                val me = member("cap-me").also { participate(r, it) }
                repeat(25) { election(f, "open-$it", ElectionStatus.OPEN) }
                val s = client.stateOk(r, me)
                s.ballots.size shouldBe 20
                s.truncated shouldBe true

                val f2 = fixture("cap2")
                val r2 = room(f2)
                val me2 = member("cap2-me").also { participate(r2, it) }
                repeat(20) { election(f2, "open-$it", ElectionStatus.OPEN) }
                repeat(5) { election(f2, "tallied-$it", ElectionStatus.TALLIED, tallyRunAt = hoursAgo(1)) }
                val s2 = client.stateOk(r2, me2)
                s2.ballots.size shouldBe 20
                s2.ballots.all { it.status == RoomBallotStatus.OPEN } shouldBe true
            })
        }

        test("unbound room: bound=false, empty list, no error") {
            testApp({
                val f = fixture("unbound")
                val r = room(f, meetingId = null)
                val me = member("unbound-me").also { participate(r, it) }
                election(f, "irrelevant", ElectionStatus.OPEN)
                val s = client.stateOk(r, me)
                s.bound shouldBe false
                s.ballots shouldBe emptyList()
                s.truncated shouldBe false
            })
        }

        test("own flags: secret and open elections report only the caller's eligibility and vote status") {
            testApp({
                val f = fixture("own")
                val r = room(f)
                val notEligible = member("own-noel").also { participate(r, it) }
                val eligibleNotVoted = member("own-el").also { participate(r, it) }
                val voted = member("own-voted").also { participate(r, it) }
                val secret = election(f, "secret", ElectionStatus.OPEN, secret = true)
                val open = election(f, "open", ElectionStatus.OPEN, secret = false)
                listOf(eligibleNotVoted, voted).forEach {
                    eligible(secret, it)
                    eligible(open, it)
                }
                votedSecret(secret, voted)
                votedOpen(open, voted)

                fun List<network.lapis.cloud.shared.domain.RoomBallotDto>.byTitle(t: String) = single { it.title == t }

                client.stateOk(r, notEligible).ballots.forEach {
                    it.ownEligible shouldBe false
                    it.ownHasVoted shouldBe false
                }
                client.stateOk(r, eligibleNotVoted).ballots.forEach {
                    it.ownEligible shouldBe true
                    it.ownHasVoted shouldBe false
                }
                val v = client.stateOk(r, voted).ballots
                v.byTitle("secret").also {
                    it.secret shouldBe true
                    it.ownEligible shouldBe true
                    it.ownHasVoted shouldBe true
                }
                v.byTitle("open").also {
                    it.secret shouldBe false
                    it.ownHasVoted shouldBe true
                }
            })
        }

        test("non-members are never eligible, even with an (artificially inserted) snapshot row") {
            testApp({
                val f = fixture("nonmember")
                val r = room(f, allowGuests = true)
                val e = election(f, "snap", ElectionStatus.OPEN)
                val guest = member("nm-guest", MemberStatus.GUEST).also { participate(r, it) }
                val friend = member("nm-friend", MemberStatus.FRIEND).also { participate(r, it) }
                eligible(e, guest)
                eligible(e, friend)
                client
                    .stateOk(r, guest)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
                client
                    .stateOk(r, friend)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
            })
        }

        test("field reduction: exact JSON keys, and no receipt code or foreign member id after a secret vote") {
            testApp({
                val f = fixture("fields")
                val r = room(f)
                val me = member("fields-me").also { participate(r, it) }
                val other = member("fields-other").also { participate(r, it) }
                val secret = election(f, "geheim", ElectionStatus.OPEN, secret = true)
                eligible(secret, me)
                eligible(secret, other)
                votedSecret(secret, other, receipt = "RCPT-SECRET-0123456789")
                val body = client.state(r.toString(), me).bodyAsText()
                val root: JsonObject = json.parseToJsonElement(body).jsonObject
                root.keys shouldBe setOf("roomId", "bound", "ballots", "truncated")
                root["ballots"]!!
                    .jsonArray
                    .single()
                    .jsonObject.keys shouldBe
                    setOf("kind", "id", "motionId", "motionTitle", "title", "status", "secret", "ownEligible", "ownHasVoted")
                body shouldNotContain "RCPT-SECRET-0123456789"
                body shouldNotContain other.toString()
                body shouldNotContain me.toString()
                body shouldNotContain f.meetingId.toString()
            })
        }

        test("V1.9.46 a secret ballot with a stored selection never leaks its option label, its id or its receipt into the room state") {
            testApp({
                val f = fixture("selection")
                val r = room(f)
                val me = member("selection-me").also { participate(r, it) }
                val other = member("selection-other").also { participate(r, it) }
                val secret = election(f, "geheim-auswahl", ElectionStatus.OPEN, secret = true)
                eligible(secret, me)
                eligible(secret, other)
                val ballotId = Uuid.random()
                val optionId = Uuid.random()
                transaction {
                    ElectionOptionTable.insert {
                        it[id] = optionId
                        it[label] = "GEHEIM-OPTION-LABEL-QQQ"
                        it[position] = 0
                        it[candidacyId] = null
                        it[ElectionOptionTable.electionId] = secret
                    }
                    ElectionParticipationTable.insert {
                        it[id] = Uuid.random()
                        it[votedAt] = DbClock.nowLocalDateTime()
                        it[ElectionParticipationTable.electionId] = secret
                        it[memberId] = other
                    }
                    ElectionBallotTable.insert {
                        it[id] = ballotId
                        it[receiptCode] = "RCPT-SELECTION-QQQ"
                        it[castAt] = DbClock.nowLocalDateTime()
                        it[ElectionBallotTable.electionId] = secret
                        it[memberId] = null
                    }
                    ElectionBallotSelectionTable.insert {
                        it[id] = Uuid.random()
                        it[ElectionBallotSelectionTable.ballotId] = ballotId
                        it[ElectionBallotSelectionTable.optionId] = optionId
                    }
                }
                electionOptionIds += optionId
                electionBallotIds += ballotId
                val body = client.state(r.toString(), me).bodyAsText()
                body shouldNotContain "GEHEIM-OPTION-LABEL-QQQ"
                body shouldNotContain "RCPT-SELECTION-QQQ"
                body shouldNotContain ballotId.toString()
                body shouldNotContain optionId.toString()
                body shouldNotContain other.toString()
            })
        }

        test(
            "V1.9.27 status mapping: OPEN -> OPEN, CLOSED within 12 h -> DECIDED, older CLOSED and every ABORTED absent, foreign Sitzung absent",
        ) {
            testApp({
                val f = fixture("vstatus")
                val other = fixture("vstatus-other")
                val r = room(f)
                val me = member("vstatus-me").also { participate(r, it) }
                vote(f, "aborted", VoteStatus.ABORTED, closedAt = hoursAgo(1))
                vote(f, "closed-13h", VoteStatus.CLOSED, closedAt = hoursAgo(13), winner = 0)
                vote(other, "foreign-open", VoteStatus.OPEN, meetingId = other.meetingId)
                vote(f, "closed-1h", VoteStatus.CLOSED, closedAt = hoursAgo(1), winner = 1)
                vote(f, "open", VoteStatus.OPEN)

                val s = client.stateOk(r, me)
                s.ballots.map { it.title } shouldBe listOf("open", "closed-1h")
                s.ballots.map { it.status } shouldBe listOf(RoomBallotStatus.OPEN, RoomBallotStatus.DECIDED)
                s.ballots.all { it.kind == RoomBallotKind.VOTE && !it.secret } shouldBe true
                s.ballots.first().motionTitle shouldBe "Antrag zu open"
            })
        }

        test("V1.9.27 vote content: options ordered by position, the winner only once CLOSED, a tie has no winner") {
            testApp({
                val f = fixture("vcontent")
                val r = room(f)
                val me = member("vcontent-me").also { participate(r, it) }
                val open = vote(f, "open", VoteStatus.OPEN, labels = listOf("A", "B", "C"), winner = 2)
                val won = vote(f, "won", VoteStatus.CLOSED, closedAt = hoursAgo(1), winner = 1, secondPrice = BigDecimal("12.00"))
                vote(f, "tie", VoteStatus.CLOSED, closedAt = hoursAgo(2), winner = null)

                val s = client.stateOk(r, me)
                val byTitle = s.ballots.associateBy { it.title }
                byTitle.getValue("open").options.map { it.label } shouldBe listOf("A", "B", "C")
                byTitle.getValue("open").options.map { it.position } shouldBe listOf(0, 1, 2)
                byTitle.getValue("open").options.map { it.id } shouldBe open.optionIds.map { it.toString() }
                // even a (corrupt) winner on an OPEN row never reaches the client
                byTitle.getValue("open").winnerOptionId shouldBe null
                byTitle.getValue("won").winnerOptionId shouldBe won.optionIds[1].toString()
                byTitle.getValue("tie").winnerOptionId shouldBe null
                s.ballots.filter { it.kind == RoomBallotKind.ELECTION }.shouldBeEmpty()
            })
        }

        test(
            "V1.9.27 eligibility: General Assembly -> every ACTIVE member; Committee -> only a seated member, by date; non-members never",
        ) {
            testApp({
                val ga = fixture("veligga", type = CommitteeType.GENERAL_ASSEMBLY)
                val gaRoom = room(ga, allowGuests = true)
                val gaMember = member("veligga-m").also { participate(gaRoom, it) }
                val gaFriend = member("veligga-f", MemberStatus.FRIEND).also { participate(gaRoom, it) }
                val gaGuest = member("veligga-g", MemberStatus.GUEST).also { participate(gaRoom, it) }
                vote(ga, "ga-vote", VoteStatus.OPEN)
                client
                    .stateOk(gaRoom, gaMember)
                    .ballots
                    .single()
                    .ownEligible shouldBe true
                client
                    .stateOk(gaRoom, gaFriend)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
                client
                    .stateOk(gaRoom, gaGuest)
                    .ballots
                    .single()
                    .ownEligible shouldBe false

                // the Sitzung of the fixture is on 2026-03-01
                val c = fixture("velicom")
                val cRoom = room(c)
                val seated = member("velicom-seated").also { participate(cRoom, it) }
                val notSeated = member("velicom-not").also { participate(cRoom, it) }
                val seatedLater = member("velicom-later").also { participate(cRoom, it) }
                val seatEnded = member("velicom-ended").also { participate(cRoom, it) }
                seat(c, seated)
                seat(c, seatedLater, since = LocalDate(2026, 4, 1))
                seat(c, seatEnded, until = LocalDate(2026, 2, 1))
                vote(c, "c-vote", VoteStatus.OPEN)
                client
                    .stateOk(cRoom, seated)
                    .ballots
                    .single()
                    .ownEligible shouldBe true
                client
                    .stateOk(cRoom, notSeated)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
                client
                    .stateOk(cRoom, seatedLater)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
                client
                    .stateOk(cRoom, seatEnded)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
            })
        }

        test("V1.9.27 isCommitteeEligible decides exactly like eligibleMemberIds (the room and castVoteBallot cannot drift apart)") {
            DatabaseConfig.connect()
            val ga = fixture("parity-ga", type = CommitteeType.GENERAL_ASSEMBLY)
            val c = fixture("parity-c")
            val active = member("parity-active")
            val withdrawn = member("parity-withdrawn", MemberStatus.WITHDRAWN)
            val friend = member("parity-friend", MemberStatus.FRIEND)
            val seated = member("parity-seated")
            val ended = member("parity-ended")
            seat(c, seated)
            seat(c, ended, until = LocalDate(2026, 2, 1))
            val date = LocalDate(2026, 3, 1)
            transaction {
                listOf(ga, c).forEach { fx ->
                    val row = CommitteeTable.selectAll().where { CommitteeTable.id eq fx.committeeId }.single()
                    val ids = eligibleMemberIds(committeeRow = row, scheduledDate = date)
                    listOf(active, withdrawn, friend, seated, ended, fx.creator).forEach { m ->
                        isCommitteeEligible(committeeRow = row, scheduledDate = date, memberId = m) shouldBe (m in ids)
                    }
                }
            }
        }

        test("V1.9.27 ownHasVoted: only the caller's own bid counts, a foreign bid never") {
            testApp({
                val f = fixture("vown")
                val r = room(f)
                val bidder = member("vown-bidder").also { participate(r, it) }
                val bystander = member("vown-by").also { participate(r, it) }
                seat(f, bidder)
                seat(f, bystander)
                val v = vote(f, "own", VoteStatus.OPEN)
                bid(v, bidder, stake = "42.00")
                client
                    .stateOk(r, bidder)
                    .ballots
                    .single()
                    .ownHasVoted shouldBe true
                client
                    .stateOk(r, bystander)
                    .ballots
                    .single()
                    .ownHasVoted shouldBe false
            })
        }

        test("V1.9.27 field reduction: exact keys, and no amount, no stake, no foreign member id anywhere in the JSON") {
            testApp({
                val f = fixture("vfields")
                val r = room(f)
                val me = member("vfields-me").also { participate(r, it) }
                val other = member("vfields-other").also { participate(r, it) }
                seat(f, me)
                seat(f, other)
                val open = vote(f, "offen", VoteStatus.OPEN)
                bid(open, other, stake = "7777.77")
                bid(open, me, stake = "3333.33")
                val closed =
                    vote(f, "fertig", VoteStatus.CLOSED, closedAt = hoursAgo(1), winner = 0, secondPrice = BigDecimal("4444.44"))
                bid(closed, other, stake = "8888.88")

                val body = client.state(r.toString(), me).bodyAsText()
                val ballots =
                    json
                        .parseToJsonElement(body)
                        .jsonObject["ballots"]!!
                        .jsonArray
                        .map { it.jsonObject }
                val allowed =
                    setOf(
                        "kind",
                        "id",
                        "motionId",
                        "motionTitle",
                        "title",
                        "status",
                        "secret",
                        "ownEligible",
                        "ownHasVoted",
                        "options",
                        "winnerOptionId",
                    )
                ballots.forEach { (it.keys - allowed) shouldBe emptySet() }
                ballots.flatMap { it["options"]!!.jsonArray }.forEach { option ->
                    option.jsonObject.keys shouldBe setOf("id", "label", "position")
                }
                listOf("7777", "3333", "8888", "4444", "stake", "settled", "secondPrice", "basketTotal", "castAt", "memberId").forEach {
                    body shouldNotContain it
                }
                body shouldNotContain other.toString()
                body shouldNotContain me.toString()
                body shouldNotContain f.meetingId.toString()
                ballots.first { it["title"]!!.jsonPrimitive.content == "fertig" }.containsKey("winnerOptionId") shouldBe true
            })
        }

        test(
            "V1.9.27 shared cap: 15 open elections + 10 open votes -> 20 delivered and truncated, ended ballots never displace open ones",
        ) {
            testApp({
                val f = fixture("vcap")
                val r = room(f)
                val me = member("vcap-me").also { participate(r, it) }
                repeat(15) { election(f, "open-e-$it", ElectionStatus.OPEN) }
                repeat(10) { vote(f, "open-v-$it", VoteStatus.OPEN) }
                val s = client.stateOk(r, me)
                s.ballots.size shouldBe 20
                s.truncated shouldBe true
                s.ballots.all { it.status == RoomBallotStatus.OPEN } shouldBe true

                val f2 = fixture("vcap2")
                val r2 = room(f2)
                val me2 = member("vcap2-me").also { participate(r2, it) }
                repeat(12) { election(f2, "open-e-$it", ElectionStatus.OPEN) }
                repeat(8) { vote(f2, "open-v-$it", VoteStatus.OPEN) }
                repeat(5) { vote(f2, "closed-v-$it", VoteStatus.CLOSED, closedAt = hoursAgo(1), winner = 0) }
                repeat(5) { election(f2, "tallied-e-$it", ElectionStatus.TALLIED, tallyRunAt = hoursAgo(1)) }
                val s2 = client.stateOk(r2, me2)
                s2.ballots.size shouldBe 20
                s2.ballots.all { it.status == RoomBallotStatus.OPEN } shouldBe true
                s2.truncated shouldBe true
                s2.ballots.filter { it.kind == RoomBallotKind.ELECTION }.all { it.options.isEmpty() && it.winnerOptionId == null } shouldBe
                    true

                // with room to spare, ended ballots of both kinds follow the open ones, newest first
                val f3 = fixture("vcap3")
                val r3 = room(f3)
                val me3 = member("vcap3-me").also { participate(r3, it) }
                election(f3, "open-e", ElectionStatus.OPEN)
                vote(f3, "open-v", VoteStatus.OPEN)
                election(f3, "tallied-3h", ElectionStatus.TALLIED, tallyRunAt = hoursAgo(3))
                vote(f3, "closed-1h", VoteStatus.CLOSED, closedAt = hoursAgo(1), winner = 0)
                val s3 = client.stateOk(r3, me3)
                s3.truncated shouldBe false
                s3.ballots.map { it.title }.takeLast(2) shouldBe listOf("closed-1h", "tallied-3h")
                s3.ballots.take(2).all { it.status == RoomBallotStatus.OPEN } shouldBe true
            })
        }

        test("V1.9.27 an open meritocratic vote is no secret ballot: it never pauses the stream or blocks unbinding the room") {
            testApp({
                val f = fixture("vstream")
                val r = room(f)
                val me = member("vstream-me").also { participate(r, it) }
                vote(f, "open", VoteStatus.OPEN)
                client
                    .stateOk(r, me)
                    .ballots
                    .single()
                    .secret shouldBe false
                transaction {
                    SecretBallotStreamLock.hasOpenSecretBallotForMeeting(f.meetingId) shouldBe false
                    SecretBallotStreamLock.hasPendingOrOpenSecretBallot(f.meetingId) shouldBe false
                }
            })
        }

        test("V1.9.32 consensus content per phase: COLLECTION/RATING open, CLOSED awaiting tally, EVALUATED only inside the window") {
            testApp({
                val f = fixture("cphase")
                val r = room(f)
                val me = member("cphase-me").also { participate(r, it) }
                consensus(f, "sammeln", SystemicConsensusStatus.COLLECTION)
                val rating = consensus(f, "bewerten", SystemicConsensusStatus.RATING)
                consensusSnapshot(rating, me)
                consensus(f, "zu", SystemicConsensusStatus.CLOSED)
                consensus(f, "fertig", SystemicConsensusStatus.EVALUATED, tallyRunAt = hoursAgo(1))
                consensus(f, "alt", SystemicConsensusStatus.EVALUATED, tallyRunAt = hoursAgo(48))
                consensus(f, "weg", SystemicConsensusStatus.ABORTED)
                // another Sitzung's consensus never shows up
                val other = fixture("cphase-other")
                consensus(other, "fremd", SystemicConsensusStatus.RATING, meetingId = other.meetingId)

                val byTitle = client.stateOk(r, me).ballots.associateBy { it.title }
                byTitle.keys shouldBe setOf("sammeln", "bewerten", "zu", "fertig")
                byTitle.values.all { it.kind == RoomBallotKind.CONSENSUS } shouldBe true
                byTitle.getValue("sammeln").status shouldBe RoomBallotStatus.OPEN
                byTitle.getValue("sammeln").consensusPhase shouldBe SystemicConsensusStatus.COLLECTION
                byTitle.getValue("sammeln").ownEligible shouldBe false
                byTitle.getValue("bewerten").status shouldBe RoomBallotStatus.OPEN
                byTitle.getValue("bewerten").consensusPhase shouldBe SystemicConsensusStatus.RATING
                byTitle.getValue("bewerten").ownEligible shouldBe true
                byTitle.getValue("zu").status shouldBe RoomBallotStatus.CLOSED_AWAITING_TALLY
                byTitle.getValue("zu").consensusPhase shouldBe SystemicConsensusStatus.CLOSED
                byTitle.getValue("fertig").status shouldBe RoomBallotStatus.DECIDED
                byTitle.getValue("fertig").consensusPhase shouldBe SystemicConsensusStatus.EVALUATED
                byTitle.values.all { it.options.isEmpty() && it.winnerOptionId == null } shouldBe true
            })
        }

        test("V1.9.32 consensus eligibility: head-based, only an ACTIVE member with a snapshot of the current round") {
            testApp({
                val f = fixture("celig")
                val r = room(f, allowGuests = true)
                val k = consensus(f, "elig", SystemicConsensusStatus.RATING)
                val withSnapshot = member("celig-yes").also { participate(r, it) }
                val withoutSnapshot = member("celig-no").also { participate(r, it) }
                val guest = member("celig-guest", MemberStatus.GUEST).also { participate(r, it) }
                val friend = member("celig-friend", MemberStatus.FRIEND).also { participate(r, it) }
                consensusSnapshot(k, withSnapshot)
                consensusSnapshot(k, guest)
                consensusSnapshot(k, friend)
                client
                    .stateOk(r, withSnapshot)
                    .ballots
                    .single()
                    .ownEligible shouldBe true
                client
                    .stateOk(r, withoutSnapshot)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
                client
                    .stateOk(r, guest)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
                client
                    .stateOk(r, friend)
                    .ballots
                    .single()
                    .ownEligible shouldBe false
            })
        }

        test("V1.9.32 consensus ownHasVoted is per round (anonymous and open) and a foreign rating never counts") {
            testApp({
                val f = fixture("cround")
                val r = room(f)
                val me = member("cround-me").also { participate(r, it) }
                val other = member("cround-other").also { participate(r, it) }
                listOf(true, false).forEach { secret ->
                    val k = consensus(f, "runde-$secret", SystemicConsensusStatus.RATING, secret = secret)
                    consensusSnapshot(k, me)
                    consensusSnapshot(k, other)
                    consensusRated(k, other, secret = secret)
                    client
                        .stateOk(r, me)
                        .ballots
                        .first { it.id == k.toString() }
                        .ownHasVoted shouldBe false
                    consensusRated(k, me, secret = secret)
                    client
                        .stateOk(r, me)
                        .ballots
                        .first { it.id == k.toString() }
                        .ownHasVoted shouldBe true
                    // reopenRating: round 2 with a fresh snapshot, the round-1 rating must not survive
                    transaction { SystemicConsensusTable.update({ SystemicConsensusTable.id eq k }) { it[round] = 2 } }
                    consensusSnapshot(k, me, round = 2)
                    val reopened = client.stateOk(r, me).ballots.first { it.id == k.toString() }
                    reopened.ownHasVoted shouldBe false
                    reopened.ownEligible shouldBe true
                }
            })
        }

        test(
            "V1.9.32 shared cap: open elections, votes and consensuses share ONE cap, an evaluated consensus never displaces an open one",
        ) {
            testApp({
                val f = fixture("ccap")
                val r = room(f)
                val me = member("ccap-me").also { participate(r, it) }
                repeat(15) { election(f, "open-e-$it", ElectionStatus.OPEN) }
                repeat(3) { vote(f, "open-v-$it", VoteStatus.OPEN) }
                repeat(5) { consensus(f, "open-c-$it", SystemicConsensusStatus.RATING) }
                repeat(4) { consensus(f, "done-c-$it", SystemicConsensusStatus.EVALUATED, tallyRunAt = hoursAgo(1)) }
                val s = client.stateOk(r, me)
                s.ballots.size shouldBe 20
                s.truncated shouldBe true
                s.ballots.all { it.status == RoomBallotStatus.OPEN } shouldBe true
                s.ballots.none { it.status == RoomBallotStatus.DECIDED } shouldBe true
            })
        }

        test("V1.9.32 consensus field reduction: exact JSON keys, no rating, no option, no time, no foreign id or receipt") {
            testApp({
                val f = fixture("cfields")
                val r = room(f)
                val me = member("cfields-me").also { participate(r, it) }
                val other = member("cfields-other").also { participate(r, it) }
                listOf(true, false).forEach { secret ->
                    val k = consensus(f, "k-$secret", SystemicConsensusStatus.RATING, secret = secret)
                    consensusSnapshot(k, me)
                    consensusSnapshot(k, other)
                    consensusRated(k, other, secret = secret, receipt = if (secret) "RCPT-CONSENSUS-0123456789" else null)
                }
                consensus(f, "fertig", SystemicConsensusStatus.EVALUATED, tallyRunAt = hoursAgo(1))
                val body = client.state(r.toString(), me).bodyAsText()
                val ballots =
                    json
                        .parseToJsonElement(body)
                        .jsonObject["ballots"]!!
                        .jsonArray
                        .map { it.jsonObject }
                ballots.size shouldBe 3
                ballots.forEach {
                    it.keys shouldBe
                        setOf(
                            "kind",
                            "id",
                            "motionId",
                            "motionTitle",
                            "title",
                            "status",
                            "secret",
                            "ownEligible",
                            "ownHasVoted",
                            "consensusPhase",
                        )
                }
                listOf(
                    "RCPT-CONSENSUS",
                    "OPEN-",
                    "winnerOptionId",
                    "options",
                    "openedAt",
                    "ratingOpenedAt",
                    "tallyRunAt",
                    "castAt",
                    "resistance",
                    "memberId",
                ).forEach { body shouldNotContain it }
                body shouldNotContain other.toString()
                body shouldNotContain me.toString()
                body shouldNotContain f.meetingId.toString()
            })
        }

        test("V1.9.32 a room with only a consensus keeps the uniform denial for every unauthorised caller") {
            testApp({
                val f = fixture("cauthz")
                val r = room(f)
                consensus(f, "nur-konsens", SystemicConsensusStatus.RATING)
                val notInRoom = member("cauthz-out")
                val left = member("cauthz-left").also { participate(r, it, left = true) }
                val withdrawn = member("cauthz-wd", MemberStatus.WITHDRAWN).also { participate(r, it) }
                client.shouldBeUniformlyDenied(r.toString(), notInRoom)
                client.shouldBeUniformlyDenied(r.toString(), left)
                client.shouldBeUniformlyDenied(r.toString(), withdrawn)
                client.shouldBeUniformlyDenied(Uuid.random().toString(), notInRoom)
            })
        }

        test(
            "rate limit: exhausting the own budget yields the rate error (Conflict), not the uniform denial; disabled conference likewise",
        ) {
            testApp(
                block = {
                    val f = fixture("rate")
                    val r = room(f)
                    val me = member("rate-me").also { participate(r, it) }
                    client.state(r.toString(), me).status shouldBe HttpStatusCode.OK
                    client.state(r.toString(), me).status shouldBe HttpStatusCode.OK
                    val third = client.state(r.toString(), me)
                    third.status shouldBe HttpStatusCode.Conflict
                    third.bodyAsText() shouldNotBe UNAVAILABLE
                },
                limiter = { FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes) },
            )
            testApp(
                block = {
                    val f = fixture("disabled")
                    val r = room(f)
                    val me = member("disabled-me").also { participate(r, it) }
                    val resp = client.state(r.toString(), me)
                    resp.status shouldBe HttpStatusCode.Conflict
                    resp.bodyAsText() shouldNotBe UNAVAILABLE
                },
                config = ConferenceConfig.load { null },
            )
        }
    })

private object VotingStateStubLiveKit : LiveKitAdminClient {
    override suspend fun createRoom(
        name: String,
        maxParticipants: Int,
        emptyTimeoutSeconds: Int,
        departureTimeoutSeconds: Int?,
    ): LiveKitRoomInfo = error("not used")

    override suspend fun deleteRoom(name: String): Unit = error("not used")

    override suspend fun listRooms(): List<LiveKitRoomInfo> = error("not used")

    override suspend fun listParticipants(room: String): List<LiveKitParticipantInfo> = error("not used")

    override suspend fun removeParticipant(
        room: String,
        identity: String,
    ): Unit = error("not used")
}

private fun StatusPagesConfig.installVotingStateExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}

private fun Route.registerVotingStateTestRoutes(
    config: ConferenceConfig,
    limiter: FederationInboxRateLimiter,
) {
    get("/test/room-voting-state") {
        val service =
            ConferenceService(
                call = call,
                liveKitAdminClient = VotingStateStubLiveKit,
                createRoomRateLimiter = LoginRateLimiter(),
                config = config,
                conferenceMeetingBindRateLimiter = FederationInboxRateLimiter(),
                roomVotingStateRateLimiter = limiter,
            )
        val dto = service.getRoomVotingState(call.request.queryParameters["roomId"]!!)
        call.respondText(Json.encodeToString(RoomVotingStateDto.serializer(), dto))
    }
}
