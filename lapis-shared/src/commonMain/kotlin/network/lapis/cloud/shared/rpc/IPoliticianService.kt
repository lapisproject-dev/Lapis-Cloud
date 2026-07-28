package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.PoliticianProfileDto
import network.lapis.cloud.shared.domain.PoliticianReactionDto
import network.lapis.cloud.shared.domain.PoliticianReactionValue
import network.lapis.cloud.shared.domain.PoliticianWeightSnapshotDto

/**
 * Politiker-Profile und Politiker-Ranking (V0.6.4) -- see `20-politician.kuml.kts` file header for
 * the full fachlich model (shared LTR-weight pool, largest-remainder apportionment, manually-
 * triggered weight snapshots).
 *
 * Every method here additionally requires
 * `network.lapis.cloud.shared.domain.OrganizationSettingsDto.politicianRankingEnabled == true` --
 * a [network.lapis.cloud.shared.rpc.ConflictException] otherwise, same
 * `requirePostalMailEnabled`-style gate `network.lapis.cloud.server.rpc.PostalMailService` already
 * establishes for its own opt-in flag. This applies even to [grantPoliticianStatus] -- a BOARD
 * member cannot silently activate the feature by granting status while it is toggled off.
 *
 * **Gast rating basket (CLOSED, guest-rating wave)**: the V0.6.4 "member-only rating, no Gast
 * basket" scope cut -- see `20-politician.kuml.kts` file header -- is closed now that V0.8.2's
 * OIDC guest-identity federation makes `MemberStatus.GAST` a real, reachable status. [castRating]/
 * [retractRating] now accept a GAST-status caller too (via
 * `network.lapis.cloud.server.rpc.requireActiveOrGuestMembership`, still excluding
 * ANTRAG/AUSGETRETEN/ABGELEHNT). [getTopPoliticians] sorts by the COMBINED (member+guest) weight;
 * [listPoliticians]/[getPoliticianProfile] expose all three (member/guest/combined) separately.
 * See [network.lapis.cloud.shared.domain.PoliticianProfileDto] KDoc for the exact weight shapes,
 * including the disclosed guest-weighting interim simplification.
 *
 * **Scope-cut (real-name enforcement)**: a documented no-op -- see `20-politician.kuml.kts` file
 * header "Scope-cut: real-name enforcement is a documented no-op" (no pseudonym-display layer
 * exists to override, same absence [IPeerTransferService] KDoc documents).
 *
 * **Scope-cut (social feed under a profile)**: out of scope -- no comment/discussion feature
 * exists anywhere in this codebase yet, same reasoning `17-crowdfunding.kuml.kts` already cuts the
 * concept document's recursive comment-weight extension for its own projects.
 */
@RpcService
interface IPoliticianService {
    /**
     * Role: BOARD/ADMIN. Upsert-by-memberId semantics: creates a new [PoliticianProfileDto], or
     * reactivates an existing FORMER one for the same member (fresh `grantedAt`/`grantedBy`,
     * cleared `revokedAt`/`revokedBy`, [mandateText] overwritten if non-null) -- never a second
     * profile row per member. Calling this again on an already-ACTIVE profile is an idempotent
     * update (mandate text/`grantedAt`/`grantedBy` refreshed), not a conflict -- the caller does
     * not need to know the current status up front.
     */
    suspend fun grantPoliticianStatus(
        memberId: String,
        mandateText: String? = null,
    ): PoliticianProfileDto

    /**
     * Role: BOARD/ADMIN. Flips [PoliticianProfileDto.status] to FORMER, sets `revokedAt`/
     * `revokedBy`, and irreversibly deletes every [PoliticianReactionDto]/[PoliticianWeightSnapshotDto]
     * row for this profile -- MEMBER-cast and GAST-cast reactions alike, and every persisted
     * member/guest/combined snapshot column ("Bewertungsstatistik wird geloescht: ... Mitglieder,
     * Gäste, Gesamt") -- the profile row itself is kept, still reachable via
     * [getPoliticianProfile]. A later [grantPoliticianStatus] on the same member reactivates this
     * same row starting back at Korb=0 for both baskets.
     */
    suspend fun revokePoliticianStatus(memberId: String): PoliticianProfileDto

    /** Role: BOARD/ADMIN. Mandate/function text edit, independent of any status change. */
    suspend fun updateMandateText(
        memberId: String,
        mandateText: String?,
    ): PoliticianProfileDto

    /**
     * Role: MEMBER+, caller must be [network.lapis.cloud.shared.domain.MemberStatus.AKTIV] OR
     * [network.lapis.cloud.shared.domain.MemberStatus.GAST] (guest-rating wave -- ANTRAG/
     * AUSGETRETEN/ABGELEHNT remain excluded). Upsert-on-unique-key, same idiom as
     * [ICrowdfundingService.castReaction] -- casting again with a different [value] changes the
     * rating, it does not add a second one. Rating itself costs the rater nothing (no LTR debit),
     * for both members and guests. [politicianMemberId] must reference an ACTIVE profile. The
     * returned [PoliticianReactionDto.raterType] reflects the caller's status at cast time.
     */
    suspend fun castRating(
        politicianMemberId: String,
        value: PoliticianReactionValue,
    ): PoliticianReactionDto

    /** Role: MEMBER+ or GAST (for themselves only) -- same gate as [castRating]. No-op if the caller has no rating on this politician. */
    suspend fun retractRating(politicianMemberId: String)

    /**
     * Any authenticated member. Returns an empty list if the caller has not rated this politician,
     * otherwise a single-element list -- NOT a nullable [PoliticianReactionDto], same JS-codegen
     * constraint [ICrowdfundingService.getMyReaction] KDoc documents for a nullable custom-DTO
     * return type.
     */
    suspend fun getMyRating(politicianMemberId: String): List<PoliticianReactionDto>

    /** Any authenticated member. [includeFormer] false (default) returns only ACTIVE profiles. */
    suspend fun listPoliticians(includeFormer: Boolean = false): List<PoliticianProfileDto>

    /** Any authenticated member. */
    suspend fun getPoliticianProfile(memberId: String): PoliticianProfileDto

    /**
     * Any authenticated member. Dashboard Top-N (ACTIVE only), sorted descending by
     * `combinedTrustWeight` (member + guest, per the concept's "Top-6... Mitglieder + Gäste
     * zusammengefasst"), ties broken by ascending member id. Separate member-only/guest-only
     * rankings can be built by the caller from [listPoliticians]'s per-politician
     * `memberTrustWeight`/`guestTrustWeight` fields.
     */
    suspend fun getTopPoliticians(limit: Int = 6): List<PoliticianProfileDto>

    /**
     * Role: BOARD/ADMIN. Manual trigger, same idiom as
     * [ICrowdfundingService.computeMonthlyDistribution] (no scheduler infrastructure exists in
     * this codebase). Idempotent per (politician, [periodMonth]) via a per-row `insertIgnore`
     * against the underlying unique index -- re-running with the identical month does not
     * duplicate or overwrite existing rows, and a month that is only partially snapshotted (e.g.
     * a politician was granted status after the first run) can be safely re-run to backfill just
     * the missing rows, exactly like [ICrowdfundingService.computeMonthlyDistribution].
     */
    suspend fun snapshotWeights(periodMonth: LocalDate): List<PoliticianWeightSnapshotDto>

    /** Any authenticated member. */
    suspend fun getWeightHistory(memberId: String): List<PoliticianWeightSnapshotDto>
}
