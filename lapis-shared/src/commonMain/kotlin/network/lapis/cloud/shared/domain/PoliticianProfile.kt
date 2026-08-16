package network.lapis.cloud.shared.domain

import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/** Persisted lifecycle state of a `politician_profile` row -- flipped only by an explicit BOARD/ADMIN [network.lapis.cloud.shared.rpc.IPoliticianService.grantPoliticianStatus]/[network.lapis.cloud.shared.rpc.IPoliticianService.revokePoliticianStatus] call, never automatically. */
@Serializable
enum class PoliticianProfileStatus { ACTIVE, FORMER }

/** One member's Like/Dislike on one politician's profile -- the "Korb" input the shared LTR-weight pool is split by. See `20-politician.kuml.kts` file header. */
@Serializable
enum class PoliticianReactionValue { LIKE, DISLIKE }

/**
 * Distinguishes who cast a [PoliticianReactionDto] -- MEMBER for an [MemberStatus.ACTIVE] member,
 * GAST for a federated OIDC guest ([MemberStatus.GUEST]). Frozen at cast time on
 * `politician_reaction.rater_type`, NOT re-derived from the rater's current status on every read
 * -- a historical fact about HOW this vote was cast, same "immutable historical fact" character
 * `cast_at` itself already has. Introduced in the guest-rating wave that closes V0.6.4's own
 * "member-only rating, no Gast basket" scope cut -- see `20-politician.kuml.kts` file header and
 * `network.lapis.cloud.server.rpc.PoliticianService` KDoc "Guest-rating weighting" for the full
 * rationale. MEMBER listed first, mirroring `DonorType { MEMBER, EXTERNAL }`'s own precedent.
 */
@Serializable
enum class PoliticianRaterType { MEMBER, GAST }

/**
 * A politician's profile. All six weight/count fields are always computed fresh on read --
 * [memberTrustWeight]/[memberLikeCount]/[memberDislikeCount] from the current LTR ledger and
 * MEMBER-cast [PoliticianReactionDto] rows (see
 * `network.lapis.cloud.server.rpc.PoliticianTrustWeightCalculator.computeMemberTrustWeights`),
 * [guestTrustWeight]/[guestLikeCount]/[guestDislikeCount] from GAST-cast rows alone (see
 * `computeGuestTrustWeights`) -- never a cached/persisted snapshot; a manually-triggered
 * historical trace of them is available separately via
 * [network.lapis.cloud.shared.rpc.IPoliticianService.getWeightHistory]
 * ([PoliticianWeightSnapshotDto]).
 *
 * **[guestTrustWeight] is deliberately NOT LTR-weighted, unlike [memberTrustWeight]** -- a guest
 * structurally cannot hold LTR yet (no guest-earning mechanism exists anywhere in this codebase).
 * [guestTrustWeight] is the plain, unweighted guest basket
 * (`max(0, guestLikeCount - guestDislikeCount)`), the same shape `17-crowdfunding.kuml.kts`'s
 * Verteilungs-Korb already establishes for its own unweighted democratic basket -- see
 * `PoliticianTrustWeightCalculator.computeGuestTrustWeights` KDoc for the full "why not run it
 * through the shared-LTR-pool apportionment" reasoning. This is an explicit, disclosed interim
 * simplification, not the final intended mechanic -- revisit once/if guest LTR-earning ships.
 *
 * [combinedTrustWeight] is the literal sum `memberTrustWeight + guestTrustWeight` -- what
 * `getTopPoliticians` sorts by, per the concept's "Top-6... Mitglieder + Gäste zusammengefasst".
 * Because the two addends are NOT commensurable units (one is a share of real LTR wealth, the
 * other a raw vote count), this sum is not a "fair blend" of two equally-scaled signals --
 * documented here, not silently presented as one.
 */
@Serializable
data class PoliticianProfileDto(
    val id: String,
    val memberId: String,
    val displayName: String,
    val status: PoliticianProfileStatus,
    val mandateText: String?,
    val grantedAt: LocalDateTime,
    val grantedByDisplayName: String,
    val revokedAt: LocalDateTime?,
    val revokedByDisplayName: String?,
    val memberTrustWeight: Decimal,
    val memberLikeCount: Int,
    val memberDislikeCount: Int,
    val guestTrustWeight: Decimal,
    val guestLikeCount: Int,
    val guestDislikeCount: Int,
    val combinedTrustWeight: Decimal,
)

@Serializable
data class PoliticianReactionDto(
    val id: String,
    val politicianMemberId: String,
    val value: PoliticianReactionValue,
    val castAt: LocalDateTime,
    val raterType: PoliticianRaterType,
)

/** One manually-triggered monthly snapshot of a politician's computed weight -- see `20-politician.kuml.kts` file header "politician_weight_snapshot is a manually-triggered historical record". */
@Serializable
data class PoliticianWeightSnapshotDto(
    val id: String,
    val politicianMemberId: String,
    val periodMonth: LocalDate,
    val memberTrustWeight: Decimal,
    val memberLikeCount: Int,
    val memberDislikeCount: Int,
    val guestTrustWeight: Decimal,
    val guestLikeCount: Int,
    val guestDislikeCount: Int,
    val combinedTrustWeight: Decimal,
    val computedAt: LocalDateTime,
)
