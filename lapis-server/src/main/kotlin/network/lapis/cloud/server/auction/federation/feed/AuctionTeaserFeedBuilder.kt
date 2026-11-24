package network.lapis.cloud.server.auction.federation.feed

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import network.lapis.cloud.server.auction.federation.AuctionFederationSettings
import network.lapis.cloud.server.auction.federation.AuctionFederationStore
import network.lapis.cloud.server.auction.federation.AuctionOriginSignatures
import network.lapis.cloud.server.auction.federation.AuctionTeaserIds
import network.lapis.cloud.server.auction.federation.AuctionTeaserSnapshots
import network.lapis.cloud.server.auction.federation.AuctionTeaserTombstonePolicy
import network.lapis.cloud.server.auction.federation.AuctionWithdrawals
import network.lapis.cloud.server.carpool.CarpoolOwnSigningKey
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.shared.domain.AuctionTeaserEligibility
import network.lapis.cloud.shared.domain.isAuctionTeaserEligible

/** The serialised feed and what the caller needs to audit: how many items and withdrawals, and whether the lists are complete. */
internal class BuiltAuctionTeaserFeed(
    val body: ByteArray,
    val itemCount: Int,
    val complete: Boolean,
    val withdrawnCount: Int = 0,
)

/**
 * V1.9.322 (step c2) -- builds the complete auction teaser snapshot for ONE already approved partner. Every candidate goes through the ONE
 * eligibility rule (`isAuctionTeaserEligible`) with the partner flags of the SAME transaction, then through the throttle
 * ([AuctionTeaserSnapshots]) and the ONE projection (`AuctionTeaserProjection`); a withdrawal of consent is therefore visible on the very next fetch.
 *
 * Reading order, pinned by tests: the candidates and the items first (complete, before any cut), then the lazy sweep of consented auctions that are
 * no longer shareable, then the tombstones; a hint that is in the item list is removed from the withdrawals ("item wins"). Deterministic: sorted by
 * teaser id. Bounded: at most [MAX_ITEMS] items, [SCAN_LIMIT] scanned auctions and [MAX_BYTES] bytes; whenever any bound cuts a list, `complete` is
 * `false`. Only OWN hints are published in c2 (no relay).
 */
internal object AuctionTeaserFeedBuilder {
    const val MAX_ITEMS = 1000
    const val SCAN_LIMIT = 5000
    const val MAX_BYTES = AuctionTeaserFeedLimits.MAX_FEED_BYTES

    /** Runs inside the caller's transaction; [partner] is the approved row. [beforeWithdrawalRead] is a test seam between the items and the tombstones. */
    fun build(
        partner: PartnerRow,
        settings: AuctionFederationSettings,
        ids: AuctionTeaserIds,
        originHost: String,
        now: LocalDateTime = DbClock.nowLocalDateTime(),
        fiatEnabled: Boolean = true,
        maxItems: Int = MAX_ITEMS,
        scanLimit: Int = SCAN_LIMIT,
        maxBytes: Int = MAX_BYTES,
        beforeWithdrawalRead: () -> Unit = {},
        ownKey: CarpoolOwnSigningKey? = null,
    ): BuiltAuctionTeaserFeed {
        val scanned = AuctionFederationStore.teaserCandidates(scanLimit = scanLimit, now = now)
        var complete = scanned.size <= scanLimit
        val candidates = scanned.take(scanLimit)
        val blocked = PartnerBlocklistPattern.isBlocked(host = partner.host, patterns = PartnerServerStore.blockPatterns())
        val nowInstant = now.toInstant(TimeZone.UTC)
        val eligible =
            candidates.filter { c ->
                isAuctionTeaserEligible(
                    e =
                        AuctionTeaserEligibility(
                            currency = c.row[AuctionTable.currency],
                            status = c.row[AuctionTable.status],
                            endsAt = c.row[AuctionTable.endsAt].toInstant(TimeZone.UTC),
                            optIn = c.row[AuctionTable.federationOptIn],
                            instanceEnabled = settings.enabled,
                            auctionEnabled = true,
                            fiatEnabled = fiatEnabled,
                            partnerSharing = partner.auctionSharing,
                            partnerActive = partner.active,
                            partnerBlocked = blocked,
                            partnerKeyPending = partner.signingKeyChangedAt != null,
                            sellerActive = c.sellerActive,
                            title = c.row[AuctionTable.title],
                        ),
                    now = nowInstant,
                )
            }
        val reappeared =
            AuctionFederationStore.liveOwnGlobalTombstoneIds(
                candidates = eligible.map { ids.of(it.row[AuctionTable.id]) },
                now = now,
            )
        var unsigned = 0
        val items =
            eligible
                .mapNotNull { c ->
                    AuctionTeaserSnapshots.publishable(
                        row = c.row,
                        ids = ids,
                        fiatEnabled = fiatEnabled,
                        now = now,
                        reappeared = ids.of(c.row[AuctionTable.id]) in reappeared,
                    )
                }.sortedBy { it.teaserId }
                .mapNotNull { teaser ->
                    // A receiver refuses an item without the origin signature, so an item that cannot be signed is left out and the list is incomplete.
                    val signature =
                        ownKey?.let {
                            runCatching {
                                AuctionOriginSignatures.sign(
                                    teaser = teaser,
                                    originHost = originHost,
                                    maxHops = settings.hopLimit,
                                    key = it,
                                )
                            }.getOrNull()
                        }
                    if (signature == null) {
                        unsigned++
                        null
                    } else {
                        AuctionTeaserFeedItem(
                            originHost = originHost,
                            hops = 0,
                            maxHops = settings.hopLimit,
                            teaser = teaser,
                            originSignature = signature,
                        )
                    }
                }
        if (unsigned > 0) complete = false
        val withdrawn =
            withdrawalsFor(
                partner = partner,
                ids = ids,
                now = now,
                itemIds =
                    items
                        .map {
                            it.teaser.teaserId
                        }.toSet(),
                beforeRead = beforeWithdrawalRead,
            )
        if (withdrawn.capped) complete = false
        var keptWithdrawn = withdrawn.list
        if (keptWithdrawn.size > AuctionTeaserTombstonePolicy.MAX_WITHDRAWN) {
            keptWithdrawn = keptWithdrawn.take(AuctionTeaserTombstonePolicy.MAX_WITHDRAWN)
            complete = false
        }
        var kept = items
        if (kept.size > maxItems) {
            kept = kept.take(maxItems)
            complete = false
        }
        while (true) {
            val bytes = encode(complete = complete, items = kept, withdrawn = keptWithdrawn)
            if (bytes.size <= maxBytes || (kept.isEmpty() && keptWithdrawn.isEmpty())) {
                return BuiltAuctionTeaserFeed(body = bytes, itemCount = kept.size, complete = complete, withdrawnCount = keptWithdrawn.size)
            }
            if (kept.isNotEmpty()) kept = kept.take(kept.size / 2) else keptWithdrawn = keptWithdrawn.take(keptWithdrawn.size / 2)
            complete = false
        }
    }

    /** The trailing answer for an approved partner that is no longer shared with: `items` empty, the withdrawals as usual, `complete` honest. */
    fun buildWithdrawalsOnly(
        partner: PartnerRow,
        ids: AuctionTeaserIds,
        now: LocalDateTime = DbClock.nowLocalDateTime(),
        maxBytes: Int = MAX_BYTES,
    ): BuiltAuctionTeaserFeed {
        val withdrawn = withdrawalsFor(partner = partner, ids = ids, now = now, itemIds = emptySet(), beforeRead = {})
        var complete = !withdrawn.capped
        var kept = withdrawn.list
        if (kept.size > AuctionTeaserTombstonePolicy.MAX_WITHDRAWN) {
            kept = kept.take(AuctionTeaserTombstonePolicy.MAX_WITHDRAWN)
            complete = false
        }
        while (true) {
            val bytes = encode(complete = complete, items = emptyList(), withdrawn = kept)
            if (bytes.size <= maxBytes || kept.isEmpty()) {
                return BuiltAuctionTeaserFeed(body = bytes, itemCount = 0, complete = complete, withdrawnCount = kept.size)
            }
            kept = kept.take(kept.size / 2)
            complete = false
        }
    }

    private class Withdrawn(
        val list: List<AuctionTeaserWithdrawal>,
        val capped: Boolean,
    )

    private fun withdrawalsFor(
        partner: PartnerRow,
        ids: AuctionTeaserIds,
        now: LocalDateTime,
        itemIds: Set<String>,
        beforeRead: () -> Unit,
    ): Withdrawn {
        // Lazy sweep (bounded): consented auctions that ended without anybody touching them, or whose seller left. Their hints are not items, so the
        // tombstone below reports them in this very answer.
        AuctionWithdrawals.sweep(now = now, ids = ids, limit = AuctionTeaserTombstonePolicy.MAX_LAZY_SWEEP_PER_BUILD)
        beforeRead()
        val live =
            AuctionFederationStore.liveWithdrawals(
                partnerId = partner.id,
                now = now,
                scanLimit = AuctionTeaserTombstonePolicy.WITHDRAWN_SCAN_LIMIT,
            )
        val list =
            live.rows
                .filter { it.teaserId !in itemIds }
                .map { AuctionTeaserWithdrawal(teaserId = it.teaserId, withdrawnAt = it.withdrawnAt) }
        return Withdrawn(list = list, capped = live.scanCapped)
    }

    private fun encode(
        complete: Boolean,
        items: List<AuctionTeaserFeedItem>,
        withdrawn: List<AuctionTeaserWithdrawal>,
    ): ByteArray =
        AUCTION_TEASER_FEED_JSON
            .encodeToString(
                AuctionTeaserFeedV1.serializer(),
                AuctionTeaserFeedV1(complete = complete, items = items, withdrawn = withdrawn),
            ).toByteArray(Charsets.UTF_8)
}
