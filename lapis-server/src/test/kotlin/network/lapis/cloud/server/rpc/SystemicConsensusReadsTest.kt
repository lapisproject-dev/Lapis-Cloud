package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.conference.NoOpSecretBallotStreamGuard
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.domain.SystemicConsensusOptionInput
import network.lapis.cloud.shared.domain.SystemicConsensusParticipationDto
import network.lapis.cloud.shared.domain.SystemicConsensusReceiptVerificationDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V1.9.28 -- the four additive read methods of [SystemicConsensusService] (`getSystemicConsensusResult`,
 * `getSystemicConsensusParticipation`, `listSystemicConsensusParticipations`, `verifySystemicConsensusReceipt`)
 * plus the `addOption` text validation. Same "throwaway routes calling the service class directly" house style as
 * [SystemicConsensusServiceTest]; the existing spec stays untouched and is the regression proof that extracting
 * the outcome computation out of `evaluate` changed nothing.
 */
class SystemicConsensusReadsTest :
    FunSpec({
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()
        val json = Json

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpSystemicConsensusTestData(committeeIds = createdCommitteeIds, memberIds = createdMemberIds) }

        fun member(
            email: String,
            status: MemberStatus = MemberStatus.ACTIVE,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "SK Reads Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun committee(name: String): Uuid {
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

        fun seat(
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

        fun scheduledMotion(
            committeeId: Uuid,
            submitterId: Uuid,
            day: Int,
        ): Uuid {
            val meetingId = Uuid.random()
            val at = LocalDateTime(2026, 4, day, 18, 0)
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = meetingId
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "Testmeeting"
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
            val id = Uuid.random()
            transaction {
                MotionTable.insert {
                    it[MotionTable.id] = id
                    it[targetCommitteeId] = committeeId
                    it[title] = "Testmotion"
                    it[rationale] = "Rationale"
                    it[text] = "Motionstext"
                    it[submitterMemberId] = submitterId
                    it[status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = submitterId
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
            }
            return id
        }

        fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerReadsTestRoutes() }
                }
                block()
            }

        suspend fun HttpClient.send(
            method: String,
            path: String,
            asMember: Uuid,
        ): HttpResponse =
            if (method == "GET") {
                get(path) { header("X-Member-Id", asMember.toString()) }
            } else {
                post(path) { header("X-Member-Id", asMember.toString()) }
            }

        suspend fun HttpClient.openConsensus(
            motionId: Uuid,
            asMember: Uuid,
            query: String = "",
        ): String = send("POST", "/t/open/$motionId$query", asMember).bodyAsText()

        suspend fun HttpClient.participation(
            id: String,
            asMember: Uuid,
        ): SystemicConsensusParticipationDto = json.decodeFromString(send("GET", "/t/part/$id", asMember).bodyAsText())

        fun statusQuoId(consensusId: String): String =
            transaction {
                SystemicConsensusOptionTable
                    .selectAll()
                    .where {
                        (SystemicConsensusOptionTable.systemicConsensusId eq Uuid.parse(consensusId)) and
                            (SystemicConsensusOptionTable.isStatusQuoOption eq true)
                    }.single()[SystemicConsensusOptionTable.id]
            }.toString()

        /** chair + three voters, one frozen consensus with the status quo option and options A and B. */
        class World(
            val chair: Uuid,
            val voters: List<Uuid>,
            val committeeId: Uuid,
            val motionId: Uuid,
            val consensusId: String,
            val statusQuo: String,
            val optionA: String,
            val optionB: String,
        )

        suspend fun ApplicationTestBuilder.buildWorld(
            tag: String,
            day: Int,
            secret: Boolean,
            binding: Boolean = false,
            freeze: Boolean = true,
        ): World {
            val committeeId = committee("SK Reads $tag")
            val chair = member("sk-reads-$tag-chair@example.org")
            seat(committeeId, chair, CommitteeRole.CHAIR)
            val voters = (1..3).map { member("sk-reads-$tag-v$it@example.org").also { v -> seat(committeeId, v, CommitteeRole.MEMBER) } }
            val motionId = scheduledMotion(committeeId, chair, day)
            val query = "?secret=$secret&bindingness=${if (binding) "BINDING" else "ADVISORY"}"
            val consensusId = client.openConsensus(motionId, chair, query)
            val a = client.send("POST", "/t/add-option/$consensusId?label=Option+A", voters[0]).bodyAsText()
            val b = client.send("POST", "/t/add-option/$consensusId?label=Option+B", voters[1]).bodyAsText()
            if (freeze) client.send("POST", "/t/freeze/$consensusId", chair)
            return World(chair, voters, committeeId, motionId, consensusId, statusQuoId(consensusId), a, b)
        }

        suspend fun ApplicationTestBuilder.cast(
            w: World,
            asMember: Uuid,
            passive: Int,
            a: Int,
            b: Int,
        ): String =
            client
                .send(
                    "POST",
                    "/t/cast/${w.consensusId}?r=${w.statusQuo}:$passive,${w.optionA}:$a,${w.optionB}:$b",
                    asMember,
                ).bodyAsText()

        test("getSystemicConsensusResult is a conflict before EVALUATED and equals evaluate afterwards") {
            withApp {
                val w = buildWorld(tag = "result", day = 1, secret = true)
                client.send("GET", "/t/result/${w.consensusId}", w.voters[0]).status shouldBe HttpStatusCode.Conflict
                cast(w, w.voters[0], 8, 2, 5)
                cast(w, w.voters[1], 9, 1, 6)
                cast(w, w.voters[2], 7, 3, 4)
                client.send("GET", "/t/result/${w.consensusId}", w.voters[0]).status shouldBe HttpStatusCode.Conflict
                client.send("POST", "/t/close/${w.consensusId}", w.chair)
                client.send("GET", "/t/result/${w.consensusId}", w.voters[0]).status shouldBe HttpStatusCode.Conflict
                val evaluated = client.send("POST", "/t/evaluate/${w.consensusId}", w.chair).bodyAsText()
                val read = client.send("GET", "/t/result/${w.consensusId}", w.voters[2]).bodyAsText()
                read shouldBe evaluated
                val result = json.decodeFromString<SystemicConsensusResultDto>(read)
                result.winnerOptionId shouldBe w.optionA
                result.noRatings shouldBe false
                // reopening hides the result again (it belongs to the finished round)
                client.send("POST", "/t/reopen/${w.consensusId}", w.chair).status shouldBe HttpStatusCode.OK
                client.send("GET", "/t/result/${w.consensusId}", w.voters[0]).status shouldBe HttpStatusCode.Conflict
            }
        }

        test("BINDING with the status quo option winning reads the same result evaluate recorded") {
            withApp {
                val w = buildWorld(tag = "binding", day = 2, secret = false, binding = true)
                cast(w, w.voters[0], 0, 9, 9)
                cast(w, w.voters[1], 1, 8, 9)
                cast(w, w.voters[2], 0, 9, 10)
                client.send("POST", "/t/close/${w.consensusId}", w.chair)
                val evaluated = client.send("POST", "/t/evaluate/${w.consensusId}", w.chair).bodyAsText()
                client.send("GET", "/t/result/${w.consensusId}", w.voters[0]).bodyAsText() shouldBe evaluated
                json.decodeFromString<SystemicConsensusResultDto>(evaluated).winnerOptionId shouldBe w.statusQuo
            }
        }

        test("participation: COLLECTION has no snapshot, only committee members or privileged can propose options") {
            withApp {
                val w = buildWorld(tag = "coll", day = 3, secret = true, freeze = false)
                val outsider = member("sk-reads-coll-outsider@example.org")
                val voterView = client.participation(w.consensusId, w.voters[0])
                voterView.eligible shouldBe null
                voterView.canProposeOptions shouldBe true
                voterView.canRate shouldBe false
                voterView.canManage shouldBe false
                voterView.eligibleCount shouldBe null
                client.participation(w.consensusId, w.chair).canManage shouldBe true
                val outsiderView = client.participation(w.consensusId, outsider)
                outsiderView.canProposeOptions shouldBe false
                outsiderView.canManage shouldBe false
            }
        }

        test("participation: RATING eligibility, hasRated per round for secret and open, other members' ratings do not matter") {
            for (secret in listOf(true, false)) {
                withApp {
                    val w = buildWorld(tag = "rating-$secret", day = if (secret) 4 else 5, secret = secret)
                    val outsider = member("sk-reads-rating-$secret-outsider@example.org")
                    val before = client.participation(w.consensusId, w.voters[0])
                    before.eligible shouldBe true
                    before.hasRated shouldBe false
                    before.canRate shouldBe true
                    before.eligibleCount shouldBe 4
                    before.ballotCount shouldBe 0
                    client.participation(w.consensusId, outsider).eligible shouldBe false
                    client.participation(w.consensusId, outsider).canRate shouldBe false

                    cast(w, w.voters[0], 8, 2, 5)
                    val after = client.participation(w.consensusId, w.voters[0])
                    after.hasRated shouldBe true
                    after.canRate shouldBe false
                    after.ballotCount shouldBe 1
                    // somebody else's ballot does not change my own status
                    client.participation(w.consensusId, w.voters[1]).hasRated shouldBe false

                    // round 2: a fresh snapshot, my round-1 rating no longer counts
                    client.send("POST", "/t/close/${w.consensusId}", w.chair)
                    client.send("POST", "/t/reopen/${w.consensusId}", w.chair)
                    val round2 = client.participation(w.consensusId, w.voters[0])
                    round2.round shouldBe 2
                    round2.hasRated shouldBe false
                    round2.canRate shouldBe true
                    round2.ballotCount shouldBe 0
                }
            }
        }

        test("participation: a member who is no longer ACTIVE cannot rate") {
            withApp {
                val w = buildWorld(tag = "dormant", day = 6, secret = true)
                transaction { MemberTable.update({ MemberTable.id eq w.voters[2] }) { it[status] = MemberStatus.FRIEND } }
                val view = client.participation(w.consensusId, w.voters[2])
                view.eligible shouldBe true
                view.canRate shouldBe false
            }
        }

        test("listSystemicConsensusParticipations: batch of mixed rounds, limit of 100, unknown id") {
            withApp {
                val w1 = buildWorld(tag = "batch1", day = 7, secret = true)
                val w2 = buildWorld(tag = "batch2", day = 8, secret = false, freeze = false)
                cast(w1, w1.voters[0], 8, 2, 5)
                client.send("POST", "/t/close/${w1.consensusId}", w1.chair)
                client.send("POST", "/t/reopen/${w1.consensusId}", w1.chair)
                val body =
                    client
                        .send(
                            "GET",
                            "/t/parts?ids=${w1.consensusId},${w2.consensusId},${w1.consensusId}",
                            w1.voters[0],
                        ).bodyAsText()
                val list = json.decodeFromString<List<SystemicConsensusParticipationDto>>(body)
                list shouldHaveSize 2
                list[0].round shouldBe 2
                list[0].hasRated shouldBe false
                list[1].round shouldBe 1
                list[1].eligible shouldBe null

                val tooMany = (1..101).joinToString(",") { Uuid.random().toString() }
                client.send("GET", "/t/parts?ids=$tooMany", w1.voters[0]).status shouldBe HttpStatusCode.Conflict
                client.send("GET", "/t/parts?ids=${Uuid.random()}", w1.voters[0]).status shouldBe HttpStatusCode.NotFound
                client.send("GET", "/t/parts?ids=not-a-uuid", w1.voters[0]).status shouldBe HttpStatusCode.NotFound
            }
        }

        test("verifySystemicConsensusReceipt: open consensus is a conflict, bad formats and unknown codes are not found") {
            withApp {
                val open = buildWorld(tag = "verify-open", day = 9, secret = false)
                client.send("GET", "/t/verify/${open.consensusId}?code=${"a".repeat(27)}", open.voters[0]).status shouldBe
                    HttpStatusCode.Conflict
                val w = buildWorld(tag = "verify", day = 10, secret = true)
                val notFound = client.send("GET", "/t/verify/${w.consensusId}?code=${"a".repeat(27)}", w.voters[0]).bodyAsText()
                json.decodeFromString<SystemicConsensusReceiptVerificationDto>(notFound).found shouldBe false
                val badFormat = client.send("GET", "/t/verify/${w.consensusId}?code=short", w.voters[0]).bodyAsText()
                json.decodeFromString<SystemicConsensusReceiptVerificationDto>(badFormat).found shouldBe false
            }
        }

        test("verifySystemicConsensusReceipt: no values before EVALUATED, values after, and not for a receipt of an older round") {
            withApp {
                val w = buildWorld(tag = "verify2", day = 11, secret = true)
                val receipt1 = cast(w, w.voters[0], 8, 2, 5).substringAfter(":")
                cast(w, w.voters[1], 9, 1, 6)
                cast(w, w.voters[2], 7, 3, 4)

                suspend fun verify(code: String) =
                    json.decodeFromString<SystemicConsensusReceiptVerificationDto>(
                        client.send("GET", "/t/verify/${w.consensusId}?code=$code", w.voters[0]).bodyAsText(),
                    )
                val rating = verify(receipt1)
                rating.found shouldBe true
                rating.round shouldBe 1
                rating.countedInCurrentResult shouldBe false
                rating.resistances shouldBe null

                client.send("POST", "/t/close/${w.consensusId}", w.chair)
                verify(receipt1).resistances shouldBe null
                client.send("POST", "/t/evaluate/${w.consensusId}", w.chair)
                val evaluated = verify(receipt1)
                evaluated.countedInCurrentResult shouldBe true
                evaluated.resistances!!.associate { it.optionId to it.resistance } shouldBe
                    mapOf(w.statusQuo to 8, w.optionA to 2, w.optionB to 5)
                evaluated.resistances!!.first { it.optionId == w.statusQuo }.isStatusQuoOption shouldBe true

                client.send("POST", "/t/reopen/${w.consensusId}", w.chair)
                val old = verify(receipt1)
                old.found shouldBe true
                old.round shouldBe 1
                old.countedInCurrentResult shouldBe false
                old.resistances shouldBe null
            }
        }

        test("addOption: blank, whitespace-only and 201 character texts are a conflict, 200 characters are accepted and trimmed") {
            withApp {
                val w = buildWorld(tag = "label", day = 12, secret = true, freeze = false)

                suspend fun add(label: String) =
                    client.send("POST", "/t/add-option/${w.consensusId}?label=${java.net.URLEncoder.encode(label, "UTF-8")}", w.voters[2])
                add("").status shouldBe HttpStatusCode.Conflict
                add("   ").status shouldBe HttpStatusCode.Conflict
                add("x".repeat(201)).status shouldBe HttpStatusCode.Conflict
                add(" " + "x".repeat(201) + " ").status shouldBe HttpStatusCode.Conflict
                val ok = add("  " + "y".repeat(200) + "  ")
                ok.status shouldBe HttpStatusCode.OK
                val storedId = Uuid.parse(ok.bodyAsText())
                val stored =
                    transaction {
                        SystemicConsensusOptionTable
                            .selectAll()
                            .where { SystemicConsensusOptionTable.id eq storedId }
                            .single()[SystemicConsensusOptionTable.label]
                    }
                stored shouldBe "y".repeat(200)
            }
        }
    })

private val readsJson = Json

private suspend fun ApplicationCall.respondJson(text: String) = respondText(text)

private fun service(call: ApplicationCall) = SystemicConsensusService(call = call, streamGuard = NoOpSecretBallotStreamGuard)

private fun Route.registerReadsTestRoutes() {
    post("/t/open/{motionId}") {
        val q = call.request.queryParameters
        val k =
            service(call).openSystemicConsensus(
                SystemicConsensusOpenInput(
                    motionId = call.parameters["motionId"]!!,
                    secret = q["secret"]?.toBoolean() ?: true,
                    bindingness =
                        q["bindingness"]?.let { SystemicConsensusBindingness.valueOf(it) } ?: SystemicConsensusBindingness.ADVISORY,
                ),
            )
        call.respondText(k.id)
    }
    post("/t/add-option/{id}") {
        val o =
            service(
                call,
            ).addOption(
                systemicConsensusId = call.parameters["id"]!!,
                input = SystemicConsensusOptionInput(label = call.request.queryParameters["label"]!!),
            )
        call.respondText(o.id)
    }
    post("/t/freeze/{id}") { call.respondText(service(call).freezeOptions(call.parameters["id"]!!).status.name) }
    post("/t/cast/{id}") {
        val resistances =
            call.request.queryParameters["r"]!!
                .split(",")
                .associate { pair -> pair.substringBefore(":") to pair.substringAfter(":").toInt() }
        val r =
            service(
                call,
            ).castResistanceBallot(SystemicConsensusBallotInput(systemicConsensusId = call.parameters["id"]!!, resistances = resistances))
        call.respondText("${r.id}:${r.receiptCode ?: ""}")
    }
    post("/t/close/{id}") { call.respondText(service(call).closeRating(call.parameters["id"]!!).status.name) }
    post("/t/evaluate/{id}") {
        call.respondJson(readsJson.encodeToString(service(call).evaluate(call.parameters["id"]!!)))
    }
    post("/t/reopen/{id}") { call.respondText(service(call).reopenRating(call.parameters["id"]!!).status.name) }
    get("/t/result/{id}") {
        call.respondJson(readsJson.encodeToString(service(call).getSystemicConsensusResult(call.parameters["id"]!!)))
    }
    get("/t/part/{id}") {
        call.respondJson(readsJson.encodeToString(service(call).getSystemicConsensusParticipation(call.parameters["id"]!!)))
    }
    get("/t/parts") {
        val ids = call.request.queryParameters["ids"]!!.split(",")
        call.respondJson(readsJson.encodeToString(service(call).listSystemicConsensusParticipations(ids)))
    }
    get("/t/verify/{id}") {
        val r =
            service(call).verifySystemicConsensusReceipt(
                systemicConsensusId = call.parameters["id"]!!,
                receiptCode = call.request.queryParameters["code"]!!,
            )
        call.respondJson(readsJson.encodeToString(r))
    }
}
