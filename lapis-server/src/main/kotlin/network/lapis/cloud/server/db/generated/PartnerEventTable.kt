// Welle V1.9.244 "Partnerserver P1" / V1.9.245 "P2" (provenance columns) -- hand-edited, see 79-partner-servers.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object PartnerEventTable : Table("partner_event") {
    public val id: Column<Uuid> = uuid("id")
    public val partnerServerId: Column<Uuid> = reference("partner_server_id", PartnerServerTable.id)
    public val remoteServerId: Column<Uuid> = uuid("remote_server_id")
    public val remoteEventId: Column<String> = varchar("remote_event_id", 100)
    public val title: Column<String> = varchar("title", 200)
    public val startsAt: Column<LocalDateTime> = datetime("starts_at")
    public val endsAt: Column<LocalDateTime> = datetime("ends_at")
    public val timeZone: Column<String> = varchar("time_zone", 64)
    public val online: Column<Boolean> = bool("online")
    public val place: Column<String?> = varchar("place", 200).nullable()
    public val summary: Column<String?> = varchar("summary", 300).nullable()
    public val recurrenceSentence: Column<String?> = varchar("recurrence_sentence", 200).nullable()
    public val url: Column<String> = varchar("url", 500)
    public val fetchedAt: Column<LocalDateTime> = datetime("fetched_at")
    public val expiresAt: Column<LocalDateTime> = datetime("expires_at")
    public val discoveredServerId: Column<Uuid?> = optReference("discovered_server_id", PartnerDiscoveredServerTable.id)
    public val hops: Column<Short> = short("hops").default(1.toShort())
    public val originHost: Column<String> = varchar("origin_host", 253)
    public val viaHosts: Column<String?> = varchar("via_hosts", 800).nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
