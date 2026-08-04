package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.ReserveType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Accounting UI wave -- covers the pure, DOM-independent label/color functions in the shared
 * `AccountingLabels.kt` ([sphereLabel]/[sphereColor]/[reserveTypeLabel]/[reserveTypeColor]/
 * [donorCategoryLabel]/[donorCategoryColor]), same scope posture as [MeetingsScreenTest]/
 * [CommitteesScreenTest]. `LedgerScreen.kt` is the first (and currently only) consumer of this
 * shared file, hence this test lives alongside [LedgerScreenTest] rather than a later screen's
 * test file -- a later screen adding more coverage here is expected, not a conflict.
 */
class AccountingLabelsTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun sphereLabel_isNonBlankForEveryValue() {
        GemeinnuetzigkeitSphere.entries.forEach { sphere ->
            assertTrue(sphereLabel(sphere).isNotBlank(), "expected a non-blank label for $sphere")
        }
    }

    @Test
    fun sphereColor_isARealBootstrapHueForEveryValue() {
        GemeinnuetzigkeitSphere.entries.forEach { sphere ->
            val color = sphereColor(sphere)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $sphere, got \"$color\"")
        }
    }

    // The sphere where a Verein risks its tax-exempt status if mismanaged deliberately gets the
    // "pay attention" hue -- see `AccountingLabels.kt` KDoc.
    @Test
    fun sphereColor_wirtschaftlicherGeschaeftsbetriebIsWarning() {
        assertEquals("warning", sphereColor(GemeinnuetzigkeitSphere.WIRTSCHAFTLICHER_GESCHAEFTSBETRIEB))
    }

    @Test
    fun reserveTypeLabel_isNonBlankAndCarriesTheParagraphRefForEveryValue() {
        ReserveType.entries.forEach { type ->
            val label = reserveTypeLabel(type)
            assertTrue(label.isNotBlank(), "expected a non-blank label for $type")
            assertTrue(label.contains(type.paragraphRef), "expected label for $type to carry its own paragraphRef, got \"$label\"")
        }
    }

    @Test
    fun reserveTypeColor_isARealBootstrapHueForEveryValue() {
        ReserveType.entries.forEach { type ->
            val color = reserveTypeColor(type)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $type, got \"$color\"")
        }
    }

    // The statutory cap on FREIE_RUECKLAGE is deliberately not enforced by this codebase -- the
    // "warning" hue here is the same one used by its own inline caveat wherever it's discussed.
    @Test
    fun reserveTypeColor_freieRuecklageIsWarning() {
        assertEquals("warning", reserveTypeColor(ReserveType.FREIE_RUECKLAGE))
    }

    @Test
    fun donorCategoryLabel_isNonBlankForEveryValue() {
        DonorCategory.entries.forEach { category ->
            assertTrue(donorCategoryLabel(category).isNotBlank(), "expected a non-blank label for $category")
        }
    }

    @Test
    fun donorCategoryColor_isARealBootstrapHueForEveryValue() {
        DonorCategory.entries.forEach { category ->
            val color = donorCategoryColor(category)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $category, got \"$color\"")
        }
    }

    // The four categories the backend structurally always rejects for a political party at post
    // time (`ConflictException`) get "danger" as a pre-flight signal -- see `AccountingLabels.kt`
    // KDoc. Every other category is amount/aggregate-checked only, never structurally prohibited.
    @Test
    fun donorCategoryColor_structurallyProhibitedCategoriesAreDanger() {
        val structurallyProhibited =
            setOf(
                DonorCategory.PUBLIC_LAW_CORPORATION,
                DonorCategory.OVER_25_PERCENT_STATE_OWNED_COMPANY,
                DonorCategory.OTHER_PARTY_OR_PARLIAMENTARY_GROUP_ENTITY,
                DonorCategory.PROFESSIONAL_OR_TRADE_ASSOCIATION,
            )
        structurallyProhibited.forEach { category ->
            assertEquals("danger", donorCategoryColor(category), "expected \"danger\" for $category")
        }
        DonorCategory.entries.filter { it !in structurallyProhibited }.forEach { category ->
            assertTrue(donorCategoryColor(category) != "danger", "did not expect \"danger\" for $category")
        }
    }
}
