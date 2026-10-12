package network.lapis.cloud.client

import dev.kilua.rpc.types.toDouble
import network.lapis.cloud.shared.domain.TravelExpenseContactDto
import network.lapis.cloud.shared.domain.TravelExpenseDefaults
import network.lapis.cloud.shared.domain.TravelExpenseRatesDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Welle V1.9.122 -- the pure mapping between the settings form (text) and `TravelExpenseSettingsInput` (whole cents). */
class TravelExpenseSettingsInputMappingTest {
    private val rates =
        TravelExpenseRatesDto(
            rateSet = TravelExpenseDefaults.pdvTemplate(mileageCentsPerKm = 30),
            contact = TravelExpenseContactDto(name = "Erika", email = "erika@example.org"),
            advanceAccountId = "acc-1",
        )

    @Test
    fun formValues_showWhatTheServerHolds() {
        val values = travelExpenseSettingsFormValues(rates)
        assertEquals("8", values.tier1Hours)
        assertEquals("6,00", values.tier1Eur)
        assertEquals("28,00", values.tier3Eur)
        assertEquals("0,30", values.mileageEur)
        assertEquals("0,02", values.passengerEur)
        assertEquals("Erika", values.contactName)
        assertEquals("acc-1", values.advanceAccountId)
        assertEquals("", values.donationWaiverAccountId)
    }

    @Test
    fun roundTrip_formValuesBecomeTheSameWholeCentInput() {
        val input = assertNotNull(travelExpenseSettingsInput(travelExpenseSettingsFormValues(rates)))
        assertEquals(listOf(8, 14, 24), input.tierHours)
        assertEquals(listOf(600L, 1200L, 2800L), input.tierCents)
        assertEquals(20, input.breakfastPercent)
        assertEquals(30L, input.mileageCentsPerKm)
        assertEquals(2L, input.passengerCentsPerKm)
        assertEquals("erika@example.org", input.contact.email)
        assertNull(input.contact.phone, "a blank contact field is null, never an empty string")
        assertNull(input.donationWaiverAccountId, "a blank account stays unset")
    }

    @Test
    fun blankMileageMeansNotConfigured_blankLegacyRateMeansNone() {
        val values = travelExpenseSettingsFormValues(rates).copy(mileageEur = "", perDiemLegacyEur = "")
        val input = assertNotNull(travelExpenseSettingsInput(values))
        assertNull(input.mileageCentsPerKm)
        assertNull(input.perDiemLegacyRate)
        val withLegacy = assertNotNull(travelExpenseSettingsInput(values.copy(perDiemLegacyEur = "14,25")))
        assertEquals(14.25, withLegacy.perDiemLegacyRate?.toDouble())
    }

    @Test
    fun anUnparseableNumberMapsToNull_neverToASilentZero() {
        val base = travelExpenseSettingsFormValues(rates)
        assertNull(travelExpenseSettingsInput(base.copy(tier1Hours = "acht")))
        assertNull(travelExpenseSettingsInput(base.copy(tier2Eur = "12,345")))
        assertNull(travelExpenseSettingsInput(base.copy(lunchPercent = "")))
        assertNull(travelExpenseSettingsInput(base.copy(mileageEur = "abc")))
        assertNull(travelExpenseSettingsInput(base.copy(passengerEur = "-1")))
    }

    @Test
    fun pdvTemplate_replacesOnlyTiersPercentagesAndPassengerRate() {
        val changed =
            travelExpenseSettingsFormValues(rates).copy(
                tier1Hours = "6",
                tier3Eur = "30,00",
                lunchPercent = "50",
                passengerEur = "0,10",
                mileageEur = "0,38",
                contactName = "Max",
                advanceAccountId = "acc-9",
            )
        val template = travelExpensePdvTemplateValues(changed)
        assertEquals("8", template.tier1Hours)
        assertEquals("28,00", template.tier3Eur)
        assertEquals("40", template.lunchPercent)
        assertEquals("0,02", template.passengerEur)
        // untouched: the template has no opinion on these
        assertEquals("0,38", template.mileageEur)
        assertEquals("Max", template.contactName)
        assertEquals("acc-9", template.advanceAccountId)
    }

    @Test
    fun fieldChecks_mirrorTheServerRanges() {
        assertEquals(FieldCheck.Ok, travelExpenseMileageRateCheck(""))
        assertEquals(FieldCheck.Ok, travelExpenseMileageRateCheck("0,30"))
        assertEquals(FieldCheck.Ok, travelExpenseMileageRateCheck("1,00"))
        assertTrue(travelExpenseMileageRateCheck("1,01") is FieldCheck.Invalid)
        assertTrue(travelExpenseMileageRateCheck("0") is FieldCheck.Invalid)
        assertEquals(FieldCheck.Ok, travelExpensePassengerRateCheck("0,20"))
        assertTrue(travelExpensePassengerRateCheck("0,21") is FieldCheck.Invalid)
        assertEquals(FieldCheck.Ok, travelExpensePhoneCheck(""))
        assertEquals(FieldCheck.Ok, travelExpensePhoneCheck("+49 (531) 12 34-5"))
        assertTrue(travelExpensePhoneCheck("call me") is FieldCheck.Invalid)
    }
}
