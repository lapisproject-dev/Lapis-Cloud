package network.lapis.cloud.client

/**
 * The server's hard cap per `listOpenItems` call (`OpenItemService.MAX_LIST_RESULTS`, `limit.coerceIn(1, 200)`).
 * A source-scan test in the server module (`OpenItemsRefetchLimitTest`) fails when the two drift apart.
 */
internal const val MAX_OPEN_ITEMS_LIMIT = 200

/** Default page size of the open-items list (the first option of its page-size select). */
internal const val DEFAULT_OPEN_ITEMS_PAGE_SIZE = 50

/** The "Mehr laden" chain is capped at this many loads per filter state (see [MAX_OPEN_ITEMS_LIMIT]: 20 x 200 = 4,000 rows at most). */
internal const val OPEN_ITEMS_MAX_LOADS = 20

/** How a page of open items is (re)loaded -- see `renderOpenItemsScreen`'s `loadPage`. */
internal sealed interface LoadMode {
    /** A different data set (filter, segment, page size changed): start over from the first page. */
    data object Reset : LoadMode

    /** "Mehr laden": the next page after the cursor. */
    data object More : LoadMode

    /**
     * The same data set once more, as deep as the user had scrolled ([depth] rows): after an action, "Aktualisieren"
     * or a reload request, the list must not jump back to the first page. [detail] says what happens with the detail
     * view afterwards.
     */
    data class Refetch(
        val depth: Int,
        val detail: RefetchDetail = RefetchDetail.FOLLOW_SELECTION,
    ) : LoadMode
}

/** What a [LoadMode.Refetch] does with the open detail view once the list is back. */
internal enum class RefetchDetail {
    /** Re-read the selected item; if it dropped out of the (filtered) list, close the detail and move the focus. */
    FOLLOW_SELECTION,

    /** The caller has just shown a fresh detail itself: leave it alone. */
    KEEP,
}

/**
 * The page limits of a refetch to [depth] rows: as many calls of [cap] rows as needed, the last one smaller. A [depth] of
 * zero or less (nothing loaded yet) is one page of [pageSize]. [depth] is clamped to [OPEN_ITEMS_MAX_LOADS] x [cap] -- the
 * most a user could ever have loaded -- so a wrong caller cannot turn this into an unbounded request loop.
 *
 * `planRefetchLimits(150, 200) == [150]`, `planRefetchLimits(450, 200) == [200, 200, 50]`.
 */
internal fun planRefetchLimits(
    depth: Int,
    cap: Int = MAX_OPEN_ITEMS_LIMIT,
    pageSize: Int = DEFAULT_OPEN_ITEMS_PAGE_SIZE,
): List<Int> {
    require(cap > 0) { "cap must be positive" }
    if (depth <= 0) return listOf(pageSize.coerceIn(1, cap))
    var remaining = minOf(depth, OPEN_ITEMS_MAX_LOADS * cap)
    val limits = mutableListOf<Int>()
    while (remaining > 0) {
        val limit = minOf(remaining, cap)
        limits += limit
        remaining -= limit
    }
    return limits
}

/** Number of "loads" a refetch to [depth] rows counts as against [OPEN_ITEMS_MAX_LOADS] (so the cap stays consistent). */
internal fun loadCountForDepth(
    depth: Int,
    pageSize: Int,
): Int = maxOf(1, (depth + pageSize - 1) / maxOf(1, pageSize))
