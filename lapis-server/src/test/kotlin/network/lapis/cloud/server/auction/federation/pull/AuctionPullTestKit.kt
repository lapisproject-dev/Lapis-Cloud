package network.lapis.cloud.server.auction.federation.pull

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.auction.federation.AuctionOriginSignatures
import network.lapis.cloud.server.auction.federation.AuctionPullApplyResult
import network.lapis.cloud.server.auction.federation.AuctionReceivedStore
import network.lapis.cloud.server.auction.federation.feed.AUCTION_TEASER_FEED_JSON
import network.lapis.cloud.server.auction.federation.feed.AuctionTeaserFeedItem
import network.lapis.cloud.server.auction.federation.feed.AuctionTeaserFeedV1
import network.lapis.cloud.server.auction.federation.feed.AuctionTeaserWithdrawal
import network.lapis.cloud.server.carpool.CarpoolOwnSigningKey
import network.lapis.cloud.server.carpool.feed.FeedPartner
import network.lapis.cloud.server.carpool.feed.newFeedPartner
import network.lapis.cloud.server.federation.FederationKeyPair
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.shared.domain.AuctionTeaser
import network.lapis.cloud.shared.domain.AuctionTeaserPriceKind
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The "now" of the pull tests (fixed, so no test depends on the wall clock). */
internal val AUCTION_PULL_NOW = LocalDateTime(2026, 11, 24, 10, 0, 0)
internal val AUCTION_PULL_NOW_INSTANT: Instant = AUCTION_PULL_NOW.toInstant(TimeZone.UTC)
internal const val AUCTION_PULL_OWN_HOST = "b.beispiel.de"

internal fun hexId(seed: String): String =
    MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** A foreign server that signs hints as their origin. The key is the one a partner row pins (key id on the host itself). */
internal class AuctionOriginServer(
    val host: String,
    val keyPair: FederationKeyPair = FederationKeyPairGenerator.generate(),
) {
    val keyId = "https://$host/federation/actor#main-key"
    val signing = CarpoolOwnSigningKey(keyId = keyId, privateKeyPem = keyPair.privateKeyPem)

    fun teaser(
        seed: String,
        revision: Long = 5,
        title: String = "Fahrrad $seed",
        currency: String = "EUR",
        priceMinor: Long = 2500,
        buyNowMinor: Long? = null,
        offersPossible: Boolean = false,
        endsIn: Int = 48,
        priceKind: AuctionTeaserPriceKind = AuctionTeaserPriceKind.CURRENT,
        base: Instant = AUCTION_PULL_NOW_INSTANT,
    ) = AuctionTeaser(
        teaserId = hexId(seed),
        revision = revision,
        title = title,
        currency = currency,
        priceKind = priceKind,
        priceMinor = priceMinor,
        buyNowMinor = buyNowMinor,
        offersPossible = offersPossible,
        endsAt = base + endsIn.hours,
        priceAsOf = base,
    )

    /** An item as THIS origin signs it, with the wire fields of the relay that hands it on (`hops`, `via`). */
    fun item(
        seed: String,
        maxHops: Int = 2,
        hops: Int = 0,
        via: List<String> = emptyList(),
        teaser: AuctionTeaser = teaser(seed = seed),
        signedMaxHops: Int = maxHops,
        signedBy: AuctionOriginServer = this,
        signature: String? = null,
    ): AuctionTeaserFeedItem {
        val sig =
            signature ?: AuctionOriginSignatures.sign(teaser = teaser, originHost = host, maxHops = signedMaxHops, key = signedBy.signing)
        return AuctionTeaserFeedItem(originHost = host, hops = hops, maxHops = maxHops, teaser = teaser, via = via, originSignature = sig)
    }
}

internal fun auctionFeedBytes(
    items: List<AuctionTeaserFeedItem>,
    withdrawn: List<String> = emptyList(),
    complete: Boolean = true,
): ByteArray =
    AUCTION_TEASER_FEED_JSON
        .encodeToString(
            AuctionTeaserFeedV1.serializer(),
            AuctionTeaserFeedV1(
                complete = complete,
                items = items,
                withdrawn = withdrawn.map { AuctionTeaserWithdrawal(teaserId = it, withdrawnAt = AUCTION_PULL_NOW.date) },
            ),
        ).toByteArray(Charsets.UTF_8)

internal fun parseAuctionOk(
    bytes: ByteArray,
    sourceHost: String,
    ownHost: String = AUCTION_PULL_OWN_HOST,
    ownHopLimit: Int = 2,
    blocked: List<String> = emptyList(),
    now: Instant = AUCTION_PULL_NOW_INSTANT,
) = (
    AuctionTeaserFeedReader.parse(
        bytes = bytes,
        sourceHost = sourceHost,
        ownHost = ownHost,
        ownHopLimit = ownHopLimit,
        now = now,
        blockPatterns = blocked,
    ) as AuctionFeedParse.Ok
).feed

/** A direct partner of this server that shares AUCTIONS (the carpool switch stays off), with the key of [o] pinned. */
internal fun newAuctionPartner(
    o: AuctionOriginServer,
    sharing: Boolean = true,
): FeedPartner {
    val p = newFeedPartner(host = o.host, keyPair = o.keyPair, sharing = false)
    if (sharing) transaction { PartnerServerStore.setAuctionSharing(id = p.id, enabled = true) }
    return p
}

/** Reads [bytes] as the answer of the partner [sourceId] and applies them, exactly as the poller does (reader, then ONE transaction). */
internal fun applyAuctionFeed(
    sourceId: Uuid,
    bytes: ByteArray,
    now: LocalDateTime = AUCTION_PULL_NOW,
    ownHopLimit: Int = 2,
    snapshot: PartnerRow? = null,
): AuctionPullApplyResult =
    transaction {
        val row = snapshot ?: PartnerServerStore.findPartner(sourceId)!!
        val current = PartnerServerStore.findPartner(sourceId)!!
        val parsed =
            parseAuctionOk(
                bytes = bytes,
                sourceHost = current.host,
                ownHopLimit = ownHopLimit,
                blocked = PartnerServerStore.blockPatterns(),
                now = now.toInstant(TimeZone.UTC),
            )
        AuctionReceivedStore.apply(source = row, feed = parsed, etag = "\"e\"", next = now, now = now)
    }
