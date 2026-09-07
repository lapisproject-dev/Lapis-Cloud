package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Welle V1.4.4.5 "Sterbefall-Workflow". Pins [DeathDateRules.violation] -- the ONE place a
 * "is this date of death plausible" question is answered, shared by `MemberService`
 * (server-side, `requirePlausibleDeathDate`) and `MemberAdministrationScreen`
 * (client-side, the editor dialog's own inline validation).
 */
class DeathDateRulesTest {
    private val today = LocalDate(2026, 9, 7)

    @Test
    fun violation_nullDateOfDeath_isAlwaysNull() {
        assertNull(DeathDateRules.violation(dateOfDeath = null, dateOfBirth = null, today = today))
        assertNull(DeathDateRules.violation(dateOfDeath = null, dateOfBirth = LocalDate(1990, 1, 1), today = today))
    }

    @Test
    fun violation_todaysDate_isAllowed() {
        assertNull(DeathDateRules.violation(dateOfDeath = today, dateOfBirth = null, today = today))
    }

    @Test
    fun violation_pastDate_isAllowed() {
        assertNull(DeathDateRules.violation(dateOfDeath = LocalDate(2026, 1, 1), dateOfBirth = null, today = today))
    }

    @Test
    fun violation_tomorrow_isInFuture() {
        val tomorrow = LocalDate(2026, 9, 8)
        assertEquals(DeathDateViolation.IN_FUTURE, DeathDateRules.violation(dateOfDeath = tomorrow, dateOfBirth = null, today = today))
    }

    @Test
    fun violation_beforeDateOfBirth_isBeforeBirth() {
        val dateOfBirth = LocalDate(1990, 1, 1)
        val beforeBirth = LocalDate(1989, 12, 31)
        assertEquals(
            DeathDateViolation.BEFORE_BIRTH,
            DeathDateRules.violation(dateOfDeath = beforeBirth, dateOfBirth = dateOfBirth, today = today),
        )
    }

    @Test
    fun violation_sameAsDateOfBirth_isAllowed() {
        val dateOfBirth = LocalDate(1990, 1, 1)
        assertNull(DeathDateRules.violation(dateOfDeath = dateOfBirth, dateOfBirth = dateOfBirth, today = today))
    }

    @Test
    fun violation_nullDateOfBirth_onlyChecksFuture() {
        assertNull(DeathDateRules.violation(dateOfDeath = LocalDate(1800, 1, 1), dateOfBirth = null, today = today))
        assertEquals(
            DeathDateViolation.IN_FUTURE,
            DeathDateRules.violation(dateOfDeath = LocalDate(2026, 9, 8), dateOfBirth = null, today = today),
        )
    }

    /**
     * Regressionsschutz: bewusst KEIN Abgleich gegen ein drittes "joinedAt"-Datum, egal wie
     * unplausibel die Kombination aussieht -- siehe [DeathDateRules.violation] KDoc "Bewusst KEIN
     * Abgleich gegen joinedAt". Diese Funktion kennt joinedAt nicht einmal als Parameter; dieser
     * Test dokumentiert nur, dass ein Datum lange vor jedem plausiblen Beitrittsdatum trotzdem
     * zulässig bleibt, solange es nicht in der Zukunft liegt oder vor dateOfBirth.
     */
    @Test
    fun violation_farInThePast_isStillAllowed_noJoinedAtComparison() {
        assertNull(DeathDateRules.violation(dateOfDeath = LocalDate(1950, 1, 1), dateOfBirth = null, today = today))
    }
}
