package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.SocialPostVisibility
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Soziales Netzwerk, Welle V1.1.1 -- covers the pure, DOM-independent label/color table in
 * `SocialNetworkScreen.kt` ([socialPostVisibilityLabel]/[socialPostVisibilityColor]), same scope
 * posture as [CrowdfundingScreenTest]/[AuctionScreenTest]. No rendering harness exists in this
 * module (see [GovernanceAuthzUiTest] KDoc), so the DOM-building `renderSocialNetworkScreen` etc.
 * are out of scope here, same as every other screen's `*ScreenTest.kt`.
 */
class SocialNetworkScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun socialPostVisibilityLabel_isNonBlankForEveryValue() {
        SocialPostVisibility.entries.forEach { visibility ->
            assertTrue(
                socialPostVisibilityLabel(visibility).isNotBlank(),
                "expected a non-blank label for $visibility",
            )
        }
    }

    @Test
    fun socialPostVisibilityColor_isARealBootstrapHueForEveryValue() {
        SocialPostVisibility.entries.forEach { visibility ->
            val color = socialPostVisibilityColor(visibility)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $visibility, got \"$color\"")
        }
    }
}
