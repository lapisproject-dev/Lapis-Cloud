package network.lapis.cloud.server.carpool.feed

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.plus
import network.lapis.cloud.server.carpool.CarpoolFederationSettings
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.CarpoolParticipations
import network.lapis.cloud.server.carpool.CarpoolTeaserTombstonePolicy
import network.lapis.cloud.server.carpool.CarpoolWithdrawalKey
import network.lapis.cloud.server.carpool.CarpoolWithdrawalTarget
import network.lapis.cloud.server.carpool.CarpoolWithdrawals
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.shared.domain.CarpoolFederationLimits
import network.lapis.cloud.shared.domain.CarpoolTeaserEligibility
import network.lapis.cloud.shared.domain.isCarpoolTeaserEligible
import network.lapis.cloud.shared.domain.projectCarpoolTeaser

/** The serialised feed and what the caller needs to log / audit: how many items and withdrawals, and whether the lists are complete (see [CarpoolTeaserFeedV1]). */
internal class BuiltCarpoolTeaserFeed(
    val body: ByteArray,
    val itemCount: Int,
    val complete: Boolean,
    val withdrawnCount: Int = 0,
)

/**
 * V1.9.312 "Mitfahrzentrale-Foederation" W2 -- builds the complete teaser snapshot for ONE already approved partner. The only server caller of the
 * projection besides the owner preview in `CarpoolService` (pinned by `CarpoolFederationTripwireTest`). Every candidate goes through the ONE
 * eligibility rule ([isCarpoolTeaserEligible]) with the partner flags of the SAME transaction, and the ONE projection ([projectCarpoolTeaser]);
 * a withdrawal of consent is therefore visible on the very next fetch (nothing is cached).
 *
 * Deterministic: sorted by `(departureDate, teaserId)`. Bounded: at most [MAX_ITEMS] items, [SCAN_LIMIT] scanned trips and [MAX_BYTES] bytes;
 * whenever any bound cuts the list, `complete` is `false`.
 *
 * V1.9.313 (wave 3): the snapshot also carries the live withdrawals of the partner (`withdrawn`). Reading order, pinned by tests: first the
 * candidates and the items (complete, before any cut), then the lazy sweep of opted-in trips of inactive authors, then the tombstones; a hint that
 * is in the complete item list is removed from the withdrawals ("item wins"). That order can at worst produce "item without a tombstone" (the
 * next fetch shows the tombstone), never "neither item nor tombstone" for a trip that was withdrawn while this transaction ran.
 */
internal object CarpoolTeaserFeedBuilder {
    const val MAX_ITEMS = 1000
    const val SCAN_LIMIT = 5000
    const val MAX_BYTES = 256 * 1024

    /**
     * The trips that are shareable with ANY partner right now (opted in, offer, in the window, seats free, author active), ignoring the partner
     * and instance switches: what a switch-off has to report as withdrawn. At most [scanLimit] candidates are looked at; a longer list is counted
     * ([CarpoolWithdrawalKey.scanCapped]) and the tail is missed (documented).
     */
    fun shareableTargets(
        today: LocalDate,
        scanLimit: Int,
    ): List<CarpoolWithdrawalTarget> {
        val scanned = candidatesOf(today = today, scanLimit = scanLimit)
        if (scanned.size > scanLimit) CarpoolWithdrawalKey.scanCapped.incrementAndGet()
        val candidates = scanned.take(scanLimit)
        val confirmed = CarpoolParticipations.confirmedCounts(candidates.map { it.postingId })
        return candidates
            .filter { c ->
                isCarpoolTeaserEligible(
                    e =
                        CarpoolTeaserEligibility(
                            type = c.type,
                            optIn = true,
                            instanceEnabled = true,
                            partnerSharing = true,
                            partnerActive = true,
                            partnerBlocked = false,
                            authorActive = c.authorActive,
                            departureDate = c.departureDate,
                            seatsOffered = c.seatsOffered,
                            confirmedCount = confirmed[c.postingId] ?: 0,
                            fromPlace = c.fromPlace,
                            toPlace = c.toPlace,
                        ),
                    today = today,
                )
            }.map { CarpoolWithdrawalTarget(postingId = it.postingId, departureDate = it.departureDate) }
    }

    private fun candidatesOf(
        today: LocalDate,
        scanLimit: Int,
    ) = CarpoolFederationStore.teaserCandidates(
        from = today,
        until = today.plus(CarpoolFederationLimits.MAX_DAYS_AHEAD, DateTimeUnit.DAY),
        scanLimit = scanLimit,
    )

    /** Runs inside the caller's transaction; [partner] is the approved row. [beforeWithdrawalRead] is a test seam between the items and the tombstones. */
    fun build(
        partner: PartnerRow,
        settings: CarpoolFederationSettings,
        ids: CarpoolTeaserIds,
        originHost: String,
        today: LocalDate,
        maxItems: Int = MAX_ITEMS,
        scanLimit: Int = SCAN_LIMIT,
        maxBytes: Int = MAX_BYTES,
        now: LocalDateTime = DbClock.nowLocalDateTime(),
        beforeWithdrawalRead: () -> Unit = {},
    ): BuiltCarpoolTeaserFeed {
        val scanned = candidatesOf(today = today, scanLimit = scanLimit)
        var complete = scanned.size <= scanLimit
        val candidates = scanned.take(scanLimit)
        val confirmed = CarpoolParticipations.confirmedCounts(candidates.map { it.postingId })
        val blocked = PartnerBlocklistPattern.isBlocked(host = partner.host, patterns = PartnerServerStore.blockPatterns())
        val allItems =
            candidates
                .mapNotNull { c ->
                    val eligibility =
                        CarpoolTeaserEligibility(
                            type = c.type,
                            optIn = true,
                            instanceEnabled = settings.enabled,
                            partnerSharing = partner.carpoolSharing,
                            partnerActive = partner.active,
                            partnerBlocked = blocked,
                            authorActive = c.authorActive,
                            departureDate = c.departureDate,
                            seatsOffered = c.seatsOffered,
                            confirmedCount = confirmed[c.postingId] ?: 0,
                            fromPlace = c.fromPlace,
                            toPlace = c.toPlace,
                        )
                    if (!isCarpoolTeaserEligible(e = eligibility, today = today)) return@mapNotNull null
                    projectCarpoolTeaser(
                        fromPlace = c.fromPlace,
                        toPlace = c.toPlace,
                        departureDate = c.departureDate,
                        teaserId = ids.of(c.postingId),
                    )?.let {
                        CarpoolTeaserFeedItem(originHost = originHost, hops = 0, maxHops = settings.hopLimit, teaser = it)
                    }
                }.sortedWith(compareBy({ it.teaser.departureDate }, { it.teaser.teaserId }))
        val withdrawn =
            withdrawalsFor(
                partner = partner,
                ids = ids,
                now = now,
                itemIds =
                    allItems
                        .map {
                            it.teaser.teaserId
                        }.toSet(),
                beforeRead = beforeWithdrawalRead,
            )
        if (withdrawn.capped) complete = false
        var keptWithdrawn = withdrawn.list
        if (keptWithdrawn.size > CarpoolTeaserTombstonePolicy.MAX_WITHDRAWN) {
            keptWithdrawn = keptWithdrawn.take(CarpoolTeaserTombstonePolicy.MAX_WITHDRAWN)
            complete = false
        }
        var kept = allItems
        if (kept.size > maxItems) {
            kept = kept.take(maxItems)
            complete = false
        }
        while (true) {
            val bytes = encode(complete = complete, items = kept, withdrawn = keptWithdrawn)
            if (bytes.size <= maxBytes || (kept.isEmpty() && keptWithdrawn.isEmpty())) {
                return BuiltCarpoolTeaserFeed(body = bytes, itemCount = kept.size, complete = complete, withdrawnCount = keptWithdrawn.size)
            }
            if (kept.isNotEmpty()) kept = kept.take(kept.size / 2) else keptWithdrawn = keptWithdrawn.take(keptWithdrawn.size / 2)
            complete = false
        }
    }

    /** The trailing answer for an approved partner that is no longer shared with: `items` empty, the withdrawals as usual, `complete` honest. */
    fun buildWithdrawalsOnly(
        partner: PartnerRow,
        ids: CarpoolTeaserIds,
        now: LocalDateTime = DbClock.nowLocalDateTime(),
        maxBytes: Int = MAX_BYTES,
    ): BuiltCarpoolTeaserFeed {
        val withdrawn = withdrawalsFor(partner = partner, ids = ids, now = now, itemIds = emptySet(), beforeRead = {})
        var complete = !withdrawn.capped
        var kept = withdrawn.list
        if (kept.size > CarpoolTeaserTombstonePolicy.MAX_WITHDRAWN) {
            kept = kept.take(CarpoolTeaserTombstonePolicy.MAX_WITHDRAWN)
            complete = false
        }
        while (true) {
            val bytes = encode(complete = complete, items = emptyList(), withdrawn = kept)
            if (bytes.size <= maxBytes || kept.isEmpty()) {
                return BuiltCarpoolTeaserFeed(body = bytes, itemCount = 0, complete = complete, withdrawnCount = kept.size)
            }
            kept = kept.take(kept.size / 2)
            complete = false
        }
    }

    private class Withdrawn(
        val list: List<CarpoolTeaserWithdrawal>,
        val capped: Boolean,
    )

    private fun withdrawalsFor(
        partner: PartnerRow,
        ids: CarpoolTeaserIds,
        now: LocalDateTime,
        itemIds: Set<String>,
        beforeRead: () -> Unit,
    ): Withdrawn {
        // Lazy sweep (bounded): opted-in trips of authors who left. Their hints are not items (the author is not active), so the tombstone below
        // reports them in this very answer.
        CarpoolWithdrawals.sweepInactiveAuthors(now = now, ids = ids, limit = CarpoolTeaserTombstonePolicy.MAX_LAZY_SWEEP_PER_BUILD)
        beforeRead()
        val live =
            CarpoolFederationStore.liveWithdrawals(
                partnerId = partner.id,
                now = now,
                scanLimit = CarpoolTeaserTombstonePolicy.WITHDRAWN_SCAN_LIMIT,
            )
        if (live.scanCapped) CarpoolWithdrawalKey.scanCapped.incrementAndGet()
        val list =
            live.rows
                .filter { it.teaserId !in itemIds }
                .sortedWith(
                    compareByDescending<network.lapis.cloud.server.carpool.CarpoolTeaserWithdrawalRow> {
                        it.withdrawnAt
                    }.thenBy { it.teaserId },
                ).map { CarpoolTeaserWithdrawal(teaserId = it.teaserId, withdrawnAt = it.withdrawnAt) }
        return Withdrawn(list = list, capped = live.scanCapped)
    }

    private fun encode(
        complete: Boolean,
        items: List<CarpoolTeaserFeedItem>,
        withdrawn: List<CarpoolTeaserWithdrawal>,
    ): ByteArray =
        CARPOOL_TEASER_FEED_JSON
            .encodeToString(
                CarpoolTeaserFeedV1.serializer(),
                CarpoolTeaserFeedV1(complete = complete, items = items, withdrawn = withdrawn),
            ).toByteArray(Charsets.UTF_8)
}
