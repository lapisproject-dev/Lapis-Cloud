package network.lapis.cloud.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.269 -- the rules of the R3 inputs, in the module that client AND server call (one function, one verdict). */
class PartgAnnexRulesTest {
    private fun donor(
        name: String = "Erika Beispiel",
        cents: Long = 1_000_001L,
        street: String? = "Weg 1",
        postal: String? = "12345",
        city: String? = "Ort",
        country: String = "DE",
    ) = PartgLargeDonorInputDto(
        id = null,
        personKind = PartgPersonKind.NATURAL,
        name = name,
        street = street,
        postalCode = postal,
        city = city,
        country = country,
        contributionKind = PartgContributionKind.DONATION,
        amountCents = cents,
    )

    @Test
    fun thresholdIsStrictlyGreaterThanTenThousandEuro() {
        assertEquals(PartgAnnexViolation.AMOUNT_NOT_ABOVE_THRESHOLD, PartgAnnexRules.validateDonor(donor(cents = 1_000_000L)))
        assertEquals(PartgAnnexViolation.AMOUNT_NOT_ABOVE_THRESHOLD, PartgAnnexRules.validateDonor(donor(cents = 0L)))
        assertEquals(PartgAnnexViolation.AMOUNT_NOT_ABOVE_THRESHOLD, PartgAnnexRules.validateDonor(donor(cents = -1L)))
        assertNull(PartgAnnexRules.validateDonor(donor(cents = 1_000_001L)))
        assertNull(PartgAnnexRules.validateDonor(donor(cents = PartgAnnexRules.MAX_AMOUNT_CENTS)))
        assertEquals(
            PartgAnnexViolation.AMOUNT_TOO_LARGE,
            PartgAnnexRules.validateDonor(donor(cents = PartgAnnexRules.MAX_AMOUNT_CENTS + 1)),
        )
    }

    @Test
    fun nameLengthBoundaries() {
        assertEquals(PartgAnnexViolation.NAME_EMPTY, PartgAnnexRules.validateDonor(donor(name = "")))
        assertEquals(PartgAnnexViolation.NAME_EMPTY, PartgAnnexRules.validateDonor(donor(name = "   ")))
        assertNull(PartgAnnexRules.validateDonor(donor(name = "x")))
        assertNull(PartgAnnexRules.validateDonor(donor(name = "x".repeat(200))))
        assertEquals(PartgAnnexViolation.FIELD_TOO_LONG, PartgAnnexRules.validateDonor(donor(name = "x".repeat(201))))
        // the length is checked after trimming
        assertNull(PartgAnnexRules.validateDonor(donor(name = " " + "x".repeat(200) + " ")))
    }

    @Test
    fun addressFieldLengths() {
        assertNull(PartgAnnexRules.validateDonor(donor(postal = "1".repeat(10))))
        assertEquals(PartgAnnexViolation.FIELD_TOO_LONG, PartgAnnexRules.validateDonor(donor(postal = "1".repeat(11))))
        assertNull(PartgAnnexRules.validateDonor(donor(city = "c".repeat(100))))
        assertEquals(PartgAnnexViolation.FIELD_TOO_LONG, PartgAnnexRules.validateDonor(donor(city = "c".repeat(101))))
        assertNull(PartgAnnexRules.validateDonor(donor(street = "s".repeat(200))))
        assertEquals(PartgAnnexViolation.FIELD_TOO_LONG, PartgAnnexRules.validateDonor(donor(street = "s".repeat(201))))
        assertNull(PartgAnnexRules.validateDonor(donor(street = null, postal = null, city = null)))
    }

    @Test
    fun controlAndBidiCharactersAreRefused() {
        val bad =
            listOf(
                "a\u0000b",
                "a\tb",
                "a\nb",
                "a\u007Fb",
                "a\u0085b",
                "a\u2028b",
                "a\u2029b",
                "a\u202Eb",
                "a\u202Ab",
                "a\u2066b",
                "a\u2069b",
            )
        bad.forEach {
            assertEquals(PartgAnnexViolation.CONTROL_CHARACTER, PartgAnnexRules.validateDonor(donor(name = it)), "name ${it.length}")
            assertEquals(PartgAnnexViolation.CONTROL_CHARACTER, PartgAnnexRules.validateDonor(donor(city = it)))
        }
        assertNull(PartgAnnexRules.validateDonor(donor(name = "Müller-Lüdenscheidt & Söhne")))
        assertNull(PartgAnnexRules.validateDonor(donor(name = "Иван Петров")))
    }

    @Test
    fun countryIsExactlyTwoUpperCaseLetters() {
        assertNull(PartgAnnexRules.validateDonor(donor(country = "DE")))
        listOf("de", "D", "DEU", "", "D1", "D ").forEach {
            assertEquals(PartgAnnexViolation.COUNTRY_INVALID, PartgAnnexRules.validateDonor(donor(country = it)), "country '$it'")
        }
    }

    @Test
    fun figuresBoundaries() {
        assertNull(
            PartgAnnexRules.validateFigures(
                deductions = mapOf(PartgDeduction.UNASSIGNABLE to 0L, PartgDeduction.OVER_3300 to null),
                memberCount = 0,
            ),
        )
        assertNull(PartgAnnexRules.validateFigures(deductions = emptyMap(), memberCount = PartgAnnexRules.MEMBER_COUNT_MAX))
        assertEquals(
            PartgAnnexViolation.OUT_OF_RANGE,
            PartgAnnexRules.validateFigures(
                deductions = emptyMap(),
                memberCount =
                    PartgAnnexRules.MEMBER_COUNT_MAX + 1,
            ),
        )
        assertEquals(PartgAnnexViolation.OUT_OF_RANGE, PartgAnnexRules.validateFigures(deductions = emptyMap(), memberCount = -1))
        assertEquals(
            PartgAnnexViolation.OUT_OF_RANGE,
            PartgAnnexRules.validateFigures(
                deductions =
                    mapOf(
                        PartgDeduction.CASH_OVER_1000 to -1L,
                    ),
                memberCount = null,
            ),
        )
        assertEquals(
            PartgAnnexViolation.OUT_OF_RANGE,
            PartgAnnexRules.validateFigures(
                deductions = mapOf(PartgDeduction.CASH_OVER_1000 to PartgAnnexRules.MAX_AMOUNT_CENTS + 1),
                memberCount = null,
            ),
        )
    }

    private fun note(
        block: PartgNoteBlock,
        body: String? = null,
        abs1: PartgTriState? = null,
    ) = PartgNoteDto(block = block, included = true, body = body, reliefAbs1 = abs1, reliefAbs3 = null)

    @Test
    fun notesAndHoldings() {
        assertNull(
            PartgAnnexRules.validateNotes(
                notes = listOf(note(block = PartgNoteBlock.MEDIA_PRODUCTS, body = "x".repeat(4000))),
                holdings = emptyList(),
            ),
        )
        assertEquals(
            PartgAnnexViolation.FIELD_TOO_LONG,
            PartgAnnexRules.validateNotes(
                notes = listOf(note(block = PartgNoteBlock.MEDIA_PRODUCTS, body = "x".repeat(4001))),
                holdings = emptyList(),
            ),
        )
        // a line break is allowed in a note, nothing else is
        assertNull(
            PartgAnnexRules.validateNotes(
                notes = listOf(note(block = PartgNoteBlock.MEDIA_PRODUCTS, body = "a\nb")),
                holdings = emptyList(),
            ),
        )
        assertEquals(
            PartgAnnexViolation.CONTROL_CHARACTER,
            PartgAnnexRules.validateNotes(
                notes = listOf(note(block = PartgNoteBlock.MEDIA_PRODUCTS, body = "a\u0000b")),
                holdings = emptyList(),
            ),
        )
        assertEquals(
            PartgAnnexViolation.CONTROL_CHARACTER,
            PartgAnnexRules.validateNotes(
                notes = listOf(note(block = PartgNoteBlock.MEDIA_PRODUCTS, body = "a\u202Eb")),
                holdings = emptyList(),
            ),
        )
        // relief fields belong to RELIEF_28 only
        assertNull(
            PartgAnnexRules.validateNotes(
                notes = listOf(note(block = PartgNoteBlock.RELIEF_28, abs1 = PartgTriState.YES)),
                holdings = emptyList(),
            ),
        )
        assertEquals(
            PartgAnnexViolation.OUT_OF_RANGE,
            PartgAnnexRules.validateNotes(
                notes = listOf(note(block = PartgNoteBlock.HOLDINGS, abs1 = PartgTriState.YES)),
                holdings = emptyList(),
            ),
        )
        // a block twice
        assertEquals(
            PartgAnnexViolation.TOO_MANY,
            PartgAnnexRules.validateNotes(
                notes = listOf(note(block = PartgNoteBlock.HOLDINGS), note(block = PartgNoteBlock.HOLDINGS)),
                holdings = emptyList(),
            ),
        )
        val holding = PartgHoldingDto(name = "Firma", seat = null, shareBasisPoints = 10_000, capitalCents = 0L, resultCents = -5L)
        assertNull(PartgAnnexRules.validateNotes(notes = emptyList(), holdings = List(50) { holding }))
        assertEquals(PartgAnnexViolation.TOO_MANY, PartgAnnexRules.validateNotes(notes = emptyList(), holdings = List(51) { holding }))
        assertEquals(PartgAnnexViolation.OUT_OF_RANGE, PartgAnnexRules.validateHolding(holding.copy(shareBasisPoints = 10_001)))
        assertEquals(PartgAnnexViolation.OUT_OF_RANGE, PartgAnnexRules.validateHolding(holding.copy(capitalCents = -1L)))
        assertEquals(PartgAnnexViolation.NAME_EMPTY, PartgAnnexRules.validateHolding(holding.copy(name = " ")))
        assertNull(PartgAnnexRules.validateHolding(holding.copy(resultCents = -PartgAnnexRules.MAX_AMOUNT_CENTS)))
    }

    @Test
    fun normalizeTrimsAndMapsBlankToNull() {
        assertNull(PartgAnnexRules.normalize(null))
        assertNull(PartgAnnexRules.normalize("   "))
        assertEquals("a b", PartgAnnexRules.normalize("  a b "))
        assertFalse(PartgAnnexRules.containsControl("normal text äöü"))
        assertTrue(PartgAnnexRules.containsControl("x\u0001"))
    }
}
