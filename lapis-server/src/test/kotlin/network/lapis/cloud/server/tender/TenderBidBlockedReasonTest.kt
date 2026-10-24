package network.lapis.cloud.server.tender

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TenderBidBlockedReason
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderStatus
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** V1.9.202 -- the reason the screens show for "you cannot bid" is computed by the very function the bid path agrees with. */
class TenderBidBlockedReasonTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.reset() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        fun reason(
            status: MemberStatus,
            effective: TenderStatus = TenderStatus.PUBLISHED,
            hasBid: Boolean = false,
            count: Int = 0,
            max: Int = 1000,
        ) = tenderBidBlockedReason(
            status = status,
            currency = TenderCurrency.LTR,
            effective = effective,
            hasBid = hasBid,
            bidCount = count,
            maxBids = max,
        )

        test("the matrix: participant first, then the guest rule, then the state, then the cap (only without an own row)") {
            reason(MemberStatus.ACTIVE) shouldBe null
            reason(MemberStatus.FRIEND) shouldBe null
            reason(MemberStatus.GUEST) shouldBe TenderBidBlockedReason.GUEST_LTR
            listOf(MemberStatus.APPLICATION, MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DONOR, MemberStatus.DECEASED)
                .forEach { reason(it) shouldBe TenderBidBlockedReason.NOT_PARTICIPANT }
            // NOT_PARTICIPANT wins over a closed tender, the guest rule wins over the state
            reason(MemberStatus.APPLICATION, effective = TenderStatus.CLOSED) shouldBe TenderBidBlockedReason.NOT_PARTICIPANT
            reason(MemberStatus.GUEST, effective = TenderStatus.CLOSED) shouldBe TenderBidBlockedReason.GUEST_LTR
            TenderStatus.entries.filter { it != TenderStatus.PUBLISHED }.forEach {
                reason(MemberStatus.ACTIVE, effective = it) shouldBe TenderBidBlockedReason.NOT_OPEN
            }
            reason(MemberStatus.ACTIVE, count = 3, max = 3) shouldBe TenderBidBlockedReason.MAX_BIDS_REACHED
            reason(MemberStatus.ACTIVE, count = 2, max = 3) shouldBe null
            reason(MemberStatus.ACTIVE, hasBid = true, count = 3, max = 3) shouldBe null
        }

        test("parity: the blocked reason is null exactly when submitBid goes through (before and after the deadline)") {
            val clock = tenderClock()
            TimeTestSupport.withMutableServerClock(clock = clock) {
                tenderTestApplication {
                    val board = fx.member(label = "board", role = AccountRole.BOARD)
                    val id = newTender(board = board, binding = fx.approvedMotion())
                    val members =
                        listOf(MemberStatus.ACTIVE, MemberStatus.FRIEND, MemberStatus.GUEST)
                            .associateWith { fx.member(label = "m-$it", status = it) }
                    for (phase in listOf("before", "after")) {
                        if (phase == "after") clock.advance(1.days + 1.hours)
                        members.forEach { (status, member) ->
                            val blocked = tender(member = member) { getTender(id) }.bidBlockedReason
                            val outcome = tenderAttempt(member = member) { submitBid(tenderId = id, input = bidInput(price = "9.00")) }
                            (blocked == null) shouldBe outcome.isSuccess
                            if (phase == "before" && status == MemberStatus.GUEST) blocked shouldBe TenderBidBlockedReason.GUEST_LTR
                            if (phase == "after") {
                                blocked shouldBe
                                    (
                                        if (status ==
                                            MemberStatus.GUEST
                                        ) {
                                            TenderBidBlockedReason.GUEST_LTR
                                        } else {
                                            TenderBidBlockedReason.NOT_OPEN
                                        }
                                    )
                            }
                        }
                    }
                }
            }
        }
    })
