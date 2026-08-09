package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.PoliticianProfileStatus
import network.lapis.cloud.shared.domain.PoliticianRaterType
import network.lapis.cloud.shared.domain.PoliticianReactionValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LTR-Wirtschaft UI wave -- covers `PoliticianScreen.kt`'s pure, DOM-independent surface: the
 * label/color tables ([politicianProfileStatusLabel]/[politicianProfileStatusColor],
 * [politicianReactionValueLabel]/[politicianReactionValueColor],
 * [politicianRaterTypeLabel]/[politicianRaterTypeColor]) plus the wholesale-replace helper
 * [OrganizationSettingsDto.toInputWithPoliticianRankingEnabled] -- code review round 1's specific
 * call-out: a function whose entire job is "never silently drop/reset a field" is exactly the kind
 * of regression a one-line unit test catches on the next [OrganizationSettingsDto] field addition.
 * No rendering harness exists in this module (see [GovernanceAuthzUiTest] KDoc), so the
 * DOM-building `renderPoliticianScreen` etc. are out of scope here, same as every other screen's
 * `*ScreenTest.kt`.
 */
class PoliticianScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    // A fully-populated, non-default settings row -- every nullable field set, every boolean flag
    // deliberately at its non-default value, so a copy-bug (accidentally hardcoding a default
    // instead of forwarding the source field) cannot hide behind an already-default value.
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
            politicianRankingEnabled = false,
            auctionEnabled = true,
            auctionMaxValueLtr = 500.0.toDecimal(),
        )

    @Test
    fun toInputWithPoliticianRankingEnabled_flipsOnlyThatOneField() {
        val input = fullSettings.toInputWithPoliticianRankingEnabled(true)
        assertEquals(fullSettings.name, input.name)
        assertEquals(fullSettings.street, input.street)
        assertEquals(fullSettings.postalCode, input.postalCode)
        assertEquals(fullSettings.city, input.city)
        assertEquals(fullSettings.country, input.country)
        assertEquals(fullSettings.bankIban, input.bankIban)
        assertEquals(fullSettings.bankBic, input.bankBic)
        assertEquals(fullSettings.taxExemptionAuthority, input.taxExemptionAuthority)
        assertEquals(fullSettings.taxExemptionDate, input.taxExemptionDate)
        assertEquals(fullSettings.isPoliticalParty, input.isPoliticalParty)
        assertEquals(fullSettings.postalMailEnabled, input.postalMailEnabled)
        assertTrue(input.politicianRankingEnabled, "expected politicianRankingEnabled to be flipped to true")
    }

    @Test
    fun toInputWithPoliticianRankingEnabled_togglingFalseStillPreservesEveryOtherField() {
        val input = fullSettings.toInputWithPoliticianRankingEnabled(false)
        assertEquals(fullSettings.name, input.name)
        assertEquals(fullSettings.street, input.street)
        assertEquals(fullSettings.postalCode, input.postalCode)
        assertEquals(fullSettings.city, input.city)
        assertEquals(fullSettings.country, input.country)
        assertEquals(fullSettings.bankIban, input.bankIban)
        assertEquals(fullSettings.bankBic, input.bankBic)
        assertEquals(fullSettings.taxExemptionAuthority, input.taxExemptionAuthority)
        assertEquals(fullSettings.taxExemptionDate, input.taxExemptionDate)
        assertEquals(fullSettings.isPoliticalParty, input.isPoliticalParty)
        assertEquals(fullSettings.postalMailEnabled, input.postalMailEnabled)
        assertFalse(input.politicianRankingEnabled, "expected politicianRankingEnabled to be flipped to false")
    }

    @Test
    fun toInputWithPoliticianRankingEnabled_toleratesEveryNullableFieldBeingNull() {
        val minimalSettings =
            OrganizationSettingsDto(
                id = "org-2",
                name = "Minimalverein e.V.",
                street = null,
                postalCode = null,
                city = null,
                country = null,
                bankIban = null,
                bankBic = null,
                taxExemptionAuthority = null,
                taxExemptionDate = null,
            )
        val input = minimalSettings.toInputWithPoliticianRankingEnabled(true)
        assertEquals("Minimalverein e.V.", input.name)
        assertEquals(null, input.street)
        assertEquals(null, input.postalCode)
        assertEquals(null, input.city)
        assertEquals(null, input.country)
        assertEquals(null, input.bankIban)
        assertEquals(null, input.bankBic)
        assertEquals(null, input.taxExemptionAuthority)
        assertEquals(null, input.taxExemptionDate)
        assertFalse(input.isPoliticalParty)
        assertFalse(input.postalMailEnabled)
        assertTrue(input.politicianRankingEnabled)
    }

    @Test
    fun politicianProfileStatusLabel_isNonBlankForEveryValue() {
        PoliticianProfileStatus.entries.forEach { status ->
            assertTrue(politicianProfileStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun politicianProfileStatusColor_isARealBootstrapHueForEveryValue() {
        PoliticianProfileStatus.entries.forEach { status ->
            val color = politicianProfileStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun politicianReactionValueLabel_isNonBlankForEveryValue() {
        PoliticianReactionValue.entries.forEach { value ->
            assertTrue(politicianReactionValueLabel(value).isNotBlank(), "expected a non-blank label for $value")
        }
    }

    @Test
    fun politicianReactionValueColor_isARealBootstrapHueForEveryValue() {
        PoliticianReactionValue.entries.forEach { value ->
            val color = politicianReactionValueColor(value)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $value, got \"$color\"")
        }
    }

    @Test
    fun politicianRaterTypeLabel_isNonBlankForEveryValue() {
        PoliticianRaterType.entries.forEach { type ->
            assertTrue(politicianRaterTypeLabel(type).isNotBlank(), "expected a non-blank label for $type")
        }
    }

    @Test
    fun politicianRaterTypeColor_isARealBootstrapHueForEveryValue() {
        PoliticianRaterType.entries.forEach { type ->
            val color = politicianRaterTypeColor(type)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $type, got \"$color\"")
        }
    }
}
