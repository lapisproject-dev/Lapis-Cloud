package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.292 (R4b) -- the name heuristic: a table of hits and non-hits. The keywords themselves are an unconfirmed assumption. */
class PartyAccountNameHeuristicTest {
    private fun position(
        name: String,
        type: LedgerAccountType,
    ): String? = PartyAccountNameHeuristic.suggest(name = name, type = type)?.position

    @Test
    fun theNameIsNormalisedWithUmlautsSpelledOut() {
        assertEquals(listOf("zuschuesse", "von", "gliederungen"), PartyAccountNameHeuristic.normalize("Zuschüsse von Gliederungen"))
        assertEquals(listOf("strasse", "oe", "a1"), PartyAccountNameHeuristic.normalize("  Straße / Ö -- a1 "))
        assertEquals(emptyList(), PartyAccountNameHeuristic.normalize("***"))
    }

    @Test
    fun everyRuleHitsWithUmlautAndPlural() {
        val hits =
            listOf(
                Triple("Mitgliedsbeiträge", LedgerAccountType.INCOME, "E01"),
                Triple("Beiträge der Mitglieder", LedgerAccountType.INCOME, "E01"),
                Triple("Mandatsträgerbeiträge", LedgerAccountType.INCOME, "E02"),
                Triple("Spenden von natürlichen Personen", LedgerAccountType.INCOME, "E03"),
                Triple("Spenden juristische Personen", LedgerAccountType.INCOME, "E04"),
                Triple("Zuschüsse von Gliederungen", LedgerAccountType.INCOME, "E10"),
                Triple("Zuschuss Landesverband", LedgerAccountType.INCOME, "E10"),
                Triple("Zuschüsse an Gliederungen", LedgerAccountType.EXPENSE, "A03"),
                Triple("Staatliche Mittel", LedgerAccountType.INCOME, "E08"),
                Triple("Veranstaltungseinnahmen", LedgerAccountType.INCOME, "E07"),
                Triple("Personal", LedgerAccountType.EXPENSE, "A01"),
                Triple("Löhne und Gehälter", LedgerAccountType.EXPENSE, "A01"),
                Triple("Wahlkämpfe", LedgerAccountType.EXPENSE, "A02C"),
                Triple("Wahlkampfkosten", LedgerAccountType.EXPENSE, "A02C"),
                Triple("Sonstige Ausgaben", LedgerAccountType.EXPENSE, "A02G"),
                Triple("Grundstücke", LedgerAccountType.ASSET, "BP01"),
                Triple("Forderungen an Gebietsverbände", LedgerAccountType.ASSET, "BP05"),
                Triple("Forderungen aus der staatlichen Teilfinanzierung", LedgerAccountType.ASSET, "BP06"),
                Triple("Bank", LedgerAccountType.ASSET, "BP07"),
                Triple("Kasse", LedgerAccountType.ASSET, "BP07"),
                Triple("Rückstellungen für Pensionen", LedgerAccountType.LIABILITY, "SP01"),
                Triple("Verbindlichkeiten gg. Gebietsverbänden", LedgerAccountType.LIABILITY, "SP04"),
                Triple("Verbindlichkeiten gegenüber Kreditinstituten", LedgerAccountType.LIABILITY, "SP05"),
                Triple("Sonstige Verbindlichkeiten", LedgerAccountType.LIABILITY, "SP07"),
            )
        hits.forEach { (name, type, expected) -> assertEquals(expected, position(name = name, type = type), name) }
    }

    @Test
    fun theKeywordIsTheWordOfTheNameThatTriggered() {
        val suggestion = assertNotNull(PartyAccountNameHeuristic.suggest(name = "Personalkosten", type = LedgerAccountType.EXPENSE))
        assertEquals("A01", suggestion.position)
        assertEquals("personalkosten", suggestion.keyword)
        val phrase = assertNotNull(PartyAccountNameHeuristic.suggest(name = "Staatliche Mittel", type = LedgerAccountType.INCOME))
        assertEquals("staatliche mittel", phrase.keyword)
    }

    @Test
    fun aPlainDonationOrAnAmbiguousNameIsNeverGuessed() {
        assertNull(position(name = "Spenden", type = LedgerAccountType.INCOME))
        assertNull(position(name = "Spenden und Beiträge", type = LedgerAccountType.INCOME))
        assertNull(position(name = "Beiträge", type = LedgerAccountType.INCOME))
        assertNull(position(name = "Sonstiges", type = LedgerAccountType.INCOME))
        assertNull(position(name = "", type = LedgerAccountType.INCOME))
        // two different positions of the same type hit: no guess
        assertNull(position(name = "Sonstige Ausgaben für Wahlkampf", type = LedgerAccountType.EXPENSE))
    }

    @Test
    fun theAccountTypeDecidesWhichPositionsCount() {
        assertNull(position(name = "Lohnsteuer", type = LedgerAccountType.LIABILITY))
        assertNull(position(name = "Bankgebühren", type = LedgerAccountType.EXPENSE))
        assertNull(position(name = "Mitgliedsbeiträge", type = LedgerAccountType.EXPENSE))
        // the same words, the other side of the books
        assertEquals("E10", position(name = "Zuschüsse von Gliederungen", type = LedgerAccountType.INCOME))
        assertEquals("A03", position(name = "Zuschüsse von Gliederungen", type = LedgerAccountType.EXPENSE))
        // an equity account may only be excluded, so it is never guessed
        LedgerAccountType.EQUITY.let { assertNull(position(name = "Mitgliedsbeiträge", type = it)) }
    }

    @Test
    fun aWordHitsByItsBeginning_notAsASubstring() {
        assertNull(position(name = "Kassel", type = LedgerAccountType.ASSET), "kasse$ must not hit Kassel")
        assertNull(position(name = "Bankett", type = LedgerAccountType.ASSET), "bank$ must not hit a longer word")
        assertEquals("BP07", position(name = "Kassenbestand", type = LedgerAccountType.ASSET))
        assertNull(position(name = "Alterspersonal", type = LedgerAccountType.EXPENSE), "a stem in the middle of a word does not hit")
    }

    @Test
    fun theSuggestionIsAlwaysAnAllowedLeafTarget_andDeterministic() {
        val names = listOf("Mitgliedsbeiträge", "Personal", "Bank", "Sonstige Verbindlichkeiten", "Zuschüsse von Gliederungen")
        LedgerAccountType.entries.forEach { type ->
            names.forEach { name ->
                val first = PartyAccountNameHeuristic.suggest(name = name, type = type)
                assertEquals(first, PartyAccountNameHeuristic.suggest(name = name, type = type))
                first?.let { assertTrue(PartgLedgerRules.allowedTarget(accountType = type, positionCode = it.position), "$name as $type") }
            }
        }
    }
}
