package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.server.time.OrganizationTimeZoneRules
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.OrganizationTimeZoneDto
import network.lapis.cloud.shared.rpc.IOrganizationTimeZoneService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Implements [IOrganizationTimeZoneService] (V1.9.38). ADMIN-only. Changing the zone re-interprets the
 * real instant of every open class-B deadline (a poll closing "2026-07-01 20:00" is 18:00Z in Berlin and
 * 16:00Z in Tbilisi), so the change is validated against an allow-list and audited with old and new zone.
 * The audit entry must be the LAST row-locking operation of the transaction (see [AuditLogRecorder]).
 */
class OrganizationTimeZoneService(
    private val call: ApplicationCall,
) : IOrganizationTimeZoneService {
    override suspend fun getOrganizationTimeZone(): OrganizationTimeZoneDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return OrganizationTimeZoneDto(
            zoneId = OrganizationTimeZone.current().id,
            availableZoneIds = OrganizationTimeZoneRules.availableZoneIds(),
        )
    }

    override suspend fun updateOrganizationTimeZone(zoneId: String): OrganizationTimeZoneDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val newZone = OrganizationTimeZoneRules.validateZoneId(zoneId)
        transaction {
            val old =
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .single()[OrganizationSettingsTable.timezone]
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[timezone] = newZone.id
            }
            if (old != newZone.id) {
                AuditLogRecorder.record(
                    actorMemberId = current.memberId,
                    actorRole = current.role,
                    entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                    entityId = ORGANIZATION_SETTINGS_ID,
                    action = AuditAction.UPDATE,
                    before = Json.encodeToString(buildJsonObject { put("timezone", old) }),
                    after = Json.encodeToString(buildJsonObject { put("timezone", newZone.id) }),
                )
            }
        }
        OrganizationTimeZone.invalidate()
        return OrganizationTimeZoneDto(
            zoneId = newZone.id,
            availableZoneIds = OrganizationTimeZoneRules.availableZoneIds(),
        )
    }
}
