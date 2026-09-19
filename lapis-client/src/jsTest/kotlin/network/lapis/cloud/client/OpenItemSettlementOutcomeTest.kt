package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.OpenItemSettlementDto
import network.lapis.cloud.shared.domain.OpenItemSettlementKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pins [newSettlementPostingError]: the toast must describe the settlement just created. */
class OpenItemSettlementOutcomeTest {
    private fun settlement(
        id: String,
        createdAt: LocalDateTime,
        postingError: String? = null,
        reversedAt: LocalDateTime? = null,
    ) = OpenItemSettlementDto(
        id = id,
        openItemId = "oi-1",
        kind = OpenItemSettlementKind.PAYMENT,
        amount = 10.0.toDecimal(),
        settledOn = LocalDate(2026, 1, 15),
        postingError = postingError,
        createdByMemberId = "m-1",
        createdAt = createdAt,
        reversedAt = reversedAt,
    )

    private val earlier = LocalDateTime(2026, 1, 15, 10, 0)
    private val later = LocalDateTime(2026, 1, 15, 11, 0)

    // ── Notnagel-Pfad (knownIds == null): die Liste kennt die Vorher-Ids nicht ──────────────────

    @Test
    fun olderFailedSettlement_doesNotHijackCleanNewOne_regardlessOfListOrder() {
        val failedOld = settlement("s1", earlier, postingError = "cash_register_balance_insufficient")
        val cleanNew = settlement("s2", later)
        assertNull(newSettlementPostingError(listOf(failedOld, cleanNew), knownIds = null))
        assertNull(newSettlementPostingError(listOf(cleanNew, failedOld), knownIds = null))
    }

    @Test
    fun newestSettlementError_isReported() {
        val cleanOld = settlement("s1", earlier)
        val failedNew = settlement("s2", later, postingError = "cash_register_balance_insufficient")
        assertEquals("cash_register_balance_insufficient", newSettlementPostingError(listOf(failedNew, cleanOld), knownIds = null))
    }

    @Test
    fun emptyList_hasNoError() {
        assertNull(newSettlementPostingError(emptyList(), knownIds = null))
    }

    // ── Id-Pfad (N3): gleicher Zeitstempel, Server-Reihenfolge nach zufälliger UUID ─────────────

    /**
     * Genau der Fall, den `createdAt` nicht auflösen kann: beide Ausgleiche tragen denselben
     * Zeitstempel, und die Server-Sortierung (`createdAt ASC, id ASC`) stellt den ALTEN hinter den
     * neuen, weil "s9" > "s2" ist. Der Notnagel greift daneben, der Id-Pfad trifft.
     */
    @Test
    fun equalCreatedAt_idPathReportsTheJustCreatedSettlement_whileTheFallbackWouldMissIt() {
        val oldClean = settlement("s9", earlier)
        val newFailed = settlement("s2", earlier, postingError = "cash_register_balance_insufficient")
        val serverOrder = listOf(newFailed, oldClean) // createdAt ASC, id ASC

        assertEquals(
            "cash_register_balance_insufficient",
            newSettlementPostingError(serverOrder, knownIds = setOf("s9")),
            "the settlement whose id the caller did not know before is the new one",
        )
        assertNull(
            newSettlementPostingError(serverOrder, knownIds = null),
            "documented weakness of the fallback -- equal createdAt, id ASC puts the OLD one last",
        )
    }

    @Test
    fun equalCreatedAt_idPathDoesNotReportAnOlderFailedSettlement() {
        val oldFailed = settlement("s1", earlier, postingError = "cash_register_balance_insufficient")
        val newClean = settlement("s2", earlier)
        assertNull(newSettlementPostingError(listOf(oldFailed, newClean), knownIds = setOf("s1")))
    }

    @Test
    fun idPath_reversedSettlementOfTheSameItemIsIrrelevant_onlyTheUnknownIdCounts() {
        val reversed = settlement("s1", earlier, postingError = "ledger_account_inactive", reversedAt = later)
        val created = settlement("s2", later)
        assertNull(newSettlementPostingError(listOf(reversed, created), knownIds = setOf("s1")))
    }

    @Test
    fun idPath_noNewSettlementAtAll_reportsNothing() {
        val known = settlement("s1", earlier, postingError = "cash_register_balance_insufficient")
        assertNull(newSettlementPostingError(listOf(known), knownIds = setOf("s1")))
    }
}
