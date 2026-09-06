package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.4.4.3 "Mitgliederlebenszyklus: Ehrungsverwaltung" -- see `41-member-honor.kuml.kts` for
 * the full domain rationale. Literal order load-bearing (`MemberHonorSchemaDriftTest` pins it) --
 * longest literal `HONORARY_MEMBERSHIP` (19 chars) sizes `member_honor.category VARCHAR(19)`.
 */
@Serializable
enum class MemberHonorCategory { HONORARY_MEMBERSHIP, SERVICE_AWARD, LOYALTY_AWARD, OTHER }

/**
 * One `member_honor` row -- see [MemberHonorCategory] KDoc and `MemberHonorService` for the
 * read/write path. [memberDisplayName] is a plain denormalized join (same idiom
 * `BoardMembershipDto.memberDisplayName` already establishes) -- see `MemberHonorsScreen.kt`'s own
 * KDoc for why this DTO deliberately carries no `anonymized` flag (Design-Entscheidung: an
 * anonymized member's `displayName` is already overwritten by `FoundationPersonalData`'s own Art.
 * 17 erasure, so no separate badge is needed here).
 */
@Serializable
data class MemberHonorDto(
    val id: String,
    val memberId: String,
    val memberDisplayName: String,
    val category: MemberHonorCategory,
    val title: String,
    val awardedAt: LocalDate,
    val awardedBy: String?,
    val note: String?,
    val recordedById: String,
    val recordedAt: LocalDateTime,
)

/**
 * Create/update payload -- ALL fields editable on update (bewusste Abweichung: eine Ehrung ist ein
 * seltener, von Hand geprüfter Vorgang, kein append-only Log wie `crm_interaction`; ein Tippfehler
 * im Titel oder ein falsch erfasstes Datum muss vollständig korrigierbar sein, nicht nur die
 * `note`). Server validates regardless of any client-side pre-check -- see `MemberHonorService`
 * KDoc.
 */
@Serializable
data class MemberHonorInput(
    val memberId: String,
    val category: MemberHonorCategory = MemberHonorCategory.SERVICE_AWARD,
    val title: String,
    val awardedAt: LocalDate,
    val awardedBy: String? = null,
    val note: String? = null,
)

/** Offset-paged result -- see `IMemberHonorService.listHonors` KDoc for the server-side `limit` cap. */
@Serializable
data class MemberHonorPageDto(
    val entries: List<MemberHonorDto>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

object MemberHonorLimits {
    const val TITLE_MAX_LENGTH = 200
    const val AWARDED_BY_MAX_LENGTH = 200
    const val NOTE_MAX_LENGTH = 4000
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200
}
