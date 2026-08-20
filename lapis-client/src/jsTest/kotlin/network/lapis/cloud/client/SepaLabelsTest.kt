package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.SepaDebitBatchStatus
import network.lapis.cloud.shared.domain.SepaDebitExclusionReason
import network.lapis.cloud.shared.domain.SepaDebitItemStatus
import network.lapis.cloud.shared.domain.SepaMandateStatus
import network.lapis.cloud.shared.domain.SepaReturnReason
import network.lapis.cloud.shared.domain.SepaReturnReasonSets
import network.lapis.cloud.shared.domain.SepaSequenceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V1.2.2 SEPA-Client-UI wave -- covers the pure, DOM-independent label/color functions in
 * `SepaLabels.kt`, same posture as [AccountingLabelsTest]/[ComplianceLabelsTest].
 */
class SepaLabelsTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun sepaMandateStatusLabel_isNonBlankAndDistinctForEveryValue() {
        val labels = SepaMandateStatus.entries.map { sepaMandateStatusLabel(it) }
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size, "expected pairwise-distinct labels, got $labels")
    }

    @Test
    fun sepaMandateStatusColor_isARealBootstrapHueForEveryValue() {
        SepaMandateStatus.entries.forEach { status ->
            assertTrue(sepaMandateStatusColor(status) in semanticColors)
        }
    }

    @Test
    fun sepaBatchStatusLabel_isNonBlankAndDistinctForEveryValue() {
        val labels = SepaDebitBatchStatus.entries.map { sepaBatchStatusLabel(it) }
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size, "expected pairwise-distinct labels, got $labels")
    }

    @Test
    fun sepaBatchStatusColor_isARealBootstrapHueForEveryValue() {
        SepaDebitBatchStatus.entries.forEach { status ->
            assertTrue(sepaBatchStatusColor(status) in semanticColors)
        }
    }

    @Test
    fun sepaItemStatusLabel_isNonBlankAndDistinctForEveryValue() {
        val labels = SepaDebitItemStatus.entries.map { sepaItemStatusLabel(it) }
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size, "expected pairwise-distinct labels, got $labels")
    }

    @Test
    fun sepaItemStatusColor_isARealBootstrapHueForEveryValue() {
        SepaDebitItemStatus.entries.forEach { status ->
            assertTrue(sepaItemStatusColor(status) in semanticColors)
        }
    }

    @Test
    fun sepaSequenceTypeLabel_isNonBlankAndDistinctForEveryValue() {
        val labels = SepaSequenceType.entries.map { sepaSequenceTypeLabel(it) }
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size, "expected pairwise-distinct labels, got $labels")
    }

    @Test
    fun sepaSequenceTypeColor_isARealBootstrapHueForEveryValue() {
        SepaSequenceType.entries.forEach { type ->
            assertTrue(sepaSequenceTypeColor(type) in semanticColors)
        }
    }

    @Test
    fun sepaReturnReasonLabel_isNonBlankAndDistinctForEveryValue() {
        val labels = SepaReturnReason.entries.map { sepaReturnReasonLabel(it) }
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size, "expected pairwise-distinct labels, got $labels")
    }

    /** Set-getrieben, nicht Literal-getrieben (Plan §5) -- "danger" für genau
     * [SepaReturnReasonSets.FORCES_MANDATE_REVOCATION], "warning" für jeden übrigen Code. */
    @Test
    fun sepaReturnReasonColor_matchesForcesMandateRevocationSetExactly() {
        SepaReturnReason.entries.forEach { reason ->
            val expected = if (reason in SepaReturnReasonSets.FORCES_MANDATE_REVOCATION) "danger" else "warning"
            assertEquals(expected, sepaReturnReasonColor(reason), "unexpected color for $reason")
        }
    }

    @Test
    fun sepaExclusionReasonLabel_isNonBlankAndDistinctForEveryValue() {
        val labels = SepaDebitExclusionReason.entries.map { sepaExclusionReasonLabel(it) }
        labels.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size, "expected pairwise-distinct labels, got $labels")
    }

    @Test
    fun sepaExclusionReasonColor_isARealBootstrapHueForEveryValue() {
        SepaDebitExclusionReason.entries.forEach { reason ->
            assertTrue(sepaExclusionReasonColor(reason) in semanticColors)
        }
    }

    @Test
    fun formatIbanLast4_containsTheDigitsAndAtLeastFourMaskingCharacters() {
        val formatted = formatIbanLast4("1234")
        assertTrue(formatted.contains("1234"))
        assertTrue(formatted.count { it == '•' } >= 4)
    }
}
