package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.VatFilingPeriodicity
import network.lapis.cloud.shared.domain.VatRate
import java.math.BigDecimal

/** Pure tests of [VatReturnCalculator] -- no DB access. Welle V1.4.13 "USt-Voranmeldung". */
class VatReturnCalculatorTest :
    FunSpec({
        val from = LocalDate(2026, 1, 1)
        val to = LocalDate(2026, 3, 31)

        // Defaults to the account type's own normal-balance side -- every pre-existing test
        // below books a normal (non-storno) line and keeps behaving exactly as before; only the
        // new storno-specific test at the bottom passes the non-normal side explicitly.
        fun line(
            accountType: LedgerAccountType,
            rate: VatRate,
            gross: String,
            vat: String,
            side: PostingSide = GeneralLedgerCalculator.normalBalanceSideOf(accountType),
        ) = VatReturnCalculator.VatPostingLine(
            accountType = accountType,
            side = side,
            rate = rate,
            gross = BigDecimal(gross),
            vatAmount = BigDecimal(vat),
        )

        test("Zahllast: output VAT exceeds input VAT -> balance > 0") {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines =
                        listOf(
                            line(LedgerAccountType.INCOME, VatRate.STANDARD, "119.00", "19.00"),
                            line(LedgerAccountType.EXPENSE, VatRate.STANDARD, "11.90", "1.90"),
                        ),
                    priorYear = emptyList(),
                    earliestPostedEntryDate = null,
                    disclaimerVersion = "v1",
                )
            preview.totalOutputVat shouldBe BigDecimal("19.00")
            preview.totalInputVat shouldBe BigDecimal("1.90")
            preview.balance shouldBe BigDecimal("17.10")
            (preview.balance > BigDecimal.ZERO) shouldBe true
        }

        test("Erstattungsanspruch: input VAT exceeds output VAT -> balance < 0") {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines =
                        listOf(
                            line(LedgerAccountType.INCOME, VatRate.STANDARD, "11.90", "1.90"),
                            line(LedgerAccountType.EXPENSE, VatRate.STANDARD, "119.00", "19.00"),
                        ),
                    priorYear = emptyList(),
                    earliestPostedEntryDate = null,
                    disclaimerVersion = "v1",
                )
            preview.balance shouldBe BigDecimal("-17.10")
            (preview.balance < BigDecimal.ZERO) shouldBe true
        }

        test("multiple postings of the same rate are grouped into one VatRateLineDto") {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines =
                        listOf(
                            line(LedgerAccountType.INCOME, VatRate.STANDARD, "119.00", "19.00"),
                            line(LedgerAccountType.INCOME, VatRate.STANDARD, "238.00", "38.00"),
                        ),
                    priorYear = emptyList(),
                    earliestPostedEntryDate = null,
                    disclaimerVersion = "v1",
                )
            preview.outputVatLines.size shouldBe 1
            val onlyLine = preview.outputVatLines.single()
            onlyLine.postingCount shouldBe 2
            onlyLine.grossTotal shouldBe BigDecimal("357.00")
            onlyLine.vatTotal shouldBe BigDecimal("57.00")
            onlyLine.netTotal shouldBe (onlyLine.grossTotal - onlyLine.vatTotal)
        }

        test("NOT_SUBJECT never appears in outputVatLines/inputVatLines") {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines =
                        listOf(
                            line(LedgerAccountType.INCOME, VatRate.NOT_SUBJECT, "500.00", "0.00"),
                            line(LedgerAccountType.EXPENSE, VatRate.NOT_SUBJECT, "200.00", "0.00"),
                        ),
                    priorYear = emptyList(),
                    earliestPostedEntryDate = null,
                    disclaimerVersion = "v1",
                )
            preview.outputVatLines shouldBe emptyList()
            preview.inputVatLines shouldBe emptyList()
        }

        test("ZERO appears with vatTotal = 0.00, not omitted like NOT_SUBJECT") {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines = listOf(line(LedgerAccountType.INCOME, VatRate.ZERO, "100.00", "0.00")),
                    priorYear = emptyList(),
                    earliestPostedEntryDate = null,
                    disclaimerVersion = "v1",
                )
            preview.outputVatLines.size shouldBe 1
            preview.outputVatLines.single().rate shouldBe VatRate.ZERO
            preview.outputVatLines.single().vatTotal shouldBe BigDecimal("0.00")
        }

        test("unclassifiedPostingCount/unclassifiedGrossTotal count INCOME and EXPENSE together") {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines =
                        listOf(
                            line(LedgerAccountType.INCOME, VatRate.UNCLASSIFIED, "100.00", "0.00"),
                            line(LedgerAccountType.EXPENSE, VatRate.UNCLASSIFIED, "50.00", "0.00"),
                            line(LedgerAccountType.INCOME, VatRate.STANDARD, "119.00", "19.00"),
                        ),
                    priorYear = emptyList(),
                    earliestPostedEntryDate = null,
                    disclaimerVersion = "v1",
                )
            preview.unclassifiedPostingCount shouldBe 2
            preview.unclassifiedGrossTotal shouldBe BigDecimal("150.00")
        }

        test("taxableGrossTotal excludes NOT_SUBJECT and UNCLASSIFIED, includes ZERO/REDUCED/STANDARD (INCOME only)") {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines =
                        listOf(
                            line(LedgerAccountType.INCOME, VatRate.NOT_SUBJECT, "1000.00", "0.00"),
                            line(LedgerAccountType.INCOME, VatRate.UNCLASSIFIED, "2000.00", "0.00"),
                            line(LedgerAccountType.INCOME, VatRate.ZERO, "300.00", "0.00"),
                            line(LedgerAccountType.INCOME, VatRate.STANDARD, "119.00", "19.00"),
                            // EXPENSE side must never count towards taxableGrossTotal.
                            line(LedgerAccountType.EXPENSE, VatRate.STANDARD, "500.00", "79.83"),
                        ),
                    priorYear = emptyList(),
                    earliestPostedEntryDate = null,
                    disclaimerVersion = "v1",
                )
            preview.taxableGrossTotal shouldBe BigDecimal("419.00")
        }

        test("filingPeriodicity MONTHLY when prior-year balance > 9000.00") {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines = emptyList(),
                    priorYear = listOf(line(LedgerAccountType.INCOME, VatRate.STANDARD, "56406.31", "9000.01")),
                    earliestPostedEntryDate = LocalDate(2025, 1, 1),
                    disclaimerVersion = "v1",
                )
            preview.filingPeriodicity shouldBe VatFilingPeriodicity.MONTHLY
            preview.priorYearVatBalance shouldBe BigDecimal("9000.01")
        }

        test("filingPeriodicity QUARTERLY when prior-year balance == 9000.00 exactly (boundary, not MONTHLY)") {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines = emptyList(),
                    priorYear = listOf(line(LedgerAccountType.INCOME, VatRate.STANDARD, "56406.30", "9000.00")),
                    earliestPostedEntryDate = LocalDate(2025, 1, 1),
                    disclaimerVersion = "v1",
                )
            preview.filingPeriodicity shouldBe VatFilingPeriodicity.QUARTERLY
        }

        test("exemptionOnRequestPossible = true at exactly 2000.00, false at 2000.01") {
            val atThreshold =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines = emptyList(),
                    priorYear = listOf(line(LedgerAccountType.INCOME, VatRate.STANDARD, "12539.68", "2000.00")),
                    earliestPostedEntryDate = LocalDate(2025, 1, 1),
                    disclaimerVersion = "v1",
                )
            atThreshold.exemptionOnRequestPossible shouldBe true

            val aboveThreshold =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines = emptyList(),
                    priorYear = listOf(line(LedgerAccountType.INCOME, VatRate.STANDARD, "12539.74", "2000.01")),
                    earliestPostedEntryDate = LocalDate(2025, 1, 1),
                    disclaimerVersion = "v1",
                )
            aboveThreshold.exemptionOnRequestPossible shouldBe false
        }

        test("no prior-year coverage -> filingPeriodicity UNKNOWN, priorYearVatBalance null, exemption never possible") {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines = emptyList(),
                    priorYear = emptyList(),
                    earliestPostedEntryDate = null,
                    disclaimerVersion = "v1",
                )
            preview.filingPeriodicity shouldBe VatFilingPeriodicity.UNKNOWN
            preview.priorYearVatBalance shouldBe null
            preview.exemptionOnRequestPossible shouldBe false
        }

        test("earliestPostedEntryDate strictly after the start of the prior year -> still UNKNOWN (not fully covered)") {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines = emptyList(),
                    priorYear = emptyList(),
                    earliestPostedEntryDate = LocalDate(2025, 6, 1),
                    disclaimerVersion = "v1",
                )
            preview.filingPeriodicity shouldBe VatFilingPeriodicity.UNKNOWN
            preview.priorYearVatBalance shouldBe null
        }

        test(
            "a manual storno (INCOME booked DEBIT instead of the CREDIT normal side) REDUCES " +
                "output VAT instead of doubling it",
        ) {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines =
                        listOf(
                            // Original booking: Bank DEBIT 119.00 / Ertrag CREDIT 119.00 @ STANDARD.
                            line(LedgerAccountType.INCOME, VatRate.STANDARD, "119.00", "19.00", side = PostingSide.CREDIT),
                            // Storno: Ertrag DEBIT 119.00 / Bank CREDIT 119.00 @ STANDARD (the only
                            // correction path for an immutable POSTED entry -- see
                            // `BankStatementMatcher` KDoc "the only correction path is a manual
                            // storno").
                            line(LedgerAccountType.INCOME, VatRate.STANDARD, "119.00", "19.00", side = PostingSide.DEBIT),
                        ),
                    priorYear = emptyList(),
                    earliestPostedEntryDate = null,
                    disclaimerVersion = "v1",
                )
            preview.totalOutputVat shouldBe BigDecimal("0.00")
            preview.balance shouldBe BigDecimal("0.00")
            val onlyLine = preview.outputVatLines.single()
            onlyLine.grossTotal shouldBe BigDecimal("0.00")
            onlyLine.vatTotal shouldBe BigDecimal("0.00")
            onlyLine.postingCount shouldBe 2
        }

        test("an EXPENSE reimbursement (booked CREDIT instead of the DEBIT normal side) REDUCES input VAT instead of increasing it") {
            val preview =
                VatReturnCalculator.preview(
                    from = from,
                    to = to,
                    lines =
                        listOf(
                            line(LedgerAccountType.EXPENSE, VatRate.STANDARD, "119.00", "19.00", side = PostingSide.DEBIT),
                            // Reimbursement: Aufwand CREDIT / Bank DEBIT.
                            line(LedgerAccountType.EXPENSE, VatRate.STANDARD, "119.00", "19.00", side = PostingSide.CREDIT),
                        ),
                    priorYear = emptyList(),
                    earliestPostedEntryDate = null,
                    disclaimerVersion = "v1",
                )
            preview.totalInputVat shouldBe BigDecimal("0.00")
            preview.balance shouldBe BigDecimal("0.00")
        }

        test("notApplicable() short-circuits to all-empty/all-zero with the given reason") {
            val preview =
                VatReturnCalculator.notApplicable(
                    from = from,
                    to = to,
                    reason = network.lapis.cloud.shared.domain.VatNotApplicableReason.KLEINUNTERNEHMER,
                    disclaimerVersion = "v1",
                )
            preview.applicable shouldBe false
            preview.notApplicableReason shouldBe network.lapis.cloud.shared.domain.VatNotApplicableReason.KLEINUNTERNEHMER
            preview.outputVatLines shouldBe emptyList()
            preview.inputVatLines shouldBe emptyList()
            preview.totalOutputVat shouldBe BigDecimal("0.00")
            preview.totalInputVat shouldBe BigDecimal("0.00")
            preview.balance shouldBe BigDecimal("0.00")
        }
    })
