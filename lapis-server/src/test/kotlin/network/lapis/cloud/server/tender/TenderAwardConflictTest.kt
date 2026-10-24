package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.TenderBidTable
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.TenderAcceptanceDecision
import network.lapis.cloud.shared.domain.TenderAcceptanceInput
import network.lapis.cloud.shared.domain.TenderAwardInput
import network.lapis.cloud.shared.domain.TenderBidStatus
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** V1.9.201 -- who may award, how the winner is determined for each criterion, and the conflict of interest of a bidder. */
class TenderAwardConflictTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        fun bidStatus(tenderId: String): Map<Uuid, TenderBidStatus> =
            transaction {
                TenderBidTable
                    .selectAll()
                    .where { TenderBidTable.tenderId eq Uuid.parse(tenderId) }
                    .associate { it[TenderBidTable.bidderMemberId] to it[TenderBidTable.status] }
            }

        test("a bidder with the BOARD or the ADMIN role may neither award, accept nor register an award motion on that tender") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val biddingBoard = fx.member(label = "biddingBoard", role = AccountRole.BOARD)
                    val biddingAdmin = fx.member(label = "biddingAdmin", role = AccountRole.ADMIN)
                    val third = fx.member(label = "third")
                    val binding = fx.approvedMotion()
                    val someMotion = fx.motion(committee = fx.committee(), submitter = third, status = MotionStatus.SCHEDULED)
                    val id = newTender(board = board, binding = binding)
                    val b1 = tender(member = biddingBoard) { submitBid(tenderId = id, input = bidInput(price = "10.00")) }
                    val b2 = tender(member = biddingAdmin) { submitBid(tenderId = id, input = bidInput(price = "11.00")) }
                    tender(member = third) { submitBid(tenderId = id, input = bidInput(price = "12.00")) }
                    clock.advance(1.days)
                    listOf(biddingBoard, biddingAdmin).forEach { conflicted ->
                        tenderAttempt(
                            member = conflicted,
                        ) {
                            awardTender(
                                TenderAwardInput(tenderId = id, bidId = b1.id),
                            )
                        }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                        tenderAttempt(member = conflicted) { registerAwardMotion(tenderId = id, motionId = someMotion.toString()) }
                            .exceptionOrNull()
                            .shouldBeInstanceOf<ForbiddenException>()
                        tenderAttempt(
                            member = conflicted,
                        ) { recordAcceptance(TenderAcceptanceInput(tenderId = id, decision = TenderAcceptanceDecision.ACCEPTED)) }
                            .exceptionOrNull()
                            .shouldBeInstanceOf<ForbiddenException>()
                    }
                    // the clean board member awards; the conflicted ones are shown as such
                    tender(member = biddingBoard) { getTender(id) }.viewerIsConflicted shouldBe true
                    tender(member = board) { getTender(id) }.viewerIsConflicted shouldBe false
                    tender(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = b1.id)) }.tender.status shouldBe
                        TenderStatus.AWARDED
                    tenderAttempt(member = biddingAdmin) {
                        recordAcceptance(TenderAcceptanceInput(tenderId = id, decision = TenderAcceptanceDecision.ACCEPTED))
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                    b2.id.isNotEmpty() shouldBe true
                }
            }
        }

        test("a bidder with the BOARD role may not cancel the tender, neither while running nor after the award") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val biddingBoard = fx.member(label = "biddingBoard", role = AccountRole.BOARD)
                    val other = fx.member(label = "other")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    tender(member = biddingBoard) { submitBid(tenderId = id, input = bidInput(price = "12.00")) }
                    val best = tender(member = other) { submitBid(tenderId = id, input = bidInput(price = "9.00")) }
                    tenderAttempt(member = biddingBoard) { cancelTender(tenderId = id, reason = "Absage") }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                    clock.advance(1.days)
                    tenderAttempt(member = biddingBoard) { cancelTender(tenderId = id, reason = "Absage") }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                    tender(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = best.id)) }.tender.status shouldBe
                        TenderStatus.AWARDED
                    tenderAttempt(member = biddingBoard) { cancelTender(tenderId = id, reason = "Absage") }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                    tender(member = board) { getTender(id) }.tender.status shouldBe TenderStatus.AWARDED
                }
            }
        }

        test("the list excludes board-only tenders in SQL for non-managers, so they cannot consume the result limit") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val published = newTender(board = board, binding = fx.approvedMotion())
                    val draft = newTender(board = board, binding = fx.approvedMotion(), publish = false)
                    val earlyCancelled = newTender(board = board, binding = fx.approvedMotion(), publish = false)
                    tender(member = board) { cancelTender(tenderId = earlyCancelled, reason = "Absage") }
                    val now =
                        network.lapis.cloud.server.time.OrganizationTimeZone
                            .wallNow()

                    fun ids(internal: Boolean) =
                        transaction { TenderStore.list(statusFilter = null, wallNow = now, includeInternal = internal) }
                            .map { it[network.lapis.cloud.server.db.generated.TenderTable.id].toString() }
                            .toSet()
                    ids(internal = false) shouldBe setOf(published)
                    ids(internal = true) shouldBe setOf(published, draft, earlyCancelled)
                }
            }
        }

        test("withdrawing the own bid does not lift the conflict") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val tactical = fx.member(label = "tactical", role = AccountRole.BOARD)
                    val other = fx.member(label = "other")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    tender(member = tactical) { submitBid(tenderId = id, input = bidInput(price = "10.00")) }
                    tender(member = tactical) { withdrawBid(id) }
                    val bid = tender(member = other) { submitBid(tenderId = id, input = bidInput(price = "9.00")) }
                    clock.advance(1.days)
                    tenderAttempt(
                        member = tactical,
                    ) {
                        awardTender(
                            TenderAwardInput(tenderId = id, bidId = bid.id),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    tender(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = bid.id)) }.tender.status shouldBe
                        TenderStatus.AWARDED
                }
            }
        }

        test(
            "LOWEST_PRICE: only the cheapest running bid wins; a tie needs a justification; losers are REJECTED, withdrawn bids stay WITHDRAWN",
        ) {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val a = fx.member(label = "a")
                    val b = fx.member(label = "b")
                    val c = fx.member(label = "c")
                    val withdrawn = fx.member(label = "withdrawn")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    val bidA = tender(member = a) { submitBid(tenderId = id, input = bidInput(price = "10.00")) }
                    val bidB = tender(member = b) { submitBid(tenderId = id, input = bidInput(price = "10.00")) }
                    val bidC = tender(member = c) { submitBid(tenderId = id, input = bidInput(price = "12.00")) }
                    tender(member = withdrawn) { submitBid(tenderId = id, input = bidInput(price = "1.00")) }
                    tender(member = withdrawn) { withdrawBid(id) }
                    clock.advance(1.days)

                    tenderAttempt(
                        member = board,
                    ) {
                        awardTender(
                            TenderAwardInput(tenderId = id, bidId = bidC.id),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    // the cheapest are tied (the withdrawn 1.00 does not count): a justification is mandatory
                    tenderAttempt(
                        member = board,
                    ) {
                        awardTender(
                            TenderAwardInput(tenderId = id, bidId = bidA.id),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    tenderAttempt(
                        member = board,
                    ) {
                        awardTender(TenderAwardInput(tenderId = id, bidId = bidA.id, justification = "   "))
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    tender(member = board) {
                        awardTender(TenderAwardInput(tenderId = id, bidId = bidB.id, justification = "Fruehere Zusammenarbeit"))
                    }.tender.status shouldBe
                        TenderStatus.AWARDED
                    bidStatus(id) shouldBe
                        mapOf(
                            a to TenderBidStatus.REJECTED,
                            b to TenderBidStatus.AWARDED,
                            c to TenderBidStatus.REJECTED,
                            withdrawn to TenderBidStatus.WITHDRAWN,
                        )
                }
            }
        }

        test("an ineligible cheapest bidder does not block the award of the cheapest eligible bid") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val gone = fx.member(label = "gone")
                    val stays = fx.member(label = "stays")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    tender(member = gone) { submitBid(tenderId = id, input = bidInput(price = "10.00")) }
                    val stayingBid = tender(member = stays) { submitBid(tenderId = id, input = bidInput(price = "20.00")) }
                    clock.advance(1.days)
                    transaction {
                        MemberTable.update({ MemberTable.id eq gone }) {
                            it[status] = MemberStatus.WITHDRAWN
                        }
                    }
                    tender(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = stayingBid.id)) }.tender.status shouldBe
                        TenderStatus.AWARDED
                }
            }
        }

        test("a bidder who is no longer eligible cannot be awarded; an unknown or foreign bid id is refused") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val gone = fx.member(label = "gone")
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    val bid = tender(member = gone) { submitBid(tenderId = id, input = bidInput(price = "10.00")) }
                    val otherTender = newTender(board = board, binding = fx.approvedMotion())
                    val foreignBid =
                        tender(member = fx.member(label = "x")) { submitBid(tenderId = otherTender, input = bidInput(price = "1.00")) }
                    clock.advance(1.days)
                    transaction {
                        MemberTable.update({ MemberTable.id eq gone }) {
                            it[status] = MemberStatus.WITHDRAWN
                        }
                    }
                    tenderAttempt(
                        member = board,
                    ) {
                        awardTender(
                            TenderAwardInput(tenderId = id, bidId = bid.id),
                        )
                    }.exceptionOrNull().shouldBeInstanceOf<ConflictException>()
                    tenderAttempt(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = foreignBid.id)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<NotFoundException>()
                    tenderAttempt(member = board) { awardTender(TenderAwardInput(tenderId = id, bidId = "garbage")) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<NotFoundException>()
                }
            }
        }
    })
