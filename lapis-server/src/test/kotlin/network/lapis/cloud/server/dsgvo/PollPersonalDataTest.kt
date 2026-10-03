package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PollParticipationTable
import network.lapis.cloud.server.db.generated.PollResponseRatingTable
import network.lapis.cloud.server.db.generated.PollResponseTable
import network.lapis.cloud.server.db.generated.PollTable
import network.lapis.cloud.server.rpc.PollTestData
import network.lapis.cloud.server.rpc.createAndAnswer
import network.lapis.cloud.server.rpc.pollInput
import network.lapis.cloud.server.rpc.pollTestApplication
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.PollKind
import network.lapis.cloud.shared.domain.PollRatingInput
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.9.30 -- [PollPersonalData]: the export says WHICH polls a member created/closed/took part
 * in but NEVER the answer (which cannot be attributed to the member at all); erasure hard-deletes the
 * member's participations and retains the creator/closer reference with a reason.
 */
class PollPersonalDataTest :
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

        test("registered: section key, display name and covered tables; poll_option/poll_response are allowlisted with a reason") {
            PollPersonalData.sectionKey shouldBe "polls"
            (PollPersonalData in PersonalDataRegistry.contributors) shouldBe true
            PollPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe setOf("poll", "poll_participation")
            PersonalDataRegistry.noPersonalDataAllowlist.containsKey("poll_option") shouldBe true
            PersonalDataRegistry.noPersonalDataAllowlist.containsKey("poll_response") shouldBe true
            PersonalDataRegistry.noPersonalDataAllowlist.getValue("poll_response").isNotBlank() shouldBe true
            // V1.9.41: the per-option ratings are as anonymous as the response they hang on
            PersonalDataRegistry.noPersonalDataAllowlist.getValue("poll_response_rating").isNotBlank() shouldBe true
        }

        test("V1.9.41 consensus poll: export names the kind but no rating; erasing the rater keeps every anonymous rating") {
            pollTestApplication {
                val chair = data.chair()
                val voter = data.member(label = "bewertet")
                val poll =
                    call(member = chair) { createPoll(pollInput(kind = PollKind.SK_PRIORITY)) }
                call(member = voter) { castPollRatings(PollRatingInput(pollId = poll.id, ratings = poll.options.associate { it.id to 9 })) }
                val creator = transaction { PollPersonalData.exportMember(chair) }.jsonObject
                creator
                    .getValue("pollsCreated")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("kind")
                    .jsonPrimitive.content shouldBe "SK_PRIORITY"
                val ratingsBefore = transaction { PollResponseRatingTable.selectAll().count() }
                ratingsBefore shouldBe poll.options.size.toLong()
                val participant = transaction { PollPersonalData.exportMember(voter) }.toString().lowercase()
                participant shouldNotContain "resistance"
                participant shouldNotContain "rating"
                transaction { PollPersonalData.eraseMember(memberId = voter, mode = ErasureMode.entries.first()) }
                transaction { PollParticipationTable.selectAll().where { PollParticipationTable.memberId eq voter }.count() } shouldBe 0L
                transaction { PollResponseRatingTable.selectAll().count() } shouldBe ratingsBefore
            }
        }

        test("export: created / closed polls and 'took part' -- no answer, no weight") {
            pollTestApplication {
                val chair = data.chair()
                val voter = data.member(label = "exportiert")
                data.mint(memberId = voter, amount = "777.00")
                val poll = createAndAnswer(creator = chair, answers = listOf(voter to 1))
                call(member = chair) { closePoll(poll.id) }
                val other = call(member = chair) { createPoll(pollInput(question = "Zweite Frage?")) }

                val creator = transaction { PollPersonalData.exportMember(chair) }.jsonObject
                creator
                    .getValue("pollsCreated")
                    .jsonArray
                    .map {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    }.toSet() shouldBe
                    setOf(poll.id, other.id)
                creator
                    .getValue("pollsClosedOrAborted")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("id")
                    .jsonPrimitive.content shouldBe poll.id
                creator.getValue("pollParticipations").jsonArray.size shouldBe 0

                val participant = transaction { PollPersonalData.exportMember(voter) }
                participant.jsonObject
                    .getValue("pollParticipations")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("pollId")
                    .jsonPrimitive.content shouldBe poll.id
                participant.jsonObject
                    .getValue("pollsCreated")
                    .jsonArray.size shouldBe 0
                val text = participant.toString().lowercase()
                text shouldNotContain "optionid"
                text shouldNotContain "weight"
                text shouldNotContain "777"
            }
        }

        test("erase: participations are hard-deleted in EVERY mode, creator/closer references are retained, anonymous responses stay") {
            pollTestApplication {
                val chair = data.chair()
                val voter = data.member(label = "geloescht")
                val bystander = data.member(label = "bleibt")
                val poll = createAndAnswer(creator = chair, answers = listOf(voter to 0, bystander to 1))
                call(member = chair) { closePoll(poll.id) }
                val pollId = Uuid.parse(poll.id)
                val responsesBefore = transaction { PollResponseTable.selectAll().where { PollResponseTable.pollId eq pollId }.count() }
                responsesBefore shouldBe 2L

                // the first run deletes the one participation, every further mode finds nothing left (idempotent)
                val deletedPerMode =
                    ErasureMode.entries.map { mode ->
                        transaction { PollPersonalData.eraseMember(memberId = voter, mode = mode) }
                            .single { it.table == "poll_participation" }
                            .rowsDeleted
                    }
                deletedPerMode.first() shouldBe 1
                deletedPerMode.drop(1).all { it == 0 } shouldBe true
                transaction { PollParticipationTable.selectAll().where { PollParticipationTable.memberId eq voter }.count() } shouldBe 0L
                transaction { PollParticipationTable.selectAll().where { PollParticipationTable.memberId eq bystander }.count() } shouldBe
                    1L
                transaction { PollResponseTable.selectAll().where { PollResponseTable.pollId eq pollId }.count() } shouldBe 2L

                // the creator/closer is retained with a reason
                val outcomes = transaction { PollPersonalData.eraseMember(memberId = chair, mode = ErasureMode.entries.first()) }
                val pollOutcome = outcomes.single { it.table == "poll" }
                pollOutcome.rowsRetained shouldBe 1
                (pollOutcome.retentionReason?.isNotBlank() == true) shouldBe true
                transaction { PollTable.selectAll().where { PollTable.id eq pollId }.count() } shouldBe 1L
                call(member = chair) { getPoll(poll.id) }.responseCount shouldBe 2
            }
        }
    })
