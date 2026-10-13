package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Welle V1.4.11 -- covers the pure, DOM-independent predicates in `TravelExpenseScreen.kt`, same
 * scope posture as [ContributionReliefQueueScreenTest].
 */
class TravelExpenseScreenTest {
    private val ratesWithMileage = TravelExpenseDefaults.pdvTemplate(mileageCentsPerKm = 30)

    private fun sampleLine(
        kind: TravelExpenseLineKind,
        amount: Double = 10.0,
        hasReceipt: Boolean = kind != TravelExpenseLineKind.RECEIPTED,
    ) = TravelExpenseLineDto(
        id = "line-${kind.name}",
        reportId = "report-1",
        kind = kind,
        description = "Testzeile",
        amount = amount.toDecimal(),
        receipts = if (hasReceipt) emptyList() else emptyList(), // placeholder, overridden below where needed
    )

    private fun sampleReport(
        status: TravelExpenseReportStatus = TravelExpenseReportStatus.DRAFT,
        lines: List<TravelExpenseLineDto> = emptyList(),
        totalAmount: Double = 0.0,
        payoutChoice: TravelExpensePayoutChoice? = TravelExpensePayoutChoice.PAYOUT,
        advanceCents: Long = 0,
    ) = TravelExpenseReportDto(
        id = "report-1",
        subjectMemberId = "member-1",
        subjectDisplayName = "Test Mitglied",
        status = status,
        purpose = "Konferenz",
        travelFrom = LocalDate(2026, 1, 5),
        travelTo = LocalDate(2026, 1, 5),
        totalAmount = totalAmount.toDecimal(),
        lines = lines,
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
        requestedBy = "member-1",
        requestedByDisplayName = "Test Mitglied",
        payoutChoice = payoutChoice,
        advanceCents = advanceCents,
    )

    // ---- travelExpenseLineKindEnabled ---------------------------------------------------------

    @Test
    fun travelExpenseLineKindEnabled_receiptedIsAlwaysEnabled() {
        val rates = TravelExpenseRatesDto(mileageRatePerKm = null, perDiemRate = null)
        assertTrue(travelExpenseLineKindEnabled(TravelExpenseLineKind.RECEIPTED, rates))
        assertTrue(travelExpenseLineKindEnabled(TravelExpenseLineKind.OTHER, rates))
        assertTrue(travelExpenseLineKindEnabled(TravelExpenseLineKind.ACCOMMODATION, rates))
    }

    @Test
    fun travelExpenseLineKindEnabled_mileageRequiresAConfiguredRate() {
        assertFalse(
            travelExpenseLineKindEnabled(TravelExpenseLineKind.MILEAGE, TravelExpenseRatesDto(mileageRatePerKm = null, perDiemRate = null)),
        )
        assertTrue(
            travelExpenseLineKindEnabled(
                TravelExpenseLineKind.MILEAGE,
                TravelExpenseRatesDto(mileageRatePerKm = 0.30.toDecimal(), perDiemRate = null, rateSet = ratesWithMileage),
            ),
        )
    }

    @Test
    fun travelExpenseLineKindEnabled_perDiemRequiresAConfiguredRate() {
        assertFalse(
            travelExpenseLineKindEnabled(
                TravelExpenseLineKind.PER_DIEM,
                TravelExpenseRatesDto(mileageRatePerKm = null, perDiemRate = null),
            ),
        )
        assertTrue(
            travelExpenseLineKindEnabled(
                TravelExpenseLineKind.PER_DIEM,
                TravelExpenseRatesDto(mileageRatePerKm = null, perDiemRate = 14.0.toDecimal()),
            ),
        )
    }

    // ---- travelExpenseSubmitBlockReason -------------------------------------------------------

    @Test
    fun travelExpenseSubmitBlockReason_noLinesIsBlocked() {
        val rates = TravelExpenseRatesDto(mileageRatePerKm = 0.30.toDecimal(), perDiemRate = 14.0.toDecimal())
        assertTrue(travelExpenseSubmitBlockReason(sampleReport(lines = emptyList()), rates)?.isNotBlank() == true)
    }

    @Test
    fun travelExpenseSubmitBlockReason_receiptedLineWithoutAReceiptIsBlocked() {
        val rates = TravelExpenseRatesDto(mileageRatePerKm = null, perDiemRate = null)
        val line = sampleLine(TravelExpenseLineKind.RECEIPTED).copy(receipts = emptyList())
        assertTrue(travelExpenseSubmitBlockReason(sampleReport(lines = listOf(line)), rates) != null)
    }

    @Test
    fun travelExpenseSubmitBlockReason_mileageLineWithoutAConfiguredRateIsBlocked() {
        val rates = TravelExpenseRatesDto(mileageRatePerKm = null, perDiemRate = null)
        val line = sampleLine(TravelExpenseLineKind.MILEAGE)
        assertTrue(travelExpenseSubmitBlockReason(sampleReport(lines = listOf(line)), rates) != null)
    }

    @Test
    fun travelExpenseSubmitBlockReason_allSatisfiedIsNull() {
        val rates = TravelExpenseRatesDto(mileageRatePerKm = 0.30.toDecimal(), perDiemRate = 14.0.toDecimal(), rateSet = ratesWithMileage)
        val line = sampleLine(TravelExpenseLineKind.MILEAGE)
        assertEquals(null, travelExpenseSubmitBlockReason(sampleReport(lines = listOf(line)), rates))
    }

    @Test
    fun travelExpenseSubmitBlockReason_noPayoutChoiceIsBlocked() {
        val rates = TravelExpenseRatesDto(mileageRatePerKm = 0.30.toDecimal(), rateSet = ratesWithMileage)
        val line = sampleLine(TravelExpenseLineKind.MILEAGE)
        assertTrue(travelExpenseSubmitBlockReason(sampleReport(lines = listOf(line), payoutChoice = null), rates) != null)
    }

    @Test
    fun travelExpenseSubmitBlockReason_unsavedDaysAndAnAdvanceAboveTheTotalAreBlocked() {
        val rates = TravelExpenseRatesDto(mileageRatePerKm = 0.30.toDecimal(), rateSet = ratesWithMileage)
        val line = sampleLine(TravelExpenseLineKind.MILEAGE, amount = 10.0).copy(amountCents = 1000)
        assertTrue(travelExpenseSubmitBlockReason(sampleReport(lines = listOf(line)), rates, daysDirty = true) != null)
        assertTrue(travelExpenseSubmitBlockReason(sampleReport(lines = listOf(line), advanceCents = 1001), rates) != null)
        assertEquals(null, travelExpenseSubmitBlockReason(sampleReport(lines = listOf(line), advanceCents = 1000), rates))
    }

    @Test
    fun travelExpenseSubmitBlockReason_aDayWithoutItsTimesIsBlocked() {
        val rates = TravelExpenseRatesDto(mileageRatePerKm = 0.30.toDecimal(), rateSet = ratesWithMileage)
        val day = sampleLine(TravelExpenseLineKind.DAY_ALLOWANCE).copy(dayDate = LocalDate(2026, 1, 5))
        assertTrue(travelExpenseSubmitBlockReason(sampleReport(lines = listOf(day)), rates) != null)
        val filled = day.copy(timeFrom = LocalTime(8, 0), timeTo = LocalTime(17, 0))
        assertEquals(null, travelExpenseSubmitBlockReason(sampleReport(lines = listOf(filled)), rates))
    }

    @Test
    fun travelExpenseSubmitBlockReason_everyReceiptedKindNeedsAReceipt() {
        val rates = TravelExpenseRatesDto(mileageRatePerKm = 0.30.toDecimal(), rateSet = ratesWithMileage)
        listOf(
            TravelExpenseLineKind.PUBLIC_TRANSPORT,
            TravelExpenseLineKind.ACCOMMODATION,
            TravelExpenseLineKind.HOSPITALITY,
            TravelExpenseLineKind.OTHER,
        ).forEach { kind ->
            assertTrue(
                travelExpenseSubmitBlockReason(sampleReport(lines = listOf(sampleLine(kind))), rates) != null,
                "$kind needs a receipt",
            )
        }
        // Mileage's map proof is optional.
        assertEquals(null, travelExpenseSubmitBlockReason(sampleReport(lines = listOf(sampleLine(TravelExpenseLineKind.MILEAGE))), rates))
    }

    // ---- copy as draft, field checks ----------------------------------------------------------

    @Test
    fun travelExpenseCopiedLines_mapsLegacyKindsAndSkipsDayLines() {
        val lines =
            listOf(
                sampleLine(TravelExpenseLineKind.DAY_ALLOWANCE).copy(dayDate = LocalDate(2026, 1, 5)),
                sampleLine(TravelExpenseLineKind.PER_DIEM),
                sampleLine(TravelExpenseLineKind.RECEIPTED).copy(amountCents = 4550),
                sampleLine(TravelExpenseLineKind.OTHER).copy(amountCents = 100),
            )
        val copied = travelExpenseCopiedLines(sampleReport(lines = lines))
        assertEquals(listOf(TravelExpenseLineKind.OTHER, TravelExpenseLineKind.OTHER), copied.map { it.kind })
        assertEquals(4550L, copied.first().amountCents)
    }

    @Test
    fun travelExpenseCentsAmountCheck_wholeCentsPositiveAndCapped() {
        assertEquals(FieldCheck.Ok, travelExpenseCentsAmountCheck("12,50"))
        assertEquals(FieldCheck.Ok, travelExpenseCentsAmountCheck("10000"))
        assertTrue(travelExpenseCentsAmountCheck("10000,01") is FieldCheck.Invalid)
        assertTrue(travelExpenseCentsAmountCheck("0") is FieldCheck.Invalid)
        assertTrue(travelExpenseCentsAmountCheck("1,234") is FieldCheck.Invalid)
        assertTrue(travelExpenseCentsAmountCheck("abc") is FieldCheck.Invalid)
        assertTrue(travelExpenseCentsAmountCheck("-5") is FieldCheck.Invalid)
    }

    @Test
    fun travelExpenseAdvanceCheck_emptyMeansNoAdvance() {
        assertEquals(FieldCheck.Ok, travelExpenseAdvanceCheck(""))
        assertEquals(FieldCheck.Ok, travelExpenseAdvanceCheck("50,00"))
        assertTrue(travelExpenseAdvanceCheck("-1") is FieldCheck.Invalid)
        assertTrue(travelExpenseAdvanceCheck("1000000,01") is FieldCheck.Invalid)
    }

    @Test
    fun travelExpenseAccommodationFits_theMorningAfterTheLastNightMustBeInsideTheTrip() {
        val from = LocalDate(2026, 1, 10)
        val to = LocalDate(2026, 1, 12)
        assertTrue(travelExpenseAccommodationFits(from, 2, from, to))
        assertFalse(travelExpenseAccommodationFits(from, 3, from, to))
        assertFalse(travelExpenseAccommodationFits(LocalDate(2026, 1, 9), 1, from, to))
        assertFalse(travelExpenseAccommodationFits(from, 0, from, to))
    }

    // ---- travelExpenseSubtotals ------------------------------------------------------------

    @Test
    fun travelExpenseSubtotals_sumsPerKindNeverAcrossKinds() {
        val lines =
            listOf(
                sampleLine(TravelExpenseLineKind.MILEAGE, amount = 10.0),
                sampleLine(TravelExpenseLineKind.MILEAGE, amount = 5.0),
                sampleLine(TravelExpenseLineKind.PER_DIEM, amount = 14.0),
            )
        val subtotals = travelExpenseSubtotals(sampleReport(lines = lines))
        assertEquals(15.0, subtotals.getValue(TravelExpenseLineKind.MILEAGE).toDouble())
        assertEquals(14.0, subtotals.getValue(TravelExpenseLineKind.PER_DIEM).toDouble())
        assertEquals(0.0, subtotals.getValue(TravelExpenseLineKind.RECEIPTED).toDouble())
    }

    // ---- travelExpenseCanWithdraw / travelExpenseCanCopyAsDraft ----------------------------

    @Test
    fun travelExpenseCanWithdraw_onlyDraftAndRequested() {
        assertTrue(travelExpenseCanWithdraw(sampleReport(status = TravelExpenseReportStatus.DRAFT)))
        assertTrue(travelExpenseCanWithdraw(sampleReport(status = TravelExpenseReportStatus.REQUESTED)))
        assertFalse(travelExpenseCanWithdraw(sampleReport(status = TravelExpenseReportStatus.APPROVED)))
        assertFalse(travelExpenseCanWithdraw(sampleReport(status = TravelExpenseReportStatus.EXECUTED)))
        assertFalse(travelExpenseCanWithdraw(sampleReport(status = TravelExpenseReportStatus.REJECTED)))
        assertFalse(travelExpenseCanWithdraw(sampleReport(status = TravelExpenseReportStatus.WITHDRAWN)))
    }

    @Test
    fun travelExpenseCanCopyAsDraft_onlyRejectedWithoutAnOpenDraft() {
        TravelExpenseReportStatus.entries.forEach { status ->
            assertEquals(
                status == TravelExpenseReportStatus.REJECTED,
                travelExpenseCanCopyAsDraft(sampleReport(status = status), hasOpenDraft = false),
            )
        }
    }

    @Test
    fun travelExpenseCanCopyAsDraft_blockedWhileAnOpenDraftExists() {
        // Review finding (MAJOR): copying a rejected report into a second draft while one is
        // already open orphans the first draft -- it disappears from both the editor (only one
        // DRAFT is ever rendered) and "Meine Anträge" (DRAFTs are filtered out of that list).
        assertFalse(
            travelExpenseCanCopyAsDraft(sampleReport(status = TravelExpenseReportStatus.REJECTED), hasOpenDraft = true),
        )
    }

    @Test
    fun refundAccountForm_isOfferedToTheSubjectOnLiveReportsOfAPayoutOnly() {
        val live = listOf(TravelExpenseReportStatus.REQUESTED, TravelExpenseReportStatus.APPROVED, TravelExpenseReportStatus.EXECUTED)
        TravelExpenseReportStatus.entries.forEach { status ->
            assertEquals(
                status in live,
                travelExpenseCanSetRefundAccount(sampleReport(status = status), memberId = "member-1"),
                "status $status",
            )
        }
        val executed = sampleReport(status = TravelExpenseReportStatus.EXECUTED)
        assertFalse(travelExpenseCanSetRefundAccount(executed, memberId = "someone-else"), "never for a requester or a board member")
        assertFalse(travelExpenseCanSetRefundAccount(executed, memberId = null))
        assertFalse(
            travelExpenseCanSetRefundAccount(executed.copy(paidOutsideLapis = true), memberId = "member-1"),
            "a legacy report refunded by hand gets no form",
        )
        assertFalse(
            travelExpenseCanSetRefundAccount(
                sampleReport(status = TravelExpenseReportStatus.EXECUTED, payoutChoice = TravelExpensePayoutChoice.DONATION_WAIVER),
                memberId = "member-1",
            ),
        )
    }

    @Test
    fun refundAccountRequest_aTypedIbanIsSentWithTheHolder() {
        val request =
            travelExpenseRefundAccountRequest(
                typedIban = " DE02 1203 0000 0000 2020 51 ",
                typedHolder = " Erika ",
                storedLast4 = null,
                storedHolder = null,
            )!!
        assertEquals("DE02 1203 0000 0000 2020 51", request.iban)
        assertEquals("Erika", request.holder)
    }

    @Test
    fun refundAccountRequest_aChangedHolderWithoutIbanIsAHolderOnlyUpdate() {
        val request = travelExpenseRefundAccountRequest("", "Erika Musterfrau", storedLast4 = "2051", storedHolder = "Erika Mustermann")!!
        assertEquals(null, request.iban)
        assertEquals("Erika Musterfrau", request.holder)
    }

    @Test
    fun refundAccountRequest_neverClearsAndSendsNothingWhenNothingChanged() {
        assertEquals(null, travelExpenseRefundAccountRequest("", "Erika", storedLast4 = "2051", storedHolder = "Erika"))
        assertEquals(null, travelExpenseRefundAccountRequest("", "", storedLast4 = "2051", storedHolder = "Erika"))
        assertEquals(null, travelExpenseRefundAccountRequest("", "Erika", storedLast4 = null, storedHolder = null))
    }

    @Test
    fun refundIbanCheck_acceptsBlankAndAValidLookingIbanOnly() {
        assertEquals(FieldCheck.Ok, travelExpenseRefundIbanCheck(""))
        assertEquals(FieldCheck.Ok, travelExpenseRefundIbanCheck("DE02120300000000202051"))
        assertTrue(travelExpenseRefundIbanCheck("nonsense") is FieldCheck.Invalid)
    }
}
