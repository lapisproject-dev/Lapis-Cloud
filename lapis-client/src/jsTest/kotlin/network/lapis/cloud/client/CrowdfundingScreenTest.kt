package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.CrowdfundingProjectStatus
import network.lapis.cloud.shared.domain.CrowdfundingReactionValue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * LTR-Wirtschaft UI wave -- covers the pure, DOM-independent label/color tables in
 * `CrowdfundingScreen.kt` ([crowdfundingProjectStatusLabel]/[crowdfundingProjectStatusColor],
 * [crowdfundingReactionValueLabel]/[crowdfundingReactionValueColor]), same scope posture as
 * [AccountingLabelsTest]/[AuctionScreenTest]. No rendering harness exists in this module (see
 * [GovernanceAuthzUiTest] KDoc), so the DOM-building `renderCrowdfundingScreen` etc. are out of
 * scope here, same as every other screen's `*ScreenTest.kt`.
 */
class CrowdfundingScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun crowdfundingProjectStatusLabel_isNonBlankForEveryValue() {
        CrowdfundingProjectStatus.entries.forEach { status ->
            assertTrue(crowdfundingProjectStatusLabel(status).isNotBlank(), "expected a non-blank label for $status")
        }
    }

    @Test
    fun crowdfundingProjectStatusColor_isARealBootstrapHueForEveryValue() {
        CrowdfundingProjectStatus.entries.forEach { status ->
            val color = crowdfundingProjectStatusColor(status)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $status, got \"$color\"")
        }
    }

    @Test
    fun crowdfundingReactionValueLabel_isNonBlankForEveryValue() {
        CrowdfundingReactionValue.entries.forEach { value ->
            assertTrue(crowdfundingReactionValueLabel(value).isNotBlank(), "expected a non-blank label for $value")
        }
    }

    @Test
    fun crowdfundingReactionValueColor_isARealBootstrapHueForEveryValue() {
        CrowdfundingReactionValue.entries.forEach { value ->
            val color = crowdfundingReactionValueColor(value)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $value, got \"$color\"")
        }
    }
}
