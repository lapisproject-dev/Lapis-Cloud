package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.DonationDuty
import network.lapis.cloud.shared.domain.DonorCategory
import java.math.BigDecimal

/**
 * Pure tests of [PartyDonationComplianceCalculator.check] -- no DB access anywhere in this file,
 * same rationale as [JournalEntryBalanceTest]/[UseOfFundsCalculatorTest] give for their own
 * pure-logic subjects.
 */
class PartyDonationComplianceCalculatorTest :
    FunSpec({
        val zero = BigDecimal.ZERO

        val structuralCategories =
            listOf(
                DonorCategory.PUBLIC_LAW_CORPORATION,
                DonorCategory.OVER_25_PERCENT_STATE_OWNED_COMPANY,
                DonorCategory.OTHER_PARTY_OR_PARLIAMENTARY_GROUP_ENTITY,
                DonorCategory.PROFESSIONAL_OR_TRADE_ASSOCIATION,
            )

        structuralCategories.forEach { category ->
            test("$category is PROHIBITED regardless of amount (tiny, zero, huge) with no duties and a non-blank reason") {
                listOf(BigDecimal("0.01"), zero, BigDecimal("1000000.00")).forEach { amount ->
                    val result =
                        PartyDonationComplianceCalculator.check(
                            amount = amount,
                            category = category,
                            priorPostedTotalThisYear = zero,
                        )
                    result.verdict shouldBe DonationVerdict.PROHIBITED
                    result.reason?.isNotBlank() shouldBe true
                    result.duties.shouldBeEmpty()
                }
            }

            test("$category is PROHIBITED even with a large prior-year total (still amount-independent)") {
                val result =
                    PartyDonationComplianceCalculator.check(
                        amount = BigDecimal("10.00"),
                        category = category,
                        priorPostedTotalThisYear = BigDecimal("999999.00"),
                    )
                result.verdict shouldBe DonationVerdict.PROHIBITED
                result.duties.shouldBeEmpty()
            }
        }

        test("NON_EU_FOREIGN_NATURAL_PERSON exactly at the foreign cap is ALLOWED") {
            val cap = PartyDonationComplianceCalculator.FOREIGN_DONOR_ANNUAL_CAP_EUR
            val result =
                PartyDonationComplianceCalculator.check(
                    amount = cap,
                    category = DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON,
                    priorPostedTotalThisYear = zero,
                )
            result.verdict shouldBe DonationVerdict.ALLOWED
        }

        test("NON_EU_FOREIGN_NATURAL_PERSON just above the foreign cap is PROHIBITED") {
            val cap = PartyDonationComplianceCalculator.FOREIGN_DONOR_ANNUAL_CAP_EUR
            val result =
                PartyDonationComplianceCalculator.check(
                    amount = cap + BigDecimal("0.01"),
                    category = DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON,
                    priorPostedTotalThisYear = zero,
                )
            result.verdict shouldBe DonationVerdict.PROHIBITED
            result.reason?.isNotBlank() shouldBe true
            result.duties.shouldBeEmpty()
        }

        test("NON_EU_FOREIGN_NATURAL_PERSON just below the foreign cap is ALLOWED") {
            val cap = PartyDonationComplianceCalculator.FOREIGN_DONOR_ANNUAL_CAP_EUR
            val result =
                PartyDonationComplianceCalculator.check(
                    amount = cap - BigDecimal("0.01"),
                    category = DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON,
                    priorPostedTotalThisYear = zero,
                )
            result.verdict shouldBe DonationVerdict.ALLOWED
        }

        test("NON_EU_FOREIGN_NATURAL_PERSON: a small current donation pushed over the cap by a prior-year total is PROHIBITED") {
            val cap = PartyDonationComplianceCalculator.FOREIGN_DONOR_ANNUAL_CAP_EUR
            val result =
                PartyDonationComplianceCalculator.check(
                    amount = BigDecimal("10.00"),
                    category = DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON,
                    priorPostedTotalThisYear = cap,
                )
            result.verdict shouldBe DonationVerdict.PROHIBITED
        }

        test("NON_EU_FOREIGN_NATURAL_PERSON within the cap can still accrue the prompt-report/disclosure duties") {
            // The cap (1000) is far below the disclosure/prompt-report thresholds in this wave's
            // constants, so this specific scenario cannot happen with the current figures -- this
            // test instead pins that ALLOWED-within-cap still runs the same additive-duty logic as
            // every other allowed category (no special-cased short-circuit for this category).
            val result =
                PartyDonationComplianceCalculator.check(
                    amount = BigDecimal("1.00"),
                    category = DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON,
                    priorPostedTotalThisYear = zero,
                )
            result.verdict shouldBe DonationVerdict.ALLOWED
            result.duties.shouldBeEmpty()
        }

        test(
            "ANONYMOUS at the forwarding threshold has no duty; just above triggers ANONYMOUS_FORWARDING_REQUIRED, verdict stays ALLOWED",
        ) {
            val threshold = PartyDonationComplianceCalculator.ANONYMOUS_FORWARDING_THRESHOLD_EUR

            val atThreshold =
                PartyDonationComplianceCalculator.check(
                    amount = threshold,
                    category = DonorCategory.ANONYMOUS,
                    priorPostedTotalThisYear = zero,
                )
            atThreshold.verdict shouldBe DonationVerdict.ALLOWED
            atThreshold.duties.shouldBeEmpty()

            val aboveThreshold =
                PartyDonationComplianceCalculator.check(
                    amount = threshold + BigDecimal("0.01"),
                    category = DonorCategory.ANONYMOUS,
                    priorPostedTotalThisYear = zero,
                )
            aboveThreshold.verdict shouldBe DonationVerdict.ALLOWED
            aboveThreshold.duties shouldBe setOf(DonationDuty.ANONYMOUS_FORWARDING_REQUIRED)
        }

        test("ANONYMOUS is never PROHIBITED, even for a huge amount") {
            val result =
                PartyDonationComplianceCalculator.check(
                    amount = BigDecimal("1000000.00"),
                    category = DonorCategory.ANONYMOUS,
                    priorPostedTotalThisYear = zero,
                )
            result.verdict shouldBe DonationVerdict.ALLOWED
        }

        test("ANONYMOUS ignores priorPostedTotalThisYear -- the forwarding rule is per-donation, not aggregate") {
            // Even with a huge prior total passed in (which callers should never do for ANONYMOUS,
            // but the calculator itself must not silently aggregate it), a small anonymous amount
            // stays duty-free.
            val result =
                PartyDonationComplianceCalculator.check(
                    amount = BigDecimal("10.00"),
                    category = DonorCategory.ANONYMOUS,
                    priorPostedTotalThisYear = BigDecimal("999999.00"),
                )
            result.duties.shouldBeEmpty()
        }

        listOf(
            DonorCategory.GERMAN_NATURAL_PERSON,
            DonorCategory.EU_NATURAL_PERSON,
            DonorCategory.GERMAN_COMPANY_OR_ORGANIZATION,
        ).forEach { category ->
            test("$category below both thresholds is ALLOWED with no duties") {
                val result =
                    PartyDonationComplianceCalculator.check(
                        amount = BigDecimal("50.00"),
                        category = category,
                        priorPostedTotalThisYear = zero,
                    )
                result.verdict shouldBe DonationVerdict.ALLOWED
                result.duties.shouldBeEmpty()
            }

            test("$category above the disclosure threshold only accrues ANNUAL_DISCLOSURE_REQUIRED") {
                val disclosureThreshold = PartyDonationComplianceCalculator.ANNUAL_DISCLOSURE_THRESHOLD_EUR
                val result =
                    PartyDonationComplianceCalculator.check(
                        amount = disclosureThreshold + BigDecimal("0.01"),
                        category = category,
                        priorPostedTotalThisYear = zero,
                    )
                result.verdict shouldBe DonationVerdict.ALLOWED
                result.duties shouldBe setOf(DonationDuty.ANNUAL_DISCLOSURE_REQUIRED)
            }

            test("$category above the prompt-report threshold accrues BOTH duties simultaneously") {
                val promptThreshold = PartyDonationComplianceCalculator.PROMPT_REPORT_THRESHOLD_EUR
                val result =
                    PartyDonationComplianceCalculator.check(
                        amount = promptThreshold + BigDecimal("0.01"),
                        category = category,
                        priorPostedTotalThisYear = zero,
                    )
                result.verdict shouldBe DonationVerdict.ALLOWED
                result.duties shouldBe setOf(DonationDuty.PROMPT_BUNDESTAG_REPORT_REQUIRED, DonationDuty.ANNUAL_DISCLOSURE_REQUIRED)
            }

            test("$category: a small current donation pushed over the disclosure threshold by a prior-year total accrues the duty") {
                val disclosureThreshold = PartyDonationComplianceCalculator.ANNUAL_DISCLOSURE_THRESHOLD_EUR
                val result =
                    PartyDonationComplianceCalculator.check(
                        amount = BigDecimal("1.00"),
                        category = category,
                        priorPostedTotalThisYear = disclosureThreshold,
                    )
                result.duties shouldBe setOf(DonationDuty.ANNUAL_DISCLOSURE_REQUIRED)
            }

            test("$category exactly at the disclosure threshold has no duty yet (strictly-greater-than semantics)") {
                val disclosureThreshold = PartyDonationComplianceCalculator.ANNUAL_DISCLOSURE_THRESHOLD_EUR
                val result =
                    PartyDonationComplianceCalculator.check(
                        amount = disclosureThreshold,
                        category = category,
                        priorPostedTotalThisYear = zero,
                    )
                result.duties.shouldBeEmpty()
            }
        }

        test("BigDecimal scale sanity: 100.00 and 100.0 compare equal at the cap boundary (compareTo, not equals)") {
            val cap = PartyDonationComplianceCalculator.FOREIGN_DONOR_ANNUAL_CAP_EUR
            val scaledCap = BigDecimal(cap.toPlainString()).setScale(cap.scale() + 3)
            val result =
                PartyDonationComplianceCalculator.check(
                    amount = scaledCap,
                    category = DonorCategory.NON_EU_FOREIGN_NATURAL_PERSON,
                    priorPostedTotalThisYear = zero,
                )
            result.verdict shouldBe DonationVerdict.ALLOWED
        }
    })
