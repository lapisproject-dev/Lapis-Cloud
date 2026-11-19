package network.lapis.cloud.server.carpool.feed

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.CarpoolTeaser

/*
 * V1.9.312 "Mitfahrzentrale-Foederation" W2 -- the wire format of the carpool teaser feed, version 1 (server-internal; the partner side parses it
 * from wave 3 on). The item fields match the TeaserEnvelope of docs/architecture/carpool-federation.adoc. No `generatedAt` (so the ETag is the
 * hash of the body and does not change without a content change), no server id, no name.
 */

/** The ONE path of the feed. Deliberately NOT under `/api/public` or `/api/embed`: it is authenticated server-to-server and never public. */
internal const val CARPOOL_TEASER_FEED_PATH = "/api/federation/carpool-feed/v1"

internal const val CARPOOL_TEASER_FEED_KIND = "lapis-carpool-teaser-feed"

/**
 * [complete] is `true` only when EVERY currently shareable hint is in [items] AND every live withdrawal is in [withdrawn]. When it is `false`
 * (scan limit, item cap or byte cap reached) a consumer MUST NOT treat a hint that is missing from [items] as withdrawn. V1.9.313 (wave 3):
 * [withdrawn] lists the hints that were taken back (pseudonymous id and the UTC day, nothing else); it is always present, empty by default. A hint
 * is never in [items] and [withdrawn] at the same time ("item wins").
 */
@Serializable
internal data class CarpoolTeaserFeedV1(
    val schemaVersion: Int = 1,
    val kind: String = CARPOOL_TEASER_FEED_KIND,
    val complete: Boolean,
    val items: List<CarpoolTeaserFeedItem>,
    val withdrawn: List<CarpoolTeaserWithdrawal> = emptyList(),
)

/** A hint that was taken back: the pseudonymous id and the day (UTC) it was withdrawn. No reason, no place, no date of the trip. */
@Serializable
internal data class CarpoolTeaserWithdrawal(
    val teaserId: String,
    val withdrawnAt: LocalDate,
)

/**
 * One hint with its travel budget. V1.9.314 (wave 4): [hops] = the number of relays BEFORE this feed (0 = the feed server is the origin), [maxHops] =
 * the hop limit of the ORIGIN (1..3). [via] = the relay hosts from the origin on, INCLUDING the publishing server from hops >= 1 (so `via.size == hops`).
 * [originSignature] = the signature of the origin over the content (see `CarpoolOriginSignatures`); `hops` and `via` are NOT signed. Both new fields
 * come last and default, so a wave 2/3 reader still parses the item.
 */
@Serializable
internal data class CarpoolTeaserFeedItem(
    val originHost: String,
    val hops: Int,
    val maxHops: Int,
    val teaser: CarpoolTeaser,
    val via: List<String> = emptyList(),
    val originSignature: String? = null,
)

internal val CARPOOL_TEASER_FEED_JSON =
    Json {
        explicitNulls = false
        encodeDefaults = true
    }
