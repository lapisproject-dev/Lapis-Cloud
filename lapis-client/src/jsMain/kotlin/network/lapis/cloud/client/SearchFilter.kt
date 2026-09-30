package network.lapis.cloud.client

/**
 * The pure matching logic behind [SearchableSelect] (person pickers) and [ListFilter] (name filter above a list) --
 * no widget, no DOM, unit-testable (`SearchFilterTest`).
 *
 * Matching rules, identical for both widgets:
 *  - case-insensitive and diacritic-insensitive: "muller" finds "Müller", "mueller" finds "Müller", "strasse" finds "Straße";
 *  - the query is split at whitespace, EVERY token must occur somewhere in the label or the detail text (AND), in any order;
 *  - results whose tokens all hit a word START rank before the rest ("anna" puts "Anna Berg" before "Johanna Klein"); within a
 *    rank the server order is kept;
 *  - the query is NEVER compiled into a regular expression -- only `String.contains`/`startsWith`, so `.*`, `[` or `\` typed by
 *    a user are ordinary characters and cannot raise an exception or cause catastrophic backtracking.
 */
internal data class SearchCandidate(
    val value: String,
    val label: String,
    val detail: String? = null,
)

/** A candidate plus its pre-computed search keys: computed once per `options` assignment, never per keystroke. */
internal class PreparedOption(
    val value: String,
    val label: String,
    val detail: String?,
    /** Normalised variants of "label detail" (see [normalizeForSearch]). */
    val keys: List<String>,
    /** The words of all [keys] variants -- the word-start rank looks here. */
    val words: List<String>,
)

internal class FilterResult(
    /** At most `limit` options, ranked. */
    val shown: List<PreparedOption>,
    /** How many options match in total (may exceed `shown.size`). */
    val totalMatches: Int,
)

private const val COMBINING_MARKS_FIRST = 0x0300
private const val COMBINING_MARKS_LAST = 0x036F
private val WORD_SEPARATORS = charArrayOf(' ', '-', ',', '(', ')', '.', '/', '\'', '–', '—')

/** `String.normalize("NFD")` exists only through `asDynamic()` in Kotlin/JS; encapsulated here. */
private fun nfd(text: String): String = text.asDynamic().normalize("NFD") as String

private fun foldLatin(text: String): String {
    // ß/æ/œ/ø/ł/đ have no canonical decomposition -- fixed table. Combining marks (U+0300..U+036F) are dropped after NFD.
    val mapped =
        text
            .replace("ß", "ss")
            .replace("æ", "ae")
            .replace("œ", "oe")
            .replace("ø", "o")
            .replace("ł", "l")
            .replace("đ", "d")
    return nfd(mapped).filterNot { it.code in COMBINING_MARKS_FIRST..COMBINING_MARKS_LAST }
}

/**
 * The comparable spellings of [text]: lower-case, with (1) all diacritics stripped and (2) the German umlaut transcription
 * (ä→ae, ö→oe, ü→ue) applied first. A match in EITHER spelling counts. Georgian, Cyrillic and other scripts pass through
 * unchanged (only U+0300..U+036F combining marks are removed).
 */
internal fun normalizeForSearch(text: String): List<String> {
    val lower = text.lowercase()
    val plain = foldLatin(lower)
    val transcribed = foldLatin(lower.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue"))
    return if (plain == transcribed) listOf(plain) else listOf(plain, transcribed)
}

internal fun prepareOptions(options: List<SearchCandidate>): List<PreparedOption> =
    options.map { candidate ->
        val keys = normalizeForSearch(listOfNotNull(candidate.label, candidate.detail).joinToString(" "))
        PreparedOption(
            value = candidate.value,
            label = candidate.label,
            detail = candidate.detail,
            keys = keys,
            words = keys.flatMap { key -> key.split(*WORD_SEPARATORS).filter { it.isNotEmpty() } },
        )
    }

/** The non-blank whitespace-separated tokens of [query], each as its comparable spellings. */
private fun queryTokens(query: String): List<List<String>> =
    query
        .split(' ', '\t', '\n', ' ')
        .filter { it.isNotBlank() }
        .map { normalizeForSearch(it) }

/**
 * Filters and ranks [options] by [query]. A blank query returns all options (server order). [limit] caps `shown`
 * (`null` = unlimited, for [ListFilter]); `totalMatches` always counts every match.
 */
internal fun filterOptions(
    query: String,
    options: List<PreparedOption>,
    limit: Int? = 50,
): FilterResult {
    val tokens = queryTokens(query)
    if (tokens.isEmpty()) {
        return FilterResult(shown = if (limit == null) options else options.take(limit), totalMatches = options.size)
    }
    val wordStart = mutableListOf<PreparedOption>()
    val rest = mutableListOf<PreparedOption>()
    for (option in options) {
        val matches = tokens.all { variants -> variants.any { v -> option.keys.any { key -> key.contains(v) } } }
        if (!matches) continue
        val atWordStart = tokens.all { variants -> variants.any { v -> option.words.any { word -> word.startsWith(v) } } }
        if (atWordStart) wordStart += option else rest += option
    }
    val ranked = wordStart + rest
    return FilterResult(shown = if (limit == null) ranked else ranked.take(limit), totalMatches = ranked.size)
}
