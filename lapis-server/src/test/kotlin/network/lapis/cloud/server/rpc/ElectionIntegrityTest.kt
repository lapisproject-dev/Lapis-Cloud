package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.NoOpSecretBallotStreamGuard
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AgendaItemTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionCandidacyTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import network.lapis.cloud.server.db.generated.VoteOptionTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CandidacyInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionAnswer
import network.lapis.cloud.shared.domain.ElectionBallotInput
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionResolutionInput
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionInput
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CyclicBarrier
import kotlin.uuid.Uuid

/**
 * V1.9.23 server-integrity tests for the democratic elections: mutual exclusion of the four
 * decision paths (quorum resolution, meritocratic vote, systemic consensus, election), the missing
 * locks in [ElectionService], the tally threshold floor and the ballot-secrecy fixes.
 *
 * Same "throwaway routes calling the service class directly" house style as [ElectionServiceTest].
 * Every test creates its own committee/members/meeting/motion so the global in-memory database is
 * never shared between tests; [afterSpec] hard-deletes everything this spec created.
 *
 * Concurrency tests fire requests in parallel; they accept a [HttpStatusCode.Conflict] (and, for
 * robustness against H2's lock timeouts, a 5xx) as a non-success but assert a consistent end state.
 */
class ElectionIntegrityTest :
    FunSpec({
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpIntegrityTestData(committeeIds = createdCommitteeIds, memberIds = createdMemberIds) }

        fun createMember(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Integrity Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun createCommittee(name: String): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[CommitteeTable.name] = name
                    it[type] = CommitteeType.EXECUTIVE_BOARD
                    it[description] = "Testcommittee"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdCommitteeIds += id
            return id
        }

        fun addToCommittee(
            committeeId: Uuid,
            memberId: Uuid,
            role: CommitteeRole,
        ) {
            transaction {
                CommitteeMembershipTable.insert {
                    it[id] = Uuid.random()
                    it[CommitteeMembershipTable.committeeId] = committeeId
                    it[CommitteeMembershipTable.memberId] = memberId
                    it[CommitteeMembershipTable.role] = role
                    it[since] = LocalDate(2020, 1, 1)
                    it[until] = null
                }
            }
        }

        fun createMeeting(committeeId: Uuid): Uuid {
            val id = Uuid.random()
            val at = LocalDateTime(2026, 3, 1, 18, 0)
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = id
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "Integrity Testmeeting"
                    it[scheduledAt] = at
                    it[location] = "Vereinsheim"
                    it[format] = MeetingFormat.IN_PERSON
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = at
                }
            }
            return id
        }

        fun createScheduledMotion(
            committeeId: Uuid,
            meetingId: Uuid,
            submitterId: Uuid,
            agendaItemId: Uuid? = null,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MotionTable.insert {
                    it[MotionTable.id] = id
                    it[targetCommitteeId] = committeeId
                    it[title] = "Integrity Testmotion"
                    it[rationale] = "Rationale"
                    it[text] = "Motionstext"
                    it[submitterMemberId] = submitterId
                    it[status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = submitterId
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[MotionTable.agendaItemId] = agendaItemId
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
            }
            return id
        }

        /** Committee + chair + 3 election board members + 2 voters + meeting + SCHEDULED motion. */
        class Fixture(
            val committeeId: Uuid,
            val chair: Uuid,
            val board: List<Uuid>,
            val voters: List<Uuid>,
            val meetingId: Uuid,
            val motionId: Uuid,
        )

        fun fixture(
            tag: String,
            agendaItem: Boolean = false,
        ): Fixture {
            val committeeId = createCommittee("Integrity $tag")
            val chair = createMember("integrity-$tag-chair@example.org")
            addToCommittee(committeeId, chair, CommitteeRole.CHAIR)
            val voters = (1..2).map { createMember("integrity-$tag-v$it@example.org") }
            voters.forEach { addToCommittee(committeeId, it, CommitteeRole.MEMBER) }
            val board = (1..3).map { createMember("integrity-$tag-wv$it@example.org") }
            val meetingId = createMeeting(committeeId)
            val agendaItemId =
                if (agendaItem) {
                    Uuid.random().also { aid ->
                        transaction {
                            AgendaItemTable.insert {
                                it[AgendaItemTable.id] = aid
                                it[position] = 1
                                it[title] = "TOP 1"
                                it[description] = null
                                it[presenterMemberId] = null
                                it[AgendaItemTable.meetingId] = meetingId
                            }
                        }
                    }
                } else {
                    null
                }
            val motionId = createScheduledMotion(committeeId, meetingId, chair, agendaItemId)
            return Fixture(committeeId, chair, board, voters, meetingId, motionId)
        }

        suspend fun HttpClient.openElection(
            f: Fixture,
            query: String = "secret=false",
        ): String =
            post("/test/open-election/${f.motionId}/YES_NO?$query") { header("X-Member-Id", f.chair.toString()) }
                .bodyAsText()
                .substringBefore(":")

        suspend fun HttpClient.appointBoard(
            f: Fixture,
            electionId: String,
        ) {
            post("/test/appoint-election-board/$electionId?memberIds=${f.board.joinToString(",")}") {
                header("X-Member-Id", f.chair.toString())
            }
        }

        /** Drives a YES_NO election through PREPARATION -> OPEN -> (2 ballots) -> CLOSED with 2 tally approvals. */
        suspend fun HttpClient.driveToClosedWithApprovals(
            f: Fixture,
            electionId: String,
        ) {
            appointBoard(f, electionId)
            post("/test/open-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
            post("/test/cast-election-ballot/$electionId?answer=YES") { header("X-Member-Id", f.voters[0].toString()) }
            post("/test/cast-election-ballot/$electionId?answer=YES") { header("X-Member-Id", f.voters[1].toString()) }
            post("/test/close-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
            post("/test/release-tally/$electionId") { header("X-Member-Id", f.board[0].toString()) }
            post("/test/release-tally/$electionId") { header("X-Member-Id", f.board[1].toString()) }
        }

        suspend fun <T> parallel(
            n: Int,
            block: suspend (index: Int) -> T,
        ): List<T> {
            val barrier = CyclicBarrier(n)
            return coroutineScope {
                (0 until n)
                    .map { i ->
                        async(Dispatchers.IO) {
                            barrier.await()
                            block(i)
                        }
                    }.map { it.await() }
            }
        }

        fun insertRawOpenVote(f: Fixture): Uuid {
            val id = Uuid.random()
            transaction {
                VoteTable.insert {
                    it[VoteTable.id] = id
                    it[motionId] = f.motionId
                    it[meetingId] = f.meetingId
                    it[title] = "raw vote"
                    it[status] = VoteStatus.OPEN
                    it[openedBy] = f.chair
                    it[openedAt] = LocalDateTime(2026, 3, 1, 18, 0)
                    it[closedAt] = null
                    it[winnerOptionId] = null
                    it[secondPriceLtr] = null
                    it[resolutionId] = null
                }
                listOf("YES", "NO").forEachIndexed { index, label ->
                    VoteOptionTable.insert {
                        it[VoteOptionTable.id] = Uuid.random()
                        it[voteId] = id
                        it[VoteOptionTable.label] = label
                        it[position] = index
                    }
                }
            }
            return id
        }

        fun insertRawSystemicConsensus(
            f: Fixture,
            status: SystemicConsensusStatus,
            bindingness: SystemicConsensusBindingness,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                SystemicConsensusTable.insert {
                    it[SystemicConsensusTable.id] = id
                    it[motionId] = f.motionId
                    it[meetingId] = f.meetingId
                    it[title] = "raw sc"
                    it[SystemicConsensusTable.status] = status
                    it[secret] = false
                    it[scaleMax] = 10
                    it[aggregation] = network.lapis.cloud.shared.domain.SystemicConsensusAggregation.MEAN
                    it[tiebreakRule] = network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE
                    it[groupConflictViableThreshold] = java.math.BigDecimal("0.200")
                    it[groupConflictWarnThreshold] = java.math.BigDecimal("0.500")
                    it[statusQuoOptionAuto] = true
                    it[SystemicConsensusTable.bindingness] = bindingness
                    it[maxRounds] = 3
                    it[round] = 1
                    it[winnerOptionId] = null
                    it[openedBy] = f.chair
                    it[openedAt] = LocalDateTime(2026, 3, 1, 18, 0)
                    it[ratingOpenedAt] = null
                    it[ratingClosedAt] = null
                    it[tallyRunAt] = null
                    it[resolutionId] = null
                }
            }
            return id
        }

        fun resolutionCountForMotion(motionId: Uuid): Long =
            transaction {
                val motionResolution = MotionTable.selectAll().where { MotionTable.id eq motionId }.single()[MotionTable.resolutionId]
                if (motionResolution == null) 0L else 1L
            }

        fun meetingResolutionCount(meetingId: Uuid): Long =
            transaction { ResolutionTable.selectAll().where { ResolutionTable.meetingId eq meetingId }.count() }

        fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
            testApplication {
                application {
                    install(StatusPages) { installIntegrityExceptionHandlers() }
                    routing { registerIntegrityTestRoutes() }
                }
                block()
            }
        }

        // ------------------------------------------------------------------ L1: tally on a decided motion

        test("tally refuses to run when the motion was already decided elsewhere and writes nothing") {
            withApp {
                val f = fixture("tally-decided")
                val electionId = client.openElection(f)
                client.driveToClosedWithApprovals(f, electionId)
                // Simulates the quorum path having decided the motion before tally() got the motion lock.
                transaction { MotionTable.update({ MotionTable.id eq f.motionId }) { it[status] = MotionStatus.RESOLVED } }
                val before = meetingResolutionCount(f.meetingId)
                val response = client.post("/test/tally/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                response.status shouldBe HttpStatusCode.Conflict
                meetingResolutionCount(f.meetingId) shouldBe before
                transaction {
                    ElectionTable.selectAll().where { ElectionTable.id eq Uuid.parse(electionId) }.single()[ElectionTable.status]
                } shouldBe
                    ElectionStatus.CLOSED
            }
        }

        // ------------------------------------------------------------------ L2: mutual exclusion matrix

        test("while an election is active every other decision path on the motion answers Conflict") {
            withApp {
                val f = fixture("excl-matrix", agendaItem = true)
                val electionId = client.openElection(f)
                val chair = f.chair.toString()
                client.post("/test/open-vote/${f.motionId}") { header("X-Member-Id", chair) }.status shouldBe HttpStatusCode.Conflict
                client.post("/test/resolve-motion/${f.motionId}/ADOPTED") { header("X-Member-Id", chair) }.status shouldBe
                    HttpStatusCode.Conflict
                client.post("/test/withdraw-motion/${f.motionId}") { header("X-Member-Id", chair) }.status shouldBe HttpStatusCode.Conflict
                client.post("/test/open-systemic-consensus/${f.motionId}") { header("X-Member-Id", chair) }.status shouldBe
                    HttpStatusCode.Conflict
                val agendaItemId =
                    transaction { MotionTable.selectAll().where { MotionTable.id eq f.motionId }.single()[MotionTable.agendaItemId] }
                client
                    .post("/test/record-resolution/${f.meetingId}?agendaItemId=$agendaItemId") { header("X-Member-Id", chair) }
                    .status shouldBe HttpStatusCode.Conflict
                // A legacy/raced Vote row next to the election must not be closable or abortable.
                val voteId = insertRawOpenVote(f)
                client.post("/test/close-vote/$voteId") { header("X-Member-Id", chair) }.status shouldBe HttpStatusCode.Conflict
                // The motion is untouched.
                transaction { MotionTable.selectAll().where { MotionTable.id eq f.motionId }.single()[MotionTable.status] } shouldBe
                    MotionStatus.SCHEDULED
                // After the election is aborted the motion is free again.
                client.post("/test/abort-election/$electionId") { header("X-Member-Id", chair) }.status shouldBe HttpStatusCode.OK
                client.post("/test/abort-vote/$voteId") { header("X-Member-Id", chair) }.status shouldBe HttpStatusCode.OK
                client.openElection(f).isNotBlank() shouldBe true
            }
        }

        test("openElection is refused while a vote, a systemic consensus or a resolution exists, and for a second election") {
            withApp {
                val f1 = fixture("excl-open-vote")
                insertRawOpenVote(f1)
                client
                    .post(
                        "/test/open-election/${f1.motionId}/YES_NO?secret=false",
                    ) { header("X-Member-Id", f1.chair.toString()) }
                    .status shouldBe
                    HttpStatusCode.Conflict

                val f2 = fixture("excl-open-sc")
                insertRawSystemicConsensus(f2, SystemicConsensusStatus.COLLECTION, SystemicConsensusBindingness.BINDING)
                client
                    .post(
                        "/test/open-election/${f2.motionId}/YES_NO?secret=false",
                    ) { header("X-Member-Id", f2.chair.toString()) }
                    .status shouldBe
                    HttpStatusCode.Conflict

                val f3 = fixture("excl-open-twice")
                client.openElection(f3).isNotBlank() shouldBe true
                client
                    .post(
                        "/test/open-election/${f3.motionId}/YES_NO?secret=false",
                    ) { header("X-Member-Id", f3.chair.toString()) }
                    .status shouldBe
                    HttpStatusCode.Conflict

                val f4 = fixture("excl-open-decided")
                transaction { MotionTable.update({ MotionTable.id eq f4.motionId }) { it[status] = MotionStatus.RESOLVED } }
                client
                    .post(
                        "/test/open-election/${f4.motionId}/YES_NO?secret=false",
                    ) { header("X-Member-Id", f4.chair.toString()) }
                    .status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("a closed BINDING systemic consensus cannot evaluate into a motion that has an active election") {
            withApp {
                val f = fixture("excl-sc-evaluate")
                client.openElection(f)
                val scId = insertRawSystemicConsensus(f, SystemicConsensusStatus.CLOSED, SystemicConsensusBindingness.BINDING)
                client.post("/test/evaluate-systemic-consensus/$scId") { header("X-Member-Id", f.chair.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
                meetingResolutionCount(f.meetingId) shouldBe 0L
            }
        }

        // ------------------------------------------------------------------ L4: threshold floor

        test("tallyThreshold 1 is refused: one approval must not be enough") {
            withApp {
                val f = fixture("threshold")
                client
                    .post("/test/open-election/${f.motionId}/YES_NO?secret=false&tallyThreshold=1") {
                        header("X-Member-Id", f.chair.toString())
                    }.status shouldBe HttpStatusCode.Conflict
                client
                    .post("/test/open-election/${f.motionId}/YES_NO?secret=false&tallyThreshold=2") {
                        header("X-Member-Id", f.chair.toString())
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        // ------------------------------------------------------------------ L6 / L6b: ballot secrecy

        test("secret election: castAt carries no time signal and listElectionBallots leaks no stable ids before TALLIED") {
            withApp {
                val f = fixture("secrecy")
                val electionId = client.openElection(f, "secret=true")
                client.appointBoard(f, electionId)
                client.post("/test/open-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                val openedAt =
                    transaction {
                        ElectionTable
                            .selectAll()
                            .where {
                                ElectionTable.id eq
                                    Uuid.parse(
                                        electionId,
                                    )
                            }.single()[ElectionTable.votingOpenedAt]!!
                    }
                f.voters.forEach {
                    client.post("/test/cast-election-ballot/$electionId?answer=YES") { header("X-Member-Id", it.toString()) }
                }
                val castTimes =
                    transaction {
                        ElectionBallotTable
                            .selectAll()
                            .where { ElectionBallotTable.electionId eq Uuid.parse(electionId) }
                            .map { it[ElectionBallotTable.castAt] }
                    }
                castTimes.size shouldBe 2
                castTimes.forEach { it shouldBe openedAt }
                // Polling the ballot list while OPEN must reveal nothing (no ids whose appearance times a voter).
                client.get("/test/list-ballots/$electionId") { header("X-Member-Id", f.chair.toString()) }.bodyAsText() shouldBe ""
                client.post("/test/close-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                client.get("/test/list-ballots/$electionId") { header("X-Member-Id", f.chair.toString()) }.bodyAsText() shouldBe ""
            }
        }

        test("secret election after TALLIED: ballot ids are blank and the order is canonical, not insertion order") {
            withApp {
                val f = fixture("secrecy-tallied")
                val electionId = client.openElection(f, "secret=true")
                client.appointBoard(f, electionId)
                client.post("/test/open-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                // voters[0] votes NO first, voters[1] votes YES second: insertion order is NO, YES.
                client.post("/test/cast-election-ballot/$electionId?answer=NO") { header("X-Member-Id", f.voters[0].toString()) }
                client.post("/test/cast-election-ballot/$electionId?answer=YES") { header("X-Member-Id", f.voters[1].toString()) }
                client.post("/test/close-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", f.board[1].toString()) }
                client.post("/test/tally/$electionId") { header("X-Member-Id", f.board[0].toString()) }.status shouldBe HttpStatusCode.OK
                val rows =
                    client
                        .get(
                            "/test/list-ballots/$electionId",
                        ) { header("X-Member-Id", f.chair.toString()) }
                        .bodyAsText()
                        .split(";")
                rows.size shouldBe 2
                rows.forEach { it.substringBefore(":") shouldBe "" }
                // Canonical order follows the option positions (YES, NO, ABSTAIN), independent of who voted first.
                rows.map { it.substringAfter(":") } shouldBe listOf("YES", "NO")
            }
        }

        // ------------------------------------------------------------------ L5: exact majority fraction

        test("openElection stores a reduced fraction and a legacy display percent, and refuses invalid fractions") {
            withApp {
                val f = fixture("majority-open")
                val chair = f.chair.toString()
                val ok = client.post("/test/open-election/${f.motionId}/YES_NO?secret=false&num=4&den=6") { header("X-Member-Id", chair) }
                ok.status shouldBe HttpStatusCode.OK
                ok.bodyAsText().split(":").drop(2) shouldBe listOf("2", "3", "67")
                client.post("/test/abort-election/${ok.bodyAsText().substringBefore(":")}") { header("X-Member-Id", chair) }

                listOf("num=1&den=3", "num=3", "den=2", "num=101&den=100", "num=0&den=1", "num=1&den=0").forEach { bad ->
                    client
                        .post(
                            "/test/open-election/${f.motionId}/YES_NO?secret=false&$bad",
                        ) { header("X-Member-Id", chair) }
                        .status shouldBe
                        HttpStatusCode.Conflict
                }
                // plurality has no majority fraction
                val target = createCommittee("Integrity majority-open target")
                client
                    .post(
                        "/test/open-election/${f.motionId}/MULTI_CHOICE?secret=false&targetCommitteeId=$target&seatCount=2&num=2&den=3",
                    ) {
                        header("X-Member-Id", chair)
                    }.status shouldBe HttpStatusCode.Conflict
                // nothing was left behind by the refused attempts
                transaction {
                    ElectionTable
                        .selectAll()
                        .where {
                            (ElectionTable.motionId eq f.motionId) and
                                (ElectionTable.activeMotionId eq f.motionId)
                        }.count()
                } shouldBe 0L
            }
        }

        test("tally applies the stored fraction exactly: 2 to 1 passes 2/3 and fails 3/4") {
            withApp {
                listOf("num=2&den=3" to true, "num=3&den=4" to false, "" to true).forEach { (majority, expectedMet) ->
                    val f = fixture("majority-tally-${majority.hashCode()}")
                    val electionId = client.openElection(f, "secret=false&$majority")
                    client.appointBoard(f, electionId)
                    client.post("/test/open-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                    client.post("/test/cast-election-ballot/$electionId?answer=YES") { header("X-Member-Id", f.chair.toString()) }
                    client.post("/test/cast-election-ballot/$electionId?answer=YES") { header("X-Member-Id", f.voters[0].toString()) }
                    client.post("/test/cast-election-ballot/$electionId?answer=NO") { header("X-Member-Id", f.voters[1].toString()) }
                    client.post("/test/close-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                    client.post("/test/release-tally/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                    client.post("/test/release-tally/$electionId") { header("X-Member-Id", f.board[1].toString()) }
                    val result = client.post("/test/tally/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                    result.status shouldBe HttpStatusCode.OK
                    result.bodyAsText().split(":")[2] shouldBe expectedMet.toString()
                }
            }
        }

        test("a tallied election keeps owning its motion: a postponed motion is not open to a second election") {
            withApp {
                val f = fixture("majority-retie")
                val electionId = client.openElection(f)
                client.appointBoard(f, electionId)
                client.post("/test/open-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                client.post("/test/cast-election-ballot/$electionId?answer=YES") { header("X-Member-Id", f.voters[0].toString()) }
                client.post("/test/cast-election-ballot/$electionId?answer=NO") { header("X-Member-Id", f.voters[1].toString()) }
                client.post("/test/close-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", f.board[1].toString()) }
                client.post("/test/tally/$electionId") { header("X-Member-Id", f.board[0].toString()) }.status shouldBe HttpStatusCode.OK
                transaction { MotionTable.selectAll().where { MotionTable.id eq f.motionId }.single()[MotionTable.status] } shouldBe
                    MotionStatus.POSTPONED
                // Re-scheduled (as GovernanceService.scheduleMotion does: status back to SCHEDULED, the old resolution stays attached).
                transaction { MotionTable.update({ MotionTable.id eq f.motionId }) { it[status] = MotionStatus.SCHEDULED } }
                // Unchanged behaviour: a TALLIED election is not ABORTED, so it still owns the motion.
                client
                    .post(
                        "/test/open-election/${f.motionId}/YES_NO?secret=false",
                    ) { header("X-Member-Id", f.chair.toString()) }
                    .status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        test("a tied, postponed and re-scheduled motion stays decidable and withdrawable") {
            withApp {
                listOf("resolve", "vote", "withdraw").forEach { path ->
                    val f = fixture("retie-$path")
                    val electionId = client.openElection(f)
                    client.appointBoard(f, electionId)
                    client.post("/test/open-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                    client.post("/test/cast-election-ballot/$electionId?answer=YES") { header("X-Member-Id", f.voters[0].toString()) }
                    client.post("/test/cast-election-ballot/$electionId?answer=NO") { header("X-Member-Id", f.voters[1].toString()) }
                    client.post("/test/close-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                    client.post("/test/release-tally/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                    client.post("/test/release-tally/$electionId") { header("X-Member-Id", f.board[1].toString()) }
                    client.post("/test/tally/$electionId") { header("X-Member-Id", f.board[0].toString()) }.status shouldBe
                        HttpStatusCode.OK
                    transaction { MotionTable.update({ MotionTable.id eq f.motionId }) { it[status] = MotionStatus.SCHEDULED } }
                    val chair = f.chair.toString()
                    val status =
                        when (path) {
                            "resolve" -> client.post("/test/resolve-motion/${f.motionId}/ADOPTED") { header("X-Member-Id", chair) }.status
                            "vote" -> client.post("/test/open-vote/${f.motionId}") { header("X-Member-Id", chair) }.status
                            else -> client.post("/test/withdraw-motion/${f.motionId}") { header("X-Member-Id", chair) }.status
                        }
                    status shouldBe HttpStatusCode.OK
                }
            }
        }

        test("an evaluated ADVISORY systemic consensus does not block openElection or openVote, a BINDING one does") {
            withApp {
                val f1 = fixture("advisory-then-election")
                insertRawSystemicConsensus(f1, SystemicConsensusStatus.EVALUATED, SystemicConsensusBindingness.ADVISORY)
                client.openElection(f1).isNotBlank() shouldBe true

                val f2 = fixture("advisory-then-vote")
                insertRawSystemicConsensus(f2, SystemicConsensusStatus.EVALUATED, SystemicConsensusBindingness.ADVISORY)
                client.post("/test/open-vote/${f2.motionId}") { header("X-Member-Id", f2.chair.toString()) }.status shouldBe
                    HttpStatusCode.OK

                val f3 = fixture("binding-evaluated-then-vote")
                insertRawSystemicConsensus(f3, SystemicConsensusStatus.EVALUATED, SystemicConsensusBindingness.BINDING)
                client.post("/test/open-vote/${f3.motionId}") { header("X-Member-Id", f3.chair.toString()) }.status shouldBe
                    HttpStatusCode.Conflict
            }
        }

        // ------------------------------------------------------------------ L1/L2/L3: concurrency

        test("concurrent tally and resolveMotion produce exactly one resolution for the motion") {
            withApp {
                repeat(5) { round ->
                    val f = fixture("race-tally-resolve-$round")
                    val electionId = client.openElection(f)
                    client.driveToClosedWithApprovals(f, electionId)
                    val results =
                        parallel(6) { i ->
                            if (i % 2 == 0) {
                                client.post("/test/tally/$electionId") { header("X-Member-Id", f.board[0].toString()) }.status
                            } else {
                                client
                                    .post(
                                        "/test/resolve-motion/${f.motionId}/ADOPTED",
                                    ) { header("X-Member-Id", f.chair.toString()) }
                                    .status
                            }
                        }
                    meetingResolutionCount(f.meetingId) shouldBe 1L
                    // resolveMotion must never win once an election exists.
                    results.filterIndexed { i, _ -> i % 2 == 1 }.none { it == HttpStatusCode.OK } shouldBe true
                }
            }
        }

        test("concurrent openElection and openVote: exactly one decision path wins the motion") {
            withApp {
                repeat(5) { round ->
                    val f = fixture("race-open-$round")
                    val results =
                        parallel(2) { i ->
                            if (i == 0) {
                                client
                                    .post(
                                        "/test/open-election/${f.motionId}/YES_NO?secret=false",
                                    ) { header("X-Member-Id", f.chair.toString()) }
                                    .status
                            } else {
                                client.post("/test/open-vote/${f.motionId}") { header("X-Member-Id", f.chair.toString()) }.status
                            }
                        }
                    results.count { it == HttpStatusCode.OK } shouldBe 1
                    val elections = transaction { ElectionTable.selectAll().where { ElectionTable.motionId eq f.motionId }.count() }
                    val votes = transaction { VoteTable.selectAll().where { VoteTable.motionId eq f.motionId }.count() }
                    (elections + votes) shouldBe 1L
                }
            }
        }

        test("concurrent openElection calls for the same motion create exactly one election") {
            withApp {
                repeat(5) { round ->
                    val f = fixture("race-open-open-$round")
                    val results =
                        parallel(6) {
                            client
                                .post(
                                    "/test/open-election/${f.motionId}/YES_NO?secret=false",
                                ) { header("X-Member-Id", f.chair.toString()) }
                                .status
                        }
                    results.count { it == HttpStatusCode.OK } shouldBe 1
                    transaction { ElectionTable.selectAll().where { ElectionTable.motionId eq f.motionId }.count() } shouldBe 1L
                }
            }
        }

        test("concurrent openVoting calls take exactly one electorate snapshot") {
            withApp {
                repeat(3) { round ->
                    val f = fixture("race-open-voting-$round")
                    val electionId = client.openElection(f)
                    client.appointBoard(f, electionId)
                    val results =
                        parallel(5) { client.post("/test/open-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }.status }
                    results.count { it == HttpStatusCode.OK } shouldBe 1
                    val eligible =
                        transaction {
                            ElectionEligibleVoterTable
                                .selectAll()
                                .where {
                                    ElectionEligibleVoterTable.electionId eq
                                        Uuid.parse(
                                            electionId,
                                        )
                                }.toList()
                        }
                    eligible.map { it[ElectionEligibleVoterTable.memberId] }.distinct().size shouldBe eligible.size
                }
            }
        }

        test("concurrent releaseCandidateList calls create each option exactly once") {
            withApp {
                val f = fixture("race-release-list")
                val target = createCommittee("Integrity race-release-list target")
                val electionId =
                    client
                        .post("/test/open-election/${f.motionId}/SINGLE_CHOICE?secret=false&targetCommitteeId=$target") {
                            header("X-Member-Id", f.chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                f.voters.forEach { client.post("/test/submit-candidacy/$electionId") { header("X-Member-Id", it.toString()) } }
                parallel(
                    5,
                ) { client.post("/test/release-kandidatenliste/$electionId") { header("X-Member-Id", f.chair.toString()) }.status }
                transaction {
                    ElectionOptionTable
                        .selectAll()
                        .where {
                            ElectionOptionTable.electionId eq
                                Uuid.parse(
                                    electionId,
                                )
                        }.count()
                } shouldBe
                    2L
            }
        }

        test("concurrent castElectionBallot and closeVoting never leave a ballot after the close") {
            withApp {
                repeat(3) { round ->
                    val f = fixture("race-cast-close-$round")
                    val electionId = client.openElection(f)
                    client.appointBoard(f, electionId)
                    client.post("/test/open-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                    val results =
                        parallel(3) { i ->
                            when (i) {
                                0 ->
                                    client
                                        .post(
                                            "/test/cast-election-ballot/$electionId?answer=YES",
                                        ) { header("X-Member-Id", f.voters[0].toString()) }
                                        .status
                                1 ->
                                    client
                                        .post(
                                            "/test/cast-election-ballot/$electionId?answer=NO",
                                        ) { header("X-Member-Id", f.voters[1].toString()) }
                                        .status
                                else ->
                                    client
                                        .post(
                                            "/test/close-voting/$electionId",
                                        ) { header("X-Member-Id", f.board[0].toString()) }
                                        .status
                            }
                        }
                    val accepted = results.take(2).count { it == HttpStatusCode.OK }
                    transaction {
                        ElectionBallotTable.selectAll().where { ElectionBallotTable.electionId eq Uuid.parse(electionId) }.count()
                    } shouldBe
                        accepted.toLong()
                }
            }
        }

        test("concurrent tally and abortElection end in a consistent state") {
            withApp {
                repeat(3) { round ->
                    val f = fixture("race-tally-abort-$round")
                    val electionId = client.openElection(f)
                    client.driveToClosedWithApprovals(f, electionId)
                    val results =
                        parallel(2) { i ->
                            if (i == 0) {
                                client.post("/test/tally/$electionId") { header("X-Member-Id", f.board[0].toString()) }.status
                            } else {
                                client.post("/test/abort-election/$electionId") { header("X-Member-Id", f.chair.toString()) }.status
                            }
                        }
                    results.count { it == HttpStatusCode.OK } shouldBe 1
                    val finalStatus =
                        transaction {
                            ElectionTable
                                .selectAll()
                                .where {
                                    ElectionTable.id eq
                                        Uuid.parse(
                                            electionId,
                                        )
                                }.single()[ElectionTable.status]
                        }
                    val resolutions = meetingResolutionCount(f.meetingId)
                    if (finalStatus == ElectionStatus.TALLIED) resolutions shouldBe 1L else resolutions shouldBe 0L
                }
            }
        }

        test("concurrent closeVote calls create exactly one resolution") {
            withApp {
                repeat(3) { round ->
                    val f = fixture("race-close-vote-$round")
                    val voteId =
                        client
                            .post("/test/open-vote/${f.motionId}") { header("X-Member-Id", f.chair.toString()) }
                            .bodyAsText()
                            .substringBefore(":")
                    parallel(4) { client.post("/test/close-vote/$voteId") { header("X-Member-Id", f.chair.toString()) }.status }
                    meetingResolutionCount(f.meetingId) shouldBe 1L
                }
            }
        }

        test("the same board member approving the tally concurrently is counted once") {
            withApp {
                val f = fixture("race-approve")
                val electionId = client.openElection(f)
                client.appointBoard(f, electionId)
                client.post("/test/open-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                client.post("/test/close-voting/$electionId") { header("X-Member-Id", f.board[0].toString()) }
                val results =
                    parallel(5) { client.post("/test/release-tally/$electionId") { header("X-Member-Id", f.board[1].toString()) }.status }
                results.count { it == HttpStatusCode.OK } shouldBe 1
                transaction {
                    ElectionTallyApprovalTable.selectAll().where { ElectionTallyApprovalTable.electionId eq Uuid.parse(electionId) }.count()
                } shouldBe 1L
            }
        }
    })

private fun io.ktor.server.plugins.statuspages.StatusPagesConfig.installIntegrityExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
}

private fun Route.registerIntegrityTestRoutes() {
    post("/test/open-election/{motionId}/{electionType}") {
        val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        val q = call.request.queryParameters
        val w =
            service.openElection(
                ElectionOpenInput(
                    motionId = call.parameters["motionId"]!!,
                    electionType = ElectionType.valueOf(call.parameters["electionType"]!!),
                    secret = q["secret"]?.toBoolean() ?: true,
                    seatCount = q["seatCount"]?.toInt() ?: 1,
                    targetCommitteeId = q["targetCommitteeId"],
                    targetRole = q["targetRole"]?.let { CommitteeRole.valueOf(it) },
                    requiredMajorityPercent = q["requiredMajorityPercent"]?.toInt() ?: 50,
                    tallyThreshold = q["tallyThreshold"]?.toInt() ?: 2,
                    requiredMajorityNumerator = q["num"]?.toInt(),
                    requiredMajorityDenominator = q["den"]?.toInt(),
                ),
            )
        call.respondText(
            "${w.id}:${w.status}:${w.requiredMajorityNumerator ?: ""}:${w.requiredMajorityDenominator ?: ""}:${w.requiredMajorityPercent}",
        )
    }
    post("/test/appoint-election-board/{electionId}") {
        val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        val list =
            service.appointElectionBoard(
                electionId = call.parameters["electionId"]!!,
                memberIds = call.request.queryParameters["memberIds"]!!.split(","),
            )
        call.respondText(list.size.toString())
    }
    post("/test/submit-candidacy/{electionId}") {
        val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        call.respondText(
            service.submitCandidacy(electionId = call.parameters["electionId"]!!, input = CandidacyInput(motivationText = "Motivation")).id,
        )
    }
    post("/test/release-kandidatenliste/{electionId}") {
        val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        val w = service.releaseCandidateList(call.parameters["electionId"]!!)
        call.respondText("${w.status}:${w.options.size}")
    }
    post("/test/open-voting/{electionId}") {
        val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        call.respondText(service.openVoting(call.parameters["electionId"]!!).status.name)
    }
    post("/test/cast-election-ballot/{electionId}") {
        val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        val answer = call.request.queryParameters["answer"]?.let { ElectionAnswer.valueOf(it) }
        val result =
            service.castElectionBallot(
                ElectionBallotInput(electionId = call.parameters["electionId"]!!, answer = answer, selectedOptionIds = emptyList()),
            )
        call.respondText("${result.id}:${result.receiptCode ?: ""}")
    }
    post("/test/close-voting/{electionId}") {
        val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        call.respondText(service.closeVoting(call.parameters["electionId"]!!).status.name)
    }
    post("/test/release-tally/{electionId}") {
        val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        service.approveTally(call.parameters["electionId"]!!)
        call.respondText("ok")
    }
    post("/test/tally/{electionId}") {
        val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        val e = service.tally(call.parameters["electionId"]!!)
        call.respondText("${e.winnerOptionIds.joinToString(",")}:${e.tie}:${e.majorityMet ?: ""}")
    }
    post("/test/abort-election/{electionId}") {
        val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        call.respondText(service.abortElection(call.parameters["electionId"]!!).status.name)
    }
    get("/test/list-ballots/{electionId}") {
        val service = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        val list = service.listElectionBallots(call.parameters["electionId"]!!)
        call.respondText(list.joinToString(";") { "${it.id}:${it.selectedOptionLabels.joinToString("|")}" })
    }
    post("/test/open-vote/{motionId}") {
        val service = GovernanceService(call = call)
        val v = service.openVote(VoteOpenInput(motionId = call.parameters["motionId"]!!))
        call.respondText("${v.id}:${v.status}")
    }
    post("/test/close-vote/{voteId}") {
        val service = GovernanceService(call = call)
        val v = service.closeVote(call.parameters["voteId"]!!)
        call.respondText("${v.id}:${v.status}")
    }
    post("/test/abort-vote/{voteId}") {
        val service = GovernanceService(call = call)
        call.respondText(service.abortVote(call.parameters["voteId"]!!).status.name)
    }
    post("/test/resolve-motion/{motionId}/{status}") {
        val service = GovernanceService(call = call)
        val m =
            service.resolveMotion(
                id = call.parameters["motionId"]!!,
                input =
                    MotionResolutionInput(
                        votesYes = 3,
                        votesNo = 0,
                        votesAbstain = 0,
                        status = ResolutionStatus.valueOf(call.parameters["status"]!!),
                    ),
            )
        call.respondText(m.status.name)
    }
    post("/test/withdraw-motion/{motionId}") {
        val service = GovernanceService(call = call)
        call.respondText(service.withdrawMotion(call.parameters["motionId"]!!).status.name)
    }
    post("/test/record-resolution/{meetingId}") {
        val service = GovernanceService(call = call)
        val r =
            service.recordResolution(
                meetingId = call.parameters["meetingId"]!!,
                input =
                    ResolutionInput(
                        agendaItemId = call.request.queryParameters["agendaItemId"],
                        title = "Testresolution",
                        text = "Resolutiontext",
                        votesYes = 3,
                        votesNo = 0,
                        votesAbstain = 0,
                        status = ResolutionStatus.ADOPTED,
                    ),
            )
        call.respondText(r.id)
    }
    post("/test/open-systemic-consensus/{motionId}") {
        val service = SystemicConsensusService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        val sc = service.openSystemicConsensus(SystemicConsensusOpenInput(motionId = call.parameters["motionId"]!!, secret = false))
        call.respondText(sc.id)
    }
    post("/test/evaluate-systemic-consensus/{id}") {
        val service = SystemicConsensusService(call = call, streamGuard = NoOpSecretBallotStreamGuard)
        service.evaluate(call.parameters["id"]!!)
        call.respondText("ok")
    }
}

private fun cleanUpIntegrityTestData(
    committeeIds: List<Uuid>,
    memberIds: List<Uuid>,
) {
    if (committeeIds.isEmpty() && memberIds.isEmpty()) return
    transaction {
        if (memberIds.isNotEmpty()) {
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) { it[actorMemberId] = null }
        }
        val meetingIds =
            if (committeeIds.isEmpty()) {
                emptyList()
            } else {
                MeetingTable.selectAll().where { MeetingTable.committeeId inList committeeIds }.map { it[MeetingTable.id] }
            }
        val motionIds =
            if (committeeIds.isEmpty()) {
                emptyList()
            } else {
                MotionTable.selectAll().where { MotionTable.targetCommitteeId inList committeeIds }.map { it[MotionTable.id] }
            }
        val electionIds =
            if (meetingIds.isEmpty()) {
                emptyList()
            } else {
                ElectionTable
                    .selectAll()
                    .where {
                        ElectionTable.meetingId inList meetingIds
                    }.map { it[ElectionTable.id] }
            }
        val voteIds =
            if (motionIds.isEmpty()) {
                emptyList()
            } else {
                VoteTable
                    .selectAll()
                    .where {
                        VoteTable.motionId inList motionIds
                    }.map { it[VoteTable.id] }
            }
        val scIds =
            if (motionIds.isEmpty()) {
                emptyList()
            } else {
                SystemicConsensusTable
                    .selectAll()
                    .where {
                        SystemicConsensusTable.motionId inList motionIds
                    }.map { it[SystemicConsensusTable.id] }
            }
        if (electionIds.isNotEmpty()) ElectionTable.update({ ElectionTable.id inList electionIds }) { it[resolutionId] = null }
        if (voteIds.isNotEmpty()) VoteTable.update({ VoteTable.id inList voteIds }) { it[resolutionId] = null }
        if (scIds.isNotEmpty()) SystemicConsensusTable.update({ SystemicConsensusTable.id inList scIds }) { it[resolutionId] = null }
        if (motionIds.isNotEmpty()) MotionTable.update({ MotionTable.id inList motionIds }) { it[MotionTable.resolutionId] = null }
        if (meetingIds.isNotEmpty()) {
            ResolutionTable.update({ ResolutionTable.meetingId inList meetingIds }) {
                it[ResolutionTable.electionId] = null
                it[ResolutionTable.voteId] = null
                it[ResolutionTable.systemicConsensusId] = null
            }
        }
        if (electionIds.isNotEmpty()) {
            val ballotIds =
                ElectionBallotTable
                    .selectAll()
                    .where {
                        ElectionBallotTable.electionId inList electionIds
                    }.map { it[ElectionBallotTable.id] }
            if (ballotIds.isNotEmpty()) ElectionBallotSelectionTable.deleteWhere { ElectionBallotSelectionTable.ballotId inList ballotIds }
            ElectionBallotTable.deleteWhere { ElectionBallotTable.electionId inList electionIds }
            ElectionParticipationTable.deleteWhere { ElectionParticipationTable.electionId inList electionIds }
            ElectionTallyApprovalTable.deleteWhere { ElectionTallyApprovalTable.electionId inList electionIds }
            ElectionEligibleVoterTable.deleteWhere { ElectionEligibleVoterTable.electionId inList electionIds }
            ElectionBoardMemberTable.deleteWhere { ElectionBoardMemberTable.electionId inList electionIds }
            ElectionOptionTable.deleteWhere { ElectionOptionTable.electionId inList electionIds }
            ElectionCandidacyTable.deleteWhere { ElectionCandidacyTable.electionId inList electionIds }
            ElectionTable.deleteWhere { ElectionTable.id inList electionIds }
        }
        if (voteIds.isNotEmpty()) {
            VoteOptionTable.deleteWhere { VoteOptionTable.voteId inList voteIds }
            VoteTable.deleteWhere { VoteTable.id inList voteIds }
        }
        if (scIds.isNotEmpty()) {
            SystemicConsensusBallotTable.deleteWhere { SystemicConsensusBallotTable.systemicConsensusId inList scIds }
            SystemicConsensusOptionTable.deleteWhere { SystemicConsensusOptionTable.systemicConsensusId inList scIds }
            SystemicConsensusTable.deleteWhere { SystemicConsensusTable.id inList scIds }
        }
        if (motionIds.isNotEmpty() || memberIds.isNotEmpty()) {
            val motionCondition =
                when {
                    motionIds.isNotEmpty() && memberIds.isNotEmpty() ->
                        (MotionTable.id inList motionIds) or (MotionTable.submitterMemberId inList memberIds)
                    motionIds.isNotEmpty() -> MotionTable.id inList motionIds
                    else -> MotionTable.submitterMemberId inList memberIds
                }
            MotionTable.deleteWhere { motionCondition }
        }
        if (meetingIds.isNotEmpty()) {
            ResolutionTable.deleteWhere { ResolutionTable.meetingId inList meetingIds }
            AgendaItemTable.deleteWhere { AgendaItemTable.meetingId inList meetingIds }
            MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
        }
        if (committeeIds.isNotEmpty()) {
            CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
            CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
        }
        if (memberIds.isNotEmpty()) {
            TransparenzregisterReminderTable.update({ TransparenzregisterReminderTable.resolvedBy inList memberIds }) {
                it[resolvedBy] =
                    null
            }
            TransparenzregisterReminderTable.deleteWhere { TransparenzregisterReminderTable.memberId inList memberIds }
            BoardMembershipTable.deleteWhere { BoardMembershipTable.memberId inList memberIds }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
