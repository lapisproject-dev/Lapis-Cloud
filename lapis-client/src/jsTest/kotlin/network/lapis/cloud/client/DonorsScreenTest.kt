package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.DonationDuty
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.DonorType
import network.lapis.cloud.shared.domain.ExternalDonorDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Accounting UI wave -- covers the pure, DOM-independent helper functions local to
 * `DonorsScreen.kt` ([donorTypeLabel]/[donorTypeColor], [donationDutyLabel]/[donationDutyColor],
 * [donorAddressLine]), same scope posture as [CostCentersScreenTest]/[LedgerScreenTest] (no
 * DOM/rendering test harness exists in this module). `donorCategoryLabel`/`donorCategoryColor`
 * (`AccountingLabels.kt`, D9) are already covered by their own precedent usage in
 * `LedgerScreenTest`/`AccountingLabelsTest` -- both screens call the same public top-level
 * functions, so they are deliberately not re-tested here. `activeStatusBadge` (`StatusBadge.kt`)
 * is likewise already covered via its `LedgerScreen`/`CostCentersScreen` precedent usage.
 *
 * Design-Team-Welle 2026-09-18 (Nachmittag) added [filterExternalDonors] (the Spender-Live-Suche's
 * pure predicate) -- test cases mirror [LedgerScreenTest]'s own `filterLedgerAccounts_*` suite.
 */
class DonorsScreenTest {
    @Test
    fun donorTypeLabel_member() {
        assertEquals("Mitglied", donorTypeLabel(DonorType.MEMBER))
    }

    @Test
    fun donorTypeLabel_external() {
        assertEquals("Extern", donorTypeLabel(DonorType.EXTERNAL))
    }

    @Test
    fun donorTypeColor_isDistinctPerLiteral() {
        assertEquals("primary", donorTypeColor(DonorType.MEMBER))
        assertEquals("secondary", donorTypeColor(DonorType.EXTERNAL))
    }

    @Test
    fun donationDutyLabel_coversAllThreeDuties() {
        assertEquals("Weiterleitungspflicht", donationDutyLabel(DonationDuty.ANONYMOUS_FORWARDING_REQUIRED))
        assertEquals("Unverzügliche Meldepflicht", donationDutyLabel(DonationDuty.PROMPT_BUNDESTAG_REPORT_REQUIRED))
        assertEquals(
            "Offenlegungspflicht (Rechenschaftsbericht)",
            donationDutyLabel(DonationDuty.ANNUAL_DISCLOSURE_REQUIRED),
        )
    }

    /** D9: all three duty literals deliberately share one hue -- additive, not a severity ranking. */
    @Test
    fun donationDutyColor_allThreeLiteralsShareWarningHue() {
        val colors = DonationDuty.entries.map { donationDutyColor(it) }
        assertEquals(listOf("warning", "warning", "warning"), colors)
    }

    @Test
    fun donorAddressLine_fullAddress_joinsStreetCityCountry() {
        val donor = donorFixture(street = "Musterstraße 1", postalCode = "38100", city = "Braunschweig", country = "Deutschland")
        assertEquals("Musterstraße 1, 38100 Braunschweig, Deutschland", donorAddressLine(donor))
    }

    @Test
    fun donorAddressLine_onlyCity_omitsMissingParts() {
        val donor = donorFixture(street = null, postalCode = "38100", city = "Braunschweig", country = null)
        assertEquals("38100 Braunschweig", donorAddressLine(donor))
    }

    @Test
    fun donorAddressLine_noAddressAtAll_saysSoPlainly() {
        val donor = donorFixture(street = null, postalCode = null, city = null, country = null)
        assertEquals("Keine Adresse hinterlegt", donorAddressLine(donor))
    }

    private fun donorFixture(
        street: String?,
        postalCode: String?,
        city: String?,
        country: String?,
    ): ExternalDonorDto =
        ExternalDonorDto(
            id = "donor-1",
            displayName = "Max Mustermann",
            donorCategory = DonorCategory.GERMAN_NATURAL_PERSON,
            street = street,
            postalCode = postalCode,
            city = city,
            country = country,
            active = true,
        )

    // ============================================================================================
    // Spender-Live-Suche (Design-Team-Welle 2026-09-18, Nachmittag)
    // ============================================================================================

    private fun donor(
        id: String,
        displayName: String,
    ) = ExternalDonorDto(
        id = id,
        displayName = displayName,
        donorCategory = DonorCategory.GERMAN_NATURAL_PERSON,
        street = null,
        postalCode = null,
        city = null,
        country = null,
        active = true,
    )

    private val donors =
        listOf(
            donor("donor-1", "Max Mustermann"),
            donor("donor-2", "Schmidt GmbH"),
            donor("donor-3", "Erika Musterfrau"),
            donor("donor-4", "Café Vernunft e.V."),
        )

    @Test
    fun filterExternalDonors_blankQueryKeepsEverything() {
        assertEquals(donors, filterExternalDonors(donors, ""))
        assertEquals(donors, filterExternalDonors(donors, "   "))
    }

    @Test
    fun filterExternalDonors_matchesNamePrefix() {
        val filtered = filterExternalDonors(donors, "Erika")
        assertEquals(listOf("donor-3"), filtered.map { it.id })
    }

    @Test
    fun filterExternalDonors_matchesSubstringInMiddleOfName() {
        // Teiltreffer in der Mitte des Namens, nicht nur am Anfang.
        val filtered = filterExternalDonors(donors, "mbH")
        assertEquals(listOf("donor-2"), filtered.map { it.id })
    }

    @Test
    fun filterExternalDonors_isCaseInsensitive() {
        assertEquals(listOf("donor-1"), filterExternalDonors(donors, "mustermann").map { it.id })
        assertEquals(listOf("donor-1"), filterExternalDonors(donors, "MUSTERMANN").map { it.id })
    }

    @Test
    fun filterExternalDonors_trimsSurroundingWhitespace() {
        assertEquals(listOf("donor-4"), filterExternalDonors(donors, "  Vernunft ").map { it.id })
    }

    @Test
    fun filterExternalDonors_noMatchYieldsEmptyList() {
        assertTrue(filterExternalDonors(donors, "Nichts passt").isEmpty())
    }

    @Test
    fun filterExternalDonors_preservesInputOrder() {
        val sorted = donors.sortedBy { it.displayName }
        val filtered = filterExternalDonors(sorted, "M")
        assertEquals(listOf("donor-3", "donor-1", "donor-2"), filtered.map { it.id })
    }
}
