package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.VatFilingPeriodicity
import network.lapis.cloud.shared.domain.VatNotApplicableReason
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

    // Welle V1.4.13 "USt-Voranmeldung" -- pure helper coverage.
    @Test
    fun vatNotApplicableText_isDistinctPerReasonAndNamesTheRightRule() {
        val vatDisabled = vatNotApplicableText(VatNotApplicableReason.VAT_DISABLED)
        val kleinunternehmer = vatNotApplicableText(VatNotApplicableReason.KLEINUNTERNEHMER)
        assertTrue(vatDisabled.contains("nicht aktiviert"))
        assertTrue(kleinunternehmer.contains("Kleinunternehmer"))
        assertTrue(kleinunternehmer.contains("§ 19 UStG"))
        assertTrue(vatDisabled != kleinunternehmer)
    }

    @Test
    fun vatBalanceLabel_zahllastForPositive_erstattungForNegative_keineForZero() {
        // Not assertEquals against the literal German word -- the test environment's `tr()` stub
        // prefixes untranslated keys with a marker (same reason mittelverwendungsBannerText's own
        // tests above use `.contains(...)`, not exact equality).
        assertTrue(vatBalanceLabel(19.0.toDecimal()).contains("Zahllast"))
        assertTrue(vatBalanceLabel((-19.0).toDecimal()).contains("Erstattungsanspruch"))
        assertTrue(vatBalanceLabel(0.0.toDecimal()).contains("Keine Zahllast"))
    }

    @Test
    fun vatFilingPeriodicityText_isNonBlankAndDistinctForEveryValue() {
        val texts = VatFilingPeriodicity.entries.map { vatFilingPeriodicityText(it) }
        texts.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(texts.size, texts.toSet().size, "expected a distinct text per VatFilingPeriodicity value")
    }

    @Test
    fun vatFilingPeriodicityText_unknownNamesThePriorYearCoverageGap() {
        assertTrue(vatFilingPeriodicityText(VatFilingPeriodicity.UNKNOWN).contains("Vorjahr"))
    }

    // Review MINOR fix (V1.4.13 follow-up): the Kleinunternehmer checkbox wired in
    // renderVatGateSummary saves via this wholesale-replace helper, same "never silently drop/reset
    // a field" regression-coverage reasoning as LedgerScreenTest's toInputWithPaymentAccountMapping
    // tests and PoliticianScreenTest's toInputWithPoliticianRankingEnabled tests -- both of which
    // had exactly this bug for isKleinunternehmer itself until this same review round.
    private val fullOrganizationSettings =
        OrganizationSettingsDto(
            id = "org-1",
            name = "Verein Testverein e.V.",
            street = "Vereinsstrasse 1",
            postalCode = "38100",
            city = "Braunschweig",
            country = "Deutschland",
            bankIban = "DE02120300000000202051",
            bankBic = "BYLADEM1001",
            taxExemptionAuthority = "Finanzamt Braunschweig-Wilhelmstrasse",
            taxExemptionDate = LocalDate(2025, 1, 15),
            isPoliticalParty = true,
            postalMailEnabled = true,
            politicianRankingEnabled = true,
            paymentBankAccountId = "account-bank-1",
            paymentFeeAccountId = "account-fee-1",
            contributionIncomeAccountId = "account-income-1",
            donationIncomeAccountId = "account-donation-1",
            eventIncomeAccountId = "account-event-1",
            eventIncomeSphere = GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB,
            datevBeraterNummer = 1001,
            datevMandantNummer = 42,
            travelExpenseAccountId = "account-travel-expense-1",
            volunteerAllowanceAccountId = "account-volunteer-allowance-1",
            isKleinunternehmer = false,
        )

    @Test
    fun toInputWithKleinunternehmerFlag_flipsOnlyThatOneFieldToTrue() {
        val input = fullOrganizationSettings.toInputWithKleinunternehmerFlag(true)
        assertEquals(fullOrganizationSettings.name, input.name)
        assertEquals(fullOrganizationSettings.isPoliticalParty, input.isPoliticalParty)
        assertEquals(fullOrganizationSettings.politicianRankingEnabled, input.politicianRankingEnabled)
        assertEquals(fullOrganizationSettings.paymentBankAccountId, input.paymentBankAccountId)
        assertEquals(fullOrganizationSettings.paymentFeeAccountId, input.paymentFeeAccountId)
        assertEquals(fullOrganizationSettings.contributionIncomeAccountId, input.contributionIncomeAccountId)
        assertEquals(fullOrganizationSettings.donationIncomeAccountId, input.donationIncomeAccountId)
        assertEquals(fullOrganizationSettings.eventIncomeAccountId, input.eventIncomeAccountId)
        assertEquals(fullOrganizationSettings.eventIncomeSphere, input.eventIncomeSphere)
        assertEquals(fullOrganizationSettings.datevBeraterNummer, input.datevBeraterNummer)
        assertEquals(fullOrganizationSettings.datevMandantNummer, input.datevMandantNummer)
        assertEquals(fullOrganizationSettings.travelExpenseAccountId, input.travelExpenseAccountId)
        assertEquals(fullOrganizationSettings.volunteerAllowanceAccountId, input.volunteerAllowanceAccountId)
        assertTrue(input.isKleinunternehmer, "expected isKleinunternehmer to be flipped to true")
    }

    @Test
    fun toInputWithKleinunternehmerFlag_flipsOnlyThatOneFieldToFalse() {
        val kleinunternehmerSettings = fullOrganizationSettings.copy(isKleinunternehmer = true)
        val input = kleinunternehmerSettings.toInputWithKleinunternehmerFlag(false)
        assertEquals(kleinunternehmerSettings.name, input.name)
        assertEquals(kleinunternehmerSettings.paymentBankAccountId, input.paymentBankAccountId)
        assertEquals(kleinunternehmerSettings.travelExpenseAccountId, input.travelExpenseAccountId)
        assertEquals(kleinunternehmerSettings.volunteerAllowanceAccountId, input.volunteerAllowanceAccountId)
        assertFalse(input.isKleinunternehmer, "expected isKleinunternehmer to be flipped to false")
    }
}
