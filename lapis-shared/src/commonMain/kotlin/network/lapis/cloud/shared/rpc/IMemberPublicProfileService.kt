package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.OwnPublicProfileDto

/**
 * Welle V1.9.20 "Öffentliche Seiten". Every method except [moderationRemoveBio] acts EXCLUSIVELY on
 * the caller's own profile -- there is deliberately no member-id parameter. The politician-listing
 * consent is NOT handled here: it reuses `IDsgvoService.grantPublicRankingConsent` /
 * `revokePublicRankingConsent` with `PublicRankingKind.POLITICIAN_LISTING`.
 */
@RpcService
interface IMemberPublicProfileService {
    suspend fun getOwnPublicProfile(): OwnPublicProfileDto

    /**
     * Saves the caller's short introduction. An empty (blank) [text] deletes it INCLUDING the consent.
     * Allowed while the caller is eligible (board mandate / politician) or already has a stored bio
     * (so an ex-officer can still edit and delete it). A text change keeps an existing consent.
     * Invalid text -> [MemberPublicBioValidationException].
     */
    suspend fun saveOwnBio(text: String): OwnPublicProfileDto

    /**
     * `visible = true` needs an ACTIVE caller ([MemberPublicBioNotEligibleException]), an existing text
     * ([MemberPublicBioMissingException]) and [consentTextVersion] equal to the server's current
     * version ([MemberPublicBioConsentOutdatedException]). `visible = false` withdraws at once.
     */
    suspend fun setOwnBioPublic(
        visible: Boolean,
        consentTextVersion: String?,
    ): OwnPublicProfileDto

    /** BOARD/ADMIN only. Deletes the bio of [memberId]; always `Unit` (idempotent, no existence oracle). */
    suspend fun moderationRemoveBio(memberId: String)
}
