package network.lapis.cloud.server.auction.federation.feed

import kotlinx.coroutines.sync.Semaphore

/**
 * The request budgets of the auction teaser feed, in ONE place. Per IP BEFORE authentication (so an attacker cannot use up the budget of a legitimate
 * partner), per partner and globally only AFTER the approval check (so a `429` is visible to approved partners only and says nothing about the consent
 * of another partner). Untuned defaults, like the carpool feed.
 */
internal object AuctionTeaserFeedLimits {
    const val IP_PER_MINUTE = 30
    const val IP_MAX_TRACKED_KEYS = 50_000
    const val PARTNER_PER_HOUR = 12
    const val GLOBAL_PER_HOUR = 600
    const val GLOBAL_KEY = "global"

    /** How many feed snapshots may be built at the same time (each holds a database transaction and builds a body of up to [MAX_FEED_BYTES]). */
    const val MAX_CONCURRENT_BUILDS = 4

    fun newBuildPermits(): Semaphore = Semaphore(MAX_CONCURRENT_BUILDS)

    /** The byte cap of ONE feed answer, shared by the builder (what it serves) and (c3) the pull client (what it accepts). */
    const val MAX_FEED_BYTES = 1024 * 1024
}
