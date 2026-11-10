package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.domain.VotingRightSource
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.9.298 -- a member WITHOUT voting right is a member (invited, in the committee) but not in the electorate: not frozen into a vote, not
 * in the quorum's N, no ballot. A voting right withdrawn after the freeze keeps the member in the snapshot and the denominator but the live
 * floor refuses the ballot; a voting right granted after the freeze gives no vote (not in the snapshot).
 */
class VotingRightBallotTest :
    FunSpec({
        val fx = CommitteeSeatFixture()
        val rc = RuleCommitteeFixture(fx)
        val json = Json { ignoreUnknownKeys = false }
        lateinit var board: Uuid

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            board = fx.member(role = AccountRole.BOARD, displayName = "Stimmrecht Vorstandsperson")
        }
        afterSpec {
            rc.cleanUp()
            fx.cleanUp()
        }

        fun Application.installRoutes() {
            install(StatusPages) {
                exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
                exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
                exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            }
            routing {
                post("/t/open/{motionId}") {
                    val dto = GovernanceService(call = call).openVote(VoteOpenInput(motionId = call.parameters["motionId"]!!))
                    call.respondText(json.encodeToString(VoteDto.serializer(), dto))
                }
                post("/t/cast/{voteId}/{optionId}") {
                    GovernanceService(call = call).castVoteBallot(
                        VoteBallotInput(
                            voteId = call.parameters["voteId"]!!,
                            optionId = call.parameters["optionId"]!!,
                            stakeLtr = BigDecimal("1.00"),
                        ),
                    )
                    call.respondText("ok")
                }
                get("/t/quorum/{meetingId}") {
                    val q = GovernanceService(call = call).checkQuorum(call.parameters["meetingId"]!!)
                    call.respondText(json.encodeToString(QuorumResultDto.serializer(), q))
                }
                get("/t/eligible/{committeeId}") {
                    call.respondText(GovernanceService(call = call).countCommitteeEligible(call.parameters["committeeId"]!!).toString())
                }
            }
        }

        suspend fun HttpClient.asMember(
            member: Uuid,
            path: String,
            post: Boolean = false,
        ): HttpResponse =
            if (post) {
                post(path) { header("X-Member-Id", member.toString()) }
            } else {
                get(path) { header("X-Member-Id", member.toString()) }
            }

        fun voteOf(body: String): VoteDto = json.decodeFromString(VoteDto.serializer(), body)

        fun quorumOf(body: String): QuorumResultDto = json.decodeFromString(QuorumResultDto.serializer(), body)

        fun setVotingRight(
            member: Uuid,
            value: Boolean,
        ) {
            transaction {
                MemberTable.update({ MemberTable.id eq member }) {
                    it[votingRight] = value
                    it[votingRightSource] = if (value) VotingRightSource.DEFAULT else VotingRightSource.MANUAL
                    it[votingRightChangedAt] = if (value) null else DbClock.nowLocalDateTime()
                }
            }
        }

        fun seatedGroup(count: Int): Pair<Uuid, List<Uuid>> {
            val manual = fx.committee()
            val people = (1..count).map { fx.member(displayName = "Stimmrecht Mitglied").also { m -> rc.mintLtr(member = m) } }
            people.forEach { fx.seat(committee = manual, member = it) }
            return manual to people
        }

        suspend fun HttpClient.open(
            committee: Uuid,
            meeting: Uuid,
        ): VoteDto {
            val motion = rc.motion(committee = committee, meeting = meeting, submitter = board)
            val response = asMember(board, "/t/open/$motion", post = true)
            response.status shouldBe HttpStatusCode.OK
            return voteOf(response.bodyAsText())
        }

        suspend fun HttpClient.cast(
            who: Uuid,
            vote: VoteDto,
        ): HttpStatusCode = asMember(who, "/t/cast/${vote.id}/${vote.options.first().id}", post = true).status

        test("manual committee: a member without voting right is not frozen into the vote, not in N, and cannot cast a ballot") {
            val (manual, people) = seatedGroup(3)
            setVotingRight(people[2], false)
            val meeting = rc.meeting(committee = manual)
            testApplication {
                application { installRoutes() }
                val vote = client.open(manual, meeting)
                rc.frozenElectorate(Uuid.parse(vote.id)) shouldContainExactlyInAnyOrder listOf(people[0], people[1])
                quorumOf(client.asMember(board, "/t/quorum/$meeting").bodyAsText()).eligibleMemberCount shouldBe 2
                client.cast(people[2], vote) shouldBe HttpStatusCode.Forbidden
                client.cast(people[0], vote) shouldBe HttpStatusCode.OK
            }
        }

        test("rule committee: same; the displayed 'Beschlussfähig ab' count is the electorate, the membership stays three") {
            val (base, people) = seatedGroup(3)
            setVotingRight(people[0], false)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val meeting = rc.meeting(committee = ruleBased)
            testApplication {
                application { installRoutes() }
                client.asMember(board, "/t/eligible/$ruleBased").bodyAsText() shouldBe "2"
                val vote = client.open(ruleBased, meeting)
                rc.frozenElectorate(Uuid.parse(vote.id)) shouldContainExactlyInAnyOrder listOf(people[1], people[2])
                client.cast(people[0], vote) shouldBe HttpStatusCode.Forbidden
                client.cast(people[1], vote) shouldBe HttpStatusCode.OK
            }
        }

        test("withdrawn after the freeze: still in the snapshot and in N, but the live floor refuses the ballot") {
            val (manual, people) = seatedGroup(2)
            val meeting = rc.meeting(committee = manual)
            testApplication {
                application { installRoutes() }
                val vote = client.open(manual, meeting)
                setVotingRight(people[0], false)
                rc.frozenElectorate(Uuid.parse(vote.id)) shouldContainExactlyInAnyOrder people
                quorumOf(client.asMember(board, "/t/quorum/$meeting").bodyAsText()).eligibleMemberCount shouldBe 2
                client.cast(people[0], vote) shouldBe HttpStatusCode.Forbidden
                client.cast(people[1], vote) shouldBe HttpStatusCode.OK
            }
        }

        test("granted after the freeze: no vote, the member is not in the snapshot") {
            val (manual, people) = seatedGroup(2)
            setVotingRight(people[1], false)
            val meeting = rc.meeting(committee = manual)
            testApplication {
                application { installRoutes() }
                val vote = client.open(manual, meeting)
                setVotingRight(people[1], true)
                rc.frozenElectorate(Uuid.parse(vote.id)) shouldContainExactlyInAnyOrder listOf(people[0])
                quorumOf(client.asMember(board, "/t/quorum/$meeting").bodyAsText()).eligibleMemberCount shouldBe 1
                client.cast(people[1], vote) shouldBe HttpStatusCode.Forbidden
            }
        }

        test(
            "own participation flags of an election and a consensus: a withdrawn voting right makes the member ineligible whatever the snapshot says",
        ) {
            val (manual, people) = seatedGroup(1)
            val meeting = rc.meeting(committee = manual)
            val motion = rc.motion(committee = manual, meeting = meeting, submitter = board)
            val election = rc.electionRow(meeting = meeting, motion = motion, opener = board, target = null, status = ElectionStatus.OPEN)
            val consensus =
                rc.consensusRow(
                    meeting = meeting,
                    motion = rc.motion(committee = manual, meeting = meeting, submitter = board),
                    opener = board,
                    status = SystemicConsensusStatus.RATING,
                )
            transaction {
                ElectionTable.update({ ElectionTable.id eq election }) { it[votingOpenedAt] = DbClock.nowLocalDateTime() }
                SystemicConsensusTable.update(
                    { SystemicConsensusTable.id eq consensus },
                ) { it[ratingOpenedAt] = DbClock.nowLocalDateTime() }
                ElectionEligibleVoterTable.insert {
                    it[id] = Uuid.random()
                    it[electionId] = election
                    it[memberId] = people[0]
                }
                SystemicConsensusEligibleVoterTable.insert {
                    it[id] = Uuid.random()
                    it[systemicConsensusId] = consensus
                    it[memberId] = people[0]
                    it[round] = 1
                }
            }

            fun flags(): Pair<Boolean?, Boolean?> =
                transaction {
                    val e = ElectionTable.selectAll().where { ElectionTable.id eq election }.toList()
                    val c = SystemicConsensusTable.selectAll().where { SystemicConsensusTable.id eq consensus }.toList()
                    ElectionOwnParticipation.load(electionRows = e, memberId = people[0]).getValue(election).eligible to
                        SystemicConsensusOwnParticipation.load(rows = c, memberId = people[0]).getValue(consensus).eligible
                }
            flags() shouldBe (true to true)
            setVotingRight(people[0], false)
            flags() shouldBe (false to false)
        }
    })
