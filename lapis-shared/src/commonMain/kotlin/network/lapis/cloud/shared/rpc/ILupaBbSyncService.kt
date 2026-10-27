package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.LupaBbApplyResultDto
import network.lapis.cloud.shared.domain.LupaBbCredentialsInput
import network.lapis.cloud.shared.domain.LupaBbPreviewFilter
import network.lapis.cloud.shared.domain.LupaBbPreviewPageDto
import network.lapis.cloud.shared.domain.LupaBbPreviewSummaryDto
import network.lapis.cloud.shared.domain.LupaBbSyncStatusDto

/**
 * V1.9.226 "LupaBB-Abgleich" -- see `docs/features/lupabb-sync.adoc`. New methods go to the END (Kilua numbers routes by declaration order).
 * Since V1.9.226 this is the LAST `registerService` in `Application.kt` (after `IPartyReportService`).
 *
 * EVERY method first requires the role ADMIN ([ForbiddenException]); the sync can end memberships, so it is not delegable. Nothing is ever
 * deleted or deactivated by it, no account is created and no mail is sent.
 */
@RpcService
interface ILupaBbSyncService {
    suspend fun getStatus(): LupaBbSyncStatusDto

    /** Probes the API with the given credentials (one request) and stores them sealed only if that worked. Rate limited. */
    suspend fun verifyAndSaveCredentials(input: LupaBbCredentialsInput): LupaBbSyncStatusDto

    suspend fun removeCredentials(): LupaBbSyncStatusDto

    /** Fetches the whole data set from LupaBB (at most once a minute) and compares it with the members; writes nothing. */
    suspend fun loadPreview(): LupaBbPreviewSummaryDto

    suspend fun getPreviewPage(
        token: String,
        filter: LupaBbPreviewFilter,
        offset: Int,
        limit: Int,
    ): LupaBbPreviewPageDto

    /** Applies the comparison behind [token] (single use, 15 minutes) record by record. */
    suspend fun applyPreview(token: String): LupaBbApplyResultDto

    /** Links the LupaBB record [personNumber] to the candidate [memberId] of its preview row (sets only the person number). */
    suspend fun linkMemberToLupaBb(
        token: String,
        personNumber: Long,
        memberId: String,
    ): LupaBbPreviewPageDto
}
