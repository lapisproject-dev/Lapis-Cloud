package network.lapis.cloud.server.carpool

import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.clampHopLimit
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** The state of the carpool exchange of this server. [hopLimit] is always inside 1..3. */
internal data class CarpoolFederationSettings(
    val enabled: Boolean,
    val hopLimit: Int,
)

/**
 * V1.9.311 "Mitfahrzentrale-Foederation" W1 -- the ONLY reader and writer of `organization_settings.carpool_federation_*` and
 * `carpool_posting.federation_opt_in` (pinned by `CarpoolFederationTripwireTest`). All functions run inside the caller's transaction.
 * Nothing here talks to the network.
 */
internal object CarpoolFederationStore {
    fun settings(): CarpoolFederationSettings {
        val row =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .single()
        return CarpoolFederationSettings(
            enabled = row[OrganizationSettingsTable.carpoolFederationEnabled],
            hopLimit = clampHopLimit(row[OrganizationSettingsTable.carpoolFederationHopLimit].toInt()),
        )
    }

    /** Locks the settings row, then writes. The caller validates [hopLimit] (1..3). Returns the settings as they were BEFORE. */
    fun setSettings(
        enabled: Boolean,
        hopLimit: Int,
    ): CarpoolFederationSettings {
        require(hopLimit in 1..3) { "hop limit out of range" }
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .forUpdate()
            .single()
        val before = settings()
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[carpoolFederationEnabled] = enabled
            it[carpoolFederationHopLimit] = hopLimit.toShort()
        }
        return before
    }

    fun optIn(postingId: Uuid): Boolean =
        CarpoolPostingTable
            .selectAll()
            .where { CarpoolPostingTable.id eq postingId }
            .singleOrNull()
            ?.get(CarpoolPostingTable.federationOptIn) ?: false

    fun setOptIn(
        postingId: Uuid,
        value: Boolean,
    ) {
        CarpoolPostingTable.update({ CarpoolPostingTable.id eq postingId }) { it[federationOptIn] = value }
    }

    /** A trip that turns into a REQUEST loses the consent (it can never be shared). */
    fun clearOptIn(postingId: Uuid) = setOptIn(postingId = postingId, value = false)
}
