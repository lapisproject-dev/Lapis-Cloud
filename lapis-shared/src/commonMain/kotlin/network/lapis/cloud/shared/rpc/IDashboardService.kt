package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.NavCountsDto

/**
 * V1.9.224 "Dashboard shows only what the menu does not" -- see `docs/features/dashboard.adoc`. New methods go to the END (Kilua numbers
 * routes by declaration order). Since V1.9.224 this is the LAST `registerService` in `Application.kt` (`IAttachedImageService` is second
 * to last).
 *
 * [getDashboardSignals] returns ONLY facts about the caller's own data (an own bid that was outbid, an own ballot that is missing, ...) and
 * role-based head counts; no title, no name, no amount of anybody else. [getNavCounts] returns two head counts per live menu entry.
 * Both reject only an anonymous caller ([network.lapis.cloud.shared.rpc.UnauthenticatedException]); every other caller gets values filtered
 * by the member status (a guest gets the guest-visible encounter spaces and a conference of 0/0).
 */
@RpcService
interface IDashboardService {
    suspend fun getDashboardSignals(): List<DashboardSignalDto>

    suspend fun getNavCounts(): NavCountsDto
}
