package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V1.9.203: the ranking and the highlighting of the menu search (pure). */
class MenuSearchMatcherTest {
    private fun entry(
        order: Int,
        route: String,
        title: String,
        group: String? = null,
    ) = MenuEntry(route = route, title = title, icon = "fas fa-x", groupLabel = group, order = order)

    private val entries =
        listOf(
            entry(0, "/contributions", "Beiträge", "Mitgliedschaft"),
            entry(1, "/travel", "Reisekosten", "Mitgliedschaft"),
            entry(2, "/travel-approvals", "Reisekosten-Freigaben", "Finanzen"),
            entry(3, "/dunning", "Mahnwesen", "Finanzen"),
            entry(4, "/backup", "Backup & Wiederherstellung", "System"),
            entry(5, "/members", "Mitgliederverwaltung", "Verwaltung"),
            entry(6, "/payments", "Zahlungseingänge", "Finanzen"),
        )

    private val keywords =
        mapOf(
            "/travel" to listOf("spesen", "fahrtkosten"),
            "/dunning" to listOf("rechnung", "mahnung"),
            "/backup" to listOf("sicherung"),
            "/contributions" to listOf("mitgliedsbeitrag"),
        )

    private fun routes(query: String) = MenuSearchMatcher.search(query, entries, keywords).shown.map { it.entry.route }

    @Test
    fun umlautAndTranscription_findTheSameEntry_inBothDirections() {
        assertEquals(listOf("/contributions"), routes("Beitraege"))
        assertEquals(listOf("/contributions"), routes("Beiträge"))
        assertEquals(listOf("/payments"), routes("zahlungseingaenge"))
        assertEquals(listOf("/payments"), routes("Zahlungseingänge"))
    }

    @Test
    fun partOfAWord_wordOrder_andSpacedCompound_allMatch() {
        assertEquals(listOf("/travel", "/travel-approvals"), routes("reise"))
        assertEquals(listOf("/travel", "/travel-approvals"), routes("kosten reise"))
        assertEquals(listOf("/travel", "/travel-approvals"), routes("reise kosten"))
        assertEquals(listOf("/travel-approvals"), routes("kosten freigaben"))
    }

    @Test
    fun keywordsAndGroupFindAnEntry() {
        assertEquals(listOf("/travel"), routes("spesen"))
        assertEquals(listOf("/dunning"), routes("Mahnung"))
        assertEquals(listOf("/backup"), routes("sicherung"))
        // the group label alone finds the entries of that group, in sidebar order
        assertEquals(listOf("/travel-approvals", "/dunning", "/payments"), routes("finanzen"))
        assertEquals(listOf("/members"), routes("verwaltung"))
    }

    @Test
    fun ranking_exactBeforePrefixBeforeWordStartBeforeInsideBeforeGroupBeforeKeyword() {
        val ranked =
            listOf(
                entry(0, "/kw", "Anderes", null),
                entry(1, "/group", "Nichts", "Konto Gruppe"),
                entry(2, "/inside", "Bankkonto"),
                entry(3, "/word", "Mein Konto"),
                entry(4, "/prefix", "Kontoauszug"),
                entry(5, "/exact", "Konto"),
            )
        val result = MenuSearchMatcher.search("konto", ranked, mapOf("/kw" to listOf("kontostand")))
        assertEquals(listOf("/exact", "/prefix", "/word", "/inside", "/group", "/kw"), result.shown.map { it.entry.route })
        assertEquals(listOf(100, 80, 60, 50, 30, 20), result.shown.map { it.score })
    }

    @Test
    fun aKeywordInsideAWord_scoresLowerThanItsPrefix() {
        val list = listOf(entry(0, "/a", "Alpha"), entry(1, "/b", "Beta"))
        val result = MenuSearchMatcher.search("fahrt", list, mapOf("/a" to listOf("hinfahrt"), "/b" to listOf("fahrtkosten")))
        assertEquals(listOf("/b", "/a"), result.shown.map { it.entry.route })
        assertEquals(listOf(20, 10), result.shown.map { it.score })
    }

    @Test
    fun equalScores_followTheSidebarOrder() {
        val list = listOf(entry(5, "/late", "Konto B"), entry(1, "/early", "Konto A"))
        assertEquals(listOf("/early", "/late"), MenuSearchMatcher.search("konto", list, emptyMap()).shown.map { it.entry.route })
    }

    @Test
    fun aMultiWordQuery_isScoredByItsWeakestWord() {
        val list = listOf(entry(0, "/a", "Reisekosten", null), entry(1, "/b", "Reise", "Kosten"))
        val result = MenuSearchMatcher.search("reise kosten", list, emptyMap())
        // /a: 'reise' starts the title (80) and 'kosten' is inside it (50); /b: 'reise' exact (100) but 'kosten' only in the group (30)
        assertEquals(listOf("/a", "/b"), result.shown.map { it.entry.route })
        assertEquals(listOf(50, 30), result.shown.map { it.score })
    }

    @Test
    fun atMostEightAreShown_butTheTotalCountsAll() {
        val many = (0 until 12).map { entry(it, "/r$it", "Konto $it") }
        val result = MenuSearchMatcher.search("konto", many, emptyMap())
        assertEquals(MenuSearchMatcher.MAX_RESULTS, result.shown.size)
        assertEquals(12, result.total)
        assertEquals((0 until 8).map { "/r$it" }, result.shown.map { it.entry.route })
    }

    @Test
    fun emptyOrSymbolOnlyQuery_hasNoResult_andNoMatchIsEmpty() {
        assertEquals(0, MenuSearchMatcher.search("", entries, keywords).total)
        assertEquals(0, MenuSearchMatcher.search("  &&  ", entries, keywords).total)
        assertEquals(0, MenuSearchMatcher.search("zzzzqq", entries, keywords).total)
        assertTrue(MenuSearchMatcher.search("zzzzqq", entries, keywords).shown.isEmpty())
    }

    @Test
    fun highlight_marksThePlainTitle_evenWhenFoldingChangesTheLength() {
        // "Beiträge": the query "beitraege" folds to the same text; the range covers the whole word
        assertEquals(listOf(0..7), MenuSearchMatcher.highlightRanges("Beiträge", "beitraege"))
        // the eszett: folded "strasse" is 7 long, the original 6
        assertEquals(listOf(0..5), MenuSearchMatcher.highlightRanges("Straße", "strasse"))
        assertEquals(listOf(5..7), MenuSearchMatcher.highlightRanges("Mein Konto", "kon"))
        // the 'ae' spelling in the QUERY marks the umlaut in the title: "Prüfprotokoll" <- "pruef"
        assertEquals(listOf(0..3), MenuSearchMatcher.highlightRanges("Prüfprotokoll", "pruef"))
    }

    @Test
    fun highlight_mergesOverlappingWords_andFindsEveryOccurrence() {
        assertEquals(listOf(0..10), MenuSearchMatcher.highlightRanges("Reisekosten", "reise kosten"))
        assertEquals(listOf(0..1, 3..4), MenuSearchMatcher.highlightRanges("ab ab", "ab"))
        assertEquals(emptyList(), MenuSearchMatcher.highlightRanges("Mahnwesen", "xyz"))
        assertEquals(emptyList(), MenuSearchMatcher.highlightRanges("Mahnwesen", " "))
    }
}
