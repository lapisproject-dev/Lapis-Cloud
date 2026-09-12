package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.TravelExpenseLineDto
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
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
    )

    // ---- travelExpenseLineKindEnabled ---------------------------------------------------------

    @Test
    fun travelExpenseLineKindEnabled_receiptedIsAlwaysEnabled() {
        val rates = TravelExpenseRatesDto(mileageRatePerKm = null, perDiemRate = null)
        assertTrue(travelExpenseLineKindEnabled(TravelExpenseLineKind.RECEIPTED, rates))
    }

    @Test
    fun travelExpenseLineKindEnabled_mileageRequiresAConfiguredRate() {
        assertFalse(
            travelExpenseLineKindEnabled(TravelExpenseLineKind.MILEAGE, TravelExpenseRatesDto(mileageRatePerKm = null, perDiemRate = null)),
        )
        assertTrue(
            travelExpenseLineKindEnabled(
                TravelExpenseLineKind.MILEAGE,
                TravelExpenseRatesDto(mileageRatePerKm = 0.30.toDecimal(), perDiemRate = null),
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
        val rates = TravelExpenseRatesDto(mileageRatePerKm = 0.30.toDecimal(), perDiemRate = 14.0.toDecimal())
        val line = sampleLine(TravelExpenseLineKind.MILEAGE)
        assertEquals(null, travelExpenseSubmitBlockReason(sampleReport(lines = listOf(line)), rates))
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
}
