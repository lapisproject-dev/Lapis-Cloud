package network.lapis.cloud.client

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.BreachDeadlineStatus
import network.lapis.cloud.shared.domain.BreachStatus
import network.lapis.cloud.shared.domain.DataBreachIncidentDto
import network.lapis.cloud.shared.domain.RiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compliance UI wave -- covers only the pure, DOM-independent functions factored out of
 * `DsgvoComplianceScreen.kt`: D7's breach-list re-sort, D8(a)'s exact banner copy, D11's AVV
 * overdue-review caption, the tri-state-boolean helpers, and the optional date/enum parsers --
 * same scope posture as [AuditLogScreenTest]/[ComplianceLabelsTest] (no DOM/rendering test harness
 * exists in this module).
 */
class DsgvoComplianceScreenTest {
    // ---- D7: breach list re-sort --------------------------------------------------------------

    private fun incident(
        id: String,
        deadlineStatus: BreachDeadlineStatus,
        deadline: LocalDateTime,
    ): DataBreachIncidentDto =
        DataBreachIncidentDto(
            id = id,
            discoveredAt = LocalDateTime(2026, 1, 1, 0, 0),
            description = "Test",
            affectedDataCategories = "E-Mail-Adressen",
            estimatedAffectedPersons = null,
            riskAssessment = null,
            riskLevel = null,
            authorityNotificationRequired = null,
            authorityNotifiedAt = null,
            dataSubjectsNotifiedAt = null,
            status = BreachStatus.REPORTED,
            reportedAt = LocalDateTime(2026, 1, 1, 0, 0),
            reportedBy = "member-1",
            reportedByDisplayName = null,
            updatedAt = null,
            updatedBy = null,
            authorityNotificationDeadline = deadline,
            deadlineStatus = deadlineStatus,
        )

    @Test
    fun breachDeadlineDisplayRank_overdueFirstSatisfiedLast() {
        assertEquals(0, breachDeadlineDisplayRank(BreachDeadlineStatus.OVERDUE))
        assertEquals(1, breachDeadlineDisplayRank(BreachDeadlineStatus.DUE_SOON))
        assertEquals(2, breachDeadlineDisplayRank(BreachDeadlineStatus.WITHIN_WINDOW))
        assertEquals(3, breachDeadlineDisplayRank(BreachDeadlineStatus.SATISFIED))
    }

    @Test
    fun sortBreachIncidentsForDisplay_groupsByStatusThenDeadlineAscending() {
        val satisfied = incident("satisfied", BreachDeadlineStatus.SATISFIED, LocalDateTime(2026, 1, 1, 0, 0))
        val withinWindow = incident("within-window", BreachDeadlineStatus.WITHIN_WINDOW, LocalDateTime(2026, 1, 2, 0, 0))
        val overdueLater = incident("overdue-later", BreachDeadlineStatus.OVERDUE, LocalDateTime(2026, 1, 5, 0, 0))
        val overdueEarlier = incident("overdue-earlier", BreachDeadlineStatus.OVERDUE, LocalDateTime(2026, 1, 3, 0, 0))
        val dueSoon = incident("due-soon", BreachDeadlineStatus.DUE_SOON, LocalDateTime(2026, 1, 4, 0, 0))

        // Deliberately NOT already in display order (mirrors the server's own newest-first-by-
        // reportedAt order, which this function must override).
        val input = listOf(satisfied, withinWindow, overdueLater, overdueEarlier, dueSoon)

        val sorted = sortBreachIncidentsForDisplay(input)

        assertEquals(listOf("overdue-earlier", "overdue-later", "due-soon", "within-window", "satisfied"), sorted.map { it.id })
    }

    @Test
    fun sortBreachIncidentsForDisplay_emptyListStaysEmpty() {
        assertTrue(sortBreachIncidentsForDisplay(emptyList()).isEmpty())
    }

    // ---- D8(a): honesty banners -----------------------------------------------------------

    @Test
    fun dsfaBannerText_namesTheHumanDecisionFieldNotAComputedVerdict() {
        val text = dsfaBannerText()
        assertTrue(text.contains("DSFA erforderlich"))
        assertTrue(text.contains("Art. 35"))
        assertTrue(text.contains("keine"))
    }

    @Test
    fun breachBannerText_namesTheHumanDecisionFieldAndTheStatutoryBasis() {
        val text = breachBannerText()
        assertTrue(text.contains("Meldung an Aufsichtsbehörde erforderlich"))
        assertTrue(text.contains("Art. 33"))
        assertTrue(text.contains("72"))
    }

    // ---- D11: AVV overdue-review caption ---------------------------------------------------

    @Test
    fun avvReviewOverdueCaption_isNonBlankAndNamesThePrueftermin() {
        assertTrue(avvReviewOverdueCaption().contains("Prüftermin"))
    }

    // ---- tri-state boolean helpers ---------------------------------------------------------

    @Test
    fun triStateBooleanLabel_coversAllThreeStates() {
        assertEquals("Ja", triStateBooleanLabel(true))
        assertEquals("Nein", triStateBooleanLabel(false))
        assertEquals("Noch nicht festgelegt", triStateBooleanLabel(null))
    }

    @Test
    fun parseTriStateBoolean_roundTripsWithTriStateBooleanLabelsUnderlyingValues() {
        assertEquals(true, parseTriStateBoolean("true"))
        assertEquals(false, parseTriStateBoolean("false"))
        assertNull(parseTriStateBoolean(""))
        assertNull(parseTriStateBoolean(null))
        assertNull(parseTriStateBoolean("garbage"))
    }

    // ---- parseOptionalEnum -------------------------------------------------------------------

    @Test
    fun parseOptionalEnum_blankOrNullIsNull() {
        assertNull(parseOptionalEnum<RiskLevel>(null))
        assertNull(parseOptionalEnum<RiskLevel>(""))
        assertNull(parseOptionalEnum<RiskLevel>("   "))
    }

    @Test
    fun parseOptionalEnum_validNameParses() {
        assertEquals(RiskLevel.HIGH, parseOptionalEnum<RiskLevel>("HIGH"))
    }

    // ---- parseOptionalDate ------------------------------------------------------------------

    @Test
    fun parseOptionalDate_nullOrBlankIsNull() {
        assertNull(parseOptionalDate(null))
        assertNull(parseOptionalDate(""))
        assertNull(parseOptionalDate("   "))
    }

    @Test
    fun parseOptionalDate_unparsableIsNull() {
        assertNull(parseOptionalDate("not-a-date"))
    }

    @Test
    fun parseOptionalDate_validParses() {
        assertEquals(LocalDate(2026, 3, 15), parseOptionalDate("2026-03-15"))
    }

    // ---- parseRequiredDateTime ----------------------------------------------------------------

    @Test
    fun parseRequiredDateTime_nullOrBlankIsNull() {
        assertNull(parseRequiredDateTime(null))
        assertNull(parseRequiredDateTime(""))
    }

    @Test
    fun parseRequiredDateTime_unparsableIsNull() {
        assertNull(parseRequiredDateTime("not-a-datetime"))
    }

    @Test
    fun parseRequiredDateTime_validParses() {
        assertEquals(LocalDateTime(2026, 3, 15, 9, 0), parseRequiredDateTime("2026-03-15T09:00"))
    }
}
