package network.lapis.cloud.server.auction.federation.feed

import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory counters of the auction teaser feed since the server started, for the administrator ONLY (never sent to a requester). The refusal causes
 * are kept apart here on purpose and must never reach the wire: the wire answer of every refusal is byte-identical.
 */
internal class AuctionTeaserFeedCounters {
    private val unauthenticated = AtomicLong()
    private val notApproved = AtomicLong()
    private val limited = AtomicLong()
    private val servedFull = AtomicLong()
    private val servedNotModified = AtomicLong()

    fun refusedUnauthenticated() = unauthenticated.incrementAndGet()

    fun refusedNotApproved() = notApproved.incrementAndGet()

    fun rateLimited() = limited.incrementAndGet()

    fun served() = servedFull.incrementAndGet()

    fun notModified() = servedNotModified.incrementAndGet()

    data class Snapshot(
        val refusedUnauthenticated: Long,
        val refusedNotApproved: Long,
        val rateLimited: Long,
        val served: Long,
        val notModified: Long,
    ) {
        val refusedTotal: Long get() = refusedUnauthenticated + refusedNotApproved
    }

    fun snapshot() =
        Snapshot(
            refusedUnauthenticated = unauthenticated.get(),
            refusedNotApproved = notApproved.get(),
            rateLimited = limited.get(),
            served = servedFull.get(),
            notModified = servedNotModified.get(),
        )
}
