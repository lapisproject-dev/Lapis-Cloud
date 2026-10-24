package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.rpc.GovernanceService
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionResolutionInput
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderAwardReadiness
import network.lapis.cloud.shared.domain.TenderBidView
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.rpc.ConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * V1.9.202 -- what the screens may show about the award ([network.lapis.cloud.shared.domain.TenderBidDto.awardable], `awardReadiness`,
 * `awardJustificationRequired`) is the very decision `awardTender` takes: legacy guest bids, ties, conflicted managers and every state of the
 * award motion.
 */
class TenderAwardViewTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        test("LOWEST_PRICE: a legacy guest bid is neither awardable nor does it block the cheapest valid bid; ties need a justification") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val a = fx.member(label = "a")
                    val b = fx.member(label = "b")
                    val c = fx.member(label = "c")
                    val guest = fx.member(label = "guest", status = MemberStatus.GUEST)
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    val guestBid = fx.legacyBid(tenderId = id, bidder = guest, price = "5.00")
                    val bidA = tender(member = a) { submitBid(tenderId = id, input = bidInput(price = "20.00")) }
                    val bidB = tender(member = b) { submitBid(tenderId = id, input = bidInput(price = "20.00")) }
                    val bidC = tender(member = c) { submitBid(tenderId = id, input = bidInput(price = "30.00")) }
                    tender(member = board) { getTender(id) }.let {
                        it.awardReadiness shouldBe null // not closed yet
                        it.bids.none { bid -> bid.awardable } shouldBe true
                    }
                    clock.advance(1.days + 1.hours)
                    val detail = tender(member = board) { getTender(id) }
                    detail.bidView shouldBe TenderBidView.ALL
                    detail.awardReadiness shouldBe TenderAwardReadiness.READY
                    detail.awardJustificationRequired shouldBe true // a tie between a and b
                    detail.bids
                        .filter { it.awardable }
                        .map { it.id }
                        .toSet() shouldBe setOf(bidA.id, bidB.id)
                    detail.bids.first { it.id == bidC.id }.awardable shouldBe false
                    detail.bids.first { it.id == guestBid.toString() }.awardable shouldBe false
                    // the server enforces what it marked
                    tenderAttempt(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = guestBid.toString())) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    tenderAttempt(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = bidC.id)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ConflictException>()
                    tender(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = bidA.id, justification = "Gleichstand")) }
                        .tender.status shouldBe TenderStatus.AWARDED
                }
            }
        }

        test("the award marks are only for a manager without a bid of their own; everybody else gets the neutral defaults") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val biddingBoard = fx.member(label = "biddingBoard", role = AccountRole.BOARD)
                    val treasurer = fx.member(label = "treasurer", role = AccountRole.TREASURER)
                    val plain = fx.member(label = "plain")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    tender(member = biddingBoard) { submitBid(tenderId = id, input = bidInput(price = "20.00")) }
                    tender(member = plain) { submitBid(tenderId = id, input = bidInput(price = "21.00")) }
                    clock.advance(1.days + 1.hours)
                    listOf(biddingBoard, treasurer, plain).forEach {
                        val dto = tender(member = it) { getTender(id) }
                        dto.awardReadiness shouldBe null
                        dto.awardJustificationRequired shouldBe false
                        dto.bids.none { bid -> bid.awardable } shouldBe true
                    }
                    tender(member = board) { getTender(id) }.bids.count { it.awardable } shouldBe 1
                }
            }
        }

        test("BEST_OFFER: awardReadiness walks through every state and READY is exactly when awardTender goes through") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = bestOfferScenario(fx = fx, clock = clock, present = { c, b -> c + b }, register = false)
                    val winner = s.bidIds.first()
                    val award = TenderAwardInput(tenderId = s.tenderId, bidId = winner, justification = "Das beste Konzept")

                    suspend fun readiness() = tender(member = s.board) { getTender(s.tenderId) }.awardReadiness

                    suspend fun awardRefused() =
                        tenderAttempt(member = s.board) { awardTender(award) }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()

                    readiness() shouldBe TenderAwardReadiness.NEEDS_AWARD_MOTION
                    tender(member = s.board) { registerAwardMotion(tenderId = s.tenderId, motionId = s.awardMotion.toString()) }
                    readiness() shouldBe TenderAwardReadiness.AWARD_MOTION_PENDING
                    tender(member = s.board) { getTender(s.tenderId) }.awardMotionTitle shouldBe "Tender-Testantrag"
                    awardRefused()
                    attempt(member = s.board) {
                        GovernanceService(call = it).resolveMotion(
                            id = s.awardMotion.toString(),
                            input = MotionResolutionInput(votesYes = 3, votesNo = 0, votesAbstain = 0, status = ResolutionStatus.ADOPTED),
                        )
                    }.getOrThrow()
                    transaction {
                        ResolutionTable.update({ ResolutionTable.meetingId eq s.meeting }) {
                            it[decidedAt] =
                                LocalDateTime(2026, 11, 1, 10, 0)
                        }
                    }
                    readiness() shouldBe TenderAwardReadiness.AWARD_MOTION_TOO_EARLY
                    awardRefused()
                    transaction {
                        ResolutionTable.update({ ResolutionTable.meetingId eq s.meeting }) {
                            it[decidedAt] =
                                LocalDateTime(2026, 11, 3, 12, 0)
                        }
                    }
                    readiness() shouldBe TenderAwardReadiness.READY
                    tender(member = s.board) { getTender(s.tenderId) }.awardJustificationRequired shouldBe true
                    tender(member = s.board) { awardTender(award) }.tender.status shouldBe TenderStatus.AWARDED
                }
            }
        }

        test("BEST_OFFER: a rejected award motion reads as NOT_ADOPTED and can be replaced") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val s = bestOfferScenario(fx = fx, clock = clock, present = { c, b -> c + b })
                    attempt(member = s.board) {
                        GovernanceService(call = it).resolveMotion(
                            id = s.awardMotion.toString(),
                            input = MotionResolutionInput(votesYes = 0, votesNo = 3, votesAbstain = 0, status = ResolutionStatus.REJECTED),
                        )
                    }.getOrThrow()
                    tender(member = s.board) { getTender(s.tenderId) }.awardReadiness shouldBe TenderAwardReadiness.AWARD_MOTION_NOT_ADOPTED
                }
            }
        }
    })
