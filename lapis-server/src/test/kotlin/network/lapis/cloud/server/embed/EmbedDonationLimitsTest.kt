package network.lapis.cloud.server.embed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.server.rpc.PartyDonationComplianceCalculator
import java.math.BigDecimal

/**
 * Welle V1.4.1b "Öffentliche Website-Integration -- anonymer Spenden-Pfad".
 */
class EmbedDonationLimitsTest :
    FunSpec({
        test(
            "MAX_AMOUNT_EUR equals PartyDonationComplianceCalculator.ANONYMOUS_FORWARDING_THRESHOLD_EUR " +
                "-- the guard that makes a drift of the legal constant visible here immediately",
        ) {
            EmbedDonationLimits.MAX_AMOUNT_EUR.compareTo(PartyDonationComplianceCalculator.ANONYMOUS_FORWARDING_THRESHOLD_EUR) shouldBe 0
        }

        test("effectiveMaxAmountEur lowers when pspMax is smaller, never raises above MAX_AMOUNT_EUR") {
            EmbedDonationLimits.effectiveMaxAmountEur(BigDecimal("100.00")).compareTo(BigDecimal("100.00")) shouldBe 0
            EmbedDonationLimits.effectiveMaxAmountEur(BigDecimal("10000.00")).compareTo(EmbedDonationLimits.MAX_AMOUNT_EUR) shouldBe 0
            EmbedDonationLimits.effectiveMaxAmountEur(BigDecimal("500.00")).compareTo(EmbedDonationLimits.MAX_AMOUNT_EUR) shouldBe 0
        }

        test("rangeIsUsable is false once the effective maximum falls below MIN_AMOUNT_EUR") {
            EmbedDonationLimits.rangeIsUsable(BigDecimal("3.00")) shouldBe false
            EmbedDonationLimits.rangeIsUsable(BigDecimal("5.00")) shouldBe true
            EmbedDonationLimits.rangeIsUsable(BigDecimal("500.00")) shouldBe true
        }
    })
