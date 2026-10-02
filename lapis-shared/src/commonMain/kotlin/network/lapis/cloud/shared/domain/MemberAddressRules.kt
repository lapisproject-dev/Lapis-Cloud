package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/** Welle V1.9.33 -- the five free-text columns a member edits about themselves. */
@Serializable
enum class MemberAddressField { STREET, POSTAL_CODE, CITY, COUNTRY, NATIONALITY }

/** Welle V1.9.33 -- why an address / beneficial-owner value was rejected (never carries the value). */
@Serializable
enum class MemberAddressViolation { TOO_LONG, CONTROL_CHARACTER, BIRTH_IN_FUTURE, BIRTH_BEFORE_1900, BIRTH_AFTER_DEATH }

/**
 * Welle V1.9.33 -- DOM-free plausibility rules for the self-service address and beneficial-owner
 * (GwG) data, shared by the server (`MemberService`) and the client form (inline validation).
 *
 * Lengths are measured in `String.length` (UTF-16 code units). Postgres counts code points for
 * `varchar(n)`, so for surrogate pairs (emoji) this rule counts MORE than the database would: the
 * client may reject slightly too early, never too late -- the safe side.
 *
 * Postal code and country are deliberately NOT format-checked: members live abroad and formats differ.
 */
object MemberAddressRules {
    const val STREET_MAX = 200
    const val POSTAL_CODE_MAX = 20
    const val CITY_MAX = 200
    const val COUNTRY_MAX = 100
    const val NATIONALITY_MAX = 100

    val EARLIEST_BIRTH_DATE: LocalDate = LocalDate(1900, 1, 1)

    fun maxLength(field: MemberAddressField): Int =
        when (field) {
            MemberAddressField.STREET -> STREET_MAX
            MemberAddressField.POSTAL_CODE -> POSTAL_CODE_MAX
            MemberAddressField.CITY -> CITY_MAX
            MemberAddressField.COUNTRY -> COUNTRY_MAX
            MemberAddressField.NATIONALITY -> NATIONALITY_MAX
        }

    /** Trims; blank (or `null`) becomes `null`, meaning "clear the field". */
    fun normalize(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /** Checks an already [normalize]d value; `null` is always acceptable. */
    fun textViolation(
        field: MemberAddressField,
        normalized: String?,
    ): MemberAddressViolation? =
        when {
            normalized == null -> null
            normalized.any { it.isISOControl() } -> MemberAddressViolation.CONTROL_CHARACTER
            normalized.length > maxLength(field) -> MemberAddressViolation.TOO_LONG
            else -> null
        }

    fun birthDateViolation(
        dateOfBirth: LocalDate?,
        dateOfDeath: LocalDate?,
        today: LocalDate,
    ): MemberAddressViolation? =
        when {
            dateOfBirth == null -> null
            dateOfBirth > today -> MemberAddressViolation.BIRTH_IN_FUTURE
            dateOfBirth < EARLIEST_BIRTH_DATE -> MemberAddressViolation.BIRTH_BEFORE_1900
            dateOfDeath != null && dateOfBirth > dateOfDeath -> MemberAddressViolation.BIRTH_AFTER_DEATH
            else -> null
        }
}
