package network.lapis.cloud.server.auction.federation.feed

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.AuctionTeaser

/*
 * V1.9.322 (step c2) -- the wire format of the auction teaser feed, version 1. No `generatedAt` (so the ETag is the hash of the body and does not
 * change without a content change), no server id, no seller, no bidder, no URL (the receiver builds the link from the signed origin host and the id).
 */

/** The ONE path of the feed. Deliberately NOT under `/api/public` or `/api/embed`: it is authenticated server-to-server and never public. */
internal const val AUCTION_TEASER_FEED_PATH = "/api/federation/auction-feed/v1"

internal const val AUCTION_TEASER_FEED_KIND = "lapis-auction-teaser-feed"

/**
 * [complete] is `true` only when EVERY currently shareable hint is in [items] AND every live withdrawal is in [withdrawn]. When it is `false` (scan
 * limit, item cap, byte cap or an item that could not be signed) a consumer MUST NOT treat a hint that is missing from [items] as withdrawn. A hint is
 * never in [items] and [withdrawn] at the same time ("item wins").
 */
@Serializable
internal data class AuctionTeaserFeedV1(
    val schemaVersion: Int = 1,
    val kind: String = AUCTION_TEASER_FEED_KIND,
    val complete: Boolean,
    val items: List<AuctionTeaserFeedItem>,
    val withdrawn: List<AuctionTeaserWithdrawal> = emptyList(),
)

/** A hint that was taken back: the pseudonymous id and the day (UTC). No reason, no title, no price. */
@Serializable
internal data class AuctionTeaserWithdrawal(
    val teaserId: String,
    val withdrawnAt: LocalDate,
)

/**
 * One hint with its travel budget. [hops] = the number of relays BEFORE this feed (0 = the feed server is the origin), [maxHops] = the hop limit of the
 * ORIGIN (1..2). [via] = the relay hosts (always empty in c2: only own hints are published). [originSignature] signs the content (see
 * `AuctionOriginSignatures`); `hops` and `via` are NOT signed.
 */
@Serializable
internal data class AuctionTeaserFeedItem(
    val originHost: String,
    val hops: Int,
    val maxHops: Int,
    val teaser: AuctionTeaser,
    val via: List<String> = emptyList(),
    val originSignature: String,
)

internal val AUCTION_TEASER_FEED_JSON =
    Json {
        // `buyNowMinor = null` is left out on purpose (pinned by a test): the receiver treats a missing key as "no Sofortkauf".
        explicitNulls = false
        encodeDefaults = true
    }
