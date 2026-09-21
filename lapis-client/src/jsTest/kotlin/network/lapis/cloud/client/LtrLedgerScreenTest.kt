package network.lapis.cloud.client

import dev.kilua.rpc.types.toDecimal
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.LtrLedgerEntryDto
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.PeerTransferCharacterization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * LTR-Wirtschaft UI wave -- covers the pure, DOM-independent label/color tables in
 * `LtrLedgerScreen.kt` ([ltrLedgerEntryTypeLabel]/[ltrLedgerEntryTypeColor],
 * [ltrLedgerReferenceTypeLabel], [peerTransferCharacterizationLabel]), same scope posture as
 * [AccountingLabelsTest]/[AuctionScreenTest]. No rendering harness exists in this module (see
 * [GovernanceAuthzUiTest] KDoc), so the DOM-building `renderLtrLedgerScreen` etc. are out of scope
 * here, same as every other screen's `*ScreenTest.kt`.
 */
class LtrLedgerScreenTest {
    private val semanticColors = setOf("primary", "secondary", "success", "danger", "warning", "info", "dark")

    @Test
    fun ltrLedgerEntryTypeLabel_isNonBlankForEveryValue() {
        LtrLedgerEntryType.entries.forEach { type ->
            assertTrue(ltrLedgerEntryTypeLabel(type).isNotBlank(), "expected a non-blank label for $type")
        }
    }

    @Test
    fun ltrLedgerEntryTypeColor_isARealBootstrapHueForEveryValue() {
        LtrLedgerEntryType.entries.forEach { type ->
            val color = ltrLedgerEntryTypeColor(type)
            assertTrue(color in semanticColors, "expected a real Bootstrap hue for $type, got \"$color\"")
        }
    }

    @Test
    fun ltrLedgerReferenceTypeLabel_isNonBlankForEveryValue() {
        LtrLedgerReferenceType.entries.forEach { type ->
            assertTrue(ltrLedgerReferenceTypeLabel(type).isNotBlank(), "expected a non-blank label for $type")
        }
    }

    @Test
    fun peerTransferCharacterizationLabel_isNonBlankForEveryValue() {
        PeerTransferCharacterization.entries.forEach { characterization ->
            assertTrue(
                peerTransferCharacterizationLabel(characterization).isNotBlank(),
                "expected a non-blank label for $characterization",
            )
        }
    }

    private fun entry(
        id: String,
        amount: Double,
        type: LtrLedgerEntryType,
        day: Int,
    ) = LtrLedgerEntryDto(
        id = id,
        memberId = "m",
        memberDisplayName = "M",
        entryType = type,
        amountLtr = amount.toDecimal(),
        referenceType = null,
        referenceId = null,
        note = null,
        createdById = null,
        createdByDisplayName = null,
        createdAt = LocalDateTime(2026, 1, day, 12, 0),
    )

    private val entries =
        listOf(
            entry("a", 5.0, LtrLedgerEntryType.MINT, 3),
            entry("b", -20.5, LtrLedgerEntryType.PROJECT_STAKE, 1),
            entry("c", 100.0, LtrLedgerEntryType.MINT, 2),
        )

    @Test
    fun sortLtrEntries_withoutSortKeepsTheServersOrder() {
        assertEquals(listOf("a", "b", "c"), sortLtrEntries(entries, null).map { it.id })
    }

    @Test
    fun sortLtrEntries_byAmountSortsNumericallyNotAsText() {
        assertEquals(
            listOf("b", "a", "c"),
            sortLtrEntries(entries, SortState(LTR_ENTRY_SORT_AMOUNT, SortDirection.ASC)).map { it.id },
        )
        assertEquals(
            listOf("c", "a", "b"),
            sortLtrEntries(entries, SortState(LTR_ENTRY_SORT_AMOUNT, SortDirection.DESC)).map { it.id },
        )
    }

    @Test
    fun sortLtrEntries_byDateNewestFirstWhenDescending() {
        assertEquals(
            listOf("a", "c", "b"),
            sortLtrEntries(entries, SortState(LTR_ENTRY_SORT_DATE, SortDirection.DESC)).map { it.id },
        )
    }
}
