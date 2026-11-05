package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** V1.9.283 (R3b) -- the pure rules of statement C and F. Client and server call exactly these functions. */
class PartgStatementRulesTest {
    private fun entry(
        cents: Long,
        date: LocalDate = LocalDate(2025, 6, 1),
        id: String? = null,
        purpose: String? = null,
    ) = PartgSponsoringEntryInputDto(id = id, date = date, amountCents = cents, purpose = purpose)

    private fun sponsor(
        entries: List<PartgSponsoringEntryInputDto> = listOf(entry(cents = 80_000L)),
        name: String = "Sponsor",
        country: String = "DE",
    ) = PartgSponsorInputDto(
        id = null,
        personKind = PartgPersonKind.LEGAL,
        name = name,
        street = null,
        postalCode = null,
        city = null,
        country = country,
        entries = entries,
    )

    @Test
    fun singleLimitIsStrictlyGreaterThan75000Cents() {
        assertEquals(PartgSponsorQualification.NONE, PartgStatementRules.qualification(listOf(74_999L)))
        assertEquals(PartgSponsorQualification.NONE, PartgStatementRules.qualification(listOf(75_000L)))
        assertEquals(PartgSponsorQualification.SINGLE, PartgStatementRules.qualification(listOf(75_001L)))
    }

    @Test
    fun yearlyLimitIsStrictlyGreaterThan600000CentsOverAllEntries() {
        assertEquals(PartgSponsorQualification.NONE, PartgStatementRules.qualification(List(8) { 75_000L }))
        assertEquals(PartgSponsorQualification.YEARLY, PartgStatementRules.qualification(List(8) { 75_000L } + listOf(1L)))
        assertEquals(PartgSponsorQualification.NONE, PartgStatementRules.qualification(List(8) { 74_999L }))
        assertEquals(PartgSponsorQualification.YEARLY, PartgStatementRules.qualification(List(9) { 66_667L }))
        assertEquals(600_000L, PartgStatementRules.yearlySum(List(8) { 75_000L }))
    }

    @Test
    fun bothWhenASingleServiceAndTheSumExceed() {
        assertEquals(PartgSponsorQualification.BOTH, PartgStatementRules.qualification(listOf(75_001L, 600_000L)))
        assertEquals(PartgSponsorQualification.SINGLE, PartgStatementRules.qualification(listOf(75_001L)))
        assertEquals(PartgSponsorQualification.NONE, PartgStatementRules.qualification(emptyList()))
    }

    @Test
    fun sponsorValidation() {
        assertNull(PartgStatementRules.validateSponsor(input = sponsor(), year = 2025))
        assertEquals(
            PartgAnnexViolation.ENTRIES_EMPTY,
            PartgStatementRules.validateSponsor(input = sponsor(entries = emptyList()), year = 2025),
        )
        assertEquals(
            PartgAnnexViolation.TOO_MANY,
            PartgStatementRules.validateSponsor(
                input =
                    sponsor(
                        entries =
                            List(101) {
                                entry(cents = 1L)
                            },
                    ),
                year = 2025,
            ),
        )
        assertNull(PartgStatementRules.validateSponsor(input = sponsor(entries = List(100) { entry(cents = 1L) }), year = 2025))
        assertEquals(
            PartgAnnexViolation.DATE_OUTSIDE_YEAR,
            PartgStatementRules.validateSponsor(
                input = sponsor(entries = listOf(entry(cents = 1L, date = LocalDate(2024, 12, 31)))),
                year = 2025,
            ),
        )
        assertEquals(
            PartgAnnexViolation.DATE_OUTSIDE_YEAR,
            PartgStatementRules.validateSponsor(
                input = sponsor(entries = listOf(entry(cents = 1L, date = LocalDate(2026, 1, 1)))),
                year = 2025,
            ),
        )
        val edges = listOf(entry(cents = 1L, date = LocalDate(2025, 12, 31)), entry(cents = 1L, date = LocalDate(2025, 1, 1)))
        assertNull(PartgStatementRules.validateSponsor(input = sponsor(entries = edges), year = 2025))
        assertEquals(
            PartgAnnexViolation.OUT_OF_RANGE,
            PartgStatementRules.validateSponsor(input = sponsor(entries = listOf(entry(cents = 0L))), year = 2025),
        )
        assertEquals(
            PartgAnnexViolation.OUT_OF_RANGE,
            PartgStatementRules.validateSponsor(input = sponsor(entries = listOf(entry(cents = -5L))), year = 2025),
        )
        assertEquals(
            PartgAnnexViolation.AMOUNT_TOO_LARGE,
            PartgStatementRules.validateSponsor(
                input =
                    sponsor(
                        entries =
                            listOf(
                                entry(
                                    cents = PartgAnnexRules.MAX_AMOUNT_CENTS + 1,
                                ),
                            ),
                    ),
                year = 2025,
            ),
        )
        assertNull(
            PartgStatementRules.validateSponsor(
                input = sponsor(entries = listOf(entry(cents = PartgAnnexRules.MAX_AMOUNT_CENTS))),
                year = 2025,
            ),
        )
        assertNull(
            PartgStatementRules.validateSponsor(
                input = sponsor(entries = listOf(entry(cents = 1L, purpose = "x".repeat(500)))),
                year = 2025,
            ),
        )
        assertEquals(
            PartgAnnexViolation.FIELD_TOO_LONG,
            PartgStatementRules.validateSponsor(
                input = sponsor(entries = listOf(entry(cents = 1L, purpose = "x".repeat(501)))),
                year = 2025,
            ),
        )
        assertEquals(
            PartgAnnexViolation.CONTROL_CHARACTER,
            PartgStatementRules.validateSponsor(input = sponsor(entries = listOf(entry(cents = 1L, purpose = "a\u0007b"))), year = 2025),
        )
        assertEquals(
            PartgAnnexViolation.CONTROL_CHARACTER,
            PartgStatementRules.validateSponsor(input = sponsor(entries = listOf(entry(cents = 1L, purpose = "a‮b"))), year = 2025),
        )
        assertEquals(PartgAnnexViolation.CONTROL_CHARACTER, PartgStatementRules.validateSponsor(input = sponsor(name = "a⁧b"), year = 2025))
        assertEquals(
            PartgAnnexViolation.DUPLICATE_ID,
            PartgStatementRules.validateSponsor(
                input = sponsor(entries = listOf(entry(cents = 1L, id = "x"), entry(cents = 2L, id = "x"))),
                year = 2025,
            ),
        )
        assertNull(
            PartgStatementRules.validateSponsor(input = sponsor(entries = listOf(entry(cents = 1L), entry(cents = 2L))), year = 2025),
        )
        assertEquals(PartgAnnexViolation.NAME_EMPTY, PartgStatementRules.validateSponsor(input = sponsor(name = "  "), year = 2025))
        assertEquals(
            PartgAnnexViolation.FIELD_TOO_LONG,
            PartgStatementRules.validateSponsor(input = sponsor(name = "x".repeat(201)), year = 2025),
        )
        assertEquals(PartgAnnexViolation.COUNTRY_INVALID, PartgStatementRules.validateSponsor(input = sponsor(country = "de"), year = 2025))
    }

    @Test
    fun youthOrgValidation() {
        fun youth(
            name: String = "Jugend",
            relationship: String? = null,
            income: Long? = null,
            expenses: Long? = null,
        ) = PartgYouthOrgInputDto(id = null, name = name, relationship = relationship, incomeCents = income, expensesCents = expenses)
        assertNull(PartgStatementRules.validateYouthOrg(youth()))
        assertNull(PartgStatementRules.validateYouthOrg(youth(income = 0L, expenses = PartgAnnexRules.MAX_AMOUNT_CENTS)))
        assertEquals(PartgAnnexViolation.NAME_EMPTY, PartgStatementRules.validateYouthOrg(youth(name = "")))
        assertEquals(PartgAnnexViolation.FIELD_TOO_LONG, PartgStatementRules.validateYouthOrg(youth(relationship = "x".repeat(201))))
        assertNull(PartgStatementRules.validateYouthOrg(youth(relationship = "x".repeat(200))))
        assertEquals(PartgAnnexViolation.OUT_OF_RANGE, PartgStatementRules.validateYouthOrg(youth(income = -1L)))
        assertEquals(
            PartgAnnexViolation.OUT_OF_RANGE,
            PartgStatementRules.validateYouthOrg(
                youth(
                    expenses =
                        PartgAnnexRules.MAX_AMOUNT_CENTS + 1,
                ),
            ),
        )
    }
}
