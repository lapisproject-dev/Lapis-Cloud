package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.PostingSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Accounting UI wave -- covers only the pure, DOM-independent label/color functions local to
 * `LedgerScreen.kt` ([ledgerAccountTypeLabel]/[ledgerAccountTypeColor]/[postingSideLabel]/
 * [postingSideColor]/[journalEntryStatusLabel]/[journalEntryStatusColor]), same scope posture as
 * [MeetingsScreenTest]/[CommitteesScreenTest] (no DOM/rendering test harness exists in this
 * module). [GemeinnuetzigkeitSphere]/[ReserveType]/[DonorCategory] labels live in the shared
 * `AccountingLabels.kt` and are covered by [AccountingLabelsTest] instead.
 *
 * Welle V1.4.5.2 "DATEV-Format-Export" added the [toInputWithPaymentAccountMapping] tests below --
 * same "never silently drop/reset a field" regression-coverage reasoning as
 * [PoliticianScreenTest]'s own `toInputWithPoliticianRankingEnabled` tests.
 */
class LedgerScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    private val fullSettings =
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
            eventIncomeSphere = GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB,
            // Pre-existing value the helper must carry through UNCHANGED -- this test call always
            // passes the SAME account ids/sphere back in, exercising only the two new DATEV fields.
            datevBeraterNummer = 1001,
            datevMandantNummer = 42,
        )

    // Unlike PoliticianScreen.kt's toInputWithPoliticianRankingEnabled (which forwards every
    // OTHER field unchanged from `this` and flips exactly one flag), toInputWithPaymentAccountMapping
    // takes datevBeraterNummer/datevMandantNummer as explicit override parameters, same tier as
    // paymentBankAccountId/eventIncomeSphere -- they are edited via their OWN text fields on this
    // same screen/section (see renderPaymentAccountMappingSection), not silently carried over from
    // the already-loaded settings row. These tests pin that both values reach OrganizationSettingsInput
    // unchanged, including through a null round-trip (leaving both unconfigured is a valid save).
    @Test
    fun toInputWithPaymentAccountMapping_passesDatevBeraterAndMandantNummerThrough() {
        val input =
            fullSettings.toInputWithPaymentAccountMapping(
                paymentBankAccountId = "bank-1",
                paymentFeeAccountId = "fee-1",
                contributionIncomeAccountId = "income-1",
                donationIncomeAccountId = "donation-1",
                eventIncomeAccountId = "event-1",
                eventIncomeSphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                datevBeraterNummer = 2002,
                datevMandantNummer = 7,
                travelExpenseAccountId = "travel-1",
            )
        assertEquals(fullSettings.name, input.name)
        assertEquals(fullSettings.isPoliticalParty, input.isPoliticalParty)
        assertEquals(fullSettings.politicianRankingEnabled, input.politicianRankingEnabled)
        assertEquals("bank-1", input.paymentBankAccountId)
        assertEquals(GemeinnuetzigkeitSphere.ZWECKBETRIEB, input.eventIncomeSphere)
        assertEquals(2002, input.datevBeraterNummer)
        assertEquals(7, input.datevMandantNummer)
        assertEquals("travel-1", input.travelExpenseAccountId)
    }

    @Test
    fun toInputWithPaymentAccountMapping_toleratesNullDatevNumbers() {
        val input =
            fullSettings.toInputWithPaymentAccountMapping(
                paymentBankAccountId = null,
                paymentFeeAccountId = null,
                contributionIncomeAccountId = null,
                donationIncomeAccountId = null,
                eventIncomeAccountId = null,
                eventIncomeSphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                datevBeraterNummer = null,
                datevMandantNummer = null,
                travelExpenseAccountId = null,
            )
        assertEquals(null, input.datevBeraterNummer)
        assertEquals(null, input.datevMandantNummer)
        assertEquals(null, input.travelExpenseAccountId)
    }

    // Welle V1.4.11 "Reisekostenabrechnung" -- travelExpenseAccountId reaches
    // OrganizationSettingsInput unchanged, same "own text field, not silently carried over"
    // treatment the DATEV fields above already establish, and every OTHER field stays untouched.
    @Test
    fun toInputWithPaymentAccountMapping_passesTravelExpenseAccountIdThroughAndLeavesOtherFieldsUntouched() {
        val input =
            fullSettings.toInputWithPaymentAccountMapping(
                paymentBankAccountId = "bank-1",
                paymentFeeAccountId = "fee-1",
                contributionIncomeAccountId = "income-1",
                donationIncomeAccountId = "donation-1",
                eventIncomeAccountId = "event-1",
                eventIncomeSphere = GemeinnuetzigkeitSphere.ZWECKBETRIEB,
                datevBeraterNummer = 2002,
                datevMandantNummer = 7,
                travelExpenseAccountId = "travel-1",
            )
        assertEquals("travel-1", input.travelExpenseAccountId)
        assertEquals("bank-1", input.paymentBankAccountId)
        assertEquals("fee-1", input.paymentFeeAccountId)
        assertEquals("income-1", input.contributionIncomeAccountId)
        assertEquals("donation-1", input.donationIncomeAccountId)
        assertEquals("event-1", input.eventIncomeAccountId)
    }

    // Review-Fund (2026-09, MINOR): `.toIntOrNull()` alone collapsed "left empty" and "typo'd
    // garbage" into the same `null`, silently resetting an already-configured Beraternummer/
    // Mandantennummer on save with no error shown. These tests pin [parseDatevNumberInput]'s three-
    // way distinction directly, same testability reasoning as the toInputWithPaymentAccountMapping
    // tests above.
    @Test
    fun parseDatevNumberInput_blankOrEmptyIsEmptyNeverInvalid() {
        assertEquals(DatevNumberInput.Empty, parseDatevNumberInput(""))
        assertEquals(DatevNumberInput.Empty, parseDatevNumberInput("   "))
        assertEquals(DatevNumberInput.Empty, parseDatevNumberInput(null))
    }

    @Test
    fun parseDatevNumberInput_wholeNumberIsValidAndTrimmed() {
        assertEquals(DatevNumberInput.Valid(1001), parseDatevNumberInput("1001"))
        assertEquals(DatevNumberInput.Valid(1001), parseDatevNumberInput("  1001  "))
    }

    @Test
    fun parseDatevNumberInput_garbageIsInvalidNeverSilentlyTreatedAsEmpty() {
        // "1OO1" -- letter O instead of digit 0, the exact typo scenario the review finding named.
        assertEquals(DatevNumberInput.Invalid, parseDatevNumberInput("1OO1"))
        assertEquals(DatevNumberInput.Invalid, parseDatevNumberInput("12.5"))
        assertEquals(DatevNumberInput.Invalid, parseDatevNumberInput("abc"))
    }

    @Test
    fun ledgerAccountTypeLabel_isNonBlankForEveryValue() {
        LedgerAccountType.entries.forEach { type ->
            assertTrue(ledgerAccountTypeLabel(type).isNotBlank(), "expected a non-blank label for $type")
        }
    }

    @Test
    fun ledgerAccountTypeColor_isARealBootstrapHueForEveryValue() {
        LedgerAccountType.entries.forEach { type ->
            val color = ledgerAccountTypeColor(type)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $type, got \"$color\"")
        }
    }

    @Test
    fun postingSideLabel_isSollHabenNeverDebitCredit() {
        // D8: literal "Soll"/"Haben" everywhere, never "Debit"/"Credit" or the raw enum names.
        assertEquals("Soll", postingSideLabel(PostingSide.DEBIT))
        assertEquals("Haben", postingSideLabel(PostingSide.CREDIT))
    }

    @Test
    fun postingSideColor_isARealBootstrapHueForEveryValue() {
        PostingSide.entries.forEach { side ->
            val color = postingSideColor(side)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $side, got \"$color\"")
        }
    }

    @Test
    fun journalEntryStatusLabel_isNonBlankForEveryValue() {
        JournalEntryStatus.entries.forEach { status ->
            assertTrue(journalEntryStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun journalEntryStatusColor_isARealBootstrapHueForEveryValue() {
        JournalEntryStatus.entries.forEach { status ->
            val color = journalEntryStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    // Design decision D1: DRAFT = "warning" (not-yet-final, matches the existing POSTPONED-shaped
    // convention), POSTED = "success" (settled/terminal-good, matches HELD/RESOLVED/ADOPTED).
    @Test
    fun journalEntryStatusColor_draftIsWarningPostedIsSuccess() {
        assertEquals("warning", journalEntryStatusColor(JournalEntryStatus.DRAFT))
        assertEquals("success", journalEntryStatusColor(JournalEntryStatus.POSTED))
    }

    @Test
    fun journalEntryStatusLabel_draftIsEntwurfPostedIsGebucht() {
        assertEquals("Entwurf", journalEntryStatusLabel(JournalEntryStatus.DRAFT))
        assertEquals("Gebucht", journalEntryStatusLabel(JournalEntryStatus.POSTED))
    }
}
