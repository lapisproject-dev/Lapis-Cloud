package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.4.4 "Mitgliederlebenszyklus: Familienmitgliedschaften" -- see
 * `42-member-family.kuml.kts` for the full domain rationale. Literal order load-bearing
 * (`MemberFamilySchemaDriftTest` pins it) -- longest literal `DEPENDENT` (9 chars) sizes
 * `member_family_link.role VARCHAR(9)`. Exactly two literals, deliberately -- see the kUML file
 * header "Exactly two role literals" for why a relationship/kinship classification
 * (`GUARDIAN`/`CHILD`/`SPOUSE`) is never added here.
 */
@Serializable
enum class FamilyMemberRole { PAYER, DEPENDENT }

/**
 * One `member_family_link` row, joined with its member's current display name/status/tier for
 * direct rendering -- same denormalized-join idiom [MemberHonorDto.memberDisplayName] already
 * establishes.
 */
@Serializable
data class MemberFamilyLinkDto(
    val id: String,
    val familyId: String,
    val memberId: String,
    val memberDisplayName: String,
    val memberStatus: MemberStatus,
    val role: FamilyMemberRole,
    val membershipTierId: String? = null,
    val membershipTierName: String? = null,
    val linkedAt: LocalDateTime,
    val linkedById: String,
)

/** Roster-list projection of a family -- [hasPayer] drives the "Kein Zahler -- bitte zuweisen" warning badge. */
@Serializable
data class MemberFamilySummaryDto(
    val id: String,
    val name: String,
    val memberCount: Int,
    val payerMemberId: String? = null,
    val payerDisplayName: String? = null,
) {
    val hasPayer: Boolean get() = payerMemberId != null
}

/** Full detail view of one family, backing [network.lapis.cloud.shared.rpc.IMemberFamilyService.getFamily]. */
@Serializable
data class MemberFamilyDetailDto(
    val id: String,
    val name: String,
    val createdById: String,
    val createdAt: LocalDateTime,
    val links: List<MemberFamilyLinkDto>,
)

/** Offset-paged result -- see [MemberFamilyLimits.MAX_LIMIT] for the server-side page-size cap. */
@Serializable
data class MemberFamilyPageDto(
    val entries: List<MemberFamilySummaryDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

/**
 * One dependent approaching (or past) [FamilyMembershipRules.MAJORITY_AGE_YEARS]. [turnsMajorOn]
 * is a real calendar date derived from the dependent's `dateOfBirth` -- unlike
 * [MemberAnniversaryOverviewDto], which deliberately shows only a derived AGE, this DTO's whole
 * point IS the date (a board member needs to know WHEN to assign a tier), so the trivial
 * birthdate-rederivation this enables is accepted openly rather than hidden -- guarded instead by
 * role (BOARD/ADMIN only, never a self-service view) and by scope (only DEPENDENT rows already
 * linked into a family, never the full membership). [alreadyMajor] entries are ALWAYS included
 * regardless of the requested window -- see `MemberFamilyService.listUpcomingMajorities` KDoc for
 * why silently dropping an overdue entry after 90 days is exactly the class of bug this field
 * exists to prevent.
 */
@Serializable
data class UpcomingMajorityEntryDto(
    val linkId: String,
    val familyId: String,
    val familyName: String,
    val memberId: String,
    val memberDisplayName: String,
    val turnsMajorOn: LocalDate,
    val alreadyMajor: Boolean,
    val shiftedFromLeapDay: Boolean,
)

/**
 * [dependentsWithoutDateOfBirth] is the mandatory coverage line -- same "tell me what I could not
 * compute" discipline [MemberAnniversaryOverviewDto.membersWithoutDateOfBirth] already
 * establishes, so a dependent without a recorded birthdate does not just silently vanish from the
 * list.
 */
@Serializable
data class UpcomingMajorityOverviewDto(
    val windowDays: Int,
    val from: LocalDate,
    val through: LocalDate,
    val entries: List<UpcomingMajorityEntryDto>,
    val dependentCount: Int,
    val dependentsWithoutDateOfBirth: Int,
)

object MemberFamilyLimits {
    const val NAME_MAX_LENGTH = 200
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200
    const val MAX_SEARCH_LENGTH = 200
}

/**
 * §2 BGB, hart kodiert -- KEIN Konfigurationsfeld. Ob ein Verein einen Studenten-/Jugendtarif bis
 * 25 anbietet, ist eine TARIF-Frage (`membership_tier`), keine Frage der rechtlichen Volljährigkeit.
 */
object FamilyMembershipRules {
    const val MAJORITY_AGE_YEARS = 18
}
