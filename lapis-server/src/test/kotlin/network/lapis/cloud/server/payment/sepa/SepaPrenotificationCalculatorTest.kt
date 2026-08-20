package network.lapis.cloud.server.payment.sepa

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

/** Pure tests of [SepaPrenotificationCalculator] (E-7 rule) -- no DB access anywhere in this file. */
class SepaPrenotificationCalculatorTest :
    FunSpec({
        test("first collection (previousAmount null) uses the configured days") {
            SepaPrenotificationCalculator.requiredNoticeDays(
                previousAmount = null,
                currentAmount = BigDecimal("60.00"),
                configuredDays = 5,
            ) shouldBe 5
        }

        test("unchanged amount uses the configured days") {
            SepaPrenotificationCalculator.requiredNoticeDays(
                previousAmount = BigDecimal("60.00"),
                currentAmount = BigDecimal("60.00"),
                configuredDays = 5,
            ) shouldBe 5
        }

        test("amount increase with a shorter configured period forces the full 14-day notice period") {
            SepaPrenotificationCalculator.requiredNoticeDays(
                previousAmount = BigDecimal("60.00"),
                currentAmount = BigDecimal("72.00"),
                configuredDays = 5,
            ) shouldBe 14
        }

        test("amount increase with a longer configured period keeps the longer period") {
            SepaPrenotificationCalculator.requiredNoticeDays(
                previousAmount = BigDecimal("60.00"),
                currentAmount = BigDecimal("72.00"),
                configuredDays = 21,
            ) shouldBe 21
        }

        test("amount decrease uses the configured days, not the full period") {
            SepaPrenotificationCalculator.requiredNoticeDays(
                previousAmount = BigDecimal("72.00"),
                currentAmount = BigDecimal("60.00"),
                configuredDays = 5,
            ) shouldBe 5
        }

        test("60.00 and 60.000 compare equal (BigDecimal.compareTo, not equals) -- no increase detected") {
            SepaPrenotificationCalculator.requiredNoticeDays(
                previousAmount = BigDecimal("60.00"),
                currentAmount = BigDecimal("60.000"),
                configuredDays = 5,
            ) shouldBe 5
        }

        test("batch requirement is the maximum over mixed positions") {
            val items =
                listOf(
                    BigDecimal("60.00") to BigDecimal("60.00"), // unchanged -> configuredDays
                    BigDecimal("60.00") to BigDecimal("72.00"), // increase -> full period
                    null to BigDecimal("40.00"), // first collection -> configuredDays
                )
            SepaPrenotificationCalculator.requiredNoticeDaysForBatch(items = items, configuredDays = 5) shouldBe 14
        }

        test("batch requirement over an empty list falls back to configuredDays") {
            SepaPrenotificationCalculator.requiredNoticeDaysForBatch(items = emptyList(), configuredDays = 9) shouldBe 9
        }
    })
