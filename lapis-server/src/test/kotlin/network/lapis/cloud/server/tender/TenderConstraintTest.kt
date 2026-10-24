package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.TenderAwardCriterion
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** V1.9.201 -- the database CHECKs and unique indexes of V115 stand on their own, for a writer that bypasses the service. */
class TenderConstraintTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        fun rejected(sql: String): Boolean = runCatching { transaction { exec(sql) } }.isFailure

        fun ts() = "TIMESTAMP '2026-11-02 10:00:00'"

        fun insertAward(
            tender: String,
            bid: String,
            by: Uuid,
        ) = "INSERT INTO tender_award (id, tender_id, bid_id, awarded_by, awarded_at, conflicted_count) " +
            "VALUES ('${UUID.randomUUID()}', '$tender', '$bid', '$by', ${ts()}, 0)"

        test("price, award motion, award uniqueness, acceptance reason and the one-bid rule") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val a = fx.member(label = "a")
                    val b = fx.member(label = "b")
                    val binding = fx.approvedMotion()
                    val first = newTender(board = board, binding = binding, criterion = TenderAwardCriterion.BEST_OFFER)
                    val second = newTender(board = board, binding = binding)
                    val bidA = tender(member = a) { submitBid(tenderId = first, input = bidInput(price = "5.00")) }
                    val bidB = tender(member = b) { submitBid(tenderId = first, input = bidInput(price = "6.00")) }

                    // price below the minimum, direct SQL
                    rejected("UPDATE tender_bid SET price = 0.00") shouldBe true
                    // one bid per tender and bidder
                    rejected(
                        "INSERT INTO tender_bid (id, tender_id, bidder_member_id, price, currency, text, status, submitted_at, " +
                            "price_set_at) " +
                            "VALUES ('${UUID.randomUUID()}', '$first', '$a', 1.00, 'LTR', 'x', 'SUBMITTED', ${ts()}, ${ts()})",
                    ) shouldBe true
                    // the award motion is never the binding motion
                    rejected("UPDATE tender SET award_motion_id = binding_motion_id WHERE id = '$first'") shouldBe true
                    // one award motion belongs to exactly one tender
                    val award = fx.motion(committee = fx.committee(), submitter = a)
                    transaction { exec("UPDATE tender SET award_motion_id = '$award' WHERE id = '$first'") }
                    rejected("UPDATE tender SET award_motion_id = '$award' WHERE id = '$second'") shouldBe true
                    // one award per tender and per bid
                    transaction { exec(insertAward(first, bidA.id, board)) }
                    rejected(insertAward(first, bidB.id, board)) shouldBe true
                    rejected(insertAward(second, bidA.id, board)) shouldBe true
                    // a rejection needs a reason, an acceptance does not
                    val acceptance = { decision: String, reason: String? ->
                        "INSERT INTO tender_acceptance (id, tender_id, decision, reason, decided_by, decided_at) VALUES " +
                            "('${UUID.randomUUID()}', '$first', '$decision', ${reason?.let { "'$it'" } ?: "NULL"}, '$board', ${ts()})"
                    }
                    rejected(acceptance("REJECTED", null)) shouldBe true
                    rejected(acceptance("REJECTED", "   ")) shouldBe true
                    rejected(acceptance("MAYBE", "x")) shouldBe true
                    rejected(acceptance("REJECTED", "Nachbessern")) shouldBe false
                    rejected(acceptance("ACCEPTED", null)) shouldBe false
                    // the enumerations
                    rejected("UPDATE tender SET status = 'LIMBO' WHERE id = '$first'") shouldBe true
                    rejected("UPDATE tender SET bid_visibility = 'EVERYONE' WHERE id = '$first'") shouldBe true
                    rejected("UPDATE tender SET award_criterion = 'RANDOM' WHERE id = '$first'") shouldBe true
                    rejected("UPDATE tender_bid SET status = 'LIMBO'") shouldBe true
                    // V116: the description format is PLAIN or MARKDOWN, nothing else (no raw HTML format, no NULL)
                    rejected("UPDATE tender SET description_format = 'HTML' WHERE id = '$first'") shouldBe true
                    rejected("UPDATE tender SET description_format = NULL WHERE id = '$first'") shouldBe true
                    rejected("UPDATE tender SET description_format = 'MARKDOWN' WHERE id = '$first'") shouldBe false
                    clock.advance(1.days)
                }
            }
        }
    })
