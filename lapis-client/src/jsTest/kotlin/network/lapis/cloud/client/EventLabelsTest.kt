package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.EventStatus
import network.lapis.cloud.shared.domain.EventVisibility
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Welle V1.4.3.x "Veranstaltungen: BOARD/ADMIN-Verwaltungsoberfläche" -- covers the pure,
 * DOM-independent label/color functions in `EventLabels.kt`, same scope posture as [CrmLabelsTest].
 * `dark`/`info` are added to `semanticColors` alongside `CrmLabelsTest`'s own seven -- both are real
 * Bootstrap 5.3.8 hues (see `StatusBadge.kt` KDoc "only offers eight semantic hues"), just not ones
 * `CrmLabels.kt`'s own tables happened to use.
 */
class EventLabelsTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark", "light")

    @Test
    fun eventStatusLabel_isNonBlankForEveryValue() {
        EventStatus.entries.forEach { status ->
            assertTrue(eventStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun eventStatusColor_isARealBootstrapHueForEveryValue() {
        EventStatus.entries.forEach { status ->
            val color = eventStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun eventVisibilityLabel_isNonBlankForEveryValue() {
        EventVisibility.entries.forEach { visibility ->
            assertTrue(eventVisibilityLabel(visibility).isNotBlank(), "expected a non-blank label for $visibility")
        }
    }

    @Test
    fun eventVisibilityColor_isARealBootstrapHueForEveryValue() {
        EventVisibility.entries.forEach { visibility ->
            val color = eventVisibilityColor(visibility)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $visibility, got \"$color\"")
        }
    }
}
