package network.lapis.cloud.client

import kotlin.test.Test
import kotlin.test.assertEquals

/** V1.9.203: the folding of the menu search (pure). */
class MenuSearchNormalizerTest {
    @Test
    fun umlautsAndTheirTranscriptions_foldToTheSameText() {
        assertEquals("beitrage", MenuSearchNormalizer.normalize("Beiträge"))
        assertEquals("beitrage", MenuSearchNormalizer.normalize("Beitraege"))
        assertEquals("pruf", MenuSearchNormalizer.normalize("Prüf"))
        assertEquals("pruf", MenuSearchNormalizer.normalize("Pruef"))
        assertEquals("loschung", MenuSearchNormalizer.normalize("Löschung"))
        assertEquals("loschung", MenuSearchNormalizer.normalize("Loeschung"))
        assertEquals(MenuSearchNormalizer.normalize("Anträge"), MenuSearchNormalizer.normalize("Antraege"))
    }

    @Test
    fun eszett_becomesDoubleS_inBothDirections() {
        assertEquals("strasse", MenuSearchNormalizer.normalize("Straße"))
        assertEquals("strasse", MenuSearchNormalizer.normalize("STRASSE"))
        assertEquals("gross", MenuSearchNormalizer.normalize("groß"))
    }

    @Test
    fun everythingButLettersAndDigits_becomesOneSpace() {
        assertEquals("kontenplan journal", MenuSearchNormalizer.normalize("Kontenplan & Journal"))
        assertEquals("sepa lastschrift", MenuSearchNormalizer.normalize("  SEPA--Lastschrift!!  "))
        assertEquals("", MenuSearchNormalizer.normalize("  &&& -- "))
        assertEquals("", MenuSearchNormalizer.normalize(""))
        assertEquals("v1 9 203", MenuSearchNormalizer.normalize("V1.9.203"))
    }

    @Test
    fun tokens_areDistinctAndInTypedOrder() {
        assertEquals(listOf("reise", "kosten"), MenuSearchNormalizer.tokens("Reise  kosten REISE"))
        assertEquals(emptyList(), MenuSearchNormalizer.tokens(" -- "))
    }

    @Test
    fun map_pointsEveryFoldedCharacterBackToTheOriginal() {
        val mapped = MenuSearchNormalizer.normalizeWithMap("Straße")
        assertEquals("strasse", mapped.text)
        // both 's' of the eszett come from index 4
        assertEquals(listOf(0, 1, 2, 3, 4, 4, 5), mapped.starts.toList())
        assertEquals(listOf(1, 2, 3, 4, 5, 5, 6), mapped.ends.toList())

        val transcribed = MenuSearchNormalizer.normalizeWithMap("Beitraege")
        assertEquals("beitrage", transcribed.text)
        // the folded 'a' covers the two original characters "ae"
        assertEquals(5, transcribed.starts[5])
        assertEquals(7, transcribed.ends[5])
        // and the 'g' behind it starts after both
        assertEquals(7, transcribed.starts[6])
    }
}
