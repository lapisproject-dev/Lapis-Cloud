package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.ElectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.22: the example numbers of the majority explanation must equal the server's integer arithmetic exactly. */
class ElectionMajorityExplainTest {
    @Test
    fun minYesNeeded_matchesTheServerRule_includingTheTieExclusion() {
        assertEquals(2, minYesNeeded(decisive = 3, percent = 50))
        assertEquals(3, minYesNeeded(decisive = 3, percent = 67))
        // 5 of 10 is a tie and never carries, even though 5 * 100 >= 50 * 10
        assertEquals(6, minYesNeeded(decisive = 10, percent = 50))
        assertEquals(3, minYesNeeded(decisive = 4, percent = 50))
        assertEquals(51, minYesNeeded(decisive = 100, percent = 50))
        assertEquals(2, minYesNeeded(decisive = 2, percent = 100))
        assertEquals(1, minYesNeeded(decisive = 3, percent = 1))
        assertNull(minYesNeeded(decisive = 0, percent = 50))
    }

    @Test
    fun minVotesSingleChoice_isTheCeilingOfThePercentage_withNoTieExclusion() {
        assertEquals(2, minVotesSingleChoice(total = 3, percent = 50))
        assertEquals(5, minVotesSingleChoice(total = 10, percent = 50))
        assertEquals(50, minVotesSingleChoice(total = 100, percent = 50))
        assertEquals(4, minVotesSingleChoice(total = 5, percent = 67))
        assertEquals(0, minVotesSingleChoice(total = 0, percent = 50))
    }

    @Test
    fun explanation_yesNo_namesTheThreeExamplesAndTheTieRule() {
        val lines = majorityExplanation(ElectionType.YES_NO, 50)
        assertEquals(4, lines.size, "three examples and the tie rule")
        assertTrue(lines[0].contains("3") && lines[0].contains("2"), lines[0])
        assertTrue(lines[1].contains("10") && lines[1].contains("6"), lines[1])
        assertTrue(lines[2].contains("100") && lines[2].contains("51"), lines[2])
    }

    @Test
    fun explanation_atSixtySevenPercent_withThreeVotesNeedsAllThree() {
        val line = majorityExplanation(ElectionType.YES_NO, 67).first()
        assertTrue(line.contains("3 Ja- und Nein-Stimmen") && line.contains("mindestens 3 davon"), line)
    }

    @Test
    fun explanation_singleChoice_saysThatAnUncontestedPersonNeedsNoMajority() {
        val lines = majorityExplanation(ElectionType.SINGLE_CHOICE, 50)
        assertEquals(4, lines.size)
        assertTrue(lines.last().contains("keine Mehrheit"), lines.last())
    }

    @Test
    fun explanation_multiChoice_isOneSentence_andReservedTypesHaveNone() {
        assertEquals(1, majorityExplanation(ElectionType.MULTI_CHOICE, 50).size)
        assertTrue(majorityExplanation(ElectionType.LIST_VOTE, 50).isEmpty())
        assertTrue(majorityExplanation(ElectionType.RANKED_CHOICE, 50).isEmpty())
    }

    @Test
    fun theFractionForms_matchTheServerArithmeticExactly() {
        // 2/3 of 3 decisive votes: 2 yes carry it (2 * 3 >= 2 * 3), a tie is never enough
        assertEquals(2, minYesNeeded(decisive = 3, numerator = 2, denominator = 3))
        // 2/3 of 100: 67 yes (66 * 3 = 198 < 200, 67 * 3 = 201 >= 200)
        assertEquals(67, minYesNeeded(decisive = 100, numerator = 2, denominator = 3))
        assertEquals(3, minYesNeeded(decisive = 4, numerator = 3, denominator = 4))
        assertEquals(51, minYesNeeded(decisive = 100, numerator = 1, denominator = 2))
        assertNull(minYesNeeded(decisive = 0, numerator = 2, denominator = 3))
        assertEquals(4, minVotesSingleChoice(total = 5, numerator = 2, denominator = 3))
        assertEquals(67, minVotesSingleChoice(total = 100, numerator = 2, denominator = 3))
        assertEquals(0, minVotesSingleChoice(total = 0, numerator = 1, denominator = 2))
    }

    @Test
    fun theLegacyPercentForms_areTheFractionOverOneHundred() {
        for (decisive in 1..30) {
            for (percent in listOf(1, 34, 50, 60, 67, 75, 100)) {
                assertEquals(
                    minYesNeeded(decisive = decisive, numerator = percent, denominator = 100),
                    minYesNeeded(decisive = decisive, percent = percent),
                )
            }
        }
    }

    @Test
    fun majorityPercentCeil_roundsUpLikeTheServer() {
        assertEquals(50, majorityPercentCeil(1, 2))
        assertEquals(67, majorityPercentCeil(2, 3))
        assertEquals(75, majorityPercentCeil(3, 4))
        assertEquals(60, majorityPercentCeil(3, 5))
        assertEquals(100, majorityPercentCeil(1, 1))
    }

    @Test
    fun majorityLabel_usesWordsForTheCommonFractions() {
        assertEquals("Einfache Mehrheit (mehr Ja als Nein)", majorityLabel(1, 2))
        assertEquals("Einfache Mehrheit (mehr Ja als Nein)", majorityLabel(50, 100))
        assertEquals("Zwei Drittel", majorityLabel(2, 3))
        assertEquals("Drei Viertel", majorityLabel(3, 4))
        assertEquals("mindestens 3 von 5 Stimmen", majorityLabel(3, 5))
    }
}
