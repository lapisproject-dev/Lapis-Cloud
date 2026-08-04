package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import network.lapis.cloud.shared.domain.CostCenterDto
import network.lapis.cloud.shared.domain.CostCenterResultDto
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Accounting UI wave -- covers the pure, DOM-independent helper functions local to
 * `CostCentersScreen.kt` ([costCenterLabel]/[costCenterResultLabel]), same scope posture as
 * [LedgerScreenTest]/[FinancialReportsScreenTest] (no DOM/rendering test harness exists in this
 * module). This screen introduces no new `typeBadge`/`statusBadge` enum -- it reuses
 * `StatusBadge.kt`'s [activeStatusBadge] (already covered by its own precedent usage in
 * `LedgerScreen.kt`) and no other Accounting enum. `periodRangeCaption` (also used by this
 * screen's report view) is already covered by [FinancialReportsScreenTest] -- both screens call
 * the same public top-level function, so it is deliberately not re-tested here.
 */
class CostCentersScreenTest {
    @Test
    fun costCenterLabel_joinsCodeAndNameWithMiddleDot() {
        val costCenter =
            CostCenterDto(
                id = "cc-1",
                code = "SOMMERFEST-2027",
                name = "Sommerfest 2027",
                description = null,
                active = true,
            )
        assertEquals("SOMMERFEST-2027 · Sommerfest 2027", costCenterLabel(costCenter))
    }

    @Test
    fun costCenterResultLabel_joinsCodeAndNameWithMiddleDot() {
        val result =
            CostCenterResultDto(
                costCenterId = "cc-1",
                code = "SOMMERFEST-2027",
                name = "Sommerfest 2027",
                incomeLines = emptyList(),
                expenseLines = emptyList(),
                totalIncome = 0.0.toDecimal(),
                totalExpense = 0.0.toDecimal(),
                result = 0.0.toDecimal(),
            )
        assertEquals("SOMMERFEST-2027 · Sommerfest 2027", costCenterResultLabel(result))
    }
}
