package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TenderBidInput
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** V1.9.201 -- what a bid must look like, who may bid, and the strict deadline. */
class TenderBidRulesTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        test("the price: at most two decimals (never rounded), at least 0.01, 1.5 becomes 1.50") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val bidder = fx.member(label = "bidder")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    listOf("0.001", "0.00", "0", "-1.00", "10.005", "10000000000000000.00").forEach { bad ->
                        tenderAttempt(member = bidder) {
                            submitBid(tenderId = id, input = bidInput(price = bad))
                        }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    }
                    transaction { TenderBidTable.selectAll().count() } shouldBe 0
                    tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "0.01")) }.price.toPlainString() shouldBe
                        "0.01"
                    tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "1.5")) }.price.toPlainString() shouldBe
                        "1.50"
                    tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "12")) }.price.toPlainString() shouldBe
                        "12.00"
                    tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "1.50")) }.price.toPlainString() shouldBe
                        "1.50"
                }
            }
        }

        test("EUR is refused by the service and by the database; the text must have 1 to 4000 characters") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val bidder = fx.member(label = "bidder")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    tenderAttempt(member = bidder) {
                        submitBid(
                            tenderId = id,
                            input = TenderBidInput(price = BigDecimal("5.00"), text = "x", currency = TenderCurrency.EUR),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    listOf("", "   ", "x".repeat(4001)).forEach { bad ->
                        tenderAttempt(
                            member = bidder,
                        ) {
                            submitBid(
                                tenderId = id,
                                input = bidInput(price = "5.00", text = bad),
                            )
                        }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    }
                    tender(
                        member = bidder,
                    ) { submitBid(tenderId = id, input = bidInput(price = "5.00", text = "x".repeat(4000))) }.text.length shouldBe
                        4000
                    // the CHECK constraints stand on their own, for a writer that bypasses the service
                    val failures =
                        transaction {
                            runCatching { exec("UPDATE tender_bid SET currency = 'EUR'") }.isFailure to
                                runCatching { exec("UPDATE tender SET currency = 'EUR'") }.isFailure
                        }
                    failures shouldBe (true to true)
                }
            }
        }

        test("the deadline is strict: one second before it a bid is accepted, at the deadline it is refused") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val bidder = fx.member(label = "bidder")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    clock.advance(1.days - 1.seconds)
                    tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "5.00")) }.status shouldBe
                        TenderBidStatus.SUBMITTED
                    clock.advance(1.seconds)
                    tenderAttempt(member = bidder) {
                        submitBid(tenderId = id, input = bidInput(price = "4.00"))
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    tenderAttempt(member = bidder) { withdrawBid(id) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                }
            }
        }

        test("a second submission replaces the bid; the price moment only moves when the price changes; withdraw and submit again") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val bidder = fx.member(label = "bidder")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    val first =
                        tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "10.00", text = "erster Text")) }
                    clock.advance(60.seconds)
                    val sameCents =
                        tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "10.00", text = "zweiter Text")) }
                    sameCents.id shouldBe first.id
                    sameCents.text shouldBe "zweiter Text"
                    sameCents.priceSetAt shouldBe first.priceSetAt
                    clock.advance(60.seconds)
                    val cheaper =
                        tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "9.00", text = "zweiter Text")) }
                    (cheaper.priceSetAt > first.priceSetAt) shouldBe true
                    transaction { TenderBidTable.selectAll().count() } shouldBe 1

                    tender(member = bidder) { withdrawBid(id) }.status shouldBe TenderBidStatus.WITHDRAWN
                    tenderAttempt(member = bidder) { withdrawBid(id) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    val again = tender(member = bidder) { submitBid(tenderId = id, input = bidInput(price = "8.00")) }
                    again.status shouldBe TenderBidStatus.SUBMITTED
                    again.id shouldBe first.id
                    tender(member = bidder) { listMyBids() } shouldHaveSize 1
                }
            }
        }

        test(
            "ACTIVE and FRIEND may bid; a GUEST may not bid on an LTR tender; applicants, former members, donors and the deceased may not",
        ) {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    listOf(MemberStatus.ACTIVE, MemberStatus.FRIEND).forEach { status ->
                        val member = fx.member(label = "ok-$status", status = status)
                        tender(member = member) { submitBid(tenderId = id, input = bidInput(price = "7.00")) }.status shouldBe
                            TenderBidStatus.SUBMITTED
                    }
                    // V1.9.202: a guest keeps the LTR account on the home server -- the refusal has a fixed text and nothing is stored
                    val guest = fx.member(label = "guest", status = MemberStatus.GUEST)
                    val refusal =
                        tenderAttempt(member = guest) { submitBid(tenderId = id, input = bidInput(price = "7.00")) }.exceptionOrNull()
                    refusal.shouldBeInstanceOf<ConflictException>()
                    refusal.message shouldBe "Guests can only bid on tenders paid in euro"
                    listOf(
                        MemberStatus.APPLICATION,
                        MemberStatus.WITHDRAWN,
                        MemberStatus.REJECTED,
                        MemberStatus.DONOR,
                        MemberStatus.DECEASED,
                    ).forEach { status ->
                        val member = fx.member(label = "no-$status", status = status)
                        tenderAttempt(member = member) {
                            submitBid(tenderId = id, input = bidInput(price = "7.00"))
                        }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                        tenderAttempt(member = member) { listMyBids() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    }
                    transaction { TenderBidTable.selectAll().count() } shouldBe 2
                }
            }
        }

        test("a guest may still withdraw a legacy bid that was stored before the rule (withdrawing is always safe)") {
            TimeTestSupport.withMutableServerClock(clock = tenderClock()) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    val guest = fx.member(label = "guest", status = MemberStatus.GUEST)
                    fx.legacyBid(tenderId = id, bidder = guest, price = "5.00")
                    tender(member = guest) { withdrawBid(id) }.status shouldBe TenderBidStatus.WITHDRAWN
                }
            }
        }
    })
