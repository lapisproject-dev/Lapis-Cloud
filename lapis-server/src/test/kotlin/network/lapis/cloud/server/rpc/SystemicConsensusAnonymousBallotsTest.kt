package network.lapis.cloud.server.rpc

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import network.lapis.cloud.server.conference.NoOpSecretBallotStreamGuard
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusBallotDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.domain.SystemicConsensusOptionInput
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.44 -- `listResistanceBallots` never delivers a single ballot of an anonymous consensus: not in any status
 * (RATING, CLOSED, EVALUATED, ABORTED, a second round), at no participation (0, 1, 4, 5, 6, 20 ballots), to no role
 * (member, committee chair, BOARD, ADMIN). The open consensus is unchanged. Also: no existence oracle, and a
 * leak test over every read RPC of an evaluated anonymous consensus.
 */
class SystemicConsensusAnonymousBallotsTest :
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
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "SK AnonBallots Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
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
            val at = LocalDateTime(2026, 6, day, 18, 0)
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
                    routing { registerAnonBallotsRoutes() }
                }
                block()
            }

        suspend fun HttpClient.call(
            method: String,
            path: String,
            asMember: Uuid,
        ): String =
            (
                if (method == "GET") {
                    get(path) { header("X-Member-Id", asMember.toString()) }
                } else {
                    post(path) { header("X-Member-Id", asMember.toString()) }
                }
            ).bodyAsText()

        class World(
            val chair: Uuid,
            val voters: List<Uuid>,
            val board: Uuid,
            val admin: Uuid,
            val consensusId: String,
            val statusQuo: String,
            val optionA: String,
            val optionB: String,
        )

        suspend fun ApplicationTestBuilder.buildWorld(
            tag: String,
            day: Int,
            voterCount: Int,
            secret: Boolean = true,
        ): World {
            val committeeId = committee("SK AnonBallots $tag")
            val chair = member("sk-anonb-$tag-chair@example.org")
            seat(committeeId, chair, CommitteeRole.CHAIR)
            val voters =
                (1..voterCount).map {
                    member("sk-anonb-$tag-v$it@example.org").also { v -> seat(committeeId, v, CommitteeRole.MEMBER) }
                }
            val board = member("sk-anonb-$tag-board@example.org", AccountRole.BOARD)
            val admin = member("sk-anonb-$tag-admin@example.org", AccountRole.ADMIN)
            val motionId = scheduledMotion(committeeId, chair, day)
            val consensusId = client.call("POST", "/a/open/$motionId?secret=$secret", chair)
            val a = client.call("POST", "/a/add-option/$consensusId?label=Option+A", voters[0])
            val b = client.call("POST", "/a/add-option/$consensusId?label=Option+B", voters[0])
            client.call("POST", "/a/freeze/$consensusId", chair)
            val statusQuo =
                transaction {
                    SystemicConsensusOptionTable
                        .selectAll()
                        .where {
                            (SystemicConsensusOptionTable.systemicConsensusId eq Uuid.parse(consensusId)) and
                                (SystemicConsensusOptionTable.isStatusQuoOption eq true)
                        }.single()[SystemicConsensusOptionTable.id]
                }.toString()
            return World(chair, voters, board, admin, consensusId, statusQuo, a, b)
        }

        suspend fun ApplicationTestBuilder.cast(
            w: World,
            voterIndex: Int,
        ): String =
            client.call(
                "POST",
                "/a/cast/${w.consensusId}?r=${w.statusQuo}:8,${w.optionA}:${1 + voterIndex % 9},${w.optionB}:6",
                w.voters[voterIndex],
            )

        suspend fun ApplicationTestBuilder.castAll(
            w: World,
            count: Int,
        ): List<String> = (0 until count).map { cast(w, it) }

        suspend fun ApplicationTestBuilder.rawBallots(
            w: World,
            asMember: Uuid = w.voters[0],
        ): String = client.call("GET", "/a/ballots/${w.consensusId}", asMember)

        suspend fun ApplicationTestBuilder.expectEmpty(
            w: World,
            what: String,
        ) {
            val raw = rawBallots(w)
            withClue(what) { raw shouldBe "[]" }
        }

        test("anonymous: always empty for 0, 1, 4, 5, 6 and 20 ballots in RATING, CLOSED and EVALUATED (raw JSON is exactly [])") {
            withApp {
                for ((index, n) in listOf(0, 1, 4, 5, 6, 20).withIndex()) {
                    val w = buildWorld(tag = "matrix$n", day = 1 + index, voterCount = maxOf(n, 1))
                    castAll(w, n)
                    expectEmpty(w, "n=$n RATING")
                    client.call("POST", "/a/close/${w.consensusId}", w.chair)
                    expectEmpty(w, "n=$n CLOSED")
                    client.call("POST", "/a/evaluate/${w.consensusId}", w.chair)
                    expectEmpty(w, "n=$n EVALUATED")
                    val count =
                        transaction {
                            SystemicConsensusBallotTable
                                .selectAll()
                                .where {
                                    SystemicConsensusBallotTable.systemicConsensusId eq
                                        Uuid.parse(w.consensusId)
                                }.count()
                        }
                    count shouldBe n.toLong()
                }
            }
        }

        test("anonymous: empty in ABORTED and after a second round (reopenRating)") {
            withApp {
                val aborted = buildWorld(tag = "aborted", day = 8, voterCount = 6)
                castAll(aborted, 6)
                client.call("POST", "/a/abort/${aborted.consensusId}", aborted.chair)
                expectEmpty(aborted, "ABORTED")

                val rounds = buildWorld(tag = "round2", day = 9, voterCount = 6)
                castAll(rounds, 6)
                client.call("POST", "/a/close/${rounds.consensusId}", rounds.chair)
                client.call("POST", "/a/evaluate/${rounds.consensusId}", rounds.chair)
                client.call("POST", "/a/reopen/${rounds.consensusId}", rounds.chair)
                expectEmpty(rounds, "round 2 RATING, no ballots yet")
                castAll(rounds, 6)
                expectEmpty(rounds, "round 2 RATING")
                client.call("POST", "/a/close/${rounds.consensusId}", rounds.chair)
                client.call("POST", "/a/evaluate/${rounds.consensusId}", rounds.chair)
                expectEmpty(rounds, "round 2 EVALUATED")
            }
        }

        test("anonymous, 6 ballots EVALUATED: empty for member, committee chair, BOARD and ADMIN alike") {
            withApp {
                val w = buildWorld(tag = "roles", day = 10, voterCount = 6)
                castAll(w, 6)
                client.call("POST", "/a/close/${w.consensusId}", w.chair)
                client.call("POST", "/a/evaluate/${w.consensusId}", w.chair)
                for (caller in listOf(w.voters[0], w.chair, w.board, w.admin)) {
                    rawBallots(w, caller) shouldBe "[]"
                }
            }
        }

        test("open consensus is unchanged: named ballots with their ratings, in RATING and EVALUATED") {
            withApp {
                val w = buildWorld(tag = "open", day = 11, voterCount = 3, secret = false)
                castAll(w, 3)

                fun check(list: List<SystemicConsensusBallotDto>) {
                    list.size shouldBe 3
                    for (b in list) {
                        val i = w.voters.indexOfFirst { it.toString() == b.memberId }
                        (i >= 0) shouldBe true
                        b.memberDisplayName shouldBe "SK AnonBallots Testmitglied"
                        b.round shouldBe 1
                        b.resistances shouldBe
                            mapOf(w.statusQuo to 8, w.optionA to (1 + i % 9), w.optionB to 6)
                    }
                }
                check(json.decodeFromString(rawBallots(w)))
                client.call("POST", "/a/close/${w.consensusId}", w.chair)
                client.call("POST", "/a/evaluate/${w.consensusId}", w.chair)
                check(json.decodeFromString(rawBallots(w)))
            }
        }

        test("no existence oracle: unknown and malformed ids are NotFound exactly like getSystemicConsensus") {
            withApp {
                val w = buildWorld(tag = "oracle", day = 12, voterCount = 1)
                for (id in listOf(Uuid.random().toString(), "not-a-uuid")) {
                    val ballotsRes = client.get("/a/ballots/$id") { header("X-Member-Id", w.voters[0].toString()) }
                    val getRes = client.get("/a/get/$id") { header("X-Member-Id", w.voters[0].toString()) }
                    ballotsRes.status shouldBe HttpStatusCode.NotFound
                    getRes.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        test("leak test: no read RPC of an evaluated anonymous consensus carries a rating, a ballot id or a receipt") {
            withApp {
                val w = buildWorld(tag = "leak", day = 13, voterCount = 6)
                val receipts = castAll(w, 6).map { it.substringAfter(":") }
                client.call("POST", "/a/close/${w.consensusId}", w.chair)
                client.call("POST", "/a/evaluate/${w.consensusId}", w.chair)
                val ballotIds =
                    transaction {
                        SystemicConsensusBallotTable
                            .selectAll()
                            .where { SystemicConsensusBallotTable.systemicConsensusId eq Uuid.parse(w.consensusId) }
                            .map { it[SystemicConsensusBallotTable.id].toString() }
                    }
                ballotIds.size shouldBe 6
                val caller = w.voters[0]
                val bodies =
                    listOf(
                        "get" to "/a/get/${w.consensusId}",
                        "list" to "/a/list",
                        "ballots" to "/a/ballots/${w.consensusId}",
                        "result" to "/a/result/${w.consensusId}",
                        "participation" to "/a/participation/${w.consensusId}",
                        "participations" to "/a/participations/${w.consensusId}",
                        "verify-wrong-code" to "/a/verify/${w.consensusId}?code=WRONG-CODE",
                    ).map { (name, path) -> name to client.call("GET", path, caller) }
                // receipts of OTHER voters never appear either
                for ((name, body) in bodies) {
                    for (id in ballotIds) withClue("$name leaks a ballot id") { body.contains(id) shouldBe false }
                    for (r in receipts.drop(1)) withClue("$name leaks a foreign receipt") { body.contains(r) shouldBe false }
                    withClue("$name carries a forbidden non-empty key") { forbiddenKeyHits(Json.parseToJsonElement(body)).shouldBeEmpty() }
                }
            }
        }
    })

/** Paths of keys named `resistances`, `ballotId` or `receiptCode` that hold something other than null/empty. */
private fun forbiddenKeyHits(element: JsonElement): List<String> =
    when (element) {
        is JsonObject ->
            element.entries.flatMap { (k, v) ->
                val empty =
                    v is JsonNull ||
                        (v is JsonArray && v.isEmpty()) ||
                        (v is JsonObject && v.isEmpty()) ||
                        (v is JsonPrimitive && v.content.isEmpty())
                (if (k in setOf("resistances", "ballotId", "receiptCode") && !empty) listOf(k) else emptyList()) + forbiddenKeyHits(v)
            }
        is JsonArray -> element.flatMap { forbiddenKeyHits(it) }
        else -> emptyList()
    }

private val anonBallotsJson = Json

private fun anonBallotsService(call: ApplicationCall) = SystemicConsensusService(call = call, streamGuard = NoOpSecretBallotStreamGuard)

private fun Route.registerAnonBallotsRoutes() {
    post("/a/open/{motionId}") {
        val k =
            anonBallotsService(call).openSystemicConsensus(
                SystemicConsensusOpenInput(
                    motionId = call.parameters["motionId"]!!,
                    secret = call.request.queryParameters["secret"]?.toBoolean() ?: true,
                    bindingness = SystemicConsensusBindingness.ADVISORY,
                ),
            )
        call.respondText(k.id)
    }
    post("/a/add-option/{id}") {
        val o =
            anonBallotsService(call).addOption(
                systemicConsensusId = call.parameters["id"]!!,
                input = SystemicConsensusOptionInput(label = call.request.queryParameters["label"]!!),
            )
        call.respondText(o.id)
    }
    post("/a/freeze/{id}") { call.respondText(anonBallotsService(call).freezeOptions(call.parameters["id"]!!).status.name) }
    post("/a/cast/{id}") {
        val resistances =
            call.request.queryParameters["r"]!!
                .split(",")
                .associate { pair -> pair.substringBefore(":") to pair.substringAfter(":").toInt() }
        val r =
            anonBallotsService(call).castResistanceBallot(
                SystemicConsensusBallotInput(systemicConsensusId = call.parameters["id"]!!, resistances = resistances),
            )
        call.respondText("${r.id}:${r.receiptCode ?: ""}")
    }
    post("/a/close/{id}") { call.respondText(anonBallotsService(call).closeRating(call.parameters["id"]!!).status.name) }
    post("/a/evaluate/{id}") { call.respondText(anonBallotsService(call).evaluate(call.parameters["id"]!!).winnerOptionId ?: "") }
    post("/a/reopen/{id}") { call.respondText(anonBallotsService(call).reopenRating(call.parameters["id"]!!).status.name) }
    post("/a/abort/{id}") { call.respondText(anonBallotsService(call).abortSystemicConsensus(call.parameters["id"]!!).status.name) }
    get("/a/get/{id}") {
        call.respondText(anonBallotsJson.encodeToString(anonBallotsService(call).getSystemicConsensus(call.parameters["id"]!!)))
    }
    get("/a/list") { call.respondText(anonBallotsJson.encodeToString(anonBallotsService(call).listSystemicConsensuses())) }
    get("/a/ballots/{id}") {
        call.respondText(anonBallotsJson.encodeToString(anonBallotsService(call).listResistanceBallots(call.parameters["id"]!!)))
    }
    get("/a/result/{id}") {
        call.respondText(anonBallotsJson.encodeToString(anonBallotsService(call).getSystemicConsensusResult(call.parameters["id"]!!)))
    }
    get("/a/participation/{id}") {
        call.respondText(
            anonBallotsJson.encodeToString(anonBallotsService(call).getSystemicConsensusParticipation(call.parameters["id"]!!)),
        )
    }
    get("/a/participations/{id}") {
        call.respondText(
            anonBallotsJson.encodeToString(anonBallotsService(call).listSystemicConsensusParticipations(listOf(call.parameters["id"]!!))),
        )
    }
    get("/a/verify/{id}") {
        val r =
            anonBallotsService(call).verifySystemicConsensusReceipt(
                systemicConsensusId = call.parameters["id"]!!,
                receiptCode = call.request.queryParameters["code"]!!,
            )
        call.respondText(anonBallotsJson.encodeToString(r))
    }
}
