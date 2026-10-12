package network.lapis.cloud.shared.domain

import kotlinx.serialization.Serializable

/**
 * Welle V1.9.121 -- the bulk reconciliation of legacy free-text country / nationality values (`member.country`,
 * `member.nationality`) against the selection lists. A board/admin action: a read-only dry run ([CountryLegacyMappingReportDto])
 * followed by an explicit apply that is bound to the dry run via [CountryLegacyMappingReportDto.fingerprint].
 *
 * Privacy: the report never lists a value that fewer than [MIN_GROUP_SIZE] members share (such a value is nearly an identifier);
 * those only appear in [RareSummaryDto] as counts.
 */
object CountryLegacyMapping {
    /** A value shared by fewer members is not listed verbatim. */
    const val MIN_GROUP_SIZE = 3

    /** Hard cap of rows one apply may touch. */
    const val MAX_APPLY_ROWS = 20_000
}

@Serializable
enum class LegacyOutcome { RECOGNISED, AMBIGUOUS, UNRECOGNISED }

/** One distinct legacy value (normalised for display: trimmed) shared by [count] >= [CountryLegacyMapping.MIN_GROUP_SIZE] members. */
@Serializable
data class LegacyLineDto(
    val value: String,
    val outcome: LegacyOutcome,
    /** The codes the value maps to (one for a country, one or more for a nationality); empty unless [outcome] is RECOGNISED. */
    val targets: List<String>,
    val count: Int,
)

/** Counts for all values shared by fewer than [CountryLegacyMapping.MIN_GROUP_SIZE] members. */
@Serializable
data class RareSummaryDto(
    val members: Int,
    val recognised: Int,
)

@Serializable
data class LegacySectionDto(
    /** Members that carry a legacy value and no code yet. */
    val totalMembersWithLegacy: Int,
    val recognisedMembers: Int,
    val ambiguousMembers: Int,
    val unrecognisedMembers: Int,
    /** Members the bulk run skips on purpose (accounts with the ADMIN role other than the caller): fix them individually. */
    val protectedSkippedMembers: Int,
    val lines: List<LegacyLineDto>,
    val rare: RareSummaryDto,
)

@Serializable
data class CountryLegacyMappingReportDto(
    val country: LegacySectionDto,
    val nationality: LegacySectionDto,
    /** SHA-256 over the whole plan (including rare values, excluding counts); [apply][network.lapis.cloud.shared.rpc.IMemberService.applyCountryLegacyMapping] refuses a stale one. */
    val fingerprint: String,
)

@Serializable
data class CountryLegacyCountsDto(
    val recognised: Int,
    val applied: Int,
    /** Members that still carry a legacy value afterwards. */
    val open: Int,
    val ambiguous: Int,
)

@Serializable
data class CountryLegacyMappingResultDto(
    val country: CountryLegacyCountsDto,
    val nationality: CountryLegacyCountsDto,
)
