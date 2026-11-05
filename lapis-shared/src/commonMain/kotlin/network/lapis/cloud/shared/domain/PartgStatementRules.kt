package network.lapis.cloud.shared.domain

/**
 * V1.9.283 (R3b) -- pure rules of statement C (sponsoring) and F (youth organisations), shared by client and server (same function on both
 * sides, pinned by `PartgStatementRulesTest`). The limits of C are COMPUTED from the stored entries, never enforced on save.
 */
object PartgStatementRules {
    /** A single service above 750.00 EUR (strictly greater). */
    const val SPONSORING_SINGLE_THRESHOLD_CENTS = 75_000L

    /** More than 6,000.00 EUR in a year per sponsor and unit (strictly greater). */
    const val SPONSORING_YEARLY_THRESHOLD_CENTS = 600_000L
    const val MAX_SPONSORS_PER_UNIT = 500
    const val MAX_ENTRIES_PER_SPONSOR = 100
    const val MAX_ENTRIES_PER_UNIT = 2000
    const val PURPOSE_MAX = 500
    const val MAX_YOUTH_ORGS_PER_UNIT = 20
    const val RELATIONSHIP_MAX = 200

    /** Bounded by the caps: 100 * MAX_AMOUNT_CENTS < Long.MAX_VALUE; callers validate the count first. */
    fun yearlySum(amounts: List<Long>): Long = amounts.sum()

    fun qualification(amounts: List<Long>): PartgSponsorQualification {
        val single = amounts.any { it > SPONSORING_SINGLE_THRESHOLD_CENTS }
        val yearly = yearlySum(amounts) > SPONSORING_YEARLY_THRESHOLD_CENTS
        return when {
            single && yearly -> PartgSponsorQualification.BOTH
            single -> PartgSponsorQualification.SINGLE
            yearly -> PartgSponsorQualification.YEARLY
            else -> PartgSponsorQualification.NONE
        }
    }

    /** Validates a sponsor input for the report [year]; the text fields are checked in their normalised form. */
    fun validateSponsor(
        input: PartgSponsorInputDto,
        year: Int,
    ): PartgAnnexViolation? {
        if (input.entries.isEmpty()) return PartgAnnexViolation.ENTRIES_EMPTY
        if (input.entries.size > MAX_ENTRIES_PER_SPONSOR) return PartgAnnexViolation.TOO_MANY
        for (entry in input.entries) {
            if (entry.amountCents <= 0L) return PartgAnnexViolation.OUT_OF_RANGE
            if (entry.amountCents > PartgAnnexRules.MAX_AMOUNT_CENTS) return PartgAnnexViolation.AMOUNT_TOO_LARGE
            if (entry.date.year != year) return PartgAnnexViolation.DATE_OUTSIDE_YEAR
            PartgAnnexRules.badText(value = PartgAnnexRules.normalize(entry.purpose), max = PURPOSE_MAX)?.let { return it }
        }
        val ids = input.entries.mapNotNull { it.id }
        if (ids.toSet().size != ids.size) return PartgAnnexViolation.DUPLICATE_ID
        val name = PartgAnnexRules.normalize(input.name) ?: return PartgAnnexViolation.NAME_EMPTY
        PartgAnnexRules.badText(value = name, max = PartgAnnexRules.NAME_MAX)?.let { return it }
        PartgAnnexRules.badText(value = PartgAnnexRules.normalize(input.street), max = PartgAnnexRules.STREET_MAX)?.let { return it }
        PartgAnnexRules.badText(value = PartgAnnexRules.normalize(input.postalCode), max = PartgAnnexRules.POSTAL_MAX)?.let { return it }
        PartgAnnexRules.badText(value = PartgAnnexRules.normalize(input.city), max = PartgAnnexRules.CITY_MAX)?.let { return it }
        if (!Regex("[A-Z]{2}").matches(input.country)) return PartgAnnexViolation.COUNTRY_INVALID
        return null
    }

    fun validateYouthOrg(input: PartgYouthOrgInputDto): PartgAnnexViolation? {
        val name = PartgAnnexRules.normalize(input.name) ?: return PartgAnnexViolation.NAME_EMPTY
        PartgAnnexRules.badText(value = name, max = PartgAnnexRules.NAME_MAX)?.let { return it }
        PartgAnnexRules.badText(value = PartgAnnexRules.normalize(input.relationship), max = RELATIONSHIP_MAX)?.let { return it }
        val income = input.incomeCents
        if (income != null && (income < 0L || income > PartgAnnexRules.MAX_AMOUNT_CENTS)) return PartgAnnexViolation.OUT_OF_RANGE
        val expenses = input.expensesCents
        if (expenses != null && (expenses < 0L || expenses > PartgAnnexRules.MAX_AMOUNT_CENTS)) return PartgAnnexViolation.OUT_OF_RANGE
        return null
    }
}
