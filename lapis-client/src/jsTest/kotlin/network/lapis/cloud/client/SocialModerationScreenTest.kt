package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.SocialPostErasureStatus
import network.lapis.cloud.shared.domain.SocialPostReportCategory
import network.lapis.cloud.shared.domain.SocialPostReportStatus
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Welle V1.1.5 -- covers the pure, DOM-independent label/color tables in `SocialModerationScreen.kt`,
 * same scope posture as [SocialNetworkScreenTest]/[DsgvoRightsScreenTest]. No rendering harness
 * exists in this module, so `renderSocialModerationScreen` itself is out of scope here.
 */
class SocialModerationScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark", "light")

    @Test
    fun socialPostReportCategoryLabel_isNonBlankForEveryValue() {
        SocialPostReportCategory.entries.forEach { category ->
            assertTrue(socialPostReportCategoryLabel(category).isNotBlank(), "expected a non-blank label for $category")
        }
    }

    @Test
    fun socialPostReportStatusLabel_isNonBlankForEveryValue() {
        SocialPostReportStatus.entries.forEach { status ->
            assertTrue(socialPostReportStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun socialPostReportStatusColor_isARealBootstrapHueForEveryValue() {
        SocialPostReportStatus.entries.forEach { status ->
            val color = socialPostReportStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun socialPostErasureStatusLabel_isNonBlankForEveryValue() {
        SocialPostErasureStatus.entries.forEach { status ->
            assertTrue(socialPostErasureStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun socialPostErasureStatusColor_isARealBootstrapHueForEveryValue() {
        SocialPostErasureStatus.entries.forEach { status ->
            val color = socialPostErasureStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }
}
