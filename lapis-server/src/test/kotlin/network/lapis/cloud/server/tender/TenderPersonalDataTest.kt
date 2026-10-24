package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.server.dsgvo.TenderPersonalData
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderBidStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days

/** V1.9.201 -- the data-subject export and erasure of the tender tables. */
class TenderPersonalDataTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        test("the contributor is registered and covers all four tables") {
            (TenderPersonalData in PersonalDataRegistry.contributors) shouldBe true
            TenderPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf("tender", "tender_bid", "tender_award", "tender_acceptance")
        }

        test(
            "export lists the own bids and decisions; erasure blanks the text of losing bids and keeps the winner and the decision records",
        ) {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val winner = fx.member(label = "winner")
                    val loser = fx.member(label = "loser")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    val winning =
                        tender(member = winner) { submitBid(tenderId = id, input = bidInput(price = "5.00", text = "Gewinnertext")) }
                    tender(member = loser) { submitBid(tenderId = id, input = bidInput(price = "6.00", text = "Verliertext")) }
                    clock.advance(1.days)
                    tender(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = winning.id)) }

                    val exported = transaction { TenderPersonalData.exportMember(loser) }.jsonObject
                    exported["bids"]!!
                        .jsonArray
                        .single()
                        .jsonObject["text"]!!
                        .jsonPrimitive.content shouldBe "Verliertext"
                    val managed = transaction { TenderPersonalData.exportMember(board) }.jsonObject
                    (managed["tendersManaged"] as JsonArray).size shouldBe 1
                    (managed["awardsDecided"] as JsonArray).size shouldBe 1

                    val outcomes = transaction { TenderPersonalData.eraseMember(memberId = loser, mode = ErasureMode.ANONYMIZE) }
                    outcomes.single { it.table == "tender_bid" }.rowsAnonymized shouldBe 1
                    transaction { TenderBidTable.selectAll().where { TenderBidTable.bidderMemberId eq loser }.single() }.let {
                        it[TenderBidTable.text] shouldBe TenderPersonalData.ERASED_BID_TEXT
                        it[TenderBidTable.status] shouldBe TenderBidStatus.REJECTED
                        it[TenderBidTable.price].toPlainString() shouldBe "6.00"
                    }

                    val winnerOutcome = transaction { TenderPersonalData.eraseMember(memberId = winner, mode = ErasureMode.ANONYMIZE) }
                    winnerOutcome.single { it.table == "tender_bid" }.rowsAnonymized shouldBe 0
                    transaction {
                        TenderBidTable.selectAll().where { TenderBidTable.bidderMemberId eq winner }.single()[TenderBidTable.text]
                    } shouldBe
                        "Gewinnertext"
                    val boardOutcome = transaction { TenderPersonalData.eraseMember(memberId = board, mode = ErasureMode.ANONYMIZE) }
                    boardOutcome.single { it.table == "tender_award" }.rowsRetained shouldBe 1
                    (exported is JsonObject) shouldBe true
                }
            }
        }
    })
