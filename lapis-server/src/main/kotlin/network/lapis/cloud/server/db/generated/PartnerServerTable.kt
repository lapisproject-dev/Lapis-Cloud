// Welle V1.9.244 "Partnerserver P1" -- hand-edited, see 79-partner-servers.kuml.kts file header.

package network.lapis.cloud.server.db.generated

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.PartnerFetchStatus
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.datetime
import kotlin.uuid.Uuid

public object PartnerServerTable : Table("partner_server") {
    public val id: Column<Uuid> = uuid("id")
    public val host: Column<String> = varchar("host", 253)
    public val displayName: Column<String?> = varchar("display_name", 100).nullable()
    public val remoteServerId: Column<Uuid?> = uuid("remote_server_id").nullable()
    public val remoteName: Column<String?> = varchar("remote_name", 100).nullable()
    public val active: Column<Boolean> = bool("active").default(true)
    public val carpoolSharing: Column<Boolean> = bool("carpool_sharing").default(false)
    public val createdAt: Column<LocalDateTime> = datetime("created_at")
    public val lastFetchAt: Column<LocalDateTime?> = datetime("last_fetch_at").nullable()
    public val lastStatus: Column<PartnerFetchStatus> = enumerationByName<PartnerFetchStatus>("last_status", 5).default(PartnerFetchStatus.NEVER)
    public val lastError: Column<String?> = varchar("last_error", 200).nullable()
    public val eventCount: Column<Int> = integer("event_count").default(0)
    public val consecutiveFailures: Column<Int> = integer("consecutive_failures").default(0)
    public val nextFetchAt: Column<LocalDateTime> = datetime("next_fetch_at")
    public val etag: Column<String?> = varchar("etag", 200).nullable()
    public val remotePartnersJson: Column<String?> = varchar("remote_partners_json", 65536).nullable()
    public val signingKeyId: Column<String?> = varchar("signing_key_id", 512).nullable()
    public val signingPublicKeyPem: Column<String?> = varchar("signing_public_key_pem", 4096).nullable()
    public val signingKeyPinnedAt: Column<LocalDateTime?> = datetime("signing_key_pinned_at").nullable()
    public val signingKeyPendingId: Column<String?> = varchar("signing_key_pending_id", 512).nullable()
    public val signingKeyPendingPem: Column<String?> = varchar("signing_key_pending_pem", 4096).nullable()
    public val signingKeyChangedAt: Column<LocalDateTime?> = datetime("signing_key_changed_at").nullable()
    public val carpoolFeedServedAt: Column<LocalDateTime?> = datetime("carpool_feed_served_at").nullable()
    public val carpoolFeedServedCount: Column<Long> = long("carpool_feed_served_count").default(0L)
    public val carpoolFeedLastItems: Column<Int?> = integer("carpool_feed_last_items").nullable()
    public val carpoolPullAt: Column<LocalDateTime?> = datetime("carpool_pull_at").nullable()
    public val carpoolPullOkAt: Column<LocalDateTime?> = datetime("carpool_pull_ok_at").nullable()
    public val carpoolPullNextAt: Column<LocalDateTime?> = datetime("carpool_pull_next_at").nullable()
    public val carpoolPullFailures: Column<Int> = integer("carpool_pull_failures").default(0)
    public val carpoolPullStatus: Column<String?> = varchar("carpool_pull_status", 32).nullable()
    public val carpoolPullError: Column<String?> = varchar("carpool_pull_error", 64).nullable()
    public val carpoolPullEtag: Column<String?> = varchar("carpool_pull_etag", 200).nullable()
    public val carpoolPullLastAccepted: Column<Int?> = integer("carpool_pull_last_accepted").nullable()
    public val carpoolPullLastRejected: Column<Int?> = integer("carpool_pull_last_rejected").nullable()
    public val auctionSharing: Column<Boolean> = bool("auction_sharing").default(false)
    public val auctionFeedServedAt: Column<LocalDateTime?> = datetime("auction_feed_served_at").nullable()
    public val auctionFeedServedCount: Column<Long> = long("auction_feed_served_count").default(0L)
    public val auctionFeedLastItems: Column<Int?> = integer("auction_feed_last_items").nullable()
    public val auctionPullAt: Column<LocalDateTime?> = datetime("auction_pull_at").nullable()
    public val auctionPullOkAt: Column<LocalDateTime?> = datetime("auction_pull_ok_at").nullable()
    public val auctionPullNextAt: Column<LocalDateTime?> = datetime("auction_pull_next_at").nullable()
    public val auctionPullFailures: Column<Int> = integer("auction_pull_failures").default(0)
    public val auctionPullStatus: Column<String?> = varchar("auction_pull_status", 32).nullable()
    public val auctionPullError: Column<String?> = varchar("auction_pull_error", 64).nullable()
    public val auctionPullEtag: Column<String?> = varchar("auction_pull_etag", 200).nullable()
    public val auctionPullLastAccepted: Column<Int?> = integer("auction_pull_last_accepted").nullable()
    public val auctionPullLastRejected: Column<Int?> = integer("auction_pull_last_rejected").nullable()

    override val primaryKey: PrimaryKey = PrimaryKey(id)
}
