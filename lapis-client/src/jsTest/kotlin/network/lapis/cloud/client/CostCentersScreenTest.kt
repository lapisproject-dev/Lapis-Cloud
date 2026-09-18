package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import network.lapis.cloud.shared.domain.CostCenterDto
import network.lapis.cloud.shared.domain.CostCenterResultDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Accounting UI wave -- covers the pure, DOM-independent helper functions local to
 * `CostCentersScreen.kt` ([costCenterLabel]/[costCenterResultLabel]), same scope posture as
 * [LedgerScreenTest]/[FinancialReportsScreenTest] (no DOM/rendering test harness exists in this
 * module). This screen introduces no new `typeBadge`/`statusBadge` enum -- it reuses
 * `StatusBadge.kt`'s [activeStatusBadge] (already covered by its own precedent usage in
 * `LedgerScreen.kt`) and no other Accounting enum. `periodRangeCaption` (also used by this
 * screen's report view) is already covered by [FinancialReportsScreenTest] -- both screens call
 * the same public top-level function, so it is deliberately not re-tested here.
 *
 * Design-Team-Welle 2026-09-18 (Nachmittag) added [filterCostCenters] (the Uebersicht-Live-Suche's
 * pure predicate) -- test cases mirror [LedgerScreenTest]'s own `filterLedgerAccounts_*` suite.
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

    // ============================================================================================
    // Kostenstellen-Live-Suche (Design-Team-Welle 2026-09-18, Nachmittag)
    // ============================================================================================

    private fun costCenter(
        code: String,
        name: String,
    ) = CostCenterDto(id = "cc-$code", code = code, name = name, description = null, active = true)

    private val costCenters =
        listOf(
            costCenter("VORSTAND", "Vorstandsarbeit"),
            costCenter("SOMMERFEST-2027", "Sommerfest 2027"),
            costCenter("SPENDEN-WK", "Spenden Wahlkampf"),
            costCenter("BUERO", "Büromaterial"),
        )

    @Test
    fun filterCostCenters_blankQueryKeepsEverything() {
        assertEquals(costCenters, filterCostCenters(costCenters, ""))
        assertEquals(costCenters, filterCostCenters(costCenters, "   "))
    }

    @Test
    fun filterCostCenters_matchesCodeSubstring() {
        val filtered = filterCostCenters(costCenters, "SOMMERFEST")
        assertEquals(listOf("SOMMERFEST-2027"), filtered.map { it.code })
    }

    @Test
    fun filterCostCenters_matchesNameSubstring() {
        // Teiltreffer in der Mitte des Namens, nicht nur am Anfang.
        val filtered = filterCostCenters(costCenters, "material")
        assertEquals(listOf("BUERO"), filtered.map { it.code })
    }

    @Test
    fun filterCostCenters_isCaseInsensitive() {
        assertEquals(listOf("VORSTAND"), filterCostCenters(costCenters, "vorstand").map { it.code })
        assertEquals(listOf("VORSTAND"), filterCostCenters(costCenters, "VORSTAND").map { it.code })
    }

    @Test
    fun filterCostCenters_trimsSurroundingWhitespace() {
        assertEquals(listOf("SPENDEN-WK"), filterCostCenters(costCenters, "  Wahlkampf ").map { it.code })
    }

    @Test
    fun filterCostCenters_noMatchYieldsEmptyList() {
        assertTrue(filterCostCenters(costCenters, "Nichts passt").isEmpty())
    }

    @Test
    fun filterCostCenters_preservesInputOrder() {
        // Die Sortierung passiert beim Laden, nicht im Filter -- der Filter darf sie nicht
        // umwerfen, sonst springen Zeilen beim Tippen (gleiche Absicherung wie
        // `LedgerScreenTest.filterLedgerAccounts_preservesInputOrder`).
        val sorted = costCenters.sortedBy { it.code }
        val filtered = filterCostCenters(sorted, "S")
        assertEquals(listOf("SOMMERFEST-2027", "SPENDEN-WK", "VORSTAND"), filtered.map { it.code })
    }
}
