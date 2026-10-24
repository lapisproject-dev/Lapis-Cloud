package network.lapis.cloud.client

/** One destination of the menu, as the sidebar offers it to the person who is signed in. */
data class MenuEntry(
    val route: String,
    val title: String,
    val icon: String,
    val groupLabel: String?,
    /** Position in the sidebar (the tie-breaker of the ranking). */
    val order: Int,
)

data class MenuHit(
    val entry: MenuEntry,
    val score: Int,
)

/** [shown] is at most [MenuSearchMatcher.MAX_RESULTS] long, [total] counts every match. */
data class MenuSearchResult(
    val shown: List<MenuHit>,
    val total: Int,
)

/**
 * V1.9.203 -- ranks the menu entries against a typed query. Pure (unit-tested in `MenuSearchMatcherTest`).
 *
 * An entry matches when EVERY word of the query is found in its title, its group label or one of its keywords (the German keyword table
 * [MenuSearchKeywords]); the words may come in any order. A query without spaces is also tried against the title without spaces
 * ("reise kosten" finds "Reisekosten"). The score of a word is the best place it was found; the score of an entry is the WEAKEST word
 * (otherwise one strong word would carry an entry that matches the rest only by chance):
 * exact title 100, title prefix 80, start of a title word 60, inside the title 50, group 30, keyword prefix 20, keyword inside 10.
 */
object MenuSearchMatcher {
    const val MAX_RESULTS = 8

    private const val SCORE_TITLE_EXACT = 100
    private const val SCORE_TITLE_PREFIX = 80
    private const val SCORE_TITLE_WORD = 60
    private const val SCORE_TITLE_INSIDE = 50
    private const val SCORE_GROUP = 30
    private const val SCORE_KEYWORD_PREFIX = 20
    private const val SCORE_KEYWORD_INSIDE = 10

    fun search(
        query: String,
        entries: List<MenuEntry>,
        keywords: Map<String, List<String>>,
    ): MenuSearchResult {
        val tokens = MenuSearchNormalizer.tokens(query)
        if (tokens.isEmpty()) return MenuSearchResult(shown = emptyList(), total = 0)
        val compact = tokens.joinToString("")
        val hits =
            entries
                .mapNotNull { entry ->
                    val title = MenuSearchNormalizer.normalize(entry.title)
                    val group = entry.groupLabel?.let { MenuSearchNormalizer.normalize(it) }.orEmpty()
                    val words = keywords[entry.route].orEmpty().map { MenuSearchNormalizer.normalize(it) }
                    val perToken = tokens.map { scoreToken(it, title, group, words) }
                    val score =
                        when {
                            perToken.all { it > 0 } -> perToken.min()
                            title.replace(" ", "").contains(compact) -> SCORE_TITLE_INSIDE
                            else -> 0
                        }
                    if (score > 0) MenuHit(entry = entry, score = score) else null
                }.sortedWith(compareByDescending<MenuHit> { it.score }.thenBy { it.entry.order })
        return MenuSearchResult(shown = hits.take(MAX_RESULTS), total = hits.size)
    }

    private fun scoreToken(
        token: String,
        title: String,
        group: String,
        keywords: List<String>,
    ): Int =
        when {
            title == token -> SCORE_TITLE_EXACT
            title.startsWith(token) -> SCORE_TITLE_PREFIX
            title.contains(" $token") -> SCORE_TITLE_WORD
            title.contains(token) -> SCORE_TITLE_INSIDE
            group.contains(token) -> SCORE_GROUP
            keywords.any { it.startsWith(token) || it.contains(" $token") } -> SCORE_KEYWORD_PREFIX
            keywords.any { it.contains(token) } -> SCORE_KEYWORD_INSIDE
            else -> 0
        }

    /**
     * The ranges of the ORIGINAL [text] that the words of [query] hit (sorted, merged). Computed on the folded text and mapped back, so
     * "Beiträge" is marked correctly for the query "beitraege" although the folded lengths differ. A text or query without a hit yields an
     * empty list -- the caller then simply draws no mark.
     */
    fun highlightRanges(
        text: String,
        query: String,
    ): List<IntRange> {
        val tokens = MenuSearchNormalizer.tokens(query)
        if (tokens.isEmpty()) return emptyList()
        val mapped = MenuSearchNormalizer.normalizeWithMap(text)
        val found = mutableListOf<IntRange>()
        for (token in tokens) {
            var from = 0
            while (from <= mapped.text.length - token.length) {
                val at = mapped.text.indexOf(token, from)
                if (at < 0) break
                found += mapped.starts[at] until mapped.ends[at + token.length - 1]
                from = at + token.length
            }
        }
        val merged = mutableListOf<IntRange>()
        for (range in found.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }
}
