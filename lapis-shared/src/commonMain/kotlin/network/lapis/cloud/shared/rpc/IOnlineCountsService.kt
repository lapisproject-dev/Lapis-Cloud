package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.OnlineCountsDto

/**
 * V1.9.215 "Angemeldete Nutzer" -- ADMIN-only head counts of signed-in people. Counts only; no ids, no names. New methods go to the end
 * (Kilua numbers routes by declaration order). This service is the LAST `registerService` in `Application.kt`.
 */
@RpcService
interface IOnlineCountsService {
    suspend fun onlineCounts(): OnlineCountsDto
}
