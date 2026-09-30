package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.MemberPhotoVisibility
import network.lapis.cloud.shared.domain.OwnMemberPhotoDto

/**
 * Welle V1.9.19 "Mitglieder-Foto". RPC companion to the byte-carrying `POST /api/member-photo` /
 * `GET /api/member-photo/own` routes (file bytes travel over dedicated routes, not Kilua RPC --
 * same reasoning as `IConferenceBackgroundService`). Every method except [moderationRemovePhoto]
 * acts EXCLUSIVELY on the caller's own photo -- there is deliberately no member-id parameter.
 */
@RpcService
interface IMemberPhotoService {
    suspend fun getOwnPhoto(): OwnMemberPhotoDto

    /**
     * [MemberPhotoVisibility.PUBLIC] needs a photo ([network.lapis.cloud.shared.rpc.MemberPhotoMissingException])
     * and [consentTextVersion] equal to the server's current version
     * ([network.lapis.cloud.shared.rpc.MemberPhotoConsentOutdatedException]); every publication mints a NEW
     * public token. [MemberPhotoVisibility.PRIVATE] withdraws consent and token immediately.
     */
    suspend fun setOwnPhotoVisibility(
        visibility: MemberPhotoVisibility,
        consentTextVersion: String?,
    ): OwnMemberPhotoDto

    suspend fun deleteOwnPhoto(): OwnMemberPhotoDto

    /**
     * BOARD/ADMIN only. Removes the photo of [memberId]. Always returns `Unit` -- also when there
     * was no photo (idempotent, no existence oracle) -- and never a picture or metadata.
     */
    suspend fun moderationRemovePhoto(memberId: String)
}
