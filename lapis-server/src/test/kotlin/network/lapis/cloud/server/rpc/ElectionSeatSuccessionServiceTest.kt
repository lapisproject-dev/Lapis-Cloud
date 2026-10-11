package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.conference.NoOpSecretBallotStreamGuard
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeAutoEndNoticeDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeSeatChangeKind
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionDto
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionResultDto
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.IncumbentPolicy
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SeatOutcome
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import kotlin.uuid.Uuid

/**
 * Welle V1.9.103 -- the RPC surface of the succession: the inputs `openElection` accepts and refuses, who sees which seat outcomes in
 * `getElectionResult`, and the board notice band. Runs on H2; the succession itself runs on both databases in
 * [ElectionSeatSuccessionScenarios].
 */
class ElectionSeatSuccessionServiceTest :
    FunSpec({
        val fx = CommitteeSeatFixture()
        val es = ElectionSuccessionFixture(fx)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            es.cleanUp()
            fx.cleanUp()
        }

        fun routes(): Application.() -> Unit =
            {
                install(StatusPages) {
                    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                }
                routing {
                    post("/t/open") {
                        val input = Json.decodeFromString(ElectionOpenInput.serializer(), call.receiveText())
                        val dto = ElectionService(call = call, streamGuard = NoOpSecretBallotStreamGuard).openElection(input)
                        call.respondText(Json.encodeToString(ElectionDto.serializer(), dto))
                    }
                    get("/t/result/{id}") {
                        val dto =
                            ElectionService(
                                call = call,
                                streamGuard = NoOpSecretBallotStreamGuard,
                            ).getElectionResult(call.parameters["id"]!!)
                        call.respondText(Json.encodeToString(ElectionResultDto.serializer(), dto))
                    }
                    get("/t/notice") {
                        call.respondText(
                            Json.encodeToString(
                                CommitteeAutoEndNoticeDto.serializer(),
                                GovernanceService(call = call).getCommitteeAutoEndNotice(),
                            ),
                        )
                    }
                }
            }

        suspend fun HttpClient.open(
            motionId: Uuid,
            type: ElectionType,
            target: Uuid?,
            role: CommitteeRole?,
            policy: IncumbentPolicy?,
            seatCount: Int = 1,
            asMember: Uuid,
        ) = post("/t/open") {
            header("X-Member-Id", asMember.toString())
            setBody(
                Json.encodeToString(
                    ElectionOpenInput.serializer(),
                    ElectionOpenInput(
                        motionId = motionId.toString(),
                        electionType = type,
                        seatCount = seatCount,
                        targetCommitteeId = target?.toString(),
                        targetRole = role,
                        incumbentPolicy = policy,
                    ),
                ),
            )
        }

        test("openElection: a personnel election needs a role and a policy, a YES_NO election must have neither") {
            testApplication {
                application(routes())
                val group = fx.committee(type = CommitteeType.WORKING_GROUP)
                val admin = es.admin

                suspend fun status(
                    type: ElectionType,
                    role: CommitteeRole?,
                    policy: IncumbentPolicy?,
                    target: Uuid? = group,
                ): HttpStatusCode {
                    val (motionId, _) = es.motion(group)
                    val response = client.open(motionId, type, target, role, policy, asMember = admin)
                    if (response.status == HttpStatusCode.OK) {
                        es.track(Json.decodeFromString(ElectionDto.serializer(), response.bodyAsText()).id.let(Uuid::parse))
                    }
                    return response.status
                }
                status(ElectionType.SINGLE_CHOICE, role = null, policy = IncumbentPolicy.REPLACE) shouldBe HttpStatusCode.Conflict
                status(ElectionType.SINGLE_CHOICE, role = CommitteeRole.CHAIR, policy = null) shouldBe HttpStatusCode.Conflict
                status(ElectionType.YES_NO, role = null, policy = IncumbentPolicy.KEEP, target = null) shouldBe HttpStatusCode.Conflict
                status(ElectionType.YES_NO, role = CommitteeRole.MEMBER, policy = null, target = null) shouldBe HttpStatusCode.Conflict
                status(ElectionType.YES_NO, role = null, policy = null, target = null) shouldBe HttpStatusCode.OK
                status(ElectionType.SINGLE_CHOICE, role = CommitteeRole.CHAIR, policy = IncumbentPolicy.REPLACE) shouldBe HttpStatusCode.OK
            }
        }

        test(
            "openElection: a role with exactly one holder needs REPLACE and one seat; the general assembly has no MEMBER seats to replace",
        ) {
            testApplication {
                application(routes())
                val group = fx.committee(type = CommitteeType.WORKING_GROUP)
                val assembly = fx.committee(type = CommitteeType.GENERAL_ASSEMBLY)

                suspend fun status(
                    committee: Uuid,
                    role: CommitteeRole,
                    policy: IncumbentPolicy,
                    type: ElectionType = ElectionType.SINGLE_CHOICE,
                    seatCount: Int = 1,
                ): HttpStatusCode {
                    val (motionId, _) = es.motion(committee)
                    val response = client.open(motionId, type, committee, role, policy, seatCount, asMember = es.admin)
                    if (response.status == HttpStatusCode.OK) {
                        es.track(Json.decodeFromString(ElectionDto.serializer(), response.bodyAsText()).id.let(Uuid::parse))
                    }
                    return response.status
                }
                status(group, CommitteeRole.CHAIR, IncumbentPolicy.KEEP) shouldBe HttpStatusCode.Conflict
                status(group, CommitteeRole.CHAIR, IncumbentPolicy.REPLACE, ElectionType.MULTI_CHOICE, seatCount = 2) shouldBe
                    HttpStatusCode.Conflict
                status(group, CommitteeRole.ASSESSOR, IncumbentPolicy.KEEP, ElectionType.MULTI_CHOICE, seatCount = 3) shouldBe
                    HttpStatusCode.OK
                status(assembly, CommitteeRole.MEMBER, IncumbentPolicy.REPLACE) shouldBe HttpStatusCode.Conflict
                status(assembly, CommitteeRole.CHAIR, IncumbentPolicy.REPLACE) shouldBe HttpStatusCode.OK
            }
        }

        test("openElection: only the motion's own Gremium may be seated unless the motion belongs to the general assembly") {
            testApplication {
                application(routes())
                val group = fx.committee(type = CommitteeType.WORKING_GROUP)
                val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD)
                val assembly = fx.committee(type = CommitteeType.GENERAL_ASSEMBLY)

                suspend fun status(motionCommittee: Uuid): HttpStatusCode {
                    val (motionId, _) = es.motion(motionCommittee)
                    val response =
                        client.open(
                            motionId,
                            ElectionType.SINGLE_CHOICE,
                            board,
                            CommitteeRole.CHAIR,
                            IncumbentPolicy.REPLACE,
                            asMember = es.admin,
                        )
                    if (response.status == HttpStatusCode.OK) {
                        es.track(Json.decodeFromString(ElectionDto.serializer(), response.bodyAsText()).id.let(Uuid::parse))
                    }
                    return response.status
                }
                status(group) shouldBe HttpStatusCode.Conflict
                status(board) shouldBe HttpStatusCode.OK
                status(assembly) shouldBe HttpStatusCode.OK
            }
        }

        test("the opened election carries its policy in the DTO") {
            testApplication {
                application(routes())
                val group = fx.committee(type = CommitteeType.WORKING_GROUP)
                val (motionId, _) = es.motion(group)
                val response =
                    client.open(
                        motionId,
                        ElectionType.MULTI_CHOICE,
                        group,
                        CommitteeRole.ASSESSOR,
                        IncumbentPolicy.KEEP,
                        2,
                        asMember = es.admin,
                    )
                response.status shouldBe HttpStatusCode.OK
                val dto = Json.decodeFromString(ElectionDto.serializer(), response.bodyAsText())
                es.track(Uuid.parse(dto.id))
                dto.incumbentPolicy shouldBe IncumbentPolicy.KEEP
                dto.targetRole shouldBe CommitteeRole.ASSESSOR
            }
        }

        test("getElectionResult: administrators see every outcome, an ordinary member never sees skipped winners or kept predecessors") {
            testApplication {
                application(routes())
                val group = fx.committee(type = CommitteeType.WORKING_GROUP)
                val chair = fx.member(displayName = "Gremienvorsitz")
                fx.seat(committee = group, member = chair, role = CommitteeRole.CHAIR)
                val old = fx.member(displayName = "Bisheriger Beisitzer")
                fx.seat(committee = group, member = old, role = CommitteeRole.ASSESSOR, since = SUCCESSION_OLD_SINCE)
                val good = fx.member(displayName = "Gewaehlte Person")
                val gone = fx.member(status = MemberStatus.WITHDRAWN, displayName = "Ausgeschiedene Person")
                val election =
                    es.seed(
                        committee = group,
                        role = CommitteeRole.ASSESSOR,
                        policy = IncumbentPolicy.REPLACE,
                        candidates = listOf(good to 4, gone to 3, fx.member() to 1),
                        type = ElectionType.MULTI_CHOICE,
                        seatCount = 2,
                    )
                es.tally(election)
                val plain = fx.member(role = AccountRole.MEMBER)

                suspend fun read(asMember: Uuid): ElectionResultDto {
                    val response = client.get("/t/result/${election.electionId}") { header("X-Member-Id", asMember.toString()) }
                    response.status shouldBe HttpStatusCode.OK
                    return Json.decodeFromString(ElectionResultDto.serializer(), response.bodyAsText())
                }

                val full = read(es.admin)
                full.seatOutcomes.map { it.outcome }.toSet() shouldBe
                    setOf(SeatOutcome.SEATED, SeatOutcome.SKIPPED, SeatOutcome.PREDECESSOR_KEPT)
                full.pendingAdminReview shouldBe false
                // a committee chair manages elections of the Gremium: also the full view
                read(chair).seatOutcomes.map { it.outcome }.toSet() shouldBe full.seatOutcomes.map { it.outcome }.toSet()

                val limited = read(plain)
                limited.incumbentPolicy shouldBe IncumbentPolicy.REPLACE
                limited.pendingAdminReview shouldBe true
                limited.seatOutcomes.map { it.outcome }.toSet() shouldBe setOf(SeatOutcome.SEATED)
                val raw = client.get("/t/result/${election.electionId}") { header("X-Member-Id", plain.toString()) }.bodyAsText()
                raw shouldNotContain gone.toString()
                raw shouldNotContain old.toString()
                raw shouldNotContain "MEMBER_NOT_ACTIVE"
                raw shouldNotContain "WINNER_SKIPPED"
                raw shouldNotContain "Ausgeschiedene Person"
                raw shouldContain good.toString()
            }
        }

        test("getElectionResult for a legacy election (no policy) carries no succession data") {
            testApplication {
                application(routes())
                val group = fx.committee()
                val winner = fx.member()
                val election =
                    es.seed(
                        committee = group,
                        role = CommitteeRole.MEMBER,
                        policy = null,
                        candidates =
                            listOf(
                                winner to 3,
                                fx.member() to 1,
                            ),
                    )
                es.tally(election)
                val response = client.get("/t/result/${election.electionId}") { header("X-Member-Id", fx.member().toString()) }
                val dto = Json.decodeFromString(ElectionResultDto.serializer(), response.bodyAsText())
                dto.incumbentPolicy shouldBe null
                dto.seatOutcomes.shouldBeEmpty()
                dto.pendingAdminReview shouldBe false
            }
        }

        test("notice band: seats started and ended by an election are listed, a role change is not reported as an ended seat") {
            testApplication {
                application(routes())
                val board = fx.committee(type = CommitteeType.EXECUTIVE_BOARD, name = "Vorstand Hinweisband")
                val leaving = fx.member(displayName = "Scheidender Beisitzer")
                fx.seat(committee = board, member = leaving, role = CommitteeRole.ASSESSOR, since = SUCCESSION_OLD_SINCE)
                val promoted = fx.member(displayName = "Befoerderte Person")
                fx.seat(committee = board, member = promoted, role = CommitteeRole.ASSESSOR, since = SUCCESSION_OLD_SINCE)
                val fresh = fx.member(displayName = "Neue Person")
                val election =
                    es.seed(
                        committee = board,
                        role = CommitteeRole.ASSESSOR,
                        policy = IncumbentPolicy.REPLACE,
                        candidates = listOf(promoted to 4, fresh to 3, fx.member() to 1),
                        type = ElectionType.MULTI_CHOICE,
                        seatCount = 2,
                    )
                es.tally(election)
                val boardAccount = fx.member(role = AccountRole.BOARD)

                val notice =
                    Json.decodeFromString(
                        CommitteeAutoEndNoticeDto.serializer(),
                        client.get("/t/notice") { header("X-Member-Id", boardAccount.toString()) }.bodyAsText(),
                    )
                val mine = notice.electionSeatChanges.filter { it.electionId == election.electionId.toString() }
                mine.filter { it.kind == CommitteeSeatChangeKind.ELECTION_SEATED }.map { it.memberId }.toSet() shouldBe
                    setOf(fresh.toString())
                mine.filter { it.kind == CommitteeSeatChangeKind.ELECTION_ENDED }.map { it.memberId }.toSet() shouldBe
                    setOf(leaving.toString())
                mine.forEach { it.committeeName shouldBe "Vorstand Hinweisband" }
                val ordinary = client.get("/t/notice") { header("X-Member-Id", fx.member().toString()) }
                ordinary.status shouldBe HttpStatusCode.Forbidden
            }
        }
    })
