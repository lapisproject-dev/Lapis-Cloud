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
    // The labels use gettext() (not tr()): they are embedded into other strings (e.g. the netting preview),
    // and a tr() result would leak KVision's internal "###KvI18nS###" marker there. gettext() returns the
    // plain, already translated String, so the tests compare against plain text.

    @Test
    fun everyOpenItemDirection_hasALabelAndAColor() {
        OpenItemDirection.entries.forEach { direction ->
            assertTrue(openItemDirectionLabel(direction).isNotBlank())
            assertTrue(openItemDirectionColor(direction).isNotBlank())
        }
    }

    @Test
    fun payable_isKreditor_receivable_isDebitor() {
        assertEquals("Kreditor", openItemDirectionLabel(OpenItemDirection.PAYABLE))
        assertEquals("Debitor", openItemDirectionLabel(OpenItemDirection.RECEIVABLE))
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
        assertEquals("Überfällig", openItemOverdueLabel())
    }

    // ── Welle V1.4.21 ────────────────────────────────────────────────────────────────────────────

    @Test
    fun overdueDaysLabel_namesTheExactDayCount() {
        assertEquals("seit 1 Tag", openItemOverdueDaysLabel(1))
        assertEquals("seit 12 Tagen", openItemOverdueDaysLabel(12))
    }

    @Test
    fun everySegmentAndMetricKind_hasALabel() {
        OpenItemSegment.entries.forEach { assertTrue(openItemSegmentLabel(it).isNotBlank()) }
        OpenItemMetricKind.entries.forEach { assertTrue(openItemMetricLabel(it).isNotBlank()) }
    }

    @Test
    fun everyReceivableDunningNoticeStatus_hasAColor() {
        ReceivableDunningNoticeStatus.entries.forEach { assertTrue(receivableDunningNoticeStatusColor(it).isNotBlank()) }
    }

    @Test
    fun receivableDunningLevelLabel_nullMeansNoNoticeYet() {
        assertEquals("–", receivableDunningLevelLabel(null))
        assertEquals("Stufe 2", receivableDunningLevelLabel(2))
    }
}
