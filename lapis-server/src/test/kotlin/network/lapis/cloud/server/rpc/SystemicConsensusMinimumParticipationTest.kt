package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
import network.lapis.cloud.shared.domain.SystemicConsensusBallotDto
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.domain.SystemicConsensusOptionInput
import network.lapis.cloud.shared.domain.SystemicConsensusReceiptVerificationDto
import network.lapis.cloud.shared.domain.SystemicConsensusResultDto
import network.lapis.cloud.shared.domain.SystemicConsensusTiebreakRule
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.42 -- minimum participation of an anonymous Systemic Consensus, end to end against the real service and
 * H2: `evaluate`, `getSystemicConsensusResult`, `listResistanceBallots` (V1.9.44: always empty when anonymous) and the receipt check below and at the
 * threshold, plus the binding outcome (which must not depend on the withholding) and re-rating across rounds.
 */
class SystemicConsensusMinimumParticipationTest :
    FunSpec({
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()
        val json = Json

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpSystemicConsensusTestData(committeeIds = createdCommitteeIds, memberIds = createdMemberIds) }

        fun member(email: String): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "SK MinPart Testmitglied"
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
            val at = LocalDateTime(2026, 5, day, 18, 0)
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
                    routing { registerMinPartRoutes() }
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

        /** chair + [voterCount] voters, one frozen consensus (status quo, options A and B). */
        class World(
            val chair: Uuid,
            val voters: List<Uuid>,
            val motionId: Uuid,
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
            binding: Boolean = false,
            tiebreak: SystemicConsensusTiebreakRule = SystemicConsensusTiebreakRule.LOWEST_MAX_RESISTANCE,
        ): World {
            val committeeId = committee("SK MinPart $tag")
            val chair = member("sk-minpart-$tag-chair@example.org")
            seat(committeeId, chair, CommitteeRole.CHAIR)
            val voters =
                (1..voterCount).map {
                    member("sk-minpart-$tag-v$it@example.org").also { v ->
                        seat(committeeId, v, CommitteeRole.MEMBER)
                    }
                }
            val motionId = scheduledMotion(committeeId, chair, day)
            val query = "?secret=$secret&bindingness=${if (binding) "BINDING" else "ADVISORY"}&tiebreak=${tiebreak.name}"
            val consensusId = client.call("POST", "/m/open/$motionId$query", chair)
            val a = client.call("POST", "/m/add-option/$consensusId?label=Option+A", voters[0])
            val b = client.call("POST", "/m/add-option/$consensusId?label=Option+B", voters[0])
            client.call("POST", "/m/freeze/$consensusId", chair)
            val statusQuo =
                transaction {
                    SystemicConsensusOptionTable
                        .selectAll()
                        .where {
                            (SystemicConsensusOptionTable.systemicConsensusId eq Uuid.parse(consensusId)) and
                                (SystemicConsensusOptionTable.isStatusQuoOption eq true)
                        }.single()[SystemicConsensusOptionTable.id]
                }.toString()
            return World(chair, voters, motionId, consensusId, statusQuo, a, b)
        }

        suspend fun ApplicationTestBuilder.cast(
            w: World,
            voterIndex: Int,
            passive: Int,
            a: Int,
            b: Int,
        ): String =
            client.call(
                "POST",
                "/m/cast/${w.consensusId}?r=${w.statusQuo}:$passive,${w.optionA}:$a,${w.optionB}:$b",
                w.voters[voterIndex],
            )

        /** Every voter rates (passive, A, B) = (8, 1 + i, 6): A wins, all values are distinct per voter. */
        suspend fun ApplicationTestBuilder.castAll(
            w: World,
            count: Int,
        ): List<String> = (0 until count).map { i -> cast(w, i, 8, 1 + i, 6) }

        suspend fun ApplicationTestBuilder.finish(w: World): SystemicConsensusResultDto {
            client.call("POST", "/m/close/${w.consensusId}", w.chair)
            return json.decodeFromString(client.call("POST", "/m/evaluate/${w.consensusId}", w.chair))
        }

        suspend fun ApplicationTestBuilder.read(w: World): SystemicConsensusResultDto =
            json.decodeFromString(client.call("GET", "/m/result/${w.consensusId}", w.voters[0]))

        suspend fun ApplicationTestBuilder.ballots(w: World): List<SystemicConsensusBallotDto> =
            json.decodeFromString(client.call("GET", "/m/ballots/${w.consensusId}", w.voters[0]))

        fun keys(element: JsonElement): Set<String> =
            when (element) {
                is JsonObject -> element.keys + element.values.flatMap { keys(it) }
                is JsonArray -> element.flatMap { keys(it) }.toSet()
                else -> emptySet()
            }

        test("anonymous with 0, 1, 2 and 4 ballots: evaluate and getSystemicConsensusResult both withhold, same winner as the reference") {
            withApp {
                for ((index, n) in listOf(0, 1, 2, 4).withIndex()) {
                    val w = buildWorld(tag = "below$n", day = 1 + index, voterCount = 5)
                    castAll(w, n)
                    val evaluated = finish(w)
                    val read = read(w)
                    for (dto in listOf(evaluated, read)) {
                        dto.figuresWithheld shouldBe true
                        dto.minimumResponses shouldBe 5
                        dto.optionResults.shouldBeEmpty()
                        dto.noRatings shouldBe (n == 0)
                    }
                    read shouldBe evaluated
                    // the decision comes from the full data: with n >= 1 option A (1 + i, lowest max/sum) wins
                    if (n > 0) evaluated.winnerOptionId shouldBe w.optionA
                }
            }
        }

        test("anonymous with exactly 5 ballots: figures are delivered") {
            withApp {
                val w = buildWorld(tag = "five", day = 6, voterCount = 5)
                castAll(w, 5)
                val evaluated = finish(w)
                val read = read(w)
                evaluated.figuresWithheld shouldBe false
                evaluated.optionResults.size shouldBe 3
                evaluated.winnerOptionId shouldBe w.optionA
                read shouldBe evaluated
            }
        }

        test("open consensus with 1 ballot: figures are delivered, nothing withheld") {
            withApp {
                val w = buildWorld(tag = "open", day = 7, voterCount = 2, secret = false)
                castAll(w, 1)
                val evaluated = finish(w)
                evaluated.figuresWithheld shouldBe false
                evaluated.optionResults.size shouldBe 3
            }
        }

        test("listResistanceBallots: always empty for an anonymous consensus (below, at and above the minimum); open consensus unchanged") {
            withApp {
                val below = buildWorld(tag = "ballots-below", day = 8, voterCount = 5)
                castAll(below, 4)
                ballots(below).shouldBeEmpty()
                finish(below)
                ballots(below).shouldBeEmpty()

                val enough = buildWorld(tag = "ballots-five", day = 9, voterCount = 5)
                castAll(enough, 5)
                ballots(enough).shouldBeEmpty()
                finish(enough)
                ballots(enough).shouldBeEmpty()

                val open = buildWorld(tag = "ballots-open", day = 10, voterCount = 2, secret = false)
                castAll(open, 1)
                ballots(open).single().resistances.size shouldBe 3
            }
        }

        test("BINDING below the minimum: the resolution and motion outcome do not depend on the withholding") {
            withApp {
                // ADOPTED: option A wins
                val adopted = buildWorld(tag = "bind-adopt", day = 11, voterCount = 3, binding = true)
                castAll(adopted, 1)
                finish(adopted).figuresWithheld shouldBe true
                transaction { MotionTable.selectAll().where { MotionTable.id eq adopted.motionId }.single()[MotionTable.status] } shouldBe
                    MotionStatus.RESOLVED

                // REJECTED: the status quo option wins
                val rejected = buildWorld(tag = "bind-reject", day = 12, voterCount = 3, binding = true)
                cast(rejected, 0, 0, 7, 8)
                finish(rejected).figuresWithheld shouldBe true
                transaction { MotionTable.selectAll().where { MotionTable.id eq rejected.motionId }.single()[MotionTable.status] } shouldBe
                    MotionStatus.REJECTED

                // POSTPONED: REPEAT tie, no winner
                val postponed =
                    buildWorld(
                        tag = "bind-postpone",
                        day = 13,
                        voterCount = 3,
                        binding = true,
                        tiebreak = SystemicConsensusTiebreakRule.REPEAT,
                    )
                cast(postponed, 0, 4, 4, 4)
                val result = finish(postponed)
                result.figuresWithheld shouldBe true
                result.winnerOptionId shouldBe null
                transaction { MotionTable.selectAll().where { MotionTable.id eq postponed.motionId }.single()[MotionTable.status] } shouldBe
                    MotionStatus.POSTPONED
            }
        }

        test("re-rating: the disclosure follows the current round, in both directions") {
            withApp {
                val up = buildWorld(tag = "round-up", day = 14, voterCount = 5)
                castAll(up, 5)
                finish(up).figuresWithheld shouldBe false
                client.call("POST", "/m/reopen/${up.consensusId}", up.chair)
                castAll(up, 2)
                finish(up).figuresWithheld shouldBe true
                read(up).figuresWithheld shouldBe true
                ballots(up).shouldBeEmpty()

                val down = buildWorld(tag = "round-down", day = 15, voterCount = 5)
                castAll(down, 2)
                finish(down).figuresWithheld shouldBe true
                client.call("POST", "/m/reopen/${down.consensusId}", down.chair)
                castAll(down, 5)
                finish(down).figuresWithheld shouldBe false
                read(down).optionResults.size shouldBe 3
            }
        }

        test("receipt verification with one ballot returns only the own ratings, no aggregate") {
            withApp {
                val w = buildWorld(tag = "receipt", day = 16, voterCount = 3)
                val receipt = castAll(w, 1).single().substringAfter(":")
                finish(w)
                val body = client.call("GET", "/m/verify/${w.consensusId}?code=$receipt", w.voters[0])
                val verification = json.decodeFromString<SystemicConsensusReceiptVerificationDto>(body)
                verification.found shouldBe true
                verification.resistances!!.size shouldBe 3
                val element = Json.parseToJsonElement(body)
                keys(element) shouldBe
                    setOf("found", "round", "countedInCurrentResult", "resistances", "optionId", "isStatusQuoOption", "label", "resistance")
                element.jsonObject.keys shouldBe setOf("found", "round", "countedInCurrentResult", "resistances")
            }
        }
    })

private val minPartJson = Json

private fun minPartService(call: ApplicationCall) = SystemicConsensusService(call = call, streamGuard = NoOpSecretBallotStreamGuard)

private fun Route.registerMinPartRoutes() {
    post("/m/open/{motionId}") {
        val q = call.request.queryParameters
        val k =
            minPartService(call).openSystemicConsensus(
                SystemicConsensusOpenInput(
                    motionId = call.parameters["motionId"]!!,
                    secret = q["secret"]?.toBoolean() ?: true,
                    bindingness = SystemicConsensusBindingness.valueOf(q["bindingness"] ?: "ADVISORY"),
                    tiebreakRule = SystemicConsensusTiebreakRule.valueOf(q["tiebreak"] ?: "LOWEST_MAX_RESISTANCE"),
                ),
            )
        call.respondText(k.id)
    }
    post("/m/add-option/{id}") {
        val o =
            minPartService(call).addOption(
                systemicConsensusId = call.parameters["id"]!!,
                input = SystemicConsensusOptionInput(label = call.request.queryParameters["label"]!!),
            )
        call.respondText(o.id)
    }
    post("/m/freeze/{id}") { call.respondText(minPartService(call).freezeOptions(call.parameters["id"]!!).status.name) }
    post("/m/cast/{id}") {
        val resistances =
            call.request.queryParameters["r"]!!
                .split(",")
                .associate { pair -> pair.substringBefore(":") to pair.substringAfter(":").toInt() }
        val r =
            minPartService(call).castResistanceBallot(
                SystemicConsensusBallotInput(systemicConsensusId = call.parameters["id"]!!, resistances = resistances),
            )
        call.respondText("${r.id}:${r.receiptCode ?: ""}")
    }
    post("/m/close/{id}") { call.respondText(minPartService(call).closeRating(call.parameters["id"]!!).status.name) }
    post("/m/evaluate/{id}") { call.respondText(minPartJson.encodeToString(minPartService(call).evaluate(call.parameters["id"]!!))) }
    post("/m/reopen/{id}") { call.respondText(minPartService(call).reopenRating(call.parameters["id"]!!).status.name) }
    get("/m/result/{id}") {
        call.respondText(minPartJson.encodeToString(minPartService(call).getSystemicConsensusResult(call.parameters["id"]!!)))
    }
    get("/m/ballots/{id}") {
        call.respondText(minPartJson.encodeToString(minPartService(call).listResistanceBallots(call.parameters["id"]!!)))
    }
    get("/m/verify/{id}") {
        val r =
            minPartService(call).verifySystemicConsensusReceipt(
                systemicConsensusId = call.parameters["id"]!!,
                receiptCode = call.request.queryParameters["code"]!!,
            )
        call.respondText(minPartJson.encodeToString(r))
    }
}
