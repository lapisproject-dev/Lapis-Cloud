package network.lapis.cloud.shared.domain

/** Why an input of the separate statements is rejected. The code is the whole message: never the input text. */
enum class PartgAnnexViolation {
    AMOUNT_NOT_ABOVE_THRESHOLD,
    AMOUNT_TOO_LARGE,
    NAME_EMPTY,
    FIELD_TOO_LONG,
    CONTROL_CHARACTER,
    COUNTRY_INVALID,
    TOO_MANY,
    OUT_OF_RANGE,
}

/** Pure rules of the R3 inputs, shared by client and server (same function on both sides, pinned by `PartgAnnexRulesTest`). */
object PartgAnnexRules {
    /** A person is listed only with MORE than 10,000.00 EUR in a year (strictly greater). */
    const val LARGE_DONOR_THRESHOLD_CENTS = 1_000_000L
    const val MAX_DONORS_PER_UNIT = 500
    const val MAX_HOLDINGS = 50
    const val MAX_NOTE_LENGTH = 4000
    const val NAME_MAX = 200
    const val STREET_MAX = 200
    const val POSTAL_MAX = 10
    const val CITY_MAX = 100
    const val SEAT_MAX = 100
    const val MEMBER_COUNT_MAX = 10_000_000
    const val MAX_AMOUNT_CENTS = 99_999_999_999_999L
    const val P05_ABS_CENTS = 100_000L
    const val P05_RATIO_PERCENT = 50L
    const val MAX_FINDINGS = 2000

    private val countryPattern = Regex("[A-Z]{2}")

    /** Trims; blank becomes `null`. */
    fun normalize(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

    /** C0, DEL, C1, line and paragraph separator, and the bidi control characters. */
    fun containsControl(text: String): Boolean =
        text.any {
            it.isISOControl() || it == ' ' || it == ' ' || it in '‪'..'‮' || it in '⁦'..'⁩'
        }

    private fun badText(
        value: String?,
        max: Int,
    ): PartgAnnexViolation? =
        when {
            value == null -> null
            value.length > max -> PartgAnnexViolation.FIELD_TOO_LONG
            containsControl(value) -> PartgAnnexViolation.CONTROL_CHARACTER
            else -> null
        }

    /** Validates a donor input; the text fields are checked in their normalised form. */
    fun validateDonor(input: PartgLargeDonorInputDto): PartgAnnexViolation? {
        if (input.amountCents <= LARGE_DONOR_THRESHOLD_CENTS) return PartgAnnexViolation.AMOUNT_NOT_ABOVE_THRESHOLD
        if (input.amountCents > MAX_AMOUNT_CENTS) return PartgAnnexViolation.AMOUNT_TOO_LARGE
        val name = normalize(input.name) ?: return PartgAnnexViolation.NAME_EMPTY
        badText(value = name, max = NAME_MAX)?.let { return it }
        badText(value = normalize(input.street), max = STREET_MAX)?.let { return it }
        badText(value = normalize(input.postalCode), max = POSTAL_MAX)?.let { return it }
        badText(value = normalize(input.city), max = CITY_MAX)?.let { return it }
        if (!countryPattern.matches(input.country)) return PartgAnnexViolation.COUNTRY_INVALID
        return null
    }

    fun validateFigures(
        deductions: Map<PartgDeduction, Long?>,
        memberCount: Int?,
    ): PartgAnnexViolation? {
        if (deductions.values.any { it != null && (it < 0L || it > MAX_AMOUNT_CENTS) }) return PartgAnnexViolation.OUT_OF_RANGE
        if (memberCount != null && (memberCount < 0 || memberCount > MEMBER_COUNT_MAX)) return PartgAnnexViolation.OUT_OF_RANGE
        return null
    }

    fun validateHolding(holding: PartgHoldingDto): PartgAnnexViolation? {
        val name = normalize(holding.name) ?: return PartgAnnexViolation.NAME_EMPTY
        badText(value = name, max = NAME_MAX)?.let { return it }
        badText(value = normalize(holding.seat), max = SEAT_MAX)?.let { return it }
        val share = holding.shareBasisPoints
        if (share != null && (share < 0 || share > 10_000)) return PartgAnnexViolation.OUT_OF_RANGE
        val capital = holding.capitalCents
        if (capital != null && (capital < 0L || capital > MAX_AMOUNT_CENTS)) return PartgAnnexViolation.OUT_OF_RANGE
        val result = holding.resultCents
        if (result != null && (result > MAX_AMOUNT_CENTS || result < -MAX_AMOUNT_CENTS)) return PartgAnnexViolation.OUT_OF_RANGE
        return null
    }

    /** Validates the five blocks and the holdings; the block list must contain every block exactly once. */
    fun validateNotes(
        notes: List<PartgNoteDto>,
        holdings: List<PartgHoldingDto>,
    ): PartgAnnexViolation? {
        if (notes.map { it.block }.toSet().size != notes.size ||
            notes.size > PartgNoteBlock.entries.size
        ) {
            return PartgAnnexViolation.TOO_MANY
        }
        if (holdings.size > MAX_HOLDINGS) return PartgAnnexViolation.TOO_MANY
        for (note in notes) {
            val body = note.body
            if (body != null && body.length > MAX_NOTE_LENGTH) return PartgAnnexViolation.FIELD_TOO_LONG
            // line breaks are allowed in a note, every other control character is not
            if (body != null && body.any { it != '\n' && containsControl(it.toString()) }) {
                return PartgAnnexViolation.CONTROL_CHARACTER
            }
            if (note.block != PartgNoteBlock.RELIEF_28 && (note.reliefAbs1 != null || note.reliefAbs3 != null)) {
                return PartgAnnexViolation.OUT_OF_RANGE
            }
        }
        holdings.forEach { validateHolding(it)?.let { v -> return v } }
        return null
    }
}
