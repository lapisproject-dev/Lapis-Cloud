package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.DiscoveredPartnerServerDto
import network.lapis.cloud.shared.domain.PartnerBlockDto
import network.lapis.cloud.shared.domain.PartnerBlockResultDto
import network.lapis.cloud.shared.domain.PartnerProbeResultDto
import network.lapis.cloud.shared.domain.PartnerRecursionSettingsDto
import network.lapis.cloud.shared.domain.PartnerServerOverviewDto

/**
 * V1.9.244 "Partnerserver P1" -- see `docs/features/partner-servers.adoc`. THIS is the LAST `registerService` in `Application.kt`
 * (after `IMeetingProtocolService`); Kilua numbers routes by declaration order, so new methods go to the END of this interface.
 *
 * EVERY method first requires the role ADMIN ([ForbiddenException]): the list steers outgoing requests of this server.
 */
@RpcService
interface IPartnerServerService {
    suspend fun list(): PartnerServerOverviewDto

    /** Normalises [address], checks the blocklist and fetches the feed ONCE; saves the partner (with its events) only if that worked. Rate limited. */
    suspend fun probeAndSave(
        address: String,
        displayName: String?,
    ): PartnerProbeResultDto

    suspend fun setActive(
        id: String,
        active: Boolean,
    )

    /** Removes the partner and its cached events. */
    suspend fun remove(id: String)

    suspend fun listBlocklist(): List<PartnerBlockDto>

    /** Adds a pattern (`host` or `*.host`) and deletes the cached events of the matching partners in the same transaction. */
    suspend fun addBlock(pattern: String): PartnerBlockResultDto

    suspend fun removeBlock(id: String)

    suspend fun setPartnerListHidden(hidden: Boolean)

    /** V1.9.245 "P2": the recursion switch, the depth (1..3) and the summary of the last run. */
    suspend fun getPartnerRecursionSettings(): PartnerRecursionSettingsDto

    /** [depth] outside 1..3 is a bad request. */
    suspend fun setPartnerRecursionSettings(
        enabled: Boolean,
        depth: Int,
    )

    /** The servers reached through the direct partners (at most 200, sorted by hop and host). */
    suspend fun listDiscoveredPartnerServers(): List<DiscoveredPartnerServerDto>
}
