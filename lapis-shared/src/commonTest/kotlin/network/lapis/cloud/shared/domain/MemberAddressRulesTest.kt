package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Welle V1.9.33 -- pins [MemberAddressRules], shared by `MemberService` and the client form. */
class MemberAddressRulesTest {
    private val today = LocalDate(2026, 10, 2)

    @Test
    fun normalize_trimsAndTurnsBlankIntoNull() {
        assertNull(MemberAddressRules.normalize(null))
        assertNull(MemberAddressRules.normalize("   "))
        assertNull(MemberAddressRules.normalize(""))
        assertEquals("Hauptstr. 1", MemberAddressRules.normalize("  Hauptstr. 1 "))
    }

    @Test
    fun textViolation_exactLengthPasses_oneMoreFailsForEveryField() {
        MemberAddressField.entries.forEach { field ->
            val max = MemberAddressRules.maxLength(field)
            assertNull(MemberAddressRules.textViolation(field = field, normalized = "a".repeat(max)))
            assertEquals(MemberAddressViolation.TOO_LONG, MemberAddressRules.textViolation(field = field, normalized = "a".repeat(max + 1)))
        }
    }

    @Test
    fun textViolation_controlCharactersAreRejected() {
        listOf("a\nb", "a\tb", "a\rb", "\u0000", "a\u007Fb", "a\u0085b").forEach {
            assertEquals(
                MemberAddressViolation.CONTROL_CHARACTER,
                MemberAddressRules.textViolation(field = MemberAddressField.CITY, normalized = it),
                it,
            )
        }
    }

    @Test
    fun textViolation_nullAndUmlautsAreFine() {
        assertNull(MemberAddressRules.textViolation(field = MemberAddressField.CITY, normalized = null))
        assertNull(MemberAddressRules.textViolation(field = MemberAddressField.CITY, normalized = "Köln-Mülheim"))
    }

    @Test
    fun birthDate_boundaries() {
        assertNull(MemberAddressRules.birthDateViolation(dateOfBirth = null, dateOfDeath = null, today = today))
        assertNull(MemberAddressRules.birthDateViolation(dateOfBirth = today, dateOfDeath = null, today = today))
        assertEquals(
            MemberAddressViolation.BIRTH_IN_FUTURE,
            MemberAddressRules.birthDateViolation(dateOfBirth = LocalDate(2026, 10, 3), dateOfDeath = null, today = today),
        )
        assertEquals(
            MemberAddressViolation.BIRTH_BEFORE_1900,
            MemberAddressRules.birthDateViolation(dateOfBirth = LocalDate(1899, 12, 31), dateOfDeath = null, today = today),
        )
        assertNull(MemberAddressRules.birthDateViolation(dateOfBirth = LocalDate(1900, 1, 1), dateOfDeath = null, today = today))
    }

    @Test
    fun birthDate_afterDeath_isRejected_sameDayIsAllowed() {
        val death = LocalDate(2020, 5, 5)
        assertEquals(
            MemberAddressViolation.BIRTH_AFTER_DEATH,
            MemberAddressRules.birthDateViolation(dateOfBirth = LocalDate(2020, 5, 6), dateOfDeath = death, today = today),
        )
        assertNull(MemberAddressRules.birthDateViolation(dateOfBirth = death, dateOfDeath = death, today = today))
    }
}
