package network.lapis.cloud.server.encounter

import network.lapis.cloud.server.db.generated.EncounterSpaceRoleTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

/**
 * Welle V1.9.61 -- the ONE place that answers "does this member currently hold an office (PULPIT/STEWARD) in this space". An office
 * only counts while the holder's member status is ACTIVE (a withdrawn or rejected member loses every office the instant the status
 * changes, without any cleanup job). Always re-derived from the database, never cached. Must run INSIDE the caller's open
 * `transaction {}`, same convention every query helper in this codebase follows.
 */
internal object EncounterRoles {
    /** The caller's CURRENT office in [spaceId], or `null` if there is none or the holder is no longer ACTIVE. */
    fun roleOf(
        spaceId: Uuid,
        memberId: Uuid,
    ): EncounterSpaceRole? =
        (EncounterSpaceRoleTable innerJoin MemberTable)
            .selectAll()
            .where {
                (EncounterSpaceRoleTable.spaceId eq spaceId) and
                    (EncounterSpaceRoleTable.memberId eq memberId) and
                    (MemberTable.status eq MemberStatus.ACTIVE)
            }.singleOrNull()
            ?.let { EncounterSpaceRole.valueOf(it[EncounterSpaceRoleTable.role]) }

    fun isOfficer(
        spaceId: Uuid,
        memberId: Uuid,
    ): Boolean = roleOf(spaceId = spaceId, memberId = memberId) != null

    /** `true` iff [memberId] is an ACTIVE office holder in ANY space (feeds `ConferenceStreamingService.listStreamTargets`). */
    fun isOfficerOfAnySpace(memberId: Uuid): Boolean =
        (EncounterSpaceRoleTable innerJoin MemberTable)
            .selectAll()
            .where { (EncounterSpaceRoleTable.memberId eq memberId) and (MemberTable.status eq MemberStatus.ACTIVE) }
            .limit(1)
            .any()

    /** Whether [memberId] has ANY office row in [spaceId], regardless of status -- used to PROTECT office holders from a steward's moderation. */
    fun hasAnyRoleRow(
        spaceId: Uuid,
        memberId: Uuid,
    ): Boolean =
        EncounterSpaceRoleTable
            .selectAll()
            .where { (EncounterSpaceRoleTable.spaceId eq spaceId) and (EncounterSpaceRoleTable.memberId eq memberId) }
            .limit(1)
            .any()
}
