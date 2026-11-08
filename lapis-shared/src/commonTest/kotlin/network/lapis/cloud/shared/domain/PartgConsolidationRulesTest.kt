package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.290 (R5) -- the one rule set of the consolidation: elimination is the minimum of two stated sides, a difference stays visible. */
class PartgConsolidationRulesTest {
    @Test
    fun theMinimumIsEliminatedButOnlyWhenBothSidesAreStated() {
        assertEquals(80_00L, PartgConsolidationRules.eliminable(payer = 100_00L, payee = 80_00L))
        assertEquals(80_00L, PartgConsolidationRules.eliminable(payer = 80_00L, payee = 100_00L))
        assertEquals(100_00L, PartgConsolidationRules.eliminable(payer = 100_00L, payee = 100_00L))
        assertNull(PartgConsolidationRules.eliminable(payer = 100_00L, payee = null))
        assertNull(PartgConsolidationRules.eliminable(payer = null, payee = 100_00L))
        assertNull(PartgConsolidationRules.eliminable(payer = null, payee = null))
    }

    @Test
    fun theMatchState() {
        assertEquals(PartgMatchState.MATCH, PartgConsolidationRules.matchState(payer = 5L, payee = 5L))
        assertEquals(PartgMatchState.DIFFERS, PartgConsolidationRules.matchState(payer = 5L, payee = 6L))
        assertEquals(PartgMatchState.COUNTERPART_MISSING, PartgConsolidationRules.matchState(payer = 5L, payee = null))
        assertEquals(PartgMatchState.COUNTERPART_MISSING, PartgConsolidationRules.matchState(payer = null, payee = 5L))
    }

    @Test
    fun theKindDecidesTheTwoPositions() {
        assertEquals("A03", PartgConsolidationRules.payerPosition(PartgTransferKind.SUBSIDY))
        assertEquals("E10", PartgConsolidationRules.payeePosition(PartgTransferKind.SUBSIDY))
        assertEquals("BP05", PartgConsolidationRules.payerPosition(PartgTransferKind.BALANCE))
        assertEquals("SP04", PartgConsolidationRules.payeePosition(PartgTransferKind.BALANCE))
        // the four positions are leaves of the catalogue
        listOf("A03", "E10", "BP05", "SP04").forEach { assertEquals(PartgKind.LEAF, PartgPositions.find(it)?.kind) }
    }

    @Test
    fun amountsAtLeastOneInRange() {
        assertEquals("AMOUNT_MISSING", PartgConsolidationRules.validateAmounts(payer = null, payee = null))
        assertEquals("AMOUNT_RANGE", PartgConsolidationRules.validateAmounts(payer = 0L, payee = 5L))
        assertEquals("AMOUNT_RANGE", PartgConsolidationRules.validateAmounts(payer = 5L, payee = -1L))
        assertEquals(
            "AMOUNT_RANGE",
            PartgConsolidationRules.validateAmounts(payer = PartgConsolidationRules.MAX_AMOUNT_CENTS + 1, payee = null),
        )
        assertNull(PartgConsolidationRules.validateAmounts(payer = 1L, payee = null))
        assertNull(PartgConsolidationRules.validateAmounts(payer = null, payee = PartgConsolidationRules.MAX_AMOUNT_CENTS))
    }

    @Test
    fun theNoteIsTrimmedEmptyBecomesNullTooLongAndControlCharactersAreRefused() {
        assertEquals("hallo", PartgConsolidationRules.normalizeNote("  hallo  ").getOrNull())
        assertNull(PartgConsolidationRules.normalizeNote("   ").getOrNull())
        assertNull(PartgConsolidationRules.normalizeNote(null).getOrNull())
        assertTrue(PartgConsolidationRules.normalizeNote("x".repeat(PartgConsolidationRules.NOTE_MAX)).isSuccess)
        assertEquals(
            "NOTE_TOO_LONG",
            PartgConsolidationRules.normalizeNote("x".repeat(PartgConsolidationRules.NOTE_MAX + 1)).exceptionOrNull()?.message,
        )
        assertEquals("NOTE_CONTROL", PartgConsolidationRules.normalizeNote("a\u0007b").exceptionOrNull()?.message)
    }

    @Test
    fun theLimits() {
        assertEquals(1000, PartgConsolidationRules.MAX_TRANSFERS_PER_YEAR)
        assertEquals(500, PartgConsolidationRules.MAX_COMBINATIONS_PER_YEAR)
        assertEquals(2, PartgConsolidationRules.MIN_ROWS)
        assertEquals(20, PartgConsolidationRules.MAX_ROWS)
        assertEquals(100L, PartgConsolidationRules.P17_TOLERANCE_CENTS)
    }
}
