package network.lapis.cloud.server.carpool.feed

/**
 * The request budgets of the teaser feed, in ONE place. Per IP BEFORE authentication (so an attacker cannot use up the budget of a legitimate
 * partner), per partner and globally only AFTER the approval check (so a `429` is visible to approved partners only and says nothing about the
 * consent of another partner).
 */
internal object CarpoolTeaserFeedLimits {
    const val IP_PER_MINUTE = 30
    const val IP_MAX_TRACKED_KEYS = 50_000
    const val PARTNER_PER_HOUR = 12
    const val GLOBAL_PER_HOUR = 600
    const val GLOBAL_KEY = "global"

    /**
     * V1.9.314 (wave 4): the byte cap of ONE feed answer, shared by the builder (what it serves) and the pull client (what it accepts). Raised from
     * 256 KiB because every item now carries an origin signature (about 350 bytes) and the relay path.
     */
    const val MAX_FEED_BYTES = 1024 * 1024
}
