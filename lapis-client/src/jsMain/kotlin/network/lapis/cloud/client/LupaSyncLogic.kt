package network.lapis.cloud.client

import io.kvision.html.ButtonStyle
import io.kvision.i18n.gettext
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbField
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import network.lapis.cloud.shared.domain.LupaBbPreviewRowDto
import network.lapis.cloud.shared.domain.LupaBbPreviewSummaryDto
import network.lapis.cloud.shared.domain.LupaBbSyncStatusDto

/** V1.9.226 -- the DOM-free decisions of the LupaBB sync page. */
internal object LupaSyncLogic {
    /** "Vorschau laden" works only with stored credentials. */
    fun canLoadPreview(status: LupaBbSyncStatusDto?): Boolean = status?.credentialsSet == true

    /**
     * Ambiguous plus error records (and, counted by the server, rows held back for manual handling) need a decision or a hand; they are what the
     * page shows first. The server value wins; the fallback counts the two categories only.
     */
    fun actionNeeded(
        counts: Map<LupaBbCategory, Int>,
        serverValue: Int? = null,
    ): Int = serverValue ?: ((counts[LupaBbCategory.AMBIGUOUS] ?: 0) + (counts[LupaBbCategory.ERROR] ?: 0))

    fun writableCount(summary: LupaBbPreviewSummaryDto): Int =
        summary.newCount + summary.changedCount + (summary.counts[LupaBbCategory.MISSING] ?: 0)

    /** The label of the apply button, with the numbers it will act on. */
    fun applyLabel(summary: LupaBbPreviewSummaryDto): String =
        gettext("%1 neue und %2 geänderte Datensätze übernehmen", summary.newCount, summary.changedCount)

    fun canApply(summary: LupaBbPreviewSummaryDto): Boolean = writableCount(summary) > 0

    /** A dialog only when a status will change: that is what can end a membership. */
    fun needsConfirmation(summary: LupaBbPreviewSummaryDto): Boolean = summary.statusChangeCount > 0

    /** The filter shown first: what needs action, else what will change, else everything. */
    fun defaultFilter(
        counts: Map<LupaBbCategory, Int>,
        serverActionNeeded: Int? = null,
    ): LupaBbPreviewFilter =
        when {
            actionNeeded(counts, serverActionNeeded) > 0 -> LupaBbPreviewFilter.ACTION_NEEDED
            (counts[LupaBbCategory.NEW] ?: 0) + (counts[LupaBbCategory.CHANGED] ?: 0) > 0 -> LupaBbPreviewFilter.CHANGED
            else -> LupaBbPreviewFilter.ALL
        }

    /** The filter chips in their fixed order, each with its count. */
    fun filterOptions(
        counts: Map<LupaBbCategory, Int>,
        serverActionNeeded: Int? = null,
    ): List<Pair<LupaBbPreviewFilter, String>> {
        val total = counts.values.sum()

        fun of(c: LupaBbCategory) = counts[c] ?: 0
        return listOf(
            LupaBbPreviewFilter.ACTION_NEEDED to actionNeeded(counts, serverActionNeeded),
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

    /** The longest search text the server accepts. */
    const val MAX_SEARCH_LENGTH = 100
    const val MIN_SEARCH_LENGTH = 3

    /** "LupaBB-Werte übernehmen" is offered only for a held-back row of a member, never for the own record, never while a blocker applies. */
    fun canApplyProtected(row: LupaBbPreviewRowDto): Boolean =
        row.protectedDiffs.isNotEmpty() && !row.isOwnRecord && row.protectedBlocker == null && row.memberId != null

    /** A take-over that changes the status is the serious one: the confirming button is a warning. */
    fun protectedConfirmStyle(row: LupaBbPreviewRowDto): ButtonStyle =
        if (row.protectedDiffs.any { it.field == LupaBbField.STATUS }) ButtonStyle.WARNING else ButtonStyle.PRIMARY

    /** The search starts at three characters (after trimming) and stops at the length the server accepts. */
    fun isSearchQueryUsable(query: String): Boolean = query.trim().length in MIN_SEARCH_LENGTH..MAX_SEARCH_LENGTH

    /** The route of the member administration filtered to one member (the `?member=` deep link). */
    fun memberAdministrationRoute(memberId: String): String = "${Routes.MEMBERS}?member=$memberId"
}
