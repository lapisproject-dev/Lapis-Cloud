package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Accounting UI wave -- covers only the pure, DOM-independent helper functions local to
 * `NonprofitComplianceReportsScreen.kt` ([mittelverwendungsBannerText], [useOfFundsPeriodCaption],
 * [hasOverdueAmount]), same scope posture as [LedgerScreenTest]/[FinancialReportsScreenTest] (no
 * DOM/rendering test harness exists in this module). This screen introduces no new local
 * `typeBadge`/`statusBadge` enum -- [GemeinnuetzigkeitSphere]/[ReserveType] labels are already
 * covered by [AccountingLabelsTest] (`AccountingLabels.kt`), and [renderStatementLineTable] is
 * reused verbatim from `FinancialReportsScreen.kt` (covered there, no local re-implementation).
 */
class NonprofitComplianceReportsScreenTest {
    @Test
    fun mittelverwendungsBannerText_withNullYears_showsLoadingPlaceholderNeverAHardcodedNumber() {
        val text = mittelverwendungsBannerText(null)
        assertTrue(text.contains("…"), "expected a loading placeholder ellipsis, got: $text")
        assertFalse(text.contains("2 Jahren"), "must never hardcode the currently-2 timelyUseYears assumption")
    }

    @Test
    fun mittelverwendungsBannerText_interpolatesTheServerSuppliedYearsValueLive() {
        assertTrue(mittelverwendungsBannerText(2).contains("2 Jahren"))
        // A hypothetical future backend change (e.g. to 3) must flow straight through, not get
        // silently clamped/ignored by a hardcoded assumption on the client.
        assertTrue(mittelverwendungsBannerText(3).contains("3 Jahren"))
    }

    @Test
    fun mittelverwendungsBannerText_alwaysCarriesTheNachweisHilfeNotVerdictDisclaimer() {
        // D4 / Steve Jobs review: this exact framing must survive verbatim -- it is the whole
        // point of the banner, not decoration.
        val text = mittelverwendungsBannerText(2)
        assertTrue(text.contains("Nachweis-Hilfe"))
        assertTrue(text.contains("keine automatisierte Compliance-Entscheidung"))
        assertTrue(text.contains("Freie-Rücklage-Obergrenze"))
        assertTrue(text.contains("Kleinorganisationen-Ausnahme"))
        assertTrue(text.contains("Fortbestand der Gemeinnützigkeit"))
    }

    @Test
    fun useOfFundsPeriodCaption_showsBothFiscalYears() {
        assertEquals("Zeitraum: Geschäftsjahr 2025 bis 2026", useOfFundsPeriodCaption(2025, 2026))
    }

    @Test
    fun useOfFundsPeriodCaption_singleYearWindowShowsTheSameYearTwice() {
        assertEquals("Zeitraum: Geschäftsjahr 2026 bis 2026", useOfFundsPeriodCaption(2026, 2026))
    }

    @Test
    fun hasOverdueAmount_isTrueOnlyForAPositiveAmount() {
        assertTrue(hasOverdueAmount(1.0.toDecimal()))
        assertFalse(hasOverdueAmount(0.0.toDecimal()))
        // overdueAmount is documented as never negative, but the typed comparison must not
        // misbehave if it ever were.
        assertFalse(hasOverdueAmount((-1.0).toDecimal()))
    }
}
