package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** V1.9.287 (R3c) -- the pure rules of statement D. */
class PartgEnterpriseRulesTest {
    private fun input(
        name: String = "Verlag GmbH",
        legalForm: String? = null,
        seat: String? = null,
        share: Int? = null,
        income: Long? = null,
        expenses: Long? = null,
    ) = PartgEnterpriseInputDto(
        id = null,
        name = name,
        legalForm = legalForm,
        seat = seat,
        shareBasisPoints = share,
        incomeCents = income,
        expensesCents = expenses,
    )

    @Test
    fun validInputPasses() {
        assertNull(PartgEnterpriseRules.validate(input()))
        assertNull(PartgEnterpriseRules.validate(input(legalForm = "GmbH", seat = "Berlin", share = 2500, income = 1, expenses = 2)))
    }

    @Test
    fun nameIsRequiredAndBounded() {
        assertEquals(PartgAnnexViolation.NAME_EMPTY, PartgEnterpriseRules.validate(input(name = "  ")))
        assertNull(PartgEnterpriseRules.validate(input(name = "a".repeat(200))))
        assertEquals(PartgAnnexViolation.FIELD_TOO_LONG, PartgEnterpriseRules.validate(input(name = "a".repeat(201))))
    }

    @Test
    fun legalFormAndSeatBounds() {
        assertNull(PartgEnterpriseRules.validate(input(legalForm = "a".repeat(60))))
        assertEquals(PartgAnnexViolation.FIELD_TOO_LONG, PartgEnterpriseRules.validate(input(legalForm = "a".repeat(61))))
        assertNull(PartgEnterpriseRules.validate(input(seat = "a".repeat(100))))
        assertEquals(PartgAnnexViolation.FIELD_TOO_LONG, PartgEnterpriseRules.validate(input(seat = "a".repeat(101))))
    }

    @Test
    fun controlAndBidiCharactersAreRejected() {
        assertEquals(PartgAnnexViolation.CONTROL_CHARACTER, PartgEnterpriseRules.validate(input(name = "a\nb")))
        assertEquals(PartgAnnexViolation.CONTROL_CHARACTER, PartgEnterpriseRules.validate(input(legalForm = "a‮b")))
        assertEquals(PartgAnnexViolation.CONTROL_CHARACTER, PartgEnterpriseRules.validate(input(seat = "a⁦b")))
    }

    @Test
    fun shareBounds() {
        assertEquals(PartgAnnexViolation.OUT_OF_RANGE, PartgEnterpriseRules.validate(input(share = -1)))
        assertNull(PartgEnterpriseRules.validate(input(share = 0)))
        assertNull(PartgEnterpriseRules.validate(input(share = 10_000)))
        assertEquals(PartgAnnexViolation.OUT_OF_RANGE, PartgEnterpriseRules.validate(input(share = 10_001)))
    }

    @Test
    fun amountBounds() {
        val max = PartgAnnexRules.MAX_AMOUNT_CENTS
        assertEquals(PartgAnnexViolation.OUT_OF_RANGE, PartgEnterpriseRules.validate(input(income = -1)))
        assertNull(PartgEnterpriseRules.validate(input(income = 0, expenses = 0)))
        assertNull(PartgEnterpriseRules.validate(input(income = max, expenses = max)))
        assertEquals(PartgAnnexViolation.OUT_OF_RANGE, PartgEnterpriseRules.validate(input(income = max + 1)))
        assertEquals(PartgAnnexViolation.OUT_OF_RANGE, PartgEnterpriseRules.validate(input(expenses = -1)))
        assertEquals(PartgAnnexViolation.OUT_OF_RANGE, PartgEnterpriseRules.validate(input(expenses = max + 1)))
    }

    @Test
    fun resultMayBeNegativeAndNeedsBothFigures() {
        assertEquals(-5L, PartgEnterpriseRules.resultCents(income = 10, expenses = 15))
        assertNull(PartgEnterpriseRules.resultCents(income = null, expenses = 15))
        assertNull(PartgEnterpriseRules.resultCents(income = 10, expenses = null))
    }

    @Test
    fun completeSumIsNullWithoutRowOrWithGap() {
        assertNull(PartgEnterpriseRules.completeSum(emptyList()))
        assertNull(PartgEnterpriseRules.completeSum(listOf(1L, null)))
        assertEquals(3L, PartgEnterpriseRules.completeSum(listOf(1L, 2L)))
        val max = PartgAnnexRules.MAX_AMOUNT_CENTS
        assertEquals(max * 50, PartgEnterpriseRules.completeSum(List(50) { max }))
    }

    @Test
    fun checkStatuses() {
        assertNull(PartgEnterpriseRules.check(figures = emptyList(), positionCents = 100))
        assertEquals(PartgEnterpriseCheckStatus.NOT_COMPARABLE, PartgEnterpriseRules.check(figures = listOf(1L, null), positionCents = 1))
        assertEquals(
            PartgEnterpriseCheckStatus.POSITION_NOT_ENTERED,
            PartgEnterpriseRules.check(figures = listOf(1L), positionCents = null),
        )
        assertEquals(PartgEnterpriseCheckStatus.NOT_COMPARABLE, PartgEnterpriseRules.check(figures = listOf(null), positionCents = null))
    }

    @Test
    fun toleranceIsStrictlyGreaterThan100Cents() {
        assertEquals(PartgEnterpriseCheckStatus.MATCHES, PartgEnterpriseRules.check(figures = listOf(1_000L), positionCents = 1_000L))
        assertEquals(PartgEnterpriseCheckStatus.MATCHES, PartgEnterpriseRules.check(figures = listOf(1_100L), positionCents = 1_000L))
        assertEquals(PartgEnterpriseCheckStatus.DIFFERS, PartgEnterpriseRules.check(figures = listOf(1_101L), positionCents = 1_000L))
        assertEquals(PartgEnterpriseCheckStatus.MATCHES, PartgEnterpriseRules.check(figures = listOf(900L), positionCents = 1_000L))
        assertEquals(PartgEnterpriseCheckStatus.DIFFERS, PartgEnterpriseRules.check(figures = listOf(899L), positionCents = 1_000L))
    }
}
