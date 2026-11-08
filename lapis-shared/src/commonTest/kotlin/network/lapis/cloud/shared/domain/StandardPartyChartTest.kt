package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** V1.9.289 (R4a) -- the party chart template: the positions it suggests are valid targets, and it never collides with the association chart. */
class StandardPartyChartTest {
    @Test
    fun everySuggestedPositionIsALeafOfTheMatchingSection() {
        StandardPartyChart.accounts.forEach { entry ->
            val code = entry.suggestedPosition ?: return@forEach
            val position = assertNotNull(PartgPositions.find(code), "unknown position $code")
            assertEquals(PartgKind.LEAF, position.kind, "$code is no LEAF")
            assertTrue(
                PartgLedgerRules.allowedTarget(accountType = entry.account.type, position = position),
                "${entry.account.accountNumber} (${entry.account.type}) may not be mapped to $code",
            )
        }
    }

    @Test
    fun anExcludedSuggestionHasNoPosition() {
        StandardPartyChart.accounts.filter { it.suggestExcluded }.forEach { assertEquals(null, it.suggestedPosition) }
        assertTrue(StandardPartyChart.accounts.any { it.suggestExcluded })
    }

    @Test
    fun aNumberSharedWithTheAssociationChartHasTheSameNameTypeAndClass() {
        val association = StandardAssociationChart.accounts.associateBy { it.accountNumber }
        StandardPartyChart.accounts.forEach { entry ->
            val other = association[entry.account.accountNumber] ?: return@forEach
            assertEquals(other.name, entry.account.name, entry.account.accountNumber)
            assertEquals(other.type, entry.account.type, entry.account.accountNumber)
            assertEquals(other.accountClass, entry.account.accountClass, entry.account.accountNumber)
        }
    }

    @Test
    fun numbersAreUniqueDigitsAndTheClassIsTheLeadingDigit() {
        val numbers = StandardPartyChart.accounts.map { it.account.accountNumber }
        assertEquals(numbers.size, numbers.toSet().size)
        StandardPartyChart.accounts.forEach {
            assertTrue(it.account.accountNumber.matches(Regex("[0-9]{1,10}")))
            assertEquals(
                it.account.accountNumber
                    .first()
                    .digitToInt(),
                it.account.accountClass,
            )
            assertTrue(it.account.name.isNotBlank() && it.account.name.length <= 200)
        }
    }

    @Test
    fun everyIncomeAndExpenseLeafIsCovered() {
        val covered = StandardPartyChart.accounts.mapNotNull { it.suggestedPosition }.toSet()
        val leaves =
            PartgPositions.ALL
                .filter { it.kind == PartgKind.LEAF && (it.section == PartgSection.INCOME || it.section == PartgSection.EXPENSE) }
                .map { it.code }
        assertEquals(emptyList(), leaves.filterNot { it in covered }, "uncovered income/expense leaves")
    }

    @Test
    fun theVersionIsPinned() {
        assertEquals("partei-skr42-2026-11", StandardPartyChart.VERSION)
        StandardPartyChart.accounts.forEach { StandardPartyChart.toInput(it) }
    }
}
