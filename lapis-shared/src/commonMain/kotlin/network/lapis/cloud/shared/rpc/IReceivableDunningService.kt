package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.OpenItemDetailDto
import network.lapis.cloud.shared.domain.ReceivableDunningLevelDto
import network.lapis.cloud.shared.domain.ReceivableDunningLevelInput
import network.lapis.cloud.shared.domain.ReceivableDunningSettingsDto

/**
 * Welle V1.4.15 -- the dunning-domain sibling of [IOpenItemService], structurally independent
 * from [IDunningService] (the pre-existing member-contribution dunning domain). See
 * `network.lapis.cloud.server.rpc.ReceivableDunningService` KDoc.
 */
@RpcService
interface IReceivableDunningService {
    suspend fun getReceivableDunningSettings(): ReceivableDunningSettingsDto

    /** Role: ADMIN. */
    suspend fun enableReceivableDunning(): ReceivableDunningSettingsDto

    /** Role: ADMIN. */
    suspend fun disableReceivableDunning(): ReceivableDunningSettingsDto

    suspend fun listReceivableDunningLevels(includeInactive: Boolean = false): List<ReceivableDunningLevelDto>

    /** Role: ADMIN. */
    suspend fun createReceivableDunningLevel(input: ReceivableDunningLevelInput): ReceivableDunningLevelDto

    /** Role: ADMIN. */
    suspend fun updateReceivableDunningLevel(
        levelId: String,
        input: ReceivableDunningLevelInput,
    ): ReceivableDunningLevelDto

    /** Role: ADMIN. */
    suspend fun deactivateReceivableDunningLevel(levelId: String): ReceivableDunningLevelDto

    /** Role: TREASURER/ADMIN. */
    suspend fun issueReceivableDunningNotice(openItemId: String): OpenItemDetailDto

    /** Role: TREASURER/ADMIN. */
    suspend fun skipReceivableDunningLevel(
        openItemId: String,
        reason: String,
    ): OpenItemDetailDto

    /** Role: TREASURER/ADMIN. */
    suspend fun cancelReceivableDunningNotice(
        noticeId: String,
        reason: String,
    ): OpenItemDetailDto
}
