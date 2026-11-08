package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.289 (R4a) -- the one rule set that links the bookings to the report positions. */
class PartgLedgerRulesTest {
    private fun target(
        type: LedgerAccountType,
        code: String,
    ) = PartgLedgerRules.allowedTarget(accountType = type, positionCode = code)

    @Test
    fun aTargetIsALeafOfTheMatchingSectionAndNeverASum() {
        assertTrue(target(type = LedgerAccountType.INCOME, code = "E01"))
        assertTrue(target(type = LedgerAccountType.EXPENSE, code = "A02A"))
        assertTrue(target(type = LedgerAccountType.ASSET, code = "BP07"))
        assertTrue(target(type = LedgerAccountType.LIABILITY, code = "SP05"))
        assertFalse(target(type = LedgerAccountType.INCOME, code = "A01"))
        assertFalse(target(type = LedgerAccountType.EXPENSE, code = "E01"))
        assertFalse(target(type = LedgerAccountType.ASSET, code = "SP05"))
        PartgPositions.ALL.filter { it.kind == PartgKind.SUM }.forEach { sum ->
            LedgerAccountType.entries.forEach { type -> assertFalse(target(type = type, code = sum.code), "${sum.code} is a SUM") }
        }
        assertFalse(target(type = LedgerAccountType.INCOME, code = "NOPE"))
    }

    @Test
    fun equityMayOnlyBeExcluded() {
        PartgPositions.ALL.forEach { assertFalse(target(type = LedgerAccountType.EQUITY, code = it.code)) }
        assertEquals(null, PartgLedgerRules.sectionOf(LedgerAccountType.EQUITY))
    }

    @Test
    fun theSignDependsOnTheSection() {
        assertEquals(700L, PartgLedgerRules.signedAmountCents(section = PartgSection.INCOME, debitCents = 300, creditCents = 1000))
        assertEquals(700L, PartgLedgerRules.signedAmountCents(section = PartgSection.LIABILITY, debitCents = 300, creditCents = 1000))
        assertEquals(-700L, PartgLedgerRules.signedAmountCents(section = PartgSection.EXPENSE, debitCents = 300, creditCents = 1000))
        assertEquals(-700L, PartgLedgerRules.signedAmountCents(section = PartgSection.ASSET, debitCents = 300, creditCents = 1000))
    }

    @Test
    fun flowsAndBalances() {
        assertTrue(PartgLedgerRules.isFlow(PartgSection.INCOME))
        assertTrue(PartgLedgerRules.isFlow(PartgSection.EXPENSE))
        assertFalse(PartgLedgerRules.isFlow(PartgSection.ASSET))
        assertFalse(PartgLedgerRules.isFlow(PartgSection.LIABILITY))
    }

    @Test
    fun aProposalExistsOnlyForFederalAndStateUnitsInTheModesAAndB() {
        assertTrue(PartgLedgerRules.proposalAvailable(level = PartgUnitLevel.FEDERAL, mode = PartgBookkeepingMode.A))
        assertTrue(PartgLedgerRules.proposalAvailable(level = PartgUnitLevel.STATE, mode = PartgBookkeepingMode.B))
        assertFalse(PartgLedgerRules.proposalAvailable(level = PartgUnitLevel.STATE, mode = PartgBookkeepingMode.C))
        assertFalse(PartgLedgerRules.proposalAvailable(level = PartgUnitLevel.SUBORDINATE, mode = PartgBookkeepingMode.A))
        assertTrue(PartgLedgerRules.modeAllowed(level = PartgUnitLevel.SUBORDINATE, mode = PartgBookkeepingMode.C))
        assertFalse(PartgLedgerRules.modeAllowed(level = PartgUnitLevel.SUBORDINATE, mode = PartgBookkeepingMode.B))
    }

    @Test
    fun preselectionNeverTouchesATypedInOrImportedValue() {
        assertTrue(PartgLedgerRules.preselect(entered = null, source = null, proposed = 100))
        assertTrue(PartgLedgerRules.preselect(entered = 50, source = PartgValueSource.LEDGER, proposed = 100))
        assertFalse(PartgLedgerRules.preselect(entered = 50, source = PartgValueSource.MANUAL, proposed = 100))
        assertFalse(PartgLedgerRules.preselect(entered = 50, source = PartgValueSource.IMPORT, proposed = 100))
        assertFalse(PartgLedgerRules.preselect(entered = null, source = null, proposed = -1))
        assertFalse(PartgLedgerRules.preselect(entered = 50, source = PartgValueSource.LEDGER, proposed = -1))
    }

    @Test
    fun aValueIsTransferableWithinTheColumnRange() {
        assertTrue(PartgLedgerRules.transferable(0))
        assertTrue(PartgLedgerRules.transferable(PartgLedgerRules.MAX_CENTS))
        assertFalse(PartgLedgerRules.transferable(PartgLedgerRules.MAX_CENTS + 1))
        assertFalse(PartgLedgerRules.transferable(-1))
    }
}
