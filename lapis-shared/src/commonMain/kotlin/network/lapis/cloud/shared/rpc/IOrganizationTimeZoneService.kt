package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.OrganizationTimeZoneDto

/**
 * The organization's time zone (V1.9.38). ADMIN-only on both methods: the zone is delivered to every
 * other role through `SessionInfoDto.organizationTimeZone`. Deliberately separate from
 * [IOrganizationSettingsService] so it can never be written through that service's generic,
 * wholesale-replace write-set (mass-assignment guard).
 */
@RpcService
interface IOrganizationTimeZoneService {
    /** Role: ADMIN. The current zone plus the server's allow-list of selectable ids. */
    suspend fun getOrganizationTimeZone(): OrganizationTimeZoneDto

    /**
     * Role: ADMIN. Validates [zoneId] against the allow-list (rejects offsets, short ids such as `CET`,
     * `Etc/GMT+n`, anything unknown), stores it and writes an audit entry with the old and new zone.
     * Re-interprets the real instant of every open class-B deadline -- see the architecture doc.
     */
    suspend fun updateOrganizationTimeZone(zoneId: String): OrganizationTimeZoneDto
}
