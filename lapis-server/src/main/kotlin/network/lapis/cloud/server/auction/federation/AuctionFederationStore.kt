package network.lapis.cloud.server.auction.federation

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AuctionFederationLimits
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartnerAuditKind
import network.lapis.cloud.shared.domain.PartnerAuditSnapshot
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** The state of the auction hint exchange of this server. [hopLimit] is always inside 1..2. */
internal data class AuctionFederationSettings(
    val enabled: Boolean,
    val hopLimit: Int,
)

/**
 * V1.9.322 (Fiat wave c, step c1 "foundation") -- the ONLY reader and writer of `organization_settings.auction_federation_*` and
 * `auction.federation_opt_in/_at` (pinned by `AuctionFederationTripwireTest`). All functions run inside the caller's transaction. Nothing here
 * talks to the network. The later steps add the tables `auction_teaser_snapshot` and `auction_teaser_tombstone` to this object's monopoly.
 *
 * The consent RULES (Euro only, still OPEN, enough time left, a presentable title, instance switch on) live in
 * `mayConsentToAuctionFederation` (lapis-shared) and are checked by the RPC layer; this store only persists the decision.
 */
internal object AuctionFederationStore {
    fun settings(): AuctionFederationSettings {
        val row =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .single()
        return AuctionFederationSettings(
            enabled = row[OrganizationSettingsTable.auctionFederationEnabled],
            hopLimit =
                row[OrganizationSettingsTable.auctionFederationHopLimit]
                    .toInt()
                    .coerceIn(AuctionFederationLimits.MIN_HOPS, AuctionFederationLimits.MAX_HOPS),
        )
    }

    /**
     * Locks the settings row, then writes. A hop limit outside 1..2 is REJECTED, never silently clamped: the admin must see that three hops are
     * not offered. Returns the settings as they were BEFORE (the caller audits the change).
     */
    fun setSettings(
        enabled: Boolean,
        hopLimit: Int,
    ): AuctionFederationSettings {
        require(hopLimit in AuctionFederationLimits.MIN_HOPS..AuctionFederationLimits.MAX_HOPS) { "hop limit out of range" }
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .forUpdate()
            .single()
        val before = settings()
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[auctionFederationEnabled] = enabled
            it[auctionFederationHopLimit] = hopLimit.toShort()
        }
        return before
    }

    fun optIn(auctionId: Uuid): Boolean =
        AuctionTable
            .select(AuctionTable.federationOptIn)
            .where { AuctionTable.id eq auctionId }
            .singleOrNull()
            ?.get(AuctionTable.federationOptIn) ?: false

    /** When the seller gave the consent, or `null` (never, or taken back). */
    fun optInAt(auctionId: Uuid): LocalDateTime? =
        AuctionTable
            .select(AuctionTable.federationOptInAt)
            .where { AuctionTable.id eq auctionId }
            .singleOrNull()
            ?.get(AuctionTable.federationOptInAt)

    /** Persists the decision of the seller (or the system, when it withdraws). The moment is kept only while the consent stands. */
    fun setOptIn(
        auctionId: Uuid,
        value: Boolean,
        now: LocalDateTime = DbClock.nowLocalDateTime(),
    ) {
        AuctionTable.update({ AuctionTable.id eq auctionId }) {
            it[federationOptIn] = value
            it[federationOptInAt] = if (value) now else null
        }
    }

    fun countOptIns(): Long =
        AuctionTable
            .selectAll()
            .where { AuctionTable.federationOptIn eq true }
            .count()

    /**
     * A restore brings back a foreign state: the exchange goes OFF and every consent is cleared, so an old backup can never re-publish what has been
     * withdrawn since. Returns `true` when something had to be reset (then ONE audit entry without an actor is written).
     */
    fun resetAfterRestore(now: LocalDateTime): Boolean {
        val wasEnabled = settings().enabled
        val consents = countOptIns()
        if (wasEnabled) {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[auctionFederationEnabled] = false
            }
        }
        if (consents > 0) {
            AuctionTable.update({ AuctionTable.federationOptIn eq true }) {
                it[federationOptIn] = false
                it[federationOptInAt] = null
            }
        }
        if (!wasEnabled && consents == 0L) return false
        auditResetByRestore(consents = consents, now = now)
        return true
    }

    /** The audit entry of [resetAfterRestore]: the number of consents cleared, nothing else (no auction id, no title, no amount). */
    fun auditResetByRestore(
        consents: Long,
        now: LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = null,
            actorRole = null,
            entityType = AuditEntityType.PARTNER_SERVER,
            entityId = ORGANIZATION_SETTINGS_ID,
            action = AuditAction.UPDATE,
            after =
                Json.encodeToString(
                    PartnerAuditSnapshot.serializer(),
                    PartnerAuditSnapshot(kind = PartnerAuditKind.AUCTION_RESET_BY_RESTORE, subject = consents.toString()),
                ),
            occurredAt = now,
        )
    }
}
