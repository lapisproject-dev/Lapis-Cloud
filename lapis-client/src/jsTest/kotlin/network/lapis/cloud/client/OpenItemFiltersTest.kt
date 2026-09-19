package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import dev.kilua.rpc.types.toDouble
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.OpenItemAgingBucket
import network.lapis.cloud.shared.domain.OpenItemAgingBucketDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemStatus
import network.lapis.cloud.shared.domain.OpenItemSummaryDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Welle V1.4.21 -- pins [OpenItemFilters.kt]'s pure filter/tile logic (no DOM). */
class OpenItemFiltersTest {
    private fun item(
        id: String = "oi-1",
        name: String = "Muster GmbH",
        reference: String? = null,
        status: OpenItemStatus = OpenItemStatus.OPEN,
        daysOverdue: Int = 0,
    ) = OpenItemDto(
        id = id,
        direction = OpenItemDirection.PAYABLE,
        counterpartyName = name,
        counterpartyKey = name.lowercase(),
        reference = reference,
        itemDate = LocalDate(2026, 1, 1),
        dueDate = LocalDate(2026, 2, 1),
        amount = 100.0.toDecimal(),
        openAmount = 100.0.toDecimal(),
        contraAccountId = "acc-1",
        contraAccountNumber = "50000",
        contraAccountName = "Wareneinsatz",
        sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
        status = status,
        daysOverdue = daysOverdue,
        asOf = LocalDate(2026, 3, 1),
        createdByMemberId = "m-1",
        createdAt = LocalDateTime(2026, 1, 1, 10, 0),
    )

    private fun bucket(
        bucket: OpenItemAgingBucket,
        count: Int,
        amount: Double,
    ) = OpenItemAgingBucketDto(bucket = bucket, count = count, totalAmount = amount.toDecimal())

    private val summary =
        OpenItemSummaryDto(
            asOf = LocalDate(2026, 3, 1),
            payableBuckets =
                listOf(
                    bucket(OpenItemAgingBucket.NOT_DUE, 5, 500.0),
                    bucket(OpenItemAgingBucket.DAYS_1_30, 2, 20.0),
                    bucket(OpenItemAgingBucket.DAYS_31_90, 1, 10.0),
                    bucket(OpenItemAgingBucket.OVER_90, 1, 5.0),
                ),
            receivableBuckets =
                listOf(
                    bucket(OpenItemAgingBucket.NOT_DUE, 3, 300.0),
                    bucket(OpenItemAgingBucket.DAYS_1_30, 1, 7.5),
                ),
            payableOpenTotal = 535.0.toDecimal(),
            receivableOpenTotal = 307.5.toDecimal(),
            nettingCandidateCounterpartyCount = 2,
            accountsConfigured = true,
        )

    // ── Segment / Richtung ───────────────────────────────────────────────────────────────────────

    @Test
    fun toDirection_allIsNull_directionSegmentsMapToTheirDirection() {
        assertNull(OpenItemSegment.ALL.toDirection())
        assertEquals(OpenItemDirection.PAYABLE, OpenItemSegment.PAYABLE.toDirection())
        assertEquals(OpenItemDirection.RECEIVABLE, OpenItemSegment.RECEIVABLE.toDirection())
    }

    // ── requiresClosedRows ───────────────────────────────────────────────────────────────────────

    @Test
    fun requiresClosedRows_falseForDefaultOpenStatuses() {
        assertEquals(false, OpenItemListFilter().requiresClosedRows())
        assertEquals(false, OpenItemListFilter(statuses = setOf(OpenItemStatus.OPEN)).requiresClosedRows())
    }

    @Test
    fun requiresClosedRows_trueAsSoonAsSettledOrCancelledIsIncluded() {
        assertTrue(OpenItemListFilter(statuses = setOf(OpenItemStatus.OPEN, OpenItemStatus.SETTLED)).requiresClosedRows())
        assertTrue(OpenItemListFilter(statuses = setOf(OpenItemStatus.CANCELLED)).requiresClosedRows())
    }

    // ── applyOpenItemFilter ──────────────────────────────────────────────────────────────────────

    @Test
    fun applyOpenItemFilter_filtersByStatus() {
        val items = listOf(item("a", status = OpenItemStatus.OPEN), item("b", status = OpenItemStatus.SETTLED))
        assertEquals(listOf("a"), applyOpenItemFilter(items, OpenItemListFilter()).map { it.id })
        val all = OpenItemListFilter(statuses = OpenItemStatus.entries.toSet())
        assertEquals(listOf("a", "b"), applyOpenItemFilter(items, all).map { it.id })
    }

    @Test
    fun applyOpenItemFilter_searchHitsNameAndReference_caseInsensitive() {
        val items =
            listOf(
                item("a", name = "Müller GmbH"),
                item("b", name = "Andere AG", reference = "RE-2026-0042"),
                item("c", name = "Dritter e.V."),
            )
        assertEquals(listOf("a"), applyOpenItemFilter(items, OpenItemListFilter(search = "MÜLLER")).map { it.id })
        assertEquals(listOf("b"), applyOpenItemFilter(items, OpenItemListFilter(search = "re-2026")).map { it.id })
    }

    @Test
    fun applyOpenItemFilter_searchCollapsesWhitespaceLikeTheServersCounterpartyKey() {
        val items = listOf(item("a", name = "müller gmbh"))
        assertEquals(listOf("a"), applyOpenItemFilter(items, OpenItemListFilter(search = "  Müller    GmbH ")).map { it.id })
    }

    @Test
    fun applyOpenItemFilter_blankSearchDoesNotFilter() {
        val items = listOf(item("a"), item("b"))
        assertEquals(2, applyOpenItemFilter(items, OpenItemListFilter(search = "   ")).size)
    }

    @Test
    fun applyOpenItemFilter_onlyOverdueUsesTheServersDaysOverdue() {
        val items = listOf(item("a", daysOverdue = 0), item("b", daysOverdue = 3))
        assertEquals(listOf("b"), applyOpenItemFilter(items, OpenItemListFilter(onlyOverdue = true)).map { it.id })
    }

    @Test
    fun applyOpenItemFilter_onlyOverdueExcludesCancelledItemsEvenWhenCancelledStatusIsChecked() {
        val items =
            listOf(
                item("a", status = OpenItemStatus.OPEN, daysOverdue = 5),
                item("b", status = OpenItemStatus.CANCELLED, daysOverdue = 5),
            )
        val filter =
            OpenItemListFilter(
                statuses = setOf(OpenItemStatus.OPEN, OpenItemStatus.CANCELLED),
                onlyOverdue = true,
            )
        assertEquals(listOf("a"), applyOpenItemFilter(items, filter).map { it.id })
    }

    // ── Zähler ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun openItemFilterCounts_shownNeverExceedsLoaded() {
        val items = listOf(item("a"), item("b", status = OpenItemStatus.CANCELLED), item("c", daysOverdue = 2))
        val counts = openItemFilterCounts(items, OpenItemListFilter(onlyOverdue = true))
        assertEquals(1, counts.shown)
        assertEquals(3, counts.loaded)
        assertTrue(counts.shown <= counts.loaded)
    }

    // ── Überfällig-Summe ─────────────────────────────────────────────────────────────────────────

    @Test
    fun overdueTotal_neverIncludesNotDue() {
        val total = overdueTotal(summary.payableBuckets)
        assertEquals(4, total.count)
        assertEquals(35.0, total.amount.toDouble())
    }

    @Test
    fun overdueTotal_emptyBucketsIsZero() {
        val total = overdueTotal(emptyList())
        assertEquals(0, total.count)
        assertEquals(0.0, total.amount.toDouble())
    }

    @Test
    fun overdueTotal_roundsAwayFloatingPointArtifacts() {
        val buckets = listOf(bucket(OpenItemAgingBucket.DAYS_1_30, 1, 0.1), bucket(OpenItemAgingBucket.OVER_90, 1, 0.2))
        assertEquals(0.3, overdueTotal(buckets).amount.toDouble())
    }

    @Test
    fun overdueTotalBothDirections_addsBothDirections() {
        val total = overdueTotalBothDirections(summary)
        assertEquals(5, total.count)
        assertEquals(42.5, total.amount.toDouble())
    }

    // ── Kacheln ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun metricTiles_all_isExactlyThreeAndNeverASaldo() {
        val tiles = openItemMetricTiles(OpenItemSegment.ALL, summary, canWrite = true)
        assertEquals(3, tiles.size)
        assertEquals(
            listOf(OpenItemMetricKind.PAYABLE_OPEN, OpenItemMetricKind.RECEIVABLE_OPEN, OpenItemMetricKind.OVERDUE),
            tiles.map { it.kind },
        )
        val payable = tiles[0] as OpenItemMetricTile.Money
        val receivable = tiles[1] as OpenItemMetricTile.Money
        assertEquals(535.0, payable.amount.toDouble())
        assertEquals(307.5, receivable.amount.toDouble())
        // Nie ein saldierter Wert: 535 - 307,5 = 227,5 darf nirgends als Kachel auftauchen.
        assertTrue(tiles.filterIsInstance<OpenItemMetricTile.Money>().none { it.amount.toDouble() == 227.5 })
    }

    @Test
    fun metricTiles_directionSegment_showsNettableTileOnlyWithWriteRightAndCandidates() {
        val withTile = openItemMetricTiles(OpenItemSegment.PAYABLE, summary, canWrite = true)
        assertEquals(3, withTile.size)
        assertEquals(OpenItemMetricKind.NETTABLE, withTile.last().kind)

        val noCandidates =
            openItemMetricTiles(OpenItemSegment.PAYABLE, summary.copy(nettingCandidateCounterpartyCount = 0), canWrite = true)
        assertEquals(2, noCandidates.size)

        val readOnly = openItemMetricTiles(OpenItemSegment.RECEIVABLE, summary, canWrite = false)
        assertEquals(2, readOnly.size)
        assertTrue(readOnly.none { it.kind == OpenItemMetricKind.NETTABLE })
    }

    @Test
    fun metricTiles_directionSegment_usesThatDirectionsTotalsOnly() {
        val tiles = openItemMetricTiles(OpenItemSegment.RECEIVABLE, summary, canWrite = false)
        val open = tiles[0] as OpenItemMetricTile.Money
        assertEquals(OpenItemMetricKind.RECEIVABLE_OPEN, open.kind)
        assertEquals(307.5, open.amount.toDouble())
        assertEquals(4, open.count)
        val overdue = tiles[1] as OpenItemMetricTile.Money
        assertEquals(7.5, overdue.amount.toDouble())
    }

    @Test
    fun metricTiles_alwaysBetweenTwoAndThree() {
        for (segment in OpenItemSegment.entries) {
            for (canWrite in listOf(true, false)) {
                assertTrue(openItemMetricTiles(segment, summary, canWrite).size in 2..3)
            }
        }
    }
}
