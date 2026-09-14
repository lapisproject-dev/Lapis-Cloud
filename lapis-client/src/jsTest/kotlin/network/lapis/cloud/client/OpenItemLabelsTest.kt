package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.OpenItemAgingBucket
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemSettlementKind
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.ReceivableDunningNoticeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" -- every enum literal has a label (exhaustive
 * `when`, so a missing branch would already be a compile error -- this test additionally pins the
 * exact German text and the "exactly one overdue badge" contract, mirroring
 * [TravelExpenseLabelsTest]'s shape).
 */
class OpenItemLabelsTest {
    // Fix (build gate, first review pass): every hardcoded-string comparison against a tr()-wrapped
    // label must include this KVision i18n test marker -- see VolunteerAllowanceLabelsTest/
    // SidebarLabelsTest for the established pattern this file's own author missed.
    private val kvI18nMarker = "###KvI18nS###"

    @Test
    fun everyOpenItemDirection_hasALabelAndAColor() {
        OpenItemDirection.entries.forEach { direction ->
            assertTrue(openItemDirectionLabel(direction).isNotBlank())
            assertTrue(openItemDirectionColor(direction).isNotBlank())
        }
    }

    @Test
    fun payable_isKreditor_receivable_isDebitor() {
        assertEquals("${kvI18nMarker}Kreditor", openItemDirectionLabel(OpenItemDirection.PAYABLE))
        assertEquals("${kvI18nMarker}Debitor", openItemDirectionLabel(OpenItemDirection.RECEIVABLE))
    }

    @Test
    fun everyOpenItemStatus_hasALabelAndAColor() {
        OpenItemStatus.entries.forEach { status ->
            assertTrue(openItemStatusLabel(status).isNotBlank())
            assertTrue(openItemStatusColor(status).isNotBlank())
        }
    }

    @Test
    fun everyOpenItemSettlementKind_hasALabel() {
        OpenItemSettlementKind.entries.forEach { kind ->
            assertTrue(openItemSettlementKindLabel(kind).isNotBlank())
        }
    }

    @Test
    fun everyOpenItemAgingBucket_hasALabel() {
        OpenItemAgingBucket.entries.forEach { bucket ->
            assertTrue(openItemAgingBucketLabel(bucket).isNotBlank())
        }
    }

    @Test
    fun everyReceivableDunningNoticeStatus_hasALabel() {
        ReceivableDunningNoticeStatus.entries.forEach { status ->
            assertTrue(receivableDunningNoticeStatusLabel(status).isNotBlank())
        }
    }

    @Test
    fun overdueLabel_isStable() {
        assertEquals("${kvI18nMarker}Überfällig", openItemOverdueLabel())
    }
}
