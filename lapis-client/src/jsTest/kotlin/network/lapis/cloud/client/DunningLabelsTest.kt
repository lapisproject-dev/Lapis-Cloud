package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.ContributionStatus
import network.lapis.cloud.shared.domain.DunningNoticeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Client-UI wave for GitHub Issue #5 -- covers the pure, DOM-independent label/color functions in
 * `DunningLabels.kt`, same posture as [SepaLabelsTest]/[AccountingLabelsTest].
 */
class DunningLabelsTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun dunningNoticeStatusLabel_isNonBlankAndDistinctForEveryValue() {
        val labels = DunningNoticeStatus.entries.map { dunningNoticeStatusLabel(it) }
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size, "expected pairwise-distinct labels, got $labels")
    }

    @Test
    fun dunningNoticeStatusColor_isARealBootstrapHueForEveryValue() {
        DunningNoticeStatus.entries.forEach { status ->
            assertTrue(dunningNoticeStatusColor(status) in semanticColors)
        }
    }

    @Test
    fun contributionStatusLabel_isNonBlankAndDistinctForEveryValue() {
        val labels = ContributionStatus.entries.map { contributionStatusLabel(it) }
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size, "expected pairwise-distinct labels, got $labels")
    }

    @Test
    fun contributionStatusColor_isARealBootstrapHueForEveryValue() {
        ContributionStatus.entries.forEach { status ->
            assertTrue(contributionStatusColor(status) in semanticColors)
        }
    }
}
