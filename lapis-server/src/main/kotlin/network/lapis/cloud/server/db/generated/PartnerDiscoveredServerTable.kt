// Welle V1.9.245 "Partnerserver P2" -- hand-edited, see 79-partner-servers.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PartnerFetchStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object PartnerDiscoveredServerTable : Table("partner_discovered_server") {
    public val id: Column<Uuid> = uuid("id")
    public val host: Column<String> = varchar("host", 253)
    public val remoteServerId: Column<Uuid?> = uuid("remote_server_id").nullable()
    public val remoteName: Column<String?> = varchar("remote_name", 100).nullable()
    public val rootPartnerServerId: Column<Uuid> = reference("root_partner_server_id", PartnerServerTable.id)
    public val hops: Column<Short> = short("hops")
    public val viaHosts: Column<String?> = varchar("via_hosts", 800).nullable()
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val lastReachedAt: Column<LocalDateTime?> = datetime("last_reached_at").nullable()
    public val lastFetchAt: Column<LocalDateTime?> = datetime("last_fetch_at").nullable()
    public val lastStatus: Column<PartnerFetchStatus> = enumerationByName<PartnerFetchStatus>("last_status", 5).default(PartnerFetchStatus.NEVER)
    public val lastError: Column<String?> = varchar("last_error", 200).nullable()
    public val eventCount: Column<Int> = integer("event_count").default(0)
    public val consecutiveFailures: Column<Int> = integer("consecutive_failures").default(0)
    public val nextFetchAt: Column<LocalDateTime> = datetime("next_fetch_at")
    public val etag: Column<String?> = varchar("etag", 200).nullable()
    public val remotePartnersJson: Column<String?> = varchar("remote_partners_json", 65536).nullable()
    public val partnersHidden: Column<Boolean> = bool("partners_hidden").default(false)

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
