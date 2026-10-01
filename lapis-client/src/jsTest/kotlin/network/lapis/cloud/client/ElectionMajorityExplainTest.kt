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
}
