package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.CrmContactDto
import network.lapis.cloud.shared.domain.CrmContactType
import network.lapis.cloud.shared.domain.CrmInteractionKind
import network.lapis.cloud.shared.domain.CrmLawfulBasis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Welle V1.4.2 "Interessenten-/Sympathisanten-CRM" -- covers the pure, DOM-independent label/color
 * functions in `CrmLabels.kt`, same scope posture as [AccountingLabelsTest].
 */
class CrmLabelsTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun crmContactTypeLabel_isNonBlankForEveryValue() {
        CrmContactType.entries.forEach { type ->
            assertTrue(crmContactTypeLabel(type).isNotBlank(), "expected a non-blank label for $type")
        }
    }

    @Test
    fun crmContactTypeColor_isARealBootstrapHueForEveryValue() {
        CrmContactType.entries.forEach { type ->
            val color = crmContactTypeColor(type)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $type, got \"$color\"")
        }
    }

    @Test
    fun crmLawfulBasisLabel_isNonBlankForEveryValue() {
        CrmLawfulBasis.entries.forEach { basis ->
            assertTrue(crmLawfulBasisLabel(basis).isNotBlank(), "expected a non-blank label for $basis")
        }
    }

    @Test
    fun crmInteractionKindLabel_isNonBlankForEveryValue() {
        CrmInteractionKind.entries.forEach { kind ->
            assertTrue(crmInteractionKindLabel(kind).isNotBlank(), "expected a non-blank label for $kind")
        }
    }

    @Test
    fun crmInteractionKindIcon_isANonBlankFontAwesomeClassForEveryValue() {
        CrmInteractionKind.entries.forEach { kind ->
            val icon = crmInteractionKindIcon(kind)
            assertTrue(icon.startsWith("fas fa-"), "expected a FontAwesome class for $kind, got \"$icon\"")
        }
    }

    @Test
    fun crmContactExportUrl_buildsTheExactHttpRoutePath() {
        assertEquals("/api/dsgvo/crm-contacts/contact-1/export", crmContactExportUrl("contact-1"))
    }

    @Test
    fun crmContactAddressLine_joinsStreetPostalCodeCityCountry() {
        assertEquals(
            "Musterstr. 1, 38100 Braunschweig, Deutschland",
            crmContactAddressLine(
                contactFixture(street = "Musterstr. 1", postalCode = "38100", city = "Braunschweig", country = "Deutschland"),
            ),
        )
    }

    @Test
    fun crmContactAddressLine_isHonestWhenNoAddressFieldIsSet() {
        assertEquals(
            "Keine Adresse hinterlegt",
            crmContactAddressLine(contactFixture(street = null, postalCode = null, city = null, country = null)),
        )
    }

    private fun contactFixture(
        street: String?,
        postalCode: String?,
        city: String?,
        country: String?,
    ) = CrmContactDto(
        id = "contact-1",
        displayName = "Test",
        email = null,
        phone = null,
        street = street,
        postalCode = postalCode,
        city = city,
        country = country,
        contactType = CrmContactType.INTERESSENT,
        lawfulBasis = CrmLawfulBasis.LEGITIMATE_INTEREST,
        consentSource = null,
        consentGivenAt = null,
        consentWithdrawnAt = null,
        externalDonorId = null,
        memberId = null,
        createdAt = LocalDateTime(2026, 1, 1, 0, 0),
        createdBy = "admin-1",
        lastInteractionAt = null,
        retentionReviewDueAt = LocalDateTime(2028, 1, 1, 0, 0),
        archivedAt = null,
        mayReceiveEmail = false,
    )
}
