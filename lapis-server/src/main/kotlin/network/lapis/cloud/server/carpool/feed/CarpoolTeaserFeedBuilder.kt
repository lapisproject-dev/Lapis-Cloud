package network.lapis.cloud.server.carpool.feed

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import network.lapis.cloud.server.carpool.CarpoolFederationSettings
import network.lapis.cloud.server.carpool.CarpoolFederationStore
import network.lapis.cloud.server.carpool.CarpoolParticipations
import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.shared.domain.CarpoolFederationLimits
import network.lapis.cloud.shared.domain.CarpoolTeaserEligibility
import network.lapis.cloud.shared.domain.isCarpoolTeaserEligible
import network.lapis.cloud.shared.domain.projectCarpoolTeaser

/** The serialised feed and what the caller needs to log / audit: how many items, and whether the list is complete (see [CarpoolTeaserFeedV1]). */
internal class BuiltCarpoolTeaserFeed(
    val body: ByteArray,
    val itemCount: Int,
    val complete: Boolean,
)

/**
 * V1.9.312 "Mitfahrzentrale-Foederation" W2 -- builds the complete teaser snapshot for ONE already approved partner. The only server caller of the
 * projection besides the owner preview in `CarpoolService` (pinned by `CarpoolFederationTripwireTest`). Every candidate goes through the ONE
 * eligibility rule ([isCarpoolTeaserEligible]) with the partner flags of the SAME transaction, and the ONE projection ([projectCarpoolTeaser]);
 * a withdrawal of consent is therefore visible on the very next fetch (nothing is cached).
 *
 * Deterministic: sorted by `(departureDate, teaserId)`. Bounded: at most [MAX_ITEMS] items, [SCAN_LIMIT] scanned trips and [MAX_BYTES] bytes;
 * whenever any bound cuts the list, `complete` is `false`.
 */
internal object CarpoolTeaserFeedBuilder {
    const val MAX_ITEMS = 1000
    const val SCAN_LIMIT = 5000
    const val MAX_BYTES = 256 * 1024

    /** Runs inside the caller's transaction; [partner] is the approved row. */
    fun build(
        partner: PartnerRow,
        settings: CarpoolFederationSettings,
        ids: CarpoolTeaserIds,
        originHost: String,
        today: LocalDate,
        maxItems: Int = MAX_ITEMS,
        scanLimit: Int = SCAN_LIMIT,
        maxBytes: Int = MAX_BYTES,
    ): BuiltCarpoolTeaserFeed {
        val scanned =
            CarpoolFederationStore.teaserCandidates(
                from = today,
                until = today.plus(CarpoolFederationLimits.MAX_DAYS_AHEAD, DateTimeUnit.DAY),
                scanLimit = scanLimit,
            )
        var complete = scanned.size <= scanLimit
        val candidates = scanned.take(scanLimit)
        val confirmed = CarpoolParticipations.confirmedCounts(candidates.map { it.postingId })
        val blocked = PartnerBlocklistPattern.isBlocked(host = partner.host, patterns = PartnerServerStore.blockPatterns())
        val items =
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
        var kept = items
        if (kept.size > maxItems) {
            kept = kept.take(maxItems)
            complete = false
        }
        while (true) {
            val bytes = encode(complete = complete, items = kept)
            if (bytes.size <= maxBytes ||
                kept.isEmpty()
            ) {
                return BuiltCarpoolTeaserFeed(body = bytes, itemCount = kept.size, complete = complete)
            }
            kept = kept.take(kept.size / 2)
            complete = false
        }
    }

    private fun encode(
        complete: Boolean,
        items: List<CarpoolTeaserFeedItem>,
    ): ByteArray =
        CARPOOL_TEASER_FEED_JSON
            .encodeToString(CarpoolTeaserFeedV1.serializer(), CarpoolTeaserFeedV1(complete = complete, items = items))
            .toByteArray(Charsets.UTF_8)
}
