package network.lapis.cloud.client

import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import network.lapis.cloud.shared.domain.LupaBbPreviewSummaryDto
import network.lapis.cloud.shared.domain.LupaBbSyncStatusDto

/** V1.9.226 -- the DOM-free decisions of the LupaBB sync page. */
internal object LupaSyncLogic {
    /** "Vorschau laden" works only with stored credentials. */
    fun canLoadPreview(status: LupaBbSyncStatusDto?): Boolean = status?.credentialsSet == true

    /** Ambiguous plus error records need a decision or a hand; they are what the page shows first. */
    fun actionNeeded(counts: Map<LupaBbCategory, Int>): Int = (counts[LupaBbCategory.AMBIGUOUS] ?: 0) + (counts[LupaBbCategory.ERROR] ?: 0)

    fun writableCount(summary: LupaBbPreviewSummaryDto): Int =
        summary.newCount + summary.changedCount + (summary.counts[LupaBbCategory.MISSING] ?: 0)

    /** The label of the apply button, with the numbers it will act on. */
    fun applyLabel(summary: LupaBbPreviewSummaryDto): String =
        gettext("%1 neue und %2 geänderte Datensätze übernehmen", summary.newCount, summary.changedCount)

    fun canApply(summary: LupaBbPreviewSummaryDto): Boolean = writableCount(summary) > 0

    /** A dialog only when a status will change: that is what can end a membership. */
    fun needsConfirmation(summary: LupaBbPreviewSummaryDto): Boolean = summary.statusChangeCount > 0

    /** The filter shown first: what needs action, else what will change, else everything. */
    fun defaultFilter(counts: Map<LupaBbCategory, Int>): LupaBbPreviewFilter =
        when {
            actionNeeded(counts) > 0 -> LupaBbPreviewFilter.ACTION_NEEDED
            (counts[LupaBbCategory.NEW] ?: 0) + (counts[LupaBbCategory.CHANGED] ?: 0) > 0 -> LupaBbPreviewFilter.CHANGED
            else -> LupaBbPreviewFilter.ALL
        }

    /** The filter chips in their fixed order, each with its count. */
    fun filterOptions(counts: Map<LupaBbCategory, Int>): List<Pair<LupaBbPreviewFilter, String>> {
        val total = counts.values.sum()

        fun of(c: LupaBbCategory) = counts[c] ?: 0
        return listOf(
            LupaBbPreviewFilter.ACTION_NEEDED to actionNeeded(counts),
            LupaBbPreviewFilter.NEW to of(LupaBbCategory.NEW),
            LupaBbPreviewFilter.CHANGED to of(LupaBbCategory.CHANGED),
            LupaBbPreviewFilter.UNCHANGED to of(LupaBbCategory.UNCHANGED),
            LupaBbPreviewFilter.AMBIGUOUS to of(LupaBbCategory.AMBIGUOUS),
            LupaBbPreviewFilter.SKIPPED to of(LupaBbCategory.SKIPPED),
            LupaBbPreviewFilter.ERROR to of(LupaBbCategory.ERROR),
            LupaBbPreviewFilter.MISSING to of(LupaBbCategory.MISSING),
            LupaBbPreviewFilter.ALL to total,
        ).map { (filter, count) -> filter to lupaFilterLabel(filter, count) }
    }
}
