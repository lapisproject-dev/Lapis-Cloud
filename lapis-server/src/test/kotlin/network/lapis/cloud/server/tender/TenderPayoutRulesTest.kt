package network.lapis.cloud.server.tender

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TenderCurrency
import network.lapis.cloud.shared.domain.TenderPayoutBlockedReason
import network.lapis.cloud.shared.domain.TenderPayoutFailureReason
import network.lapis.cloud.shared.domain.TenderStatus
import network.lapis.cloud.shared.rpc.ConflictException
import java.math.BigDecimal

/** V1.9.308 -- the pure rules of the payout: the amount (never rounded), LTR only, who cannot receive, who may not pay. */
class TenderPayoutRulesTest :
    FunSpec({
        test("the payout amount is exactly the price at two decimal places") {
            tenderPayoutAmount(BigDecimal("0.01")).toPlainString() shouldBe "0.01"
            tenderPayoutAmount(BigDecimal("1234.50")).toPlainString() shouldBe "1234.50"
            tenderPayoutAmount(BigDecimal("1234.5")).toPlainString() shouldBe "1234.50"
            tenderPayoutAmount(BigDecimal("7")).toPlainString() shouldBe "7.00"
            tenderPayoutAmount(BigDecimal("9999999999999999.99")).toPlainString() shouldBe "9999999999999999.99"
        }

        test("a price with a third decimal place, zero, a negative price and a price above the column maximum are refused, never rounded") {
            listOf("0.001", "1.005", "0.00", "0.009", "-5.00", "10000000000000000.00").forEach { raw ->
                shouldThrow<ConflictException> { tenderPayoutAmount(BigDecimal(raw)) }.message shouldBe PAYOUT_AMOUNT_INVALID
            }
            // trailing zeros are not a third decimal place
            tenderPayoutAmount(BigDecimal("3.500")).toPlainString() shouldBe "3.50"
        }

        test("only LTR can be paid out") {
            requireLtrPayout(TenderCurrency.LTR)
            shouldThrow<ConflictException> { requireLtrPayout(TenderCurrency.EUR) }.message shouldBe PAYOUT_LTR_ONLY
        }

        test("who cannot receive LTR: a guest and every status outside the LTR-eligible ones") {
            payoutFailureFor(MemberStatus.GUEST) shouldBe TenderPayoutFailureReason.WINNER_GUEST
            listOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED, MemberStatus.APPLICATION).forEach {
                payoutFailureFor(it) shouldBe TenderPayoutFailureReason.WINNER_NOT_LTR_ELIGIBLE
            }
            listOf(MemberStatus.ACTIVE, MemberStatus.FRIEND, MemberStatus.DONOR).forEach { payoutFailureFor(it) shouldBe null }
        }

        test("the blocked reason follows the order role, own bid, four-eyes, status") {
            val payable = TenderStatus.COMPLETED
            payoutBlockedReason(role = AccountRole.MEMBER, callerHasBid = false, callerIsAcceptor = false, status = payable) shouldBe
                TenderPayoutBlockedReason.NOT_TREASURY_ROLE
            // a missing role wins over everything else
            payoutBlockedReason(
                role = AccountRole.MEMBER,
                callerHasBid = true,
                callerIsAcceptor = true,
                status = TenderStatus.PAID,
            ) shouldBe
                TenderPayoutBlockedReason.NOT_TREASURY_ROLE
            payoutBlockedReason(role = AccountRole.TREASURER, callerHasBid = true, callerIsAcceptor = true, status = payable) shouldBe
                TenderPayoutBlockedReason.OWN_BID
            payoutBlockedReason(role = AccountRole.BOARD, callerHasBid = false, callerIsAcceptor = true, status = payable) shouldBe
                TenderPayoutBlockedReason.FOUR_EYES
            payoutBlockedReason(
                role = AccountRole.ADMIN,
                callerHasBid = false,
                callerIsAcceptor = false,
                status = TenderStatus.PAID,
            ) shouldBe
                TenderPayoutBlockedReason.NOT_PAYABLE
            TenderStatus.entries.filter { it !in setOf(TenderStatus.COMPLETED, TenderStatus.PAYOUT_FAILED) }.forEach {
                payoutBlockedReason(role = AccountRole.TREASURER, callerHasBid = false, callerIsAcceptor = false, status = it) shouldBe
                    TenderPayoutBlockedReason.NOT_PAYABLE
            }
            listOf(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN).forEach { role ->
                listOf(TenderStatus.COMPLETED, TenderStatus.PAYOUT_FAILED).forEach { status ->
                    payoutBlockedReason(role = role, callerHasBid = false, callerIsAcceptor = false, status = status) shouldBe null
                }
            }
        }
    })
