package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.shared.domain.PollCreateInput
import network.lapis.cloud.shared.domain.PollDto
import network.lapis.cloud.shared.domain.PollHeadOptionResultDto
import network.lapis.cloud.shared.domain.PollOptionDto
import network.lapis.cloud.shared.domain.PollParticipationDto
import network.lapis.cloud.shared.domain.PollResponseInput
import network.lapis.cloud.shared.domain.PollResultDto
import network.lapis.cloud.shared.domain.PollWeightedOptionResultDto

/**
 * Welle V1.9.30 -- anonymity at the JSON level (pattern of `PublicApiFieldReductionTest`): every
 * answer of every poll RPC is serialised and scanned, and the field names of every poll DTO are
 * pinned by an allowlist -- adding a field that could carry a weight, a respondent or a timestamp
 * of a response fails this test and forces a conscious decision.
 */
class PollAnonymityLeakTest :
    FunSpec({
        val data = PollTestData()
        val json = Json

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        beforeTest { data.deleteAllPolls() }
        afterSpec {
            data.deleteAllPolls()
            data.cleanUp()
        }

        // "weighted..." is NOT forbidden: PollResultDto's weightedResult* fields are whole-percent shares of the
        // aggregate. What must never appear is a single response's weight, any LTR amount or a respondent.
        val forbiddenFragments =
            listOf(
                "weightltr",
                "weight_ltr",
                "ltr",
                "memberid",
                "member_id",
                "castat",
                "respondedat",
                "answeredat",
                "balance",
                "respondent",
            )

        test("field allowlist: the serialised shape of every poll DTO is exactly the reviewed one") {
            fun names(serializer: KSerializer<*>) =
                (0 until serializer.descriptor.elementsCount).map { serializer.descriptor.getElementName(it) }.toSet()
            names(PollDto.serializer()) shouldBe
                setOf(
                    "id",
                    "question",
                    "description",
                    "options",
                    "status",
                    "createdAt",
                    "createdByDisplayName",
                    "closesAt",
                    "closedAt",
                    "binding",
                    "resultAvailable",
                    "responseCount",
                    "canManage",
                )
            names(PollOptionDto.serializer()) shouldBe setOf("id", "position", "text")
            names(PollParticipationDto.serializer()) shouldBe setOf("pollId", "eligible", "hasResponded", "canRespond")
            names(PollResultDto.serializer()) shouldBe
                setOf(
                    "pollId",
                    "responseCount",
                    "headResultAvailable",
                    "headResult",
                    "weightedResultAvailable",
                    "weightedWithheldReason",
                    "weightedResult",
                )
            names(PollHeadOptionResultDto.serializer()) shouldBe setOf("optionId", "count")
            names(PollWeightedOptionResultDto.serializer()) shouldBe setOf("optionId", "sharePercent")
            names(PollResponseInput.serializer()) shouldBe setOf("pollId", "optionId")
            names(PollCreateInput.serializer()) shouldBe setOf("question", "description", "options", "closesAt")
            // none of the allowed names can carry a response-level secret
            listOf(
                PollDto.serializer(),
                PollOptionDto.serializer(),
                PollParticipationDto.serializer(),
                PollResultDto.serializer(),
                PollHeadOptionResultDto.serializer(),
                PollWeightedOptionResultDto.serializer(),
                PollResponseInput.serializer(),
            ).flatMap { names(it) }.forEach { name ->
                forbiddenFragments.forEach { fragment -> name.lowercase() shouldNotContain fragment }
            }
        }

        test("every RPC answer of a full poll lifecycle is free of weights, LTR amounts, respondents and response times") {
            pollTestApplication {
                val chair = data.chair()
                // recognisable balances: none of these numbers may appear in any answer
                val balances = listOf("12345.67", "23456.78", "34567.89", "45678.90", "56789.01", "67890.12", "78901.23")
                val voters = balances.map { b -> data.member(label = "leak").also { data.mint(memberId = it, amount = b) } }
                val outputs = mutableListOf<String>()

                val created = call(member = chair) { createPoll(pollInput()) }
                outputs += json.encodeToString(PollDto.serializer(), created)
                voters.forEachIndexed { i, v ->
                    val participation =
                        call(member = v) {
                            castPollResponse(
                                PollResponseInput(
                                    pollId = created.id,
                                    optionId =
                                        created.options[
                                            i %
                                                2,
                                        ].id,
                                ),
                            )
                        }
                    outputs += json.encodeToString(PollParticipationDto.serializer(), participation)
                    outputs += json.encodeToString(PollDto.serializer(), call(member = v) { getPoll(created.id) })
                    outputs += json.encodeToString(PollParticipationDto.serializer(), call(member = v) { getPollParticipation(created.id) })
                }
                call(member = chair) { listPolls() }.forEach { outputs += json.encodeToString(PollDto.serializer(), it) }
                outputs += json.encodeToString(PollDto.serializer(), call(member = chair) { closePoll(created.id) })
                outputs += json.encodeToString(PollResultDto.serializer(), call(member = chair) { getPollResult(created.id) })
                call(member = voters[0]) { listPollParticipations(listOf(created.id)) }.forEach {
                    outputs += json.encodeToString(PollParticipationDto.serializer(), it)
                }

                outputs.isEmpty() shouldBe false
                outputs.forEach { out ->
                    val lower = out.lowercase()
                    forbiddenFragments.forEach { fragment -> lower shouldNotContain fragment }
                    balances.forEach { b -> out shouldNotContain b.substringBefore('.') }
                }
            }
        }

        test("the weighted result consists of whole-percent shares only (no absolute LTR sums)") {
            pollTestApplication {
                val chair = data.chair()
                val ja = (0 until 3).map { data.member(label = "ja").also { m -> data.mint(memberId = m, amount = "1000.00") } }
                val nein = (0 until 3).map { data.member(label = "nein").also { m -> data.mint(memberId = m, amount = "500.00") } }
                val poll = createAndAnswer(creator = chair, answers = ja.map { it to 0 } + nein.map { it to 1 })
                call(member = chair) { closePoll(poll.id) }
                val result = call(member = chair) { getPollResult(poll.id) }
                result.weightedResultAvailable shouldBe true
                result.weightedResult.map { it.sharePercent } shouldBe listOf(67, 33)
                val out = json.encodeToString(PollResultDto.serializer(), result)
                out shouldNotContain "3000"
                out shouldNotContain "1500"
            }
        }

        test("member A sees only HIS OWN participation state, never B's") {
            pollTestApplication {
                val chair = data.chair()
                val a = data.member(label = "a")
                val b = data.member(label = "b")
                val poll = call(member = chair) { createPoll(pollInput()) }
                call(member = b) { castPollResponse(PollResponseInput(pollId = poll.id, optionId = poll.options[0].id)) }

                val asA = call(member = a) { listPollParticipations(listOf(poll.id)) }.single()
                asA.hasResponded shouldBe false
                asA.canRespond shouldBe true
                call(member = a) { getPollParticipation(poll.id) }.hasResponded shouldBe false
                val asB = call(member = b) { listPollParticipations(listOf(poll.id)) }.single()
                asB.hasResponded shouldBe true
                // the DTO has no field that could name anybody else
                json.encodeToString(PollParticipationDto.serializer(), asA).lowercase() shouldNotContain "member"
            }
        }
    })
