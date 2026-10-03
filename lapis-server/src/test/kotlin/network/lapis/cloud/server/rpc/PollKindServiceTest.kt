package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollResponseRatingTable
import network.lapis.cloud.server.db.generated.PollResponseTable
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.shared.domain.PollCreateInput
import network.lapis.cloud.shared.domain.PollDecisionOutcome
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollKind
import network.lapis.cloud.shared.domain.PollRatingInput
import network.lapis.cloud.shared.domain.PollResponseInput
import network.lapis.cloud.shared.domain.PollRules
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/** V1.9.41 -- consensus kinds (SK_DECISION / SK_PRIORITY) of [PollService], end to end on a real H2. */
class PollKindServiceTest :
    FunSpec({
        val data = PollTestData()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { data.deleteAllPolls() }
        afterSpec {
            data.deleteAllPolls()
            data.cleanUp()
        }

        fun members(n: Int): List<Uuid> = (0 until n).map { data.member(label = "SK $it") }

        fun vector(
            poll: PollDto,
            value: Int,
        ): Map<String, Int> = poll.options.associate { it.id to value }

        fun responseRows(pollId: String): Long =
            transaction { PollResponseTable.selectAll().where { PollResponseTable.pollId eq Uuid.parse(pollId) }.count() }

        fun ratingRows(): Long = transaction { PollResponseRatingTable.selectAll().count() }

        suspend fun PollApp.skPoll(
            creator: Uuid,
            kind: PollKind = PollKind.SK_DECISION,
            options: List<String> = listOf("A", "B"),
        ): PollDto = call(member = creator) { createPoll(pollInput(kind = kind, options = options)) }

        test("kind is stored and delivered, the default is SINGLE_CHOICE (also for an old client JSON without the field)") {
            pollTestApplication {
                val chair = data.chair()
                call(member = chair) { createPoll(pollInput()) }.kind shouldBe PollKind.SINGLE_CHOICE
                skPoll(chair, PollKind.SK_PRIORITY).kind shouldBe PollKind.SK_PRIORITY
                val old = Json.decodeFromString(PollCreateInput.serializer(), """{"question":"Q?","options":["a","b"]}""")
                old.kind shouldBe PollKind.SINGLE_CHOICE
                old.optionExplanations shouldBe emptyList()
            }
        }

        test("SK_DECISION adds the passive option at position 10, SK_PRIORITY and SINGLE_CHOICE do not") {
            pollTestApplication {
                val chair = data.chair()
                val decision = skPoll(chair, PollKind.SK_DECISION)
                decision.options.map { it.position } shouldContainExactly listOf(0, 1, PollRules.PASSIVE_OPTION_POSITION)
                decision.options.map { it.isPassive } shouldContainExactly listOf(false, false, true)
                decision.options
                    .last()
                    .explanation
                    .shouldBeNull()
                skPoll(chair, PollKind.SK_PRIORITY).options.none { it.isPassive } shouldBe true
                call(member = chair) { createPoll(pollInput()) }.options.none { it.isPassive } shouldBe true
            }
        }

        test("explanations: normalised, 1000 allowed, 1001 / line breaks / control characters rejected, size and kind checked") {
            pollTestApplication {
                val chair = data.chair()
                val ok =
                    call(member = chair) {
                        createPoll(pollInput(kind = PollKind.SK_PRIORITY, optionExplanations = listOf("  Text  ", " ")))
                    }
                ok.options.map { it.explanation } shouldContainExactly listOf("Text", null)
                call(member = chair) {
                    createPoll(pollInput(kind = PollKind.SK_PRIORITY, optionExplanations = listOf("x".repeat(1000), null)))
                }

                suspend fun bad(
                    explanations: List<String?>,
                    kind: PollKind = PollKind.SK_PRIORITY,
                ) = attempt(member = chair) { createPoll(pollInput(kind = kind, optionExplanations = explanations)) }
                bad(listOf("x".repeat(1001), null)).exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                bad(listOf("a\n".repeat(25) + "b", null)).exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                bad(listOf("a\u0007b", null)).exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                bad(listOf("one")).exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                bad(listOf("one", "two"), PollKind.SINGLE_CHOICE).exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                bad(listOf(null, null), PollKind.SINGLE_CHOICE).exceptionOrNull() shouldBe null
            }
        }

        test("castPollRatings: a complete vector is stored without LTR, without option and with weight 0") {
            pollTestApplication(
                ltrBalanceProvider =
                    object : LtrBalanceProvider {
                        override fun balanceAsOf(
                            memberId: Uuid,
                            asOf: kotlinx.datetime.LocalDateTime,
                        ): BigDecimal = error("LTR must not be read for a consensus poll")

                        override fun lockForDebit(memberId: Uuid): Unit = error("LTR must not be touched for a consensus poll")

                        override fun freeBalance(memberId: Uuid): BigDecimal = error("LTR must not be read for a consensus poll")
                    },
            ) {
                val chair = data.chair()
                val poll = skPoll(chair)
                val voter = members(1).single()
                val ledgerBefore = data.ledgerCount()
                val participation = call(member = voter) { castPollRatings(PollRatingInput(pollId = poll.id, ratings = vector(poll, 3))) }
                participation.hasResponded shouldBe true
                data.ledgerCount() shouldBe ledgerBefore
                responseRows(poll.id) shouldBe 1L
                transaction {
                    val response = PollResponseTable.selectAll().where { PollResponseTable.pollId eq Uuid.parse(poll.id) }.single()
                    response[PollResponseTable.optionId].shouldBeNull()
                    response[PollResponseTable.weightLtr].compareTo(BigDecimal.ZERO) shouldBe 0
                    PollResponseRatingTable
                        .selectAll()
                        .where { PollResponseRatingTable.responseId eq response[PollResponseTable.id] }
                        .count()
                } shouldBe 3L
                transaction {
                    PollParticipationTable
                        .selectAll()
                        .where {
                            PollParticipationTable.pollId eq
                                Uuid.parse(
                                    poll.id,
                                )
                        }.count()
                } shouldBe
                    1L
            }
        }

        test(
            "castPollRatings rejects gaps, foreign ids, case duplicates, out-of-range values, oversized maps and a missing passive option",
        ) {
            pollTestApplication {
                val chair = data.chair()
                val poll = skPoll(chair)
                val voter = members(1).single()
                val full = vector(poll, 2)

                suspend fun fails(ratings: Map<String, Int>) =
                    attempt(member = voter) { castPollRatings(PollRatingInput(pollId = poll.id, ratings = ratings)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<BadRequestException>()

                fails(full - poll.options.first().id)
                fails(full + (Uuid.random().toString() to 1))
                fails(full - poll.options.last().id) // passive option missing
                fails(
                    full + (
                        poll.options
                            .first()
                            .id
                            .uppercase() to 1
                    ),
                )
                fails(full.mapValues { 11 })
                fails(full.mapValues { -1 })
                fails((0..11).associate { Uuid.random().toString() to 1 })
                fails(mapOf("not-a-uuid" to 1))
                ratingRows() shouldBe 0L
                responseRows(poll.id) shouldBe 0L
            }
        }

        test("castPollRatings: a second answer and an answer after closing are conflicts, a non-member is forbidden first") {
            pollTestApplication {
                val chair = data.chair()
                val poll = skPoll(chair)
                val (a, b) = members(2)
                call(member = a) { castPollRatings(PollRatingInput(pollId = poll.id, ratings = vector(poll, 1))) }
                attempt(member = a) { castPollRatings(PollRatingInput(pollId = poll.id, ratings = vector(poll, 1))) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                val guest = data.member(label = "Gast", status = network.lapis.cloud.shared.domain.MemberStatus.GUEST)
                attempt(member = guest) { castPollRatings(PollRatingInput(pollId = Uuid.random().toString(), ratings = vector(poll, 1))) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                call(member = chair) { closePoll(poll.id) }
                attempt(member = b) { castPollRatings(PollRatingInput(pollId = poll.id, ratings = vector(poll, 1))) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
            }
        }

        test("the two answer paths reject the wrong poll kind before writing anything") {
            pollTestApplication {
                val chair = data.chair()
                val classic = call(member = chair) { createPoll(pollInput()) }
                val sk = skPoll(chair)
                val voter = members(1).single()
                attempt(member = voter) { castPollRatings(PollRatingInput(pollId = classic.id, ratings = vector(classic, 1))) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<BadRequestException>()
                attempt(member = voter) { castPollResponse(PollResponseInput(pollId = sk.id, optionId = sk.options.first().id)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<BadRequestException>()
                responseRows(classic.id) shouldBe 0L
                responseRows(sk.id) shouldBe 0L
                transaction { PollParticipationTable.selectAll().count() } shouldBe 0L
            }
        }

        test("the result exists only after closing; six answers give a decision, three give only the count") {
            pollTestApplication {
                val chair = data.chair()
                val poll = skPoll(chair)
                val voters = members(6)
                voters.forEach { v ->
                    call(member = v) {
                        castPollRatings(
                            PollRatingInput(
                                pollId = poll.id,
                                ratings =
                                    poll.options.associate { o ->
                                        o.id to
                                            if (o.isPassive) {
                                                5
                                            } else if (o.position == 0) {
                                                1
                                            } else {
                                                8
                                            }
                                    },
                            ),
                        )
                    }
                }
                attempt(member = voters[0]) { getPollResult(poll.id) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                call(member = chair) { closePoll(poll.id) }
                val result = call(member = voters[0]) { getPollResult(poll.id) }
                result.kind shouldBe PollKind.SK_DECISION
                result.headResultAvailable shouldBe false
                result.weightedResultAvailable shouldBe false
                val rating = result.ratingResult.shouldNotBeNull()
                rating.outcome shouldBe PollDecisionOutcome.OPTION_WINS
                rating.winnerOptionId shouldBe poll.options.first().id
                rating.options.first().cumulativeResistance shouldBe 6

                val few = skPoll(chair)
                members(3).forEach { call(member = it) { castPollRatings(PollRatingInput(pollId = few.id, ratings = vector(few, 4))) } }
                call(member = chair) { closePoll(few.id) }
                val withheld = call(member = voters[0]) { getPollResult(few.id) }
                withheld.responseCount shouldBe 3
                withheld.ratingResultAvailable shouldBe false
                withheld.ratingResult.shouldBeNull()
            }
        }

        test("poll DTOs of consensus polls never carry per-response data") {
            pollTestApplication {
                val chair = data.chair()
                val poll = skPoll(chair)
                val json = Json.encodeToString(PollDto.serializer(), poll)
                listOf("responseId", "response_id", "weight", "ltr").forEach { (json.lowercase().contains(it.lowercase())) shouldBe false }
            }
        }
    })
