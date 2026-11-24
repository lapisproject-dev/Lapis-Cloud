package network.lapis.cloud.server.auction.federation

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.auction.AuctionFiatPricing
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.shared.domain.AuctionFederationLimits
import network.lapis.cloud.shared.domain.AuctionTeaser
import network.lapis.cloud.shared.domain.AuctionTeaserPriceKind
import network.lapis.cloud.shared.domain.AuctionTeaserProjection
import org.jetbrains.exposed.v1.core.ResultRow
import java.security.MessageDigest
import java.text.Normalizer
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * V1.9.322 (step c2) -- the throttle of the shown price and the ONE place that decides over a new revision of a hint.
 *
 * The feed must not reveal the bid history. Rules (each pinned by a test):
 *  - Without a bid (`START`) the price is the starting price the seller sets: a change is passed on at once (it reveals no bid).
 *  - With bids (`CURRENT`, derived from bids) a new price is shown at most every [AuctionFederationLimits.PRICE_REFRESH_MINUTES] minutes; the first
 *    transition START to CURRENT is throttled the same way. Until then the stored price stays.
 *  - A change of the Sofortkauf price, the offers flag or the currency changes the content hash and therefore the revision at once.
 *  - The revision is strictly monotonic ([AuctionFederationStore.newRevision]); the update is conditional on the old revision, so two parallel
 *    builds can never overwrite each other.
 */
internal object AuctionTeaserSnapshots {
    private val REFRESH = AuctionFederationLimits.PRICE_REFRESH_MINUTES.minutes

    /** The hash of everything on the wire EXCEPT the revision: a different hash means a new revision. */
    fun contentSha(t: AuctionTeaser): String {
        val text =
            listOf(
                t.teaserId,
                t.title,
                t.currency,
                t.priceKind.name,
                t.priceMinor.toString(),
                t.buyNowMinor?.toString() ?: "-",
                if (t.offersPossible) "1" else "0",
                t.endsAt.toString(),
                t.priceAsOf.toString(),
            ).joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private class Chosen(
        val kind: AuctionTeaserPriceKind,
        val priceMinor: Long,
        val asOf: LocalDateTime,
    )

    private fun floorMinute(now: LocalDateTime): LocalDateTime =
        AuctionTeaserProjection.floorToMinute(now.toInstant(TimeZone.UTC)).toLocalDateTime(TimeZone.UTC)

    private fun chooseNew(
        snap: AuctionTeaserSnapshotRow,
        liveMinor: Long,
        liveKind: AuctionTeaserPriceKind,
        now: LocalDateTime,
    ): Chosen {
        val keep = Chosen(kind = snap.priceKind, priceMinor = snap.priceMinor, asOf = snap.priceAsOf)
        val fresh = Chosen(kind = liveKind, priceMinor = liveMinor, asOf = floorMinute(now))
        if (snap.priceKind == liveKind && snap.priceMinor == liveMinor) return keep
        if (liveKind == AuctionTeaserPriceKind.START) return fresh
        val age = now.toInstant(TimeZone.UTC) - snap.priceAsOf.toInstant(TimeZone.UTC)
        return if (age >= REFRESH) fresh else keep
    }

    private fun project(
        row: ResultRow,
        ids: AuctionTeaserIds,
        fiatEnabled: Boolean,
        revision: Long,
        kind: AuctionTeaserPriceKind,
        priceMinor: Long,
        asOf: LocalDateTime,
    ): AuctionTeaser? {
        val endsAt: Instant = row[AuctionTable.endsAt].toInstant(TimeZone.UTC)
        val asOfInstant = minOf(asOf.toInstant(TimeZone.UTC), endsAt)
        return AuctionTeaserProjection.project(
            teaserId = ids.of(row[AuctionTable.id]),
            revision = revision,
            localTitle = Normalizer.normalize(row[AuctionTable.title], Normalizer.Form.NFC),
            currency = row[AuctionTable.currency],
            priceKind = kind,
            priceMinor = priceMinor,
            buyNowMinor = row[AuctionTable.buyNowPriceMinor],
            offersEnabled = row[AuctionTable.offersEnabled],
            fiatEnabled = fiatEnabled,
            endsAt = endsAt,
            priceAsOf = asOfInstant,
        )
    }

    /** What the seller's consent creates: the snapshot of the live values (also used when a snapshot is missing). `null` = not projectable. */
    fun createOnConsent(
        row: ResultRow,
        ids: AuctionTeaserIds,
        fiatEnabled: Boolean,
        now: LocalDateTime,
    ): AuctionTeaserSnapshotRow? {
        val live = AuctionFiatPricing.visiblePrice(row)
        val kind = if (live.hasBids) AuctionTeaserPriceKind.CURRENT else AuctionTeaserPriceKind.START
        val asOf = floorMinute(now)
        val probe =
            project(row = row, ids = ids, fiatEnabled = fiatEnabled, revision = 1, kind = kind, priceMinor = live.minor, asOf = asOf)
                ?: return null
        return AuctionFederationStore.upsertSnapshotOnConsent(
            auctionId = row[AuctionTable.id],
            teaserId = probe.teaserId,
            priceKind = kind,
            priceMinor = live.minor,
            priceAsOf = asOf,
            contentSha256 = contentSha(probe),
            now = now,
        )
    }

    /**
     * The hint to publish for this (already eligible) candidate, advancing the snapshot when due. `null` = not publishable now. [reappeared] = the
     * hint carries a live global tombstone of this server (it was withdrawn and is shared again): it gets a fresh revision and the tombstone goes.
     */
    fun publishable(
        row: ResultRow,
        ids: AuctionTeaserIds,
        fiatEnabled: Boolean,
        now: LocalDateTime,
        reappeared: Boolean = false,
    ): AuctionTeaser? {
        val auctionId = row[AuctionTable.id]
        val live = AuctionFiatPricing.visiblePrice(row)
        val liveKind = if (live.hasBids) AuctionTeaserPriceKind.CURRENT else AuctionTeaserPriceKind.START
        var snap =
            AuctionFederationStore.snapshot(auctionId) ?: createOnConsent(row = row, ids = ids, fiatEnabled = fiatEnabled, now = now)
                ?: return null
        val chosen = chooseNew(snap = snap, liveMinor = live.minor, liveKind = liveKind, now = now)
        val candidate =
            project(
                row = row,
                ids = ids,
                fiatEnabled = fiatEnabled,
                revision = snap.revision,
                kind = chosen.kind,
                priceMinor = chosen.priceMinor,
                asOf = chosen.asOf,
            ) ?: return null
        val sha = contentSha(candidate)
        if (sha != snap.contentSha256 || snap.teaserId != candidate.teaserId || reappeared) {
            val advanced =
                AuctionFederationStore.advanceSnapshot(
                    old = snap,
                    teaserId = candidate.teaserId,
                    priceKind = chosen.kind,
                    priceMinor = chosen.priceMinor,
                    priceAsOf = chosen.asOf,
                    contentSha256 = sha,
                    now = now,
                )
            // Lost the race: the stored state of the winner is the truth, we publish exactly that.
            snap = AuctionFederationStore.snapshot(auctionId) ?: return null
            if (!advanced) {
                return project(
                    row = row,
                    ids = ids,
                    fiatEnabled = fiatEnabled,
                    revision = snap.revision,
                    kind = snap.priceKind,
                    priceMinor = snap.priceMinor,
                    asOf = snap.priceAsOf,
                )
            }
            if (reappeared) AuctionFederationStore.forgetOwnGlobalTombstone(candidate.teaserId)
        }
        return project(
            row = row,
            ids = ids,
            fiatEnabled = fiatEnabled,
            revision = snap.revision,
            kind = snap.priceKind,
            priceMinor = snap.priceMinor,
            asOf = snap.priceAsOf,
        )
    }
}
