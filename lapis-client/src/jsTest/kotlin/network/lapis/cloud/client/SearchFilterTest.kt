package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure matching logic of [SearchableSelect] / [ListFilter] (no widget, no DOM). */
class SearchFilterTest {
    private fun prepared(vararg entries: Pair<String, String>) = prepareOptions(entries.map { SearchCandidate(it.first, it.second) })

    private fun ids(
        query: String,
        options: List<PreparedOption>,
        limit: Int? = 50,
    ) = filterOptions(query, options, limit).shown.map { it.value }

    @Test
    fun diacritics_areIgnored_inBothDirections() {
        val options = prepared("1" to "Müller, Anna", "2" to "Straße 5 Verein", "3" to "Zoë Lang")
        assertEquals(listOf("1"), ids("muller", options))
        assertEquals(listOf("1"), ids("mueller", options))
        assertEquals(listOf("1"), ids("Müller", options))
        assertEquals(listOf("2"), ids("strasse", options))
        assertEquals(listOf("2"), ids("STRAßE", options))
        assertEquals(listOf("3"), ids("zoe", options))
    }

    @Test
    fun everyToken_mustMatch_inAnyOrder() {
        val options = prepared("1" to "Anna Berg", "2" to "Berg Hans", "3" to "Anna Klein")
        assertEquals(listOf("1", "2"), ids("berg", options))
        assertEquals(listOf("1"), ids("berg anna", options))
        assertEquals(listOf("1"), ids("  anna   berg ", options))
        assertEquals(emptyList(), ids("anna hans", options))
    }

    @Test
    fun blankQuery_returnsEverything_inServerOrder() {
        val options = prepared("b" to "Berta", "a" to "Anna")
        assertEquals(listOf("b", "a"), ids("", options))
        assertEquals(listOf("b", "a"), ids("   ", options))
    }

    @Test
    fun detail_isSearchedToo() {
        val options = prepareOptions(listOf(SearchCandidate("1", "Anna Berg", "Nr. 4711"), SearchCandidate("2", "Hans Klein", "Nr. 99")))
        assertEquals(listOf("1"), ids("4711", options))
        assertEquals(listOf("1"), ids("anna 4711", options))
        assertEquals(emptyList(), ids("hans 4711", options))
    }

    @Test
    fun wordStartMatches_rankBeforeInnerMatches_andServerOrderHoldsWithinARank() {
        val options = prepared("1" to "Johanna Klein", "2" to "Anna Berg", "3" to "Hanna Roth", "4" to "Annabel Voss")
        // word starts: "Anna Berg", "Annabel Voss"; inner matches: "Johanna Klein", "Hanna Roth"
        assertEquals(listOf("2", "4", "1", "3"), ids("anna", options))
    }

    @Test
    fun limit_capsShown_butTotalMatchesCountsAll() {
        val options = prepareOptions((1..120).map { SearchCandidate("id$it", "Mitglied $it") })
        val result = filterOptions("mitglied", options, limit = 50)
        assertEquals(50, result.shown.size)
        assertEquals(120, result.totalMatches)
        assertEquals(120, filterOptions("mitglied", options, limit = null).shown.size)
    }

    @Test
    fun regexMetacharacters_areOrdinaryCharacters() {
        val options = prepared("1" to "Anna (Vorstand)", "2" to "a.b", "3" to "Karl")
        assertEquals(emptyList(), ids(".*", options))
        assertEquals(emptyList(), ids("[", options))
        assertEquals(emptyList(), ids("\\", options))
        assertEquals(emptyList(), ids("(((", options))
        assertEquals(listOf("2"), ids("a.b", options))
        assertEquals(listOf("1"), ids("(vorstand)", options))
    }

    @Test
    fun georgianAndCyrillic_passThroughUnchanged() {
        val options = prepared("1" to "ირაკლი ბეჭვაია", "2" to "Иван Петров", "3" to "Anna")
        assertEquals(listOf("1"), ids("ირაკ", options))
        assertEquals(listOf("2"), ids("петров", options))
        assertEquals(listOf("2"), ids("ИВАН", options))
    }

    @Test
    fun decomposedAndLigatureInput_doesNotBreakMatching() {
        // "u" + combining diaeresis (NFD) must find a precomposed "ü" and vice versa; "ﬁ" (U+FB01) has a compatibility form only.
        val options = prepared("1" to "Müller", "2" to "Müller2", "3" to "ﬁnn")
        assertEquals(listOf("1", "2"), ids("muller", options))
        assertEquals(listOf("1", "2"), ids("Müller", options))
        assertTrue(ids("fi", options).size <= 1)
    }

    @Test
    fun normalize_yieldsBothSpellings_forUmlauts_andOneForPlainText() {
        assertEquals(listOf("muller", "mueller"), normalizeForSearch("Müller"))
        assertEquals(listOf("anna"), normalizeForSearch("Anna"))
        assertEquals(listOf("strasse"), normalizeForSearch("Straße"))
    }
}
