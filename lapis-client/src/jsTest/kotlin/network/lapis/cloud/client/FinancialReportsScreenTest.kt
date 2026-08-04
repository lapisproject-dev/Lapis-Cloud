package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Accounting UI wave -- covers only the pure, DOM-independent helper functions local to
 * `FinancialReportsScreen.kt` ([periodRangeCaption], [isNegative], [resultQualifierLabel],
 * [balancedLabel]/[balancedColor]), same scope posture as [LedgerScreenTest]/[MotionsScreenTest]
 * (no DOM/rendering test harness exists in this module). This screen introduces no new
 * `typeBadge`/`statusBadge` enum -- [LedgerAccountType]'s label/color table (reused here for the
 * GuV/Bilanz/Jahresabschluss line tables) is already covered by [LedgerScreenTest].
 */
class FinancialReportsScreenTest {
    @Test
    fun periodRangeCaption_withNullFrom_saysSeitGruendung() {
        val to = LocalDate(2026, 12, 31)
        assertEquals("Zeitraum: seit Gründung bis 2026-12-31", periodRangeCaption(null, to))
    }

    @Test
    fun periodRangeCaption_withFrom_showsBothDates() {
        val from = LocalDate(2026, 1, 1)
        val to = LocalDate(2026, 12, 31)
        assertEquals("Zeitraum: 2026-01-01 bis 2026-12-31", periodRangeCaption(from, to))
    }

    @Test
    fun isNegative_isTrueOnlyForNegativeAmounts() {
        assertEquals(true, isNegative((-1.0).toDecimal()))
        assertEquals(false, isNegative(0.0.toDecimal()))
        assertEquals(false, isNegative(1.0.toDecimal()))
    }

    @Test
    fun resultQualifierLabel_onlyAppearsWhenNegative() {
        assertEquals("(Jahresfehlbetrag)", resultQualifierLabel(negative = true))
        assertNull(resultQualifierLabel(negative = false))
    }

    @Test
    fun balancedLabel_reflectsTheServerSuppliedFlagVerbatim() {
        assertEquals("Bilanz ausgeglichen", balancedLabel(true))
        assertEquals("Bilanz NICHT ausgeglichen", balancedLabel(false))
    }

    @Test
    fun balancedColor_successWhenBalancedDangerWhenNot() {
        assertEquals("success", balancedColor(true))
        assertEquals("danger", balancedColor(false))
    }
}
