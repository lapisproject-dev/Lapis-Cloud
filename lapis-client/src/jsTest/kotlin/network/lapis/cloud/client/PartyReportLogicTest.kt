package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.PartgAmountError
import network.lapis.cloud.shared.domain.PartgKind
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.225 -- the DOM-free logic of the entry tab: input text, drafts, the change list, 0 versus empty, the fill state and the labels. */
class PartyReportLogicTest {
    private fun draft(
        saved: Map<String, Long> = emptyMap(),
        prior: Map<String, Long> = emptyMap(),
    ) = PartgSheetDraft(year = 2025, saved = saved, savedPrior = prior)

    @Test
    fun inputText_isGermanWithGroupingAndTwoDecimals() {
        assertEquals("0,00", partgInputText(0))
        assertEquals("0,05", partgInputText(5))
        assertEquals("12,34", partgInputText(1234))
        assertEquals("1.234,56", partgInputText(123456))
        assertEquals("999.999.999.999,99", partgInputText(99_999_999_999_999))
    }

    @Test
    fun anEditedCell_becomesAChange_inItsOwnYear() {
        val d = draft()
        d.edit(PartgColumn.CURRENT, "E01", "100,5")
        d.edit(PartgColumn.PRIOR, "E01", "7")
        assertEquals(
            listOf(PartgValueChangeDto(2024, "E01", 700L), PartgValueChangeDto(2025, "E01", 10050L)).sortedBy { it.year },
            d.changes().sortedBy { it.year },
        )
        assertEquals(2, d.changeCount)
    }

    @Test
    fun zeroIsAValue_andABlankFieldDeletes() {
        val d = draft(saved = mapOf("E01" to 500L))
        d.edit(PartgColumn.CURRENT, "E02", "0")
        d.edit(PartgColumn.CURRENT, "E01", "")
        val changes = d.changes()
        assertEquals(PartgValueChangeDto(2025, "E02", 0L), changes.single { it.positionCode == "E02" })
        assertEquals(PartgValueChangeDto(2025, "E01", null), changes.single { it.positionCode == "E01" })
    }

    @Test
    fun typingTheSavedValueAgain_isNoChange() {
        val d = draft(saved = mapOf("E01" to 123456L))
        d.edit(PartgColumn.CURRENT, "E01", "1.234,56")
        assertEquals(0, d.changeCount)
        d.edit(PartgColumn.CURRENT, "E01", "1.234,57")
        assertEquals(1, d.changeCount)
        d.discard()
        assertEquals("1.234,56", d.text(PartgColumn.CURRENT, "E01"))
        assertEquals(0, d.changeCount)
    }

    @Test
    fun invalidInput_isFlagged_andNeverSent() {
        val d = draft()
        d.edit(PartgColumn.CURRENT, "E01", "-5")
        d.edit(PartgColumn.CURRENT, "E02", "abc")
        assertTrue(d.hasInvalid)
        assertEquals(PartgAmountError.NEGATIVE, (d.parsed(PartgColumn.CURRENT, "E01") as PartgCellValue.Invalid).reason)
        assertEquals(PartgAmountError.NOT_A_NUMBER, (d.parsed(PartgColumn.CURRENT, "E02") as PartgCellValue.Invalid).reason)
        assertEquals(emptyList(), d.changes().filter { it.amountCents != null })
        assertEquals(emptyMap(), d.valuesFor(PartgColumn.CURRENT))
    }

    @Test
    fun onlyTypedInPositionsCanBeEdited() {
        val d = draft()
        var refused = false
        try {
            d.edit(PartgColumn.CURRENT, "E11", "5")
        } catch (e: IllegalArgumentException) {
            refused = true
        }
        assertTrue(refused)
    }

    @Test
    fun fillWithZero_touchesOnlyEmptyFieldsOfTheReportYear() {
        val d = draft(saved = mapOf("E01" to 100L), prior = mapOf("E02" to 200L))
        d.fillEmptyWithZero()
        val changes = d.changes()
        assertEquals(PartgPositions.LEAF_COUNT - 1, changes.size)
        assertTrue(changes.all { it.year == 2025 && it.amountCents == 0L })
        assertFalse(changes.any { it.positionCode == "E01" })
        assertEquals("", d.text(PartgColumn.PRIOR, "E01"))
    }

    @Test
    fun liveSums_useTheSharedFunction() {
        val d = draft(saved = mapOf("E01" to 1000L, "A01" to 300L))
        d.edit(PartgColumn.CURRENT, "E03", "5,00")
        val sums = PartgPositions.sums(d.valuesFor(PartgColumn.CURRENT))
        assertEquals(1500L, sums["E11"])
        assertEquals(1200L, sums["RES"])
    }

    @Test
    fun fillState_isEmptyPartialOrComplete() {
        assertEquals(PartgFillState.EMPTY, partgFillState(0, 35))
        assertEquals(PartgFillState.PARTIAL, partgFillState(12, 35))
        assertEquals(PartgFillState.COMPLETE, partgFillState(35, 35))
    }

    @Test
    fun everyPosition_hasALabel_equalToTheCatalogue() {
        PartgPositions.ALL.forEach { position ->
            assertEquals(position.labelDe, resolvedAttributeText(partgLabel(position.code)), "label of ${position.code}")
        }
        assertEquals(42, PartgPositions.ALL.size)
        assertEquals(35, PartgPositions.ALL.count { it.kind == PartgKind.LEAF })
    }
}
