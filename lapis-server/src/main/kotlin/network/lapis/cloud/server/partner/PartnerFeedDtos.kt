package network.lapis.cloud.server.partner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * V1.9.244 -- the wire format of the public partner feed, version 1. Used for the output (PartnerFeedBuilder) AND for the input (PartnerFeedParser).
 * Extensibility rules (docs/architecture/partner-server-feed.adoc): unknown fields are ignored; new kinds of entries are NEW named lists with a
 * `kind` per entry, never new meanings of existing fields; a breaking change is `schemaVersion` 2 under a new path.
 */

internal const val PARTNER_FEED_KIND = "lapis-partner-feed"
internal const val PARTNER_FEED_EVENT_KIND = "event"
internal const val PARTNER_FEED_PARTNER_KIND = "partner"
internal const val PARTNER_FEED_PATH = "/api/public/partner-feed/v1"

@Serializable
internal data class PartnerFeedV1(
    val schemaVersion: Int,
    val kind: String,
    val serverId: String,
    val name: String,
    val generatedAt: String,
    val events: List<PartnerFeedEvent> = emptyList(),
    val partners: List<PartnerFeedPartner> = emptyList(),
    val partnersHidden: Boolean = false,
)

/** [startsAt] / [endsAt]: ISO-8601 WITH offset (`2026-11-05T19:00:00+01:00`); [timeZone]: IANA id of the organisation (display only). */
@Serializable
internal data class PartnerFeedEvent(
    val kind: String,
    val id: String,
    val title: String,
    val startsAt: String,
    val endsAt: String,
    val timeZone: String,
    val online: Boolean,
    val place: String? = null,
    val summary: String? = null,
    val url: String,
    val recurrence: PartnerFeedRecurrence? = null,
)

@Serializable
internal data class PartnerFeedRecurrence(
    val sentence: String,
)

@Serializable
internal data class PartnerFeedPartner(
    val kind: String,
    val host: String,
    val name: String,
)

internal val PARTNER_FEED_JSON =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        isLenient = false
    }
